package org.retrobridge.galaxyservices.pixel;

import android.content.*;import android.database.Cursor;import android.net.Uri;import android.provider.MediaStore;
import org.retrobridge.galaxyservices.Wire;import java.io.*;import java.security.MessageDigest;import java.util.*;
import org.json.JSONObject;

/** Receives only paired camera JPEGs. No read permission over the Pixel photo library. */
final class CameraBackupEndpoint {
 static final String HOST="camera.galaxy-services.invalid";
 static final int MAX=32*1024*1024;
 // Use one MediaStore album for both camera photos and videos.
 static final String BACKUP_ALBUM="Pictures/Galaxy S2";
 final Context app;final SharedPreferences receipts;final VideoBackupEndpoint videos;
 CameraBackupEndpoint(Context c){app=c;videos=new VideoBackupEndpoint(c);receipts=c.getSharedPreferences("camera-receipts",0);}
 static void response(OutputStream out,int code,String message)throws Exception{
  byte[] b=message.getBytes("UTF-8");out.write(("HTTP/1.1 "+code+" "+(code==200?"OK":"Error")+"\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: "+b.length+"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));out.write(b);out.flush();
 }
 void status(String text){try{Wire.writeFile(new File(app.getFilesDir(),"camera-status.txt"),text.getBytes("UTF-8"));}catch(Exception ignored){}}
 synchronized void serve(InputStream in,OutputStream out)throws Exception{
  File temp=null;Uri pending=null;String hash=null;
  try{
   String[] lines=new String(Wire.header(in),"ISO-8859-1").split("\r\n");String[] req=lines[0].split(" ");if(req.length==3&&req[1].startsWith("/videos/")){videos.serve(lines,in,out);return;}
   if(req.length!=3||!req[1].matches("/photos/[0-9a-f]{64}")||!(req[0].equals("GET")||req[0].equals("PUT"))){response(out,400,"{\"error\":\"request\"}");return;}
   hash=req[1].substring(8);Map<String,String> headers=new HashMap<>();
   for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');if(colon>0){String k=lines[i].substring(0,colon).toLowerCase(Locale.US);if(headers.put(k,lines[i].substring(colon+1).trim())!=null)throw new IOException("Duplicate header");}}
   boolean exists=receipts.getBoolean("done:"+hash,false);
   if(req[0].equals("GET")){response(out,200,new JSONObject().put("sha256",hash).put("stored",exists).toString());return;}
   int size=Integer.parseInt(headers.get("content-length"));long taken=Long.parseLong(headers.get("x-photo-taken"));
   if(size<4||size>MAX||headers.containsKey("transfer-encoding")||!"image/jpeg".equals(headers.get("content-type"))||taken<0||taken>System.currentTimeMillis()+86400000L){response(out,400,"{\"error\":\"metadata\"}");return;}
   temp=File.createTempFile("camera-", ".pending",app.getCacheDir());MessageDigest digest=MessageDigest.getInstance("SHA-256");
   byte[] buffer=new byte[65536];int left=size;long deadline=android.os.SystemClock.elapsedRealtime()+90000;
   try(FileOutputStream f=new FileOutputStream(temp)){while(left>0){if(android.os.SystemClock.elapsedRealtime()>deadline)throw new IOException("Upload timeout");int n=in.read(buffer,0,Math.min(buffer.length,left));if(n<0)throw new EOFException();f.write(buffer,0,n);digest.update(buffer,0,n);left-=n;}f.getFD().sync();}
   if(!hash.equals(Wire.hex(digest.digest())))throw new IOException("Digest mismatch");
   try(RandomAccessFile f=new RandomAccessFile(temp,"r")){if(f.readUnsignedShort()!=0xffd8)throw new IOException("Not JPEG");f.seek(size-2);if(f.readUnsignedShort()!=0xffd9)throw new IOException("Incomplete JPEG");}
   android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(temp.toString(),options);if(options.outWidth<=0||options.outHeight<=0)throw new IOException("Invalid JPEG");
   if(!exists){
    ContentResolver cr=app.getContentResolver();String journal=receipts.getString("pending:"+hash,null);
    if(journal!=null){Uri prior=Uri.parse(journal);boolean finished=false;try(Cursor cursor=cr.query(prior,new String[]{"is_pending","_size"},null,null,null)){if(cursor!=null&&cursor.moveToFirst())finished=cursor.getInt(0)==0&&cursor.getLong(1)==size;}catch(Exception ignored){}
     if(finished){if(!receipts.edit().putBoolean("done:"+hash,true).remove("pending:"+hash).commit())throw new IOException("Receipt save failed");exists=true;}else{try{cr.delete(prior,null,null);}catch(Exception ignored){}receipts.edit().remove("pending:"+hash).commit();}}
    if(!exists){
     String name="S2_"+new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date(taken))+"_"+hash.substring(0,16)+".jpg";
     ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,name);values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");values.put(MediaStore.Images.Media.RELATIVE_PATH,BACKUP_ALBUM);values.put(MediaStore.Images.Media.DATE_TAKEN,taken);values.put(MediaStore.Images.Media.IS_PENDING,1);
     pending=cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(pending==null)throw new IOException("Photo create failed");if(!receipts.edit().putString("pending:"+hash,pending.toString()).commit())throw new IOException("Journal save failed");
     try(InputStream src=new FileInputStream(temp);OutputStream dest=cr.openOutputStream(pending,"w")){if(dest==null)throw new IOException("Photo storage unavailable");int n;while((n=src.read(buffer))!=-1)dest.write(buffer,0,n);dest.flush();}
     digest.reset();try(InputStream verify=cr.openInputStream(pending)){if(verify==null)throw new IOException("Verify unavailable");int n;while((n=verify.read(buffer))!=-1)digest.update(buffer,0,n);}if(!hash.equals(Wire.hex(digest.digest())))throw new IOException("Stored photo mismatch");
     values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);if(cr.update(pending,values,null,null)!=1)throw new IOException("Publish failed");
     // Keep a published photo on receipt-write failure. Journal lets the next attempt recover it.
     pending=null;if(!receipts.edit().putBoolean("done:"+hash,true).remove("pending:"+hash).putInt("count",receipts.getInt("count",0)+1).commit())throw new IOException("Receipt save failed");
    }
   }
   status("Camera photos received: "+receipts.getInt("count",0));response(out,200,new JSONObject().put("sha256",hash).put("stored",true).toString());
  }catch(Exception e){status("Camera backup: "+e.getClass().getSimpleName());response(out,500,"{\"error\":\"transfer_failed\"}");}
  finally{if(temp!=null)temp.delete();if(pending!=null){try{app.getContentResolver().delete(pending,null,null);}catch(Exception ignored){}if(hash!=null)receipts.edit().remove("pending:"+hash).commit();}}
 }
}
