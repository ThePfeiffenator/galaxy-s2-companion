package org.retrobridge.galaxyservices.s2;

import android.app.*;import android.content.*;import android.database.*;import android.net.wifi.*;import android.os.*;import android.provider.MediaStore;
import java.io.*;import java.net.*;import java.security.*;import java.util.*;import javax.net.ssl.HttpsURLConnection;import org.json.JSONObject;
import org.retrobridge.galaxyservices.Wire;

/** Copies new camera JPEGs only. Never deletes originals or propagates deletions. */
final class CameraBackup {
 final LegacyService service;final Handler handler=new Handler();ContentObserver observer;volatile boolean stopped,busy;
 final SharedPreferences settings,receipts;final Runnable timer=new Runnable(){public void run(){kick();if(!stopped)handler.postDelayed(this,60000);}};
 CameraBackup(LegacyService s){service=s;settings=s.getSharedPreferences("camera-backup",0);receipts=s.getSharedPreferences("camera-sent",0);if(receipts.getInt("format",0)<2){SharedPreferences.Editor edit=receipts.edit();for(String key:receipts.getAll().keySet())if(key.startsWith("item:"))edit.remove(key);edit.putInt("count",0).putInt("format",2).commit();}}
 void start(){observer=new ContentObserver(handler){public void onChange(boolean self){handler.removeCallbacks(changed);handler.postDelayed(changed,12000);}};service.getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,true,observer);handler.postDelayed(timer,20000);}
 final Runnable changed=new Runnable(){public void run(){kick();}};
 PendingIntent retryIntent(){return PendingIntent.getService(service,31,new Intent(service,LegacyService.class).putExtra("camera",true),PendingIntent.FLAG_UPDATE_CURRENT);}
 void retryLater(){if(!stopped&&settings.getBoolean("enabled",false))((AlarmManager)service.getSystemService(Context.ALARM_SERVICE)).set(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+120000,retryIntent());}
 void cancelRetry(){((AlarmManager)service.getSystemService(Context.ALARM_SERVICE)).cancel(retryIntent());}
 void close(){stopped=true;cancelRetry();handler.removeCallbacksAndMessages(null);if(observer!=null)service.getContentResolver().unregisterContentObserver(observer);}
 void status(String value){try{Wire.writeFile(new File(service.getFilesDir(),"camera-status.txt"),value.getBytes("UTF-8"));}catch(Exception ignored){}}
 synchronized void kick(){if(stopped||busy||!settings.getBoolean("enabled",false))return;busy=true;new Thread(new Runnable(){public void run(){sync();}},"camera-backup").start();}
 static List<File> photos()throws IOException{
  // Scan the two camera directories directly: Samsung's MediaStore can lag new files.
  TreeMap<String,File> unique=new TreeMap<String,File>();File external=Environment.getExternalStorageDirectory();
  File[] roots={new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),"Camera"),new File(external,"external_sd/DCIM/Camera"),new File("/storage/sdcard1/DCIM/Camera")};
  for(File root:roots){File canonical=root.getCanonicalFile();File[] files=root.listFiles();if(files==null)continue;for(File file:files){String name=file.getName().toLowerCase(Locale.US);if(!(name.endsWith(".jpg")||name.endsWith(".jpeg")))continue;File f=file.getCanonicalFile();if(f.isFile()&&canonical.equals(f.getParentFile()))unique.put(f.toString(),f);}}
  ArrayList<File> result=new ArrayList<File>(unique.values());Collections.sort(result,new Comparator<File>(){public int compare(File a,File b){return a.lastModified()<b.lastModified()?-1:a.lastModified()==b.lastModified()?0:1;}});return result;
 }
 JSONObject request(String method,String hash,File file,long taken)throws Exception{
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://camera.galaxy-services.invalid/photos/"+hash).openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",Wire.LOCAL)));
  c.setConnectTimeout(15000);c.setReadTimeout(45000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setRequestMethod(method);
  try{if(file!=null){c.setDoOutput(true);c.setFixedLengthStreamingMode((int)file.length());c.setRequestProperty("Content-Type","image/jpeg");c.setRequestProperty("X-Photo-Taken",String.valueOf(taken));try(InputStream in=new FileInputStream(file);OutputStream out=c.getOutputStream()){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){if(stopped)throw new IOException("Stopped");out.write(b,0,n);}}}
   if(c.getResponseCode()!=200)throw new IOException("Pixel HTTP "+c.getResponseCode());ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] buf=new byte[1024];int n;while((n=in.read(buf))!=-1){if(b.size()+n>4096)throw new IOException("Reply too large");b.write(buf,0,n);}}JSONObject reply=new JSONObject(b.toString("UTF-8"));if(!hash.equals(reply.getString("sha256")))throw new IOException("Receipt mismatch");return reply;
  }finally{c.disconnect();}
 }
 void sync(){PowerManager.WakeLock wake=null;WifiManager.WifiLock wifi=null;try{
  service.gateway();wake=((PowerManager)service.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GalaxyServices:camera");wake.acquire(180000);
  wifi=((WifiManager)service.getSystemService(Context.WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"GalaxyServices:camera");wifi.acquire();
  long since=settings.getLong("since",System.currentTimeMillis());
  int sent=0;long deadline=SystemClock.elapsedRealtime()+120000;
  for(File f:photos()){
   if(stopped||!settings.getBoolean("enabled",false)||sent>=5||SystemClock.elapsedRealtime()>=deadline)break;
   if(f.lastModified()<since||System.currentTimeMillis()-f.lastModified()<10000)continue;
   long size=f.length(),modified=f.lastModified();if(size<4||size>32*1024*1024)continue;
   String local="path:"+Wire.hex(MessageDigest.getInstance("SHA-256").digest(f.getCanonicalPath().getBytes("UTF-8")))+":"+size+":"+modified;if(receipts.getBoolean(local,false))continue;
   MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}if(size!=f.length()||modified!=f.lastModified())continue;String hash=Wire.hex(digest.digest());
   long taken=modified;try{String date=new android.media.ExifInterface(f.toString()).getAttribute(android.media.ExifInterface.TAG_DATETIME);if(date!=null){java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss",Locale.US);format.setLenient(false);long parsed=format.parse(date).getTime();if(parsed>0&&parsed<=System.currentTimeMillis()+86400000L)taken=parsed;}}catch(Exception ignored){}
   boolean known=receipts.getBoolean("photo:"+hash,false);
   if(!known){JSONObject reply=request("GET",hash,null,taken);if(!reply.getBoolean("stored"))reply=request("PUT",hash,f,taken);if(!reply.getBoolean("stored"))throw new IOException("Missing receipt");}
   if(!receipts.edit().putBoolean(local,true).putBoolean("photo:"+hash,true).putInt("count",receipts.getInt("count",0)+(known?0:1)).commit())throw new IOException("Receipt storage failed");sent++;
  }
  if(sent>=5)retryLater();else cancelRetry();status("Camera photos backed up: "+receipts.getInt("count",0));
 }catch(Exception e){retryLater();status("Camera backup waiting for Pixel");}finally{if(wifi!=null&&wifi.isHeld())wifi.release();if(wake!=null&&wake.isHeld())wake.release();synchronized(this){busy=false;}}}
}
