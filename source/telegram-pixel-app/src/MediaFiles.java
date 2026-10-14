package org.retrobridge.galaxychat.pixel;

import android.content.Context;
import android.graphics.*;
import android.media.*;
import android.util.Base64;
import org.retrobridge.galaxychat.protocol.Wire;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded private media cache. Remote peers receive opaque handles, never paths. */
final class MediaFiles {
 static final long MAX=100L*1024*1024, BUDGET=384L*1024*1024;
 static final int CHUNK=16384;
 private final File root;
 private final ConcurrentHashMap<String,Job> jobs=new ConcurrentHashMap<>();
 private final Map<String,Download> handles=new LinkedHashMap<>();
 private final ExecutorService conversion=Executors.newSingleThreadExecutor();
 static final class Job {volatile boolean done,failed;File file;String mime;}
 static final class Download {File file;String name,mime;long used;Download(File f,String n,String m){file=f;name=n;mime=m;used=System.currentTimeMillis();}}
 MediaFiles(Context c){root=new File(c.getFilesDir(),"companion-media");root.mkdirs();clean();}
 static String hash(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[32768];for(int n;(n=in.read(b))!=-1;)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte v:d.digest())s.append(String.format(Locale.US,"%02x",v&255));return s.toString();}
 static String name(String s){s=s.replaceAll("[\\\\/\\p{Cntrl}]","_");if(s.isEmpty())s="attachment";return s.length()>120?s.substring(s.length()-120):s;}
 private static String uuid(JSONObject p)throws Exception{String id=p.getString("upload_id");if(!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IOException("Invalid upload");return id;}
 private File meta(String id){return new File(root,"up-"+id+".json");}
 private File part(String id)throws Exception{File f=new File(root,"up-"+id+".data");if(f.exists()||!meta(id).exists())return f;JSONObject m=metadata(id);File sent=staged(id,m);if(sent.exists())return sent;File legacy=new File(root,"sent-"+id+"-"+name(m.optString("name")));return legacy.exists()?legacy:f;}
 private File staged(String id,JSONObject m){return new File(new File(root,"sent-"+id),name(m.optString("name")));}
 private JSONObject metadata(String id)throws Exception{File f=meta(id);if(!f.isFile()||f.length()>4096)throw new IOException("Upload expired");return new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),"UTF-8"));}
 synchronized JSONObject begin(JSONObject p)throws Exception{
  String id=uuid(p),sha=p.getString("sha256");long size=p.getLong("size");AttachmentChunks.validate(size,sha);
  JSONObject old=meta(id).exists()?metadata(id):null;
  if(old!=null){meta(id).setLastModified(System.currentTimeMillis());if(size!=old.getLong("size")||!sha.equals(old.getString("sha256")))throw new IOException("Upload identifier conflict");return Wire.json("offset",part(id).length());}
  clean();if(reserved()+size>BUDGET||root.getUsableSpace()<size+32L*1024*1024)throw new IOException("Not enough attachment storage");
  JSONObject m=Wire.json("size",size,"sha256",sha,"name",name(p.optString("name")),"mime",safeMime(p.optString("mime","application/octet-stream")));
  try(FileOutputStream out=new FileOutputStream(meta(id))){out.write(Wire.utf8(m.toString()));out.getFD().sync();}try(FileOutputStream out=new FileOutputStream(part(id))){out.getFD().sync();}
  return Wire.json("offset",0);
 }
 synchronized JSONObject chunk(JSONObject p)throws Exception{
  String id=uuid(p);JSONObject m=metadata(id);meta(id).setLastModified(System.currentTimeMillis());long offset=p.getLong("offset");String encoded=p.getString("data");if(encoded.length()>22000)throw new IOException("Chunk too large");byte[] data=Base64.decode(encoded,Base64.NO_WRAP);return Wire.json("offset",AttachmentChunks.append(part(id),m.getLong("size"),offset,data));
 }
 synchronized File upload(JSONObject p)throws Exception{String id=uuid(p);JSONObject m=metadata(id);File f=part(id);if(f.length()!=m.getLong("size")||!hash(f).equals(m.getString("sha256")))throw new IOException("Incomplete attachment");File named=staged(id,m);named.getParentFile().mkdirs();if(!named.exists()){if(!f.renameTo(named))throw new IOException("Could not stage attachment");}named.setLastModified(System.currentTimeMillis());return named;}
 synchronized JSONObject uploadMeta(JSONObject p)throws Exception{return metadata(uuid(p));}
 synchronized JSONObject prepare(File source,int fileId,String mode,String mime,String filename)throws Exception{
  if(!source.isFile()||source.length()>MAX)throw new IOException("Attachment exceeds 100 MB");
  boolean preview=mode.equals("preview")||mode.equals("icon"),voice=mode.equals("voice");
  String key=fileId+"-"+mode;Job j=jobs.get(key);if(j!=null&&j.done&&!j.failed&&!j.file.isFile()){jobs.remove(key);j=null;}
  if(j==null&&jobs.size()>=128){for(Map.Entry<String,Job> item:jobs.entrySet())if(item.getValue().done)jobs.remove(item.getKey(),item.getValue());if(jobs.size()>=128)throw new IOException("Media queue is full. Try again shortly.");}
  if(j==null){Job next=new Job();Job prior=jobs.putIfAbsent(key,next);j=prior==null?next:prior;if(prior==null){
   final Job job=j;conversion.execute(()->{try{synchronized(MediaFiles.this){clean();if((preview||voice)&&reserved()+(voice?MAX:1024*1024)>BUDGET)throw new IOException("Attachment storage is full");}if(preview){job.file=new File(root,"view-"+key+".jpg");thumbnail(source,job.file,mode.equals("icon")?96:420);job.mime="image/jpeg";}else if(voice){job.file=new File(root,"view-"+key+".wav");decodeAudio(source,job.file);job.mime="audio/wav";}else{job.file=source;job.mime=mime;}job.done=true;}catch(Exception e){job.failed=true;job.done=true;}});
  }}
  if(!j.done)return Wire.json("ready",false);if(j.failed)throw new IOException("This media cannot be previewed");j.file.setLastModified(System.currentTimeMillis());
  synchronized(this){String token=null;for(Map.Entry<String,Download> e:handles.entrySet())if(e.getValue().file.equals(j.file)){token=e.getKey();e.getValue().used=System.currentTimeMillis();break;}
   if(token==null){token=UUID.randomUUID().toString();handles.put(token,new Download(j.file,preview?"preview.jpg":voice?"voice.wav":name(filename),j.mime));while(handles.size()>128)handles.remove(handles.keySet().iterator().next());}
   Download d=handles.get(token);return Wire.json("ready",true,"token",token,"size",d.file.length(),"name",d.name,"mime",d.mime);
  }
 }
 synchronized JSONObject read(JSONObject p)throws Exception{
  Download d=handles.get(p.optString("token"));long at=p.optLong("offset",-1);int limit=p.optInt("limit",CHUNK);
  if(d==null||System.currentTimeMillis()-d.used>15*60*1000L||!d.file.isFile())throw new IOException("Download expired");
  long size=d.file.length();byte[] b=new byte[AttachmentChunks.range(size,at,limit)];try(RandomAccessFile f=new RandomAccessFile(d.file,"r")){f.seek(at);f.readFully(b);}d.used=System.currentTimeMillis();return Wire.json("offset",at,"data",Base64.encodeToString(b,Base64.NO_WRAP),"eof",at+b.length==size);
 }
 static String safeMime(String mime){return mime.length()<=128&&mime.matches("[a-zA-Z0-9][a-zA-Z0-9.+-]*/[a-zA-Z0-9][a-zA-Z0-9.+-]*")?mime:"application/octet-stream";}
 private long reserved()throws Exception{long n=total();File[] all=root.listFiles();if(all!=null)for(File f:all)if(f.getName().matches("up-[a-f0-9-]{36}\\.json")){String id=f.getName().substring(3,39);JSONObject m=metadata(id);n+=Math.max(0,m.getLong("size")-part(id).length());}return n;}
 private long total(){long n=0;File[] a=root.listFiles();if(a!=null)for(File f:a){if(f.isDirectory()){File[] children=f.listFiles();if(children!=null)for(File child:children)n+=child.length();}else n+=f.length();}return n;}
 private void clean(){File[] a=root.listFiles();if(a==null)return;long now=System.currentTimeMillis();for(File f:a){if(f.isDirectory()){File[] children=f.listFiles();if(children!=null)for(File child:children)if(now-child.lastModified()>24*60*60*1000L)child.delete();File[] left=f.listFiles();if(left!=null&&left.length==0)f.delete();}else if(now-f.lastModified()>24*60*60*1000L)f.delete();}}
 private static void thumbnail(File source,File target,int edge)throws Exception{
  BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(source.getPath(),o);if(o.outWidth<1||o.outHeight<1)throw new IOException("No image");o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>edge*2)o.inSampleSize*=2;o.inJustDecodeBounds=false;Bitmap b=BitmapFactory.decodeFile(source.getPath(),o);if(b==null)throw new IOException("Image decode failed");
  try{int orient=1;try{orient=new ExifInterface(source.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(Exception ignored){}Matrix m=new Matrix();if(orient==6)m.postRotate(90);else if(orient==3)m.postRotate(180);else if(orient==8)m.postRotate(270);float scale=Math.min(1f,(float)edge/Math.max(b.getWidth(),b.getHeight()));m.postScale(scale,scale);Bitmap c=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);Bitmap matte=Bitmap.createBitmap(c.getWidth(),c.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(matte);canvas.drawColor(Color.WHITE);canvas.drawBitmap(c,0,0,null);try(FileOutputStream out=new FileOutputStream(target)){if(!matte.compress(Bitmap.CompressFormat.JPEG,86,out))throw new IOException("Image encode failed");}finally{matte.recycle();if(c!=b)c.recycle();}}finally{b.recycle();}
 }
 /** Decode modern Telegram audio on Pixel to PCM WAV that the S2 can play. */
 private static void decodeAudio(File input,File output)throws Exception{
  MediaExtractor x=new MediaExtractor();MediaCodec codec=null;
  try{x.setDataSource(input.getPath());MediaFormat fmt=null;for(int i=0;i<x.getTrackCount();i++){MediaFormat f=x.getTrackFormat(i);if(f.getString(MediaFormat.KEY_MIME).startsWith("audio/")){fmt=f;x.selectTrack(i);break;}}if(fmt==null)throw new IOException("No audio");
   codec=MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));codec.configure(fmt,null,null,0);codec.start();boolean inputEnd=false,outputEnd=false;int rate=fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);long end=System.currentTimeMillis()+90000;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
   try(RandomAccessFile out=new RandomAccessFile(output,"rw")){out.setLength(0);out.write(new byte[44]);while(!outputEnd){if(System.currentTimeMillis()>end||out.length()>MAX)throw new IOException("Audio conversion limit");if(!inputEnd){int i=codec.dequeueInputBuffer(10000);if(i>=0){ByteBuffer b=codec.getInputBuffer(i);int n=x.readSampleData(b,0);if(n<0){codec.queueInputBuffer(i,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnd=true;}else{codec.queueInputBuffer(i,0,n,x.getSampleTime(),0);x.advance();}}}
    int i=codec.dequeueOutputBuffer(info,10000);if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=codec.getOutputFormat();rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);if(f.containsKey(MediaFormat.KEY_PCM_ENCODING)&&f.getInteger(MediaFormat.KEY_PCM_ENCODING)!=android.media.AudioFormat.ENCODING_PCM_16BIT)throw new IOException("Unsupported PCM");}else if(i>=0){ByteBuffer b=codec.getOutputBuffer(i);b.position(info.offset);b.limit(info.offset+info.size);byte[] bytes=new byte[info.size];b.get(bytes);out.write(bytes);outputEnd=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(i,false);}}
    long size=out.length()-44;ByteBuffer h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);h.put("RIFF".getBytes("US-ASCII")).putInt((int)size+36).put("WAVEfmt ".getBytes("US-ASCII")).putInt(16).putShort((short)1).putShort((short)channels).putInt(rate).putInt(rate*channels*2).putShort((short)(channels*2)).putShort((short)16).put("data".getBytes("US-ASCII")).putInt((int)size);out.seek(0);out.write(h.array());
   }
  }finally{if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}x.release();}
 }
}
