package org.retrobridge.galaxyservices.s2;
import android.content.*;import android.media.*;import android.os.*;import android.speech.*;import java.io.*;import java.net.*;import java.util.*;import javax.net.ssl.HttpsURLConnection;import org.json.*;import org.retrobridge.galaxyservices.Wire;

/** Standard Android recognition contract lets the existing keyboard keep its own dictation UI. */
public final class PixelRecognitionService extends RecognitionService {
 volatile Session current;
 void note(String s){try{Wire.writeFile(new File(getFilesDir(),"speech-status.txt"),s.getBytes("UTF-8"));}catch(Exception ignored){}}
 protected void onStartListening(Intent i,Callback cb){if(current!=null){try{cb.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY);}catch(Exception ignored){}return;}Session s=new Session(i,cb);current=s;new Thread(s,"s2-dictation").start();}
 protected void onStopListening(Callback cb){Session s=current;if(s!=null)s.stop=true;}
 protected void onCancel(Callback cb){Session s=current;if(s!=null){s.cancel=true;s.stop=true;s.disconnect();}}
 public void onDestroy(){Session s=current;if(s!=null){s.cancel=true;s.stop=true;s.disconnect();}super.onDestroy();}
 final class Session implements Runnable {
  final Intent intent;final Callback callback;volatile boolean stop,cancel;volatile HttpsURLConnection connection;
  Session(Intent i,Callback c){intent=i;callback=c;}
  void disconnect(){HttpsURLConnection c=connection;if(c!=null)c.disconnect();}
  void error(int e){note("Recognition error="+e);if(!cancel)try{callback.error(e);}catch(Exception ignored){}}
  public void run(){AudioRecord record=null;byte[] pcm=null;PowerManager.WakeLock wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GalaxyServices:dictation");wake.acquire(55000);
   try{
    int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);if(min<1)throw new IOException("Audio unavailable");
    note("Listening");record=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,6400));if(record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("Audio unavailable");
    short[] frame=new short[800];ByteArrayOutputStream bytes=new ByteArrayOutputStream();record.startRecording();callback.readyForSpeech(new Bundle());
    long started=SystemClock.elapsedRealtime(),lastVoice=started;boolean speech=false;int voiced=0;double noise=100;
    while(!stop&&SystemClock.elapsedRealtime()-started<25000){int n=record.read(frame,0,frame.length);if(n<=0)throw new IOException("Audio read");double sum=0;for(int k=0;k<n;k++){int v=frame[k];sum+=(double)v*v;bytes.write(v&255);bytes.write((v>>8)&255);}double rms=Math.sqrt(sum/n);long now=SystemClock.elapsedRealtime();
     if(now-started<200)noise=Math.min(500,Math.max(60,rms));boolean active=rms>Math.max(180,noise*2.4);callback.rmsChanged((float)Math.max(-2,Math.min(12,20*Math.log10(Math.max(1,rms)/100.0))));
     if(active){lastVoice=now;voiced+=n;if(!speech&&voiced>=1600){speech=true;callback.beginningOfSpeech();}}
     if(speech&&now-lastVoice>1250)break;if(!speech&&now-started>6000)break;
    }
    record.stop();record.release();record=null;if(cancel)return;callback.endOfSpeech();if(!speech){error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);return;}
    pcm=bytes.toByteArray();note("Sending audio; bytes="+pcm.length);String language=intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);if(language==null)language=Locale.getDefault().toString();language=language.replace('_','-');if(!language.matches("[A-Za-z]{2,3}(-[A-Za-z]{2,8}){0,2}"))language="en-US";
    connection=(HttpsURLConnection)new URL("https://speech.galaxy-services.invalid/recognize?language="+language).openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",Wire.LOCAL)));connection.setConnectTimeout(7000);connection.setReadTimeout(30000);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","audio/L16;rate=16000;channels=1");connection.setFixedLengthStreamingMode(pcm.length);connection.getOutputStream().write(pcm);
    if(connection.getResponseCode()!=200)throw new IOException("Speech connection");ByteArrayOutputStream response=new ByteArrayOutputStream();InputStream in=connection.getInputStream();byte[] buf=new byte[1024];int n;while((n=in.read(buf))!=-1){if(response.size()+n>32768)throw new IOException("Speech response");response.write(buf,0,n);}JSONObject result=new JSONObject(response.toString("UTF-8"));if(cancel)return;if(result.has("error")){int e=result.getInt("error");error(e>=1&&e<=9?e:SpeechRecognizer.ERROR_CLIENT);return;}
    JSONArray a=result.getJSONArray("results");ArrayList<String> words=new ArrayList<String>();for(int k=0;k<a.length()&&k<5;k++)words.add(a.getString(k));if(words.isEmpty()){error(SpeechRecognizer.ERROR_NO_MATCH);return;}Bundle b=new Bundle();b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,words);note("Results delivered; alternatives="+words.size());callback.results(b);
   }catch(Exception e){error(record!=null?SpeechRecognizer.ERROR_AUDIO:SpeechRecognizer.ERROR_NETWORK);}
   finally{if(record!=null){try{record.stop();}catch(Exception ignored){}record.release();}disconnect();if(pcm!=null)Arrays.fill(pcm,(byte)0);if(wake.isHeld())wake.release();if(current==this)current=null;}
  }
 }
}
