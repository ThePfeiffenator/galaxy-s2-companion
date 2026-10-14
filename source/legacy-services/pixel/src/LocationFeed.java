package org.retrobridge.galaxyservices.pixel;
import android.content.*;import android.content.pm.PackageManager;import android.location.*;import android.os.*;import java.io.*;import org.json.*;import org.retrobridge.galaxyservices.Wire;

/** A demand-driven location relay. Coordinates remain in memory and on the paired phones. */
final class LocationFeed implements LocationListener {
 final Context app;final LocationManager manager;final Handler handler=new Handler(Looper.getMainLooper());
 volatile Location fix;volatile long demand;boolean running,closed;
 LocationFeed(Context c){app=c;manager=(LocationManager)c.getSystemService(Context.LOCATION_SERVICE);}
 final Runnable idle=new Runnable(){public void run(){if(closed)return;if(SystemClock.elapsedRealtime()-demand>20000){manager.removeUpdates(LocationFeed.this);running=false;fix=null;}else handler.postDelayed(this,5000);}};
 void request(){demand=SystemClock.elapsedRealtime();handler.post(new Runnable(){public void run(){
  if(closed||running)return;
  try{if(!manager.isLocationEnabled())return;
   String p=manager.getAllProviders().contains("fused")?"fused":LocationManager.GPS_PROVIDER;
   if(Build.VERSION.SDK_INT>=31)manager.requestLocationUpdates(p,new LocationRequest.Builder(1000).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).setMinUpdateIntervalMillis(1000).build(),app.getMainExecutor(),LocationFeed.this);
   else manager.requestLocationUpdates(p,1000,0,LocationFeed.this,Looper.getMainLooper());running=true;
   Location old=manager.getLastKnownLocation(p);if(old!=null)onLocationChanged(old);
   handler.removeCallbacks(idle);handler.postDelayed(idle,5000);
  }catch(Exception e){note("Location permission or provider unavailable");}
 }});}
 void note(String text){try{Wire.writeFile(new File(app.getFilesDir(),"location-status.txt"),text.getBytes("UTF-8"));}catch(Exception ignored){}}
 public void onLocationChanged(Location value){if(value!=null&&value.hasAccuracy()&&value.getAccuracy()>0){fix=new Location(value);note("Location available; accuracy="+Math.round(value.getAccuracy())+" m");}}
 public void onProviderEnabled(String p){}public void onProviderDisabled(String p){fix=null;}public void onStatusChanged(String p,int s,Bundle b){}
 JSONObject snapshot()throws Exception {
  if(app.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")!=PackageManager.PERMISSION_GRANTED)throw new SecurityException();
  if(!manager.isLocationEnabled())throw new IOException("Location disabled");request();Location v=fix;
  if(v==null){note("Waiting for location");throw new IOException("Waiting for location");}long age=(SystemClock.elapsedRealtimeNanos()-v.getElapsedRealtimeNanos())/1000000;
  if(age<0||age>15000){note("Waiting for fresh location; fix age="+(age/1000)+" s");throw new IOException("Waiting for fresh location");}
  JSONObject j=new JSONObject().put("version",1).put("time",System.currentTimeMillis()-age).put("latitude",v.getLatitude()).put("longitude",v.getLongitude()).put("accuracy",v.getAccuracy());
  if(v.hasAltitude())j.put("altitude",v.getAltitude());if(v.hasBearing())j.put("bearing",v.getBearing());if(v.hasSpeed())j.put("speed",v.getSpeed());return j;
 }
 void serve(InputStream in,OutputStream out)throws Exception {
  String first=new String(Wire.header(in),"US-ASCII").split("\r\n")[0];String code="200 OK";byte[] body;
  try{if(!first.equals("GET /location HTTP/1.1")&&!first.equals("GET /location HTTP/1.0"))throw new FileNotFoundException();body=snapshot().toString().getBytes("UTF-8");}
  catch(FileNotFoundException e){code="404 Not Found";body=new byte[0];}
  catch(SecurityException e){code="403 Forbidden";body=new byte[0];}
  catch(Exception e){code="503 Service Unavailable";body=new byte[0];}
  out.write(("HTTP/1.1 "+code+"\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));out.write(body);out.flush();
 }
 void close(){closed=true;handler.removeCallbacksAndMessages(null);manager.removeUpdates(this);fix=null;}
}
