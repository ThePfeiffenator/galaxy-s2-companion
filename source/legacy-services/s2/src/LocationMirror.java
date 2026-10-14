package org.retrobridge.galaxyservices.s2;
import android.app.*;import android.content.*;import android.location.*;import android.os.*;import android.provider.Settings;import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;import javax.net.ssl.HttpsURLConnection;import org.json.*;import org.retrobridge.galaxyservices.Wire;

/** Supplies the Pixel's real, recent fix to legacy Maps through Android's provider API. */
final class LocationMirror {
 final LegacyService service;final LocationManager manager;final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();boolean owned;long delivered=-1,lastGood;
 LocationMirror(LegacyService s){service=s;manager=(LocationManager)s.getSystemService(Context.LOCATION_SERVICE);}
 void start(){if(service.getSharedPreferences("settings",0).getBoolean("locationProviderOwned",false)){owned=true;restore();}worker.scheduleWithFixedDelay(new Runnable(){public void run(){poll();}},10,2,TimeUnit.SECONDS);}
 void note(String t){try{Wire.writeFile(new File(service.getFilesDir(),"location-status.txt"),t.getBytes("UTF-8"));}catch(Exception ignored){}}
 boolean needed(){
  if(!service.getSharedPreferences("settings",0).getBoolean("pixelLocation",true))return false;
  String allowed=Settings.Secure.getString(service.getContentResolver(),Settings.Secure.LOCATION_PROVIDERS_ALLOWED);if(allowed==null||!Arrays.asList(allowed.split(",")).contains("gps"))return false;
  ActivityManager a=(ActivityManager)service.getSystemService(Context.ACTIVITY_SERVICE);List<ActivityManager.RunningTaskInfo> tasks=a.getRunningTasks(1);
  if(!tasks.isEmpty()&&tasks.get(0).topActivity!=null&&"com.google.android.apps.maps".equals(tasks.get(0).topActivity.getPackageName()))return true;
  for(ActivityManager.RunningServiceInfo r:a.getRunningServices(100))if(r.foreground&&"com.google.android.apps.maps".equals(r.service.getPackageName()))return true;
  return false;
 }
 JSONObject fetch()throws Exception {
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://location.galaxy-services.invalid/location").openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",Wire.LOCAL)));
  c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setInstanceFollowRedirects(false);
  try{if(c.getResponseCode()!=200)throw new IOException("Waiting for Pixel location");InputStream in=c.getInputStream();ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[1024];int n;while((n=in.read(buf))!=-1){if(b.size()+n>4096)throw new IOException("Invalid location response");b.write(buf,0,n);}return new JSONObject(b.toString("UTF-8"));}finally{c.disconnect();}
 }
 synchronized void restore(){if(owned){try{manager.removeTestProvider(LocationManager.GPS_PROVIDER);}catch(Exception ignored){}owned=false;service.getSharedPreferences("settings",0).edit().putBoolean("locationProviderOwned",false).commit();}delivered=-1;}
 void poll(){try{
  if(service.stopping||!needed()){restore();return;}
  if(Settings.Secure.getInt(service.getContentResolver(),Settings.Secure.ALLOW_MOCK_LOCATION,0)==0){restore();note("Location relay setting needed");return;}
  service.gateway();JSONObject j=fetch();long time=j.getLong("time"),age=System.currentTimeMillis()-time;
  double lat=j.getDouble("latitude"),lon=j.getDouble("longitude"),accuracy=j.getDouble("accuracy");
  if(j.getInt("version")!=1||age< -2000||age>15000||Double.isNaN(lat)||Double.isNaN(lon)||Double.isNaN(accuracy)||Math.abs(lat)>90||Math.abs(lon)>180||accuracy<=0||accuracy>100000)throw new IOException("Invalid or stale location");
  if(service.stopping||time==delivered)return;
  if(!owned){manager.addTestProvider(LocationManager.GPS_PROVIDER,false,false,false,false,true,true,true,Criteria.POWER_LOW,Criteria.ACCURACY_FINE);owned=true;service.getSharedPreferences("settings",0).edit().putBoolean("locationProviderOwned",true).commit();manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER,true);}
  Location l=new Location(LocationManager.GPS_PROVIDER);l.setLatitude(lat);l.setLongitude(lon);l.setAccuracy((float)accuracy);l.setTime(time);
  if(j.has("altitude"))l.setAltitude(j.getDouble("altitude"));if(j.has("speed"))l.setSpeed((float)j.getDouble("speed"));if(j.has("bearing"))l.setBearing((float)j.getDouble("bearing"));
  manager.setTestProviderStatus(LocationManager.GPS_PROVIDER,LocationProvider.AVAILABLE,null,SystemClock.elapsedRealtime());manager.setTestProviderLocation(LocationManager.GPS_PROVIDER,l);delivered=time;lastGood=SystemClock.elapsedRealtime();note("Pixel location; accuracy="+Math.round(accuracy)+" m; updated="+time);
 }catch(Exception e){note("Waiting: "+e.getClass().getSimpleName());}finally{if(owned&&SystemClock.elapsedRealtime()-lastGood>15000)restore();}}
 void close(){worker.shutdownNow();new Thread(new Runnable(){public void run(){try{worker.awaitTermination(12,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}restore();}},"location-cleanup").start();}
}
