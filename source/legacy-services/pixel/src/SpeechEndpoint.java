package org.retrobridge.galaxyservices.pixel;
import android.content.*;import android.os.*;import android.speech.*;import android.media.AudioFormat;import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;import org.json.*;import org.retrobridge.galaxyservices.Wire;

/** Authenticated TLS endpoint. Audio exists only in RAM and a pipe to the on-device recognizer. */
final class SpeechEndpoint {
 final Context app;final Handler main=new Handler(Looper.getMainLooper());final Semaphore gate=new Semaphore(1);
 SpeechEndpoint(Context c){app=c;}
 void note(String s){try{Wire.writeFile(new File(app.getFilesDir(),"speech-status.txt"),s.getBytes("UTF-8"));}catch(Exception ignored){}}
 void serve(InputStream in,OutputStream out)throws Exception {
  byte[] pcm=null;boolean acquired=false;String metrics="";
  try{
   String header=new String(Wire.header(in),"ISO-8859-1");String[] request=header.substring(0,header.indexOf("\r\n")).split(" ");
   if(request.length!=3||!request[0].equals("POST"))throw new IOException("Method");URI u=new URI(request[1]);if(!"/recognize".equals(u.getPath()))throw new IOException("Path");
   String language="en-US";if(u.getRawQuery()!=null){String q=u.getRawQuery();if(!q.matches("language=[A-Za-z]{2,3}(-[A-Za-z]{2,8}){0,2}"))throw new IOException("Language");language=q.substring(9);}
   int length=-1;for(String line:header.split("\r\n")){String lower=line.toLowerCase(Locale.US);if(lower.startsWith("transfer-encoding:"))throw new IOException("Encoding");if(lower.startsWith("content-length:")){if(length!=-1)throw new IOException("Duplicate length");length=Integer.parseInt(line.substring(15).trim());}}
   if(length<3200||length>960000||(length&1)!=0)throw new IOException("Audio size");
   acquired=gate.tryAcquire();if(!acquired){reply(out,200,new JSONObject().put("error",SpeechRecognizer.ERROR_RECOGNIZER_BUSY));return;}
   pcm=new byte[length];new DataInputStream(in).readFully(pcm);metrics=audioMetrics(pcm);Job job=new Job(pcm,language);note(metrics+"; recognizing");main.post(job);
   if(!job.done.await(25,TimeUnit.SECONDS)){main.post(()->job.finish(SpeechRecognizer.ERROR_SPEECH_TIMEOUT));job.done.await(2,TimeUnit.SECONDS);}
   JSONObject result=new JSONObject();if(job.error!=0)result.put("error",job.error);else if(job.words.isEmpty())result.put("error",SpeechRecognizer.ERROR_NO_MATCH);else result.put("results",new JSONArray(job.words));
   note(metrics+"; "+(result.has("error")?"Recognition error="+result.getInt("error"):"Recognized; alternatives="+job.words.size()));reply(out,200,result);
  }catch(Exception e){note("Request failed: "+e.getClass().getSimpleName());reply(out,400,new JSONObject().put("error",SpeechRecognizer.ERROR_NETWORK));}
  finally{if(pcm!=null)Arrays.fill(pcm,(byte)0);if(acquired)gate.release();}
 }
 static String audioMetrics(byte[] pcm){
  int count=pcm.length/2,peak=0,zero=0,clipped=0;double sum=0,squares=0;StringBuilder levels=new StringBuilder();
  for(int offset=0;offset<count;offset+=1600){double chunkSum=0,chunkSquares=0;int n=Math.min(1600,count-offset);
   for(int k=0;k<n;k++){int p=(offset+k)*2;int value=(short)((pcm[p]&255)|(pcm[p+1]<<8));int absolute=Math.abs(value);peak=Math.max(peak,absolute);if(value==0)zero++;if(absolute>=32760)clipped++;sum+=value;squares+=(double)value*value;chunkSum+=value;chunkSquares+=(double)value*value;}
   if(offset<160000){if(levels.length()>0)levels.append(',');double mean=chunkSum/n;levels.append(Math.round(Math.sqrt(Math.max(0,chunkSquares/n-mean*mean))));}
  }
  double mean=sum/count;return "time="+System.currentTimeMillis()+"; audio_ms="+(count/16)+"; rms="+Math.round(Math.sqrt(squares/count))+"; ac="+Math.round(Math.sqrt(Math.max(0,squares/count-mean*mean)))+"; peak="+peak+"; dc="+Math.round(mean)+"; zero_pct="+(100L*zero/count)+"; clipped="+clipped+"; ac_100ms="+levels;
 }
 static void reply(OutputStream out,int code,JSONObject j)throws Exception{byte[] data=j.toString().getBytes("UTF-8");out.write(("HTTP/1.1 "+code+" "+(code==200?"OK":"Bad Request")+"\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));out.write(data);out.flush();}
 final class Job implements Runnable,RecognitionListener {
  final byte[] audio;final String language;final CountDownLatch done=new CountDownLatch(1);final ArrayList<String> words=new ArrayList<String>();volatile int error;SpeechRecognizer recognizer;ParcelFileDescriptor read,write;boolean finished;
  Job(byte[] a,String l){audio=a;language=l;}
  public void run(){try{
   if(!SpeechRecognizer.isOnDeviceRecognitionAvailable(app)){finish(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE);return;}
   ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();read=pipe[0];write=pipe[1];recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(app);recognizer.setRecognitionListener(this);
   Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,language).putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE,read).putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,1).putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,AudioFormat.ENCODING_PCM_16BIT).putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,16000).putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION,RecognizerIntent.EXTRA_AUDIO_SOURCE);
   recognizer.startListening(i);final ParcelFileDescriptor sink=write;new Thread(()->{try(OutputStream o=new ParcelFileDescriptor.AutoCloseOutputStream(sink)){o.write(audio);}catch(Exception e){main.post(()->finish(SpeechRecognizer.ERROR_AUDIO));}},"speech-pipe").start();
  }catch(Exception e){finish(SpeechRecognizer.ERROR_CLIENT);}}
  void accept(Bundle b){ArrayList<String> result=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(result==null||result.isEmpty())return;if(words.isEmpty())words.addAll(result);else words.set(0,words.get(0)+" "+result.get(0));}
  void finish(int e){if(finished)return;finished=true;error=e;if(recognizer!=null)recognizer.destroy();try{if(read!=null)read.close();}catch(Exception ignored){}try{if(write!=null)write.close();}catch(Exception ignored){}done.countDown();}
  public void onResults(Bundle b){accept(b);finish(0);}public void onSegmentResults(Bundle b){accept(b);}public void onEndOfSegmentedSession(){finish(0);}public void onError(int e){finish(e);}
  public void onReadyForSpeech(Bundle b){}public void onBeginningOfSpeech(){}public void onRmsChanged(float r){}public void onBufferReceived(byte[] b){}public void onEndOfSpeech(){}public void onPartialResults(Bundle b){}public void onEvent(int t,Bundle b){}
 }
}
