package org.retrobridge.galaxyservices.pixel;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.net.Uri;
import android.graphics.*;
import org.retrobridge.galaxyservices.Wire;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;

/** Private, acknowledged attachment inbox. No message content is logged. */
final class IncomingMedia {
 static final int MAX=32*1024*1024;
 private static IncomingMedia instance;
 static synchronized IncomingMedia get(Context c)throws Exception {if(instance==null)instance=new IncomingMedia(c.getApplicationContext());return instance;}
 final Context app;final SQLiteDatabase db;final File folder;final SharedPreferences prefs;
 IncomingMedia(Context c)throws Exception {
  app=c;prefs=c.getSharedPreferences("incoming-media",0);folder=new File(c.getFilesDir(),"incoming-media");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("storage");
  db=c.openOrCreateDatabase("incoming-media.db",0,null);
  db.execSQL("CREATE TABLE IF NOT EXISTS incoming (id TEXT PRIMARY KEY,phone TEXT NOT NULL,mime TEXT NOT NULL,caption TEXT NOT NULL,stamp INTEGER NOT NULL,digest TEXT NOT NULL,size INTEGER NOT NULL,source TEXT NOT NULL,received INTEGER NOT NULL DEFAULT 0)");
  db.execSQL("PRAGMA synchronous=FULL");
 }
 synchronized void initialize()throws Exception {
  if(prefs.contains("baseline"))return;
  long high=0;try(Cursor c=app.getContentResolver().query(Uri.parse("content://mms"),new String[]{"_id"},null,null,"_id DESC")){if(c==null)throw new IOException("MMS access");if(c.moveToFirst())high=c.getLong(0);}
  if(!prefs.edit().putLong("baseline",high).putLong("started",System.currentTimeMillis()).commit())throw new IOException("settings");
 }
 boolean enabled(){return app.getSharedPreferences("media-relay",0).getBoolean("enabled",false);}
 boolean exists(String id){try(Cursor c=db.rawQuery("SELECT 1 FROM incoming WHERE id=?",new String[]{id})){return c.moveToFirst();}}
 static String hash(String s)throws Exception{return Wire.hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")));}
 File file(String id)throws IOException{if(!id.matches("[0-9a-f]{64}"))throw new IOException("id");return new File(folder,id+".bin");}
 synchronized void scan()throws Exception {
  if(!enabled())return;initialize();
  try(Cursor c=app.getContentResolver().query(Uri.parse("content://mms/inbox"),new String[]{"_id","date","m_type"},"_id>?",new String[]{Long.toString(prefs.getLong("baseline",0))},"_id ASC")){
   if(c==null)throw new IOException("MMS unavailable");int handled=0;
   while(c.moveToNext()&&handled<8){long mid=c.getLong(0);if(c.getInt(2)!=132)continue;String id=hash("mms:"+mid);if(exists(id))continue;handled++;readMms(mid,id,c.getLong(1)*1000L);}
  }
 }
 void readMms(long mid,String id,long stamp)throws Exception {
  ContentResolver cr=app.getContentResolver();String phone=null,caption="",mime=null;long part=-1;int media=0,recipients=0;
  try(Cursor c=cr.query(Uri.parse("content://mms/"+mid+"/addr"),new String[]{"address","type"},null,null,null)){
   if(c==null)return;while(c.moveToNext()){if(c.getInt(1)==137)phone=RcsRelay.get(app).number(c.getString(0));if(c.getInt(1)==151||c.getInt(1)==130||c.getInt(1)==129)recipients++;}
  }if(phone==null||recipients>1)return;
  try(Cursor c=cr.query(Uri.parse("content://mms/"+mid+"/part"),new String[]{"_id","ct","text"},null,null,"seq ASC")){
   if(c==null)return;while(c.moveToNext()){String type=c.getString(1);if("text/plain".equals(type)){if(c.getString(2)!=null)caption+=(caption.isEmpty()?"":"\n")+c.getString(2);}else if(type!=null&&(type.startsWith("image/")||type.startsWith("video/"))){part=c.getLong(0);mime=type;media++;}}
  }
  if(media==1&&caption.length()<=10000)capture(id,phone,stamp,caption,mime,Uri.parse("content://mms/part/"+part),"mms");
 }
 synchronized boolean notification(String route,String phone,long stamp,String caption,String mime,Uri uri)throws Exception {
  if(!enabled())return false;initialize();if(stamp<prefs.getLong("started",Long.MAX_VALUE))return false;
  if(uri==null||!"content".equals(uri.getScheme())||mime==null||!(mime.startsWith("image/")||mime.startsWith("video/")))return false;
  // MMS is copied from the public telephony provider, never twice from a notification.
  try(Cursor c=app.getContentResolver().query(Uri.parse("content://mms/inbox"),new String[]{"_id"},"date>=? AND date<=?",new String[]{Long.toString(stamp/1000-15),Long.toString(stamp/1000+15)},null)){
   if(c==null)throw new IOException("classification");while(c.moveToNext()){
    try(Cursor a=app.getContentResolver().query(Uri.parse("content://mms/"+c.getLong(0)+"/addr"),new String[]{"address","type"},null,null,null)){if(a!=null)while(a.moveToNext())if(a.getInt(1)==137&&phone.equals(RcsRelay.get(app).number(a.getString(0))))return false;}
   }
  }
  String id=hash("notification:"+route+":"+stamp+":"+uri.toString());
  if(exists(id))return true;
  capture(id,phone,stamp,caption==null?"":caption,mime,uri,"notification");return true;
 }
 void capture(String id,String phone,long stamp,String caption,String mime,Uri uri,String source)throws Exception {
  if(exists(id)||caption.length()>10000)return;
  try(Cursor c=db.rawQuery("SELECT count(*),coalesce(sum(size),0) FROM incoming WHERE received=0",null)){c.moveToFirst();if(c.getInt(0)>=64||c.getLong(1)>128L*1024*1024)throw new IOException("queue full");}
  File temp=new File(folder,id+".tmp"),dest=file(id);long size=0;
  try {
   try(InputStream in=app.getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(temp)){if(in==null)throw new IOException("attachment");byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){size+=n;if(size>MAX)throw new IOException("attachment size");out.write(b,0,n);}out.getFD().sync();}
   byte[] head=new byte[16];try(FileInputStream in=new FileInputStream(temp)){if(in.read(head)<12)throw new IOException("attachment header");}
   if(mime.startsWith("image/")){
    BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(temp.getPath(),o);if(o.outWidth<1||o.outHeight<1)throw new IOException("image format");
    o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>1600)o.inSampleSize*=2;o.inJustDecodeBounds=false;
    Bitmap image=BitmapFactory.decodeFile(temp.getPath(),o);if(image==null)throw new IOException("image decoding");try(FileOutputStream out=new FileOutputStream(dest)){if(!image.compress(Bitmap.CompressFormat.JPEG,88,out))throw new IOException("image encoding");out.getFD().sync();}finally{image.recycle();}mime="image/jpeg";
   }else if(("video/mp4".equals(mime)||"video/3gpp".equals(mime))&&"ftyp".equals(new String(head,4,4,"US-ASCII"))){if(!temp.renameTo(dest))throw new IOException("storage");}
   else throw new IOException("attachment format");
   MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(dest)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
   ContentValues v=new ContentValues();v.put("id",id);v.put("phone",phone);v.put("stamp",stamp);v.put("caption",caption);v.put("mime",mime);v.put("digest",Wire.hex(digest.digest()));v.put("size",dest.length());v.put("source",source);db.insertOrThrow("incoming",null,v);
  }finally{temp.delete();if(!exists(id))dest.delete();}
 }
 synchronized JSONObject list()throws Exception {
  scan();JSONArray a=new JSONArray();try(Cursor c=db.rawQuery("SELECT id,phone,mime,caption,stamp,digest,size,source FROM incoming WHERE received=0 ORDER BY stamp LIMIT 4",null)){
   while(c.moveToNext())a.put(new JSONObject().put("id",c.getString(0)).put("number",c.getString(1)).put("mime",c.getString(2)).put("caption",c.getString(3)).put("time",c.getLong(4)).put("sha256",c.getString(5)).put("size",c.getLong(6)).put("source",c.getString(7)));
  }return new JSONObject().put("state","ok").put("messages",a);
 }
 synchronized File attachment(String id)throws Exception {if(!enabled())throw new IOException("disabled");try(Cursor c=db.rawQuery("SELECT received FROM incoming WHERE id=?",new String[]{id})){if(!c.moveToFirst()||c.getInt(0)!=0)throw new IOException("missing");}File f=file(id);if(!f.isFile())throw new IOException("missing");return f;}
 synchronized JSONObject ack(String id)throws Exception {ContentValues v=new ContentValues();v.put("received",1);db.update("incoming",v,"id=?",new String[]{id});file(id).delete();return new JSONObject().put("state","ok");}
}
