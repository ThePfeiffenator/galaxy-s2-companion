package org.retrobridge.galaxyservices.s2;
import android.app.*;import android.content.*;import android.net.Uri;import android.os.*;
import java.io.*;import java.net.*;import java.util.*;import javax.net.ssl.HttpsURLConnection;import org.json.*;import org.retrobridge.galaxyservices.*;

/** Bounded polling over the saved authenticated phone link; no PC dependencies. */
final class CompanionSync {
 final LegacyService app;final Handler handler=new Handler();volatile boolean stopped,busy;long contactsAt,newsAt;boolean first=true;JSONObject shown=new JSONObject();final File cache;
 CompanionSync(LegacyService c){app=c;cache=new File(c.getFilesDir(),"forwarded-notifications.json");try{shown=new JSONObject(new String(Wire.readFile(cache),"UTF-8"));}catch(Exception ignored){}}
 final Runnable timer=new Runnable(){public void run(){kick();if(!stopped)handler.postDelayed(this,15000);}};
 PendingIntent alarm(){return PendingIntent.getService(app,62,new Intent(app,LegacyService.class).putExtra("companionSync",true),PendingIntent.FLAG_UPDATE_CURRENT);}
 void start(){handler.postDelayed(timer,15000);}
 void close(){stopped=true;handler.removeCallbacksAndMessages(null);((AlarmManager)app.getSystemService(Context.ALARM_SERVICE)).cancel(alarm());}
 synchronized void kick(){if(stopped||busy)return;busy=true;new Thread(new Runnable(){public void run(){sync();}},"companion-sync").start();}
 JSONObject fetch(String path,int limit)throws Exception{
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://companion.galaxy-services.invalid/"+path).openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",Wire.LOCAL)));
  c.setConnectTimeout(10000);c.setReadTimeout(30000);c.setUseCaches(false);c.setInstanceFollowRedirects(false);c.setRequestProperty("X-Companion-Key",new String(Wire.readFile(new File(app.getFilesDir(),"link.key")),"US-ASCII").trim());
  try{if(c.getResponseCode()!=200)throw new IOException("Companion unavailable");ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1){if(b.size()+n>limit)throw new IOException("Snapshot limit");b.write(bytes,0,n);}}return new JSONObject(b.toString("UTF-8"));}finally{c.disconnect();}
 }
 android.graphics.Bitmap icon(String hash){try{if(!hash.matches("[0-9a-f]{64}"))return null;File file=new File(app.getCacheDir(),"notice-icon-"+hash+".png");if(!file.isFile()){JSONObject result=fetch("icons/"+hash,100000);byte[] png=android.util.Base64.decode(result.getString("png"),android.util.Base64.DEFAULT);if(png.length>65536||!hash.equals(Wire.hex(java.security.MessageDigest.getInstance("SHA-256").digest(png))))throw new IOException("Icon checksum");Wire.writeFile(file,png);}return android.graphics.BitmapFactory.decodeFile(file.toString());}catch(Exception e){return null;}}
 void notices(JSONObject feed)throws Exception{
  JSONObject next=new JSONObject();JSONArray rows=feed.optBoolean("enabled",false)?feed.getJSONArray("items"):new JSONArray();
  if(feed.optBoolean("enabled",false)&&(feed.getInt("version")!=1||!feed.getBoolean("complete")||Math.abs(System.currentTimeMillis()-feed.getLong("generated"))>300000||rows.length()>200))throw new IOException("Invalid notifications");
  for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);String key=r.getString("key");if(!key.matches("[0-9a-f]{64}")||next.has(key)||r.getString("app").length()>120||r.getString("title").length()>200||r.getString("text").length()>1800||r.getLong("when")<0)throw new IOException("Invalid notification");next.put(key,r);}
  NotificationManager manager=(NotificationManager)app.getSystemService(Context.NOTIFICATION_SERVICE);
  for(Iterator<String> it=shown.keys();it.hasNext();){String key=it.next();if(!next.has(key))manager.cancel(key,710);}
  // Commit before publishing so a tap always resolves to the same private content.
  File tmp=new File(cache+".new");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(next.toString().getBytes("UTF-8"));out.getFD().sync();}if(!tmp.renameTo(cache))throw new IOException("Notification storage");
  for(Iterator<String> it=next.keys();it.hasNext();){String key=it.next();JSONObject r=next.getJSONObject(key),prior=shown.optJSONObject(key);String iconKey=r.optString("icon");boolean iconCached=iconKey.length()==0||new File(app.getCacheDir(),"notice-icon-"+iconKey+".png").isFile();
   // Android clears notifications at reboot; rebuild them silently on first sync.
   // Retry a missing image after a link interruption without repeating the sound.
   if(!first&&iconCached&&prior!=null&&prior.toString().equals(r.toString()))continue;
   Intent intent=new Intent(app,ForwardedNoticeActivity.class).setData(Uri.parse("galaxy-notification:"+key)).putExtra("key",key);PendingIntent tap=PendingIntent.getActivity(app,0,intent,PendingIntent.FLAG_UPDATE_CURRENT);
   String title=r.getString("title"),label=r.getString("app");Notification n=new Notification.Builder(app).setLargeIcon(icon(r.optString("icon"))).setSmallIcon(R.drawable.service_notification).setContentTitle(title.length()==0?label:label+" · "+title).setContentText(r.getString("text")).setWhen(r.getLong("when")).setContentIntent(tap).setOnlyAlertOnce(true).setAutoCancel(true).getNotification();
   if(!first&&prior==null&&r.optBoolean("alert"))n.defaults=Notification.DEFAULT_SOUND|Notification.DEFAULT_LIGHTS;manager.notify(key,710,n);
  }
  shown=next;first=false;
 }
 void sync(){PowerManager.WakeLock wake=null;try{
  app.gateway();wake=((PowerManager)app.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GalaxyServices:companion-sync");wake.acquire(120000);
  try{notices(fetch("notifications",1000000));}catch(Exception ignored){}
  if(SystemClock.elapsedRealtime()-contactsAt>60000){try{new ContactsMirror(app).sync(fetch("contacts",12000000));contactsAt=SystemClock.elapsedRealtime();}catch(Exception e){Wire.writeFile(new File(app.getFilesDir(),"contacts-status.txt"),("Contacts waiting: "+e.getClass().getSimpleName()).getBytes("UTF-8"));}}
  if(newsAt==0||SystemClock.elapsedRealtime()-newsAt>1800000){try{
   ComponentName news=new ComponentName("org.retrobridge.apnews","org.retrobridge.apnews.engine.APNewsWidgetReceiver");
   app.getPackageManager().getReceiverInfo(news,0);
   app.sendBroadcast(new Intent("galaxy.APNewsWidgetIntents.ACTION_SERVICE_UPDATE").setComponent(news).putExtra("APNEWS_SERVICE","GET_NEWS_HEAD").putExtra("Is_Widget_Auto_Refresh",true));
   newsAt=SystemClock.elapsedRealtime();
  }catch(android.content.pm.PackageManager.NameNotFoundException absent){newsAt=SystemClock.elapsedRealtime();}}
 }catch(Exception ignored){}finally{if(wake!=null&&wake.isHeld())wake.release();synchronized(this){busy=false;}if(!stopped)((AlarmManager)app.getSystemService(Context.ALARM_SERVICE)).set(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+60000,alarm());}}
}
