package org.retrobridge.galaxyservices.pixel;
import android.content.*;import android.database.Cursor;import android.net.Uri;import android.provider.MediaStore;import android.media.MediaMetadataRetriever;
import java.io.*;import java.util.*;import java.security.*;import org.json.*;import org.retrobridge.galaxyservices.Wire;

/** Resumable, digest-checked camera videos; only owned MediaStore entries are touched. */
final class VideoBackupEndpoint {
 static final long MAX=4L*1024*1024*1024;static final int CHUNK=262144;
 final Context app;final File dir;final SharedPreferences receipts;
 VideoBackupEndpoint(Context c){app=c;dir=new File(c.getFilesDir(),"camera-video-incoming");dir.mkdirs();receipts=c.getSharedPreferences("camera-video-receipts",0);}
 static String digest(InputStream in)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);return Wire.hex(d.digest());}
 static void save(File file,JSONObject value)throws Exception{File tmp=new File(file+".new");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(value.toString().getBytes("UTF-8"));out.getFD().sync();}if(!tmp.renameTo(file))throw new IOException("Video journal");}
 static HashMap<String,String> headers(String[] lines)throws Exception{HashMap<String,String> h=new HashMap<>();for(int i=1;i<lines.length;i++){int n=lines[i].indexOf(':');if(n>0&&h.put(lines[i].substring(0,n).toLowerCase(Locale.US),lines[i].substring(n+1).trim())!=null)throw new IOException("Duplicate header");}if(h.containsKey("transfer-encoding"))throw new IOException("Transfer encoding");return h;}
 synchronized void serve(String[] lines,InputStream in,OutputStream out)throws Exception{
  try{
   String[] req=lines[0].split(" ");if(req.length!=3||!req[1].matches("/videos/[0-9a-f]{64}(/complete)?"))throw new IOException("Video route");String hash=req[1].substring(8,72);boolean finish=req[1].endsWith("/complete");Map<String,String> h=headers(lines);
   if(!req[0].equals("GET")&&!req[0].equals("PUT")&&!req[0].equals("POST"))throw new IOException("Video method");
   File part=new File(dir,hash+".part"),meta=new File(dir,hash+".json");JSONObject state=meta.isFile()?new JSONObject(new String(Wire.readFile(meta),"UTF-8")):null;boolean done=receipts.getBoolean("done:"+hash,false);
   if(req[0].equals("GET")&&!finish){CameraBackupEndpoint.response(out,200,new JSONObject().put("sha256",hash).put("stored",done).put("offset",state==null?0:state.getLong("offset")).toString());return;}
   if(done){part.delete();meta.delete();CameraBackupEndpoint.response(out,200,new JSONObject().put("sha256",hash).put("stored",true).put("offset",0).toString());return;}
   long total=Long.parseLong(h.get("x-video-size")),taken=Long.parseLong(h.get("x-video-taken"));String mime=h.get("x-video-type");
   if(total<12||total>MAX||taken<0||taken>System.currentTimeMillis()+86400000L||!("video/mp4".equals(mime)||"video/3gpp".equals(mime)))throw new IOException("Video metadata");
   if(state==null){if(dir.getUsableSpace()<total+64*1024*1024L)throw new IOException("Video storage full");String[] pending=dir.list((d,n)->n.endsWith(".json"));if(pending!=null&&pending.length>=16)throw new IOException("Pending video limit");state=new JSONObject().put("size",total).put("taken",taken).put("mime",mime).put("offset",0);save(meta,state);}
   if(state.getLong("size")!=total||state.getLong("taken")!=taken||!state.getString("mime").equals(mime))throw new IOException("Video identity changed");
   if(!done&&req[0].equals("PUT")&&!finish){
    long offset=Long.parseLong(h.get("x-video-offset")),expected=state.getLong("offset");int size=Integer.parseInt(h.get("content-length"));String chunkHash=h.get("x-chunk-sha256");if(size<1||size>CHUNK||offset!=expected||offset+size>total||chunkHash==null||!chunkHash.matches("[0-9a-f]{64}"))throw new IOException("Video chunk range");
    byte[] chunk=new byte[size];int n=0;while(n<size){int got=in.read(chunk,n,size-n);if(got<0)throw new EOFException();n+=got;}if(!chunkHash.equals(Wire.hex(MessageDigest.getInstance("SHA-256").digest(chunk))))throw new IOException("Video chunk checksum");
    try(RandomAccessFile f=new RandomAccessFile(part,"rw")){if(f.length()<expected)throw new IOException("Video journal length");f.setLength(expected);f.seek(expected);f.write(chunk);f.getFD().sync();}
    state.put("offset",offset+size);save(meta,state);
   }else if(!done&&req[0].equals("POST")&&finish){
    if(state.getLong("offset")!=total||part.length()!=total)throw new IOException("Video incomplete");try(InputStream verify=new FileInputStream(part)){if(!hash.equals(digest(verify)))throw new IOException("Video checksum");}
    MediaMetadataRetriever probe=new MediaMetadataRetriever();try{probe.setDataSource(part.toString());if(!"yes".equals(probe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)))throw new IOException("No video track");}finally{probe.release();}
    Uri uri=null;String journal=receipts.getString("pending:"+hash,null);ContentResolver cr=app.getContentResolver();if(journal!=null)uri=Uri.parse(journal);
    boolean published=false;if(uri!=null){try(Cursor c=cr.query(uri,new String[]{"is_pending","_size"},null,null,null)){if(c!=null&&c.moveToFirst()&&c.getInt(0)==0&&c.getLong(1)==total){try(InputStream verify=cr.openInputStream(uri)){published=verify!=null&&hash.equals(digest(verify));}}else if(c==null||c.getCount()==0)uri=null;}}
    if(!published){
     if(uri==null){ContentValues values=new ContentValues();values.put("_display_name","S2_"+new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date(taken))+"_"+hash.substring(0,16)+(mime.equals("video/mp4")?".mp4":".3gp"));values.put("mime_type",mime);values.put("relative_path",CameraBackupEndpoint.BACKUP_ALBUM);values.put("datetaken",taken);values.put("is_pending",1);uri=cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IOException("Video create");if(!receipts.edit().putString("pending:"+hash,uri.toString()).commit())throw new IOException("Video receipt journal");}
     try(InputStream src=new FileInputStream(part);OutputStream dst=cr.openOutputStream(uri,"wt")){if(dst==null)throw new IOException("Video storage");byte[] b=new byte[65536];int n;while((n=src.read(b))!=-1)dst.write(b,0,n);dst.flush();}
     try(InputStream verify=cr.openInputStream(uri)){if(verify==null||!hash.equals(digest(verify)))throw new IOException("Stored video checksum");}
     ContentValues values=new ContentValues();values.put("is_pending",0);if(cr.update(uri,values,null,null)!=1)throw new IOException("Video publish");
    }
    if(!receipts.edit().putBoolean("done:"+hash,true).remove("pending:"+hash).putInt("count",receipts.getInt("count",0)+1).commit())throw new IOException("Video receipt save");done=true;
    Wire.writeFile(new File(app.getFilesDir(),"camera-video-status.txt"),("Camera videos received: "+receipts.getInt("count",0)).getBytes("UTF-8"));
   }else if(!done)throw new IOException("Video method");
   long offset=state.getLong("offset");if(done){part.delete();meta.delete();new File(meta+".new").delete();}
   CameraBackupEndpoint.response(out,200,new JSONObject().put("sha256",hash).put("stored",done).put("offset",offset).toString());
  }catch(Exception e){Wire.writeFile(new File(app.getFilesDir(),"camera-video-status.txt"),("Video backup waiting: "+e.getClass().getSimpleName()).getBytes("UTF-8"));CameraBackupEndpoint.response(out,503,"{\"error\":\"video_transfer_incomplete\"}");}
 }
}
