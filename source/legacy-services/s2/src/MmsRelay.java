package org.retrobridge.galaxyservices.s2;

import android.app.*;import android.content.*;import android.database.*;import android.database.sqlite.*;import android.net.Uri;import android.net.wifi.WifiManager;import android.os.*;
import org.retrobridge.galaxyservices.*;import java.io.*;import java.net.*;import java.security.*;import java.util.*;import javax.net.ssl.HttpsURLConnection;import org.json.*;

/** Observes only new owner-created stock MMS outbox entries. Never replays history. */
final class MmsRelay {
 static final Uri MMS=Uri.parse("content://mms"),OUTBOX=Uri.parse("content://mms/outbox"),PENDING=Uri.parse("content://mms-sms/pending");
 final LegacyService app;final Handler handler=new Handler();final SharedPreferences prefs;final SQLiteDatabase db;ContentObserver observer;volatile boolean stopped,busy;
 MmsRelay(LegacyService s){app=s;prefs=s.getSharedPreferences("media-relay",0);db=open(s);}
 static SQLiteDatabase open(Context c){SQLiteDatabase db=c.openOrCreateDatabase("message-media.db",0,null);db.execSQL("CREATE TABLE IF NOT EXISTS attempts (mid INTEGER NOT NULL,stamp INTEGER NOT NULL,id TEXT NOT NULL UNIQUE,digest TEXT,state TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(mid,stamp))");return db;}
 static void recoverBaseline(Context app)throws Exception{
  SQLiteDatabase db=open(app);long mid,stamp;Cursor c=db.rawQuery("SELECT mid,stamp FROM attempts WHERE state='BASELINE'",null);
  try{if(c.getCount()!=1||!c.moveToFirst())throw new IOException("Expected one existing attachment");mid=c.getLong(0);stamp=c.getLong(1);}finally{c.close();db.close();}
  ContentResolver cr=app.getContentResolver();c=cr.query(MMS,new String[]{"_id"},"_id=? AND date=? AND msg_box=4",key(mid,stamp),null);try{if(c==null||c.getCount()!=1)throw new IOException("Attachment changed");}finally{if(c!=null)c.close();}
  ContentValues v=new ContentValues();v.put("err_type",10);v.put("err_code",1);if(cr.update(PENDING,v,pendingSelection(mid),null)!=1)throw new IOException("Pending row missing");
  cr.notifyChange(MMS,null);cr.notifyChange(Uri.parse("content://mms-sms/conversations"),null);
 }
 static int enable(Context c)throws Exception{
  if(!new File(c.getFilesDir(),"media-relay.key").isFile())throw new IOException("Pairing missing");
  ComponentName nativeMms=new ComponentName("com.android.mms","com.android.mms.transaction.TransactionService");
  if(c.getPackageManager().getComponentEnabledSetting(nativeMms)!=android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED)throw new IOException("Original MMS service still enabled");
  SharedPreferences p=c.getSharedPreferences("media-relay",0);if(p.getBoolean("initialized",false)){if(!p.edit().putBoolean("enabled",true).commit())throw new IOException("Settings");return 0;}
  SQLiteDatabase db=open(c);int n=0;Cursor rows=null;db.beginTransaction();try{
   rows=c.getContentResolver().query(OUTBOX,new String[]{"_id","date"},null,null,null);if(rows==null)throw new IOException("Outbox unavailable");
   while(rows.moveToNext()){ContentValues v=new ContentValues();v.put("mid",rows.getLong(0));v.put("stamp",rows.getLong(1));v.put("id",UUID.randomUUID().toString().replace("-",""));v.put("state","BASELINE");v.put("created",System.currentTimeMillis());db.insertWithOnConflict("attempts",null,v,SQLiteDatabase.CONFLICT_IGNORE);n++;}
   db.setTransactionSuccessful();
  }finally{if(rows!=null)rows.close();db.endTransaction();db.close();}
  // A crash before this commit simply repeats the no-send baseline, conservatively.
  if(!p.edit().putBoolean("initialized",true).putBoolean("enabled",true).commit())throw new IOException("Settings");return n;
 }
 void start(){observer=new ContentObserver(handler){public void onChange(boolean self){handler.removeCallbacks(changed);handler.postDelayed(changed,2500);}};app.getContentResolver().registerContentObserver(MMS,true,observer);handler.postDelayed(timer,10000);}
 final Runnable changed=new Runnable(){public void run(){kick();}};
 final Runnable timer=new Runnable(){public void run(){kick();if(!stopped)handler.postDelayed(this,15000);}};
 PendingIntent retryIntent(){return PendingIntent.getService(app,41,new Intent(app,LegacyService.class).putExtra("media",true),PendingIntent.FLAG_UPDATE_CURRENT);}
 void retry(boolean needed){AlarmManager alarm=(AlarmManager)app.getSystemService(Context.ALARM_SERVICE);if(needed&&!stopped&&prefs.getBoolean("enabled",false))alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+60000,retryIntent());else alarm.cancel(retryIntent());}
 void close(){stopped=true;retry(false);handler.removeCallbacksAndMessages(null);if(observer!=null)app.getContentResolver().unregisterContentObserver(observer);}
 synchronized void kick(){if(stopped||busy||!prefs.getBoolean("enabled",false)||!prefs.getBoolean("initialized",false))return;busy=true;new Thread(new Runnable(){public void run(){sync();}},"message-media").start();}
 void status(String message){try{Wire.writeFile(new File(app.getFilesDir(),"media-status.txt"),message.getBytes("UTF-8"));}catch(Exception ignored){}}
 static String hash(byte[] b)throws Exception{return Wire.hex(MessageDigest.getInstance("SHA-256").digest(b));}
 File payloadFile(String id){return new File(app.getFilesDir(),"outgoing-media-"+id+".bin");}
 static String[] key(long mid,long stamp){return new String[]{String.valueOf(mid),String.valueOf(stamp)};}
 // Samsung FL24's pending provider drops selectionArgs. Only validated numeric IDs
 // may be placed in this selection; never interpolate addresses or message text.
 static String pendingSelection(long mid){if(mid<1)throw new IllegalArgumentException("Message ID");return "proto_type=1 AND msg_id="+Long.toString(mid);}
 boolean current(long mid,long stamp){Cursor c=app.getContentResolver().query(MMS,new String[]{"_id"},"_id=? AND date=? AND msg_box=4",key(mid,stamp),null);try{return c!=null&&c.moveToFirst();}finally{if(c!=null)c.close();}}
 MediaPayload read(long mid)throws Exception{
  ContentResolver cr=app.getContentResolver();Cursor c=cr.query(Uri.parse("content://mms/"+mid+"/addr"),new String[]{"address","type"},null,null,null);String number=null;int recipients=0;
  try{if(c==null)throw new IOException("Recipients unavailable");while(c.moveToNext()){int t=c.getInt(1);if(t==151){recipients++;number=c.getString(0);}else if(t==130||t==129)throw new IOException("One recipient supported");}}finally{if(c!=null)c.close();}
  if(recipients!=1||number==null)throw new IOException("One recipient supported");number=android.telephony.PhoneNumberUtils.stripSeparators(number);
  String mime=null,caption="";long mediaId=-1;int count=0;
  c=cr.query(Uri.parse("content://mms/"+mid+"/part"),new String[]{"_id","ct","text","seq"},null,null,"seq ASC");
  try{if(c==null)throw new IOException("Parts unavailable");while(c.moveToNext()){String type=c.getString(1);if("application/smil".equals(type))continue;if("text/plain".equals(type)){String t=c.getString(2);if(t!=null)caption+=(caption.length()>0?"\n":"")+t;}else if(type!=null&&(type.startsWith("image/")||type.startsWith("video/"))){count++;mime=type;mediaId=c.getLong(0);}else throw new IOException("Attachment type unsupported");}}finally{if(c!=null)c.close();}
  if(count!=1)throw new IOException("One picture or video supported");
  ByteArrayOutputStream b=new ByteArrayOutputStream();InputStream in=cr.openInputStream(Uri.parse("content://mms/part/"+mediaId));if(in==null)throw new IOException("Attachment unavailable");
  try{byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1){if(b.size()+n>MediaPayload.MAX_MEDIA)throw new IOException("Attachment exceeds 2 MB");b.write(buffer,0,n);}}finally{in.close();}
  byte[] bytes=b.toByteArray();if(mime.startsWith("image/"))mime=MediaPayload.imageMime(bytes);
  return new MediaPayload(number,mime,caption,bytes);
 }
 static JSONObject request(Context app,String method,String id,byte[] payload,String digest)throws Exception{
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://media.galaxy-services.invalid/outgoing/"+id+(method.equals("POST")?"/send":"")).openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",Wire.LOCAL)));
  c.setConnectTimeout(15000);c.setReadTimeout(45000);c.setUseCaches(false);c.setInstanceFollowRedirects(false);c.setRequestMethod(method);
  String secret=new String(Wire.readFile(new File(app.getFilesDir(),"media-relay.key")),"US-ASCII").trim();if(!secret.matches("[0-9a-f]{64}"))throw new IOException("Pairing missing");c.setRequestProperty("X-Galaxy-Media-Key",secret);
  try{if(method.equals("PUT")||method.equals("POST")){c.setDoOutput(true);c.setFixedLengthStreamingMode(payload==null?0:payload.length);c.setRequestProperty("Content-Type","application/octet-stream");if(digest!=null)c.setRequestProperty("X-Media-SHA256",digest);OutputStream out=c.getOutputStream();try{if(payload!=null)out.write(payload);}finally{out.close();}}
   if(c.getResponseCode()!=200)throw new IOException("Pixel unavailable");ByteArrayOutputStream b=new ByteArrayOutputStream();InputStream in=c.getInputStream();try{byte[] bytes=new byte[1024];int n;while((n=in.read(bytes))!=-1){if(b.size()+n>4096)throw new IOException("Reply size");b.write(bytes,0,n);}}finally{in.close();}JSONObject result=new JSONObject(b.toString("UTF-8"));if(!id.equals(result.getString("id")))throw new IOException("Receipt mismatch");return result;
  }finally{c.disconnect();}
 }
 void localState(String id,String state){ContentValues v=new ContentValues();v.put("state",state);db.update("attempts",v,"id=?",new String[]{id});}
 void finish(long mid,long stamp,String id,boolean sent)throws Exception{
  // Restrict every stock-provider mutation to the exact owner attempt we observed.
  if(current(mid,stamp)){
   ContentValues v=new ContentValues();v.put("resp_st",sent?128:192);if(sent)v.put("msg_box",2);
   if(app.getContentResolver().update(MMS,v,"_id=? AND date=? AND msg_box=4",key(mid,stamp))==1){
    // The stock delete_mms_pending_on_update trigger removes the pending row
    // atomically when the PDU leaves the outbox. This provider has no pending DELETE.
    if(!sent){v.clear();v.put("err_type",10);v.put("err_code",1);app.getContentResolver().update(PENDING,v,pendingSelection(mid),null);}
   }
  }
  localState(id,sent?"SENT":"FAILED");payloadFile(id).delete();status(sent?"Picture/video sent":"Picture/video could not be sent");
 }
 void sync(){PowerManager.WakeLock wake=null;WifiManager.WifiLock wifi=null;boolean waiting=false;try{
  wake=((PowerManager)app.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GalaxyServices:messages");wake.acquire(180000);
  waiting=true;try{new IncomingMms(app).sync();}catch(Exception incoming){try{Wire.writeFile(new File(app.getFilesDir(),"incoming-media-status.txt"),(incoming.getClass().getSimpleName()+": "+incoming.getMessage()).getBytes("UTF-8"));}catch(Exception ignored){}}
  List<long[]> rows=new ArrayList<long[]>();Cursor c=app.getContentResolver().query(OUTBOX,new String[]{"_id","date"},null,null,"date ASC");try{if(c==null)throw new IOException("Outbox unavailable");while(c.moveToNext())rows.add(new long[]{c.getLong(0),c.getLong(1)});}finally{if(c!=null)c.close();}
  int work=0;long deadline=SystemClock.elapsedRealtime()+120000;
  for(long[] row:rows){if(stopped||!prefs.getBoolean("enabled",false)||work>=4||SystemClock.elapsedRealtime()>deadline)break;long mid=row[0],stamp=row[1];if(stamp>System.currentTimeMillis()/1000-3)continue;
   String id=null,digest=null,state=null;c=db.rawQuery("SELECT id,digest,state FROM attempts WHERE mid=? AND stamp=?",key(mid,stamp));try{if(c.moveToFirst()){id=c.getString(0);digest=c.getString(1);state=c.getString(2);}}finally{c.close();}
   if("BASELINE".equals(state)||"SENT".equals(state)||"FAILED".equals(state))continue;
   if(id==null){
    // A manual retry cannot resolve an unknown outcome from an earlier attempt.
    c=db.rawQuery("SELECT count(*) FROM attempts WHERE mid=? AND state IN ('UNCERTAIN','SUBMITTING','SUBMITTED')",new String[]{String.valueOf(mid)});boolean unknown;try{c.moveToFirst();unknown=c.getInt(0)>0;}finally{c.close();}if(unknown){status("Check the earlier message on Pixel before retrying");continue;}
    id=UUID.randomUUID().toString().replace("-","");ContentValues v=new ContentValues();v.put("mid",mid);v.put("stamp",stamp);v.put("id",id);v.put("created",System.currentTimeMillis());
    try{byte[] bytes=read(mid).encode();if(!current(mid,stamp))continue;digest=hash(bytes);Wire.writeFile(payloadFile(id),bytes);v.put("digest",digest);v.put("state","READY");db.insertOrThrow("attempts",null,v);}catch(IOException invalid){v.put("state","FAILED");db.insertWithOnConflict("attempts",null,v,SQLiteDatabase.CONFLICT_IGNORE);finish(mid,stamp,id,false);final String reason=invalid.getMessage();handler.post(new Runnable(){public void run(){ToastMessage.show(app,reason);}});continue;}
   }
   if(!current(mid,stamp))continue;work++;waiting=true;app.gateway();if(wifi==null){wifi=((WifiManager)app.getSystemService(Context.WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"GalaxyServices:messages");wifi.acquire();}
   JSONObject reply=request(app,"GET",id,null,null);String remote=reply.getString("state");
   if(remote.equals("MISSING")){if("SUBMITTED".equals(state)||"SUBMITTING".equals(state)||"UNCERTAIN".equals(state)){localState(id,"UNCERTAIN");status("Check message status on Pixel");continue;}byte[] bytes=Wire.readFile(payloadFile(id));if(!digest.equals(hash(bytes)))throw new IOException("Local attachment changed");reply=request(app,"PUT",id,bytes,digest);remote=reply.getString("state");}
   if(remote.equals("STAGED")){if(!current(mid,stamp))continue;localState(id,"SUBMITTING");reply=request(app,"POST",id,null,null);remote=reply.getString("state");}
   if(remote.equals("SENT"))finish(mid,stamp,id,true);
   else if(remote.equals("FAILED"))finish(mid,stamp,id,false);
   else if(remote.equals("UNCERTAIN")){localState(id,"UNCERTAIN");status("Check message status on Pixel");}
   else if(remote.equals("SUBMITTED")||remote.equals("SUBMITTING")){localState(id,remote);status("Sending picture/video through Pixel");}
   else status("Waiting for Pixel message access");
  }
 }catch(Exception e){waiting=true;status("Pictures/videos waiting for Pixel");}finally{retry(waiting);if(wifi!=null&&wifi.isHeld())wifi.release();if(wake!=null&&wake.isHeld())wake.release();synchronized(this){busy=false;}}}
 static final class ToastMessage{static void show(Context c,String reason){android.widget.Toast.makeText(c,"Picture/video not sent: "+reason,android.widget.Toast.LENGTH_LONG).show();}}
}
