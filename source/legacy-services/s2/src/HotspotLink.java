package org.retrobridge.galaxyservices.s2;

import android.bluetooth.*;
import android.content.*;
import android.net.wifi.*;
import android.os.*;
import org.retrobridge.galaxyservices.Wire;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Receives only this paired bridge's Wi-Fi configuration over encrypted Bluetooth. */
final class HotspotLink {
 static final UUID ID=UUID.fromString("42cf658f-88bb-4a16-aed5-f78d6999c610");
 final LegacyService service;final SharedPreferences prefs;final Handler main=new Handler();
 final ScheduledExecutorService timeout=Executors.newSingleThreadScheduledExecutor();
 volatile boolean busy,closed;volatile BluetoothSocket active;long lastAttempt,lastRejoin;int failures;
 HotspotLink(LegacyService s){service=s;prefs=s.getSharedPreferences("wifi-bootstrap",0);}
 void note(String s){try{Wire.writeFile(new File(service.getFilesDir(),"hotspot-status.txt"),s.getBytes("UTF-8"));}catch(Exception ignored){}}
 synchronized void kick(){if(closed||busy||SystemClock.elapsedRealtime()-lastAttempt<20000)return;busy=true;lastAttempt=SystemClock.elapsedRealtime();new Thread(new Runnable(){public void run(){check();}},"wifi-bootstrap").start();}
 boolean reachable(){Socket s=new Socket();try{s.connect(new InetSocketAddress(service.gateway(),Wire.PORT),2000);return true;}catch(Exception e){return false;}finally{Wire.close(s);}}
 void check(){PowerManager.WakeLock wake=null;try{
  if(reachable()){failures=0;note("Connected");return;}failures++;
  BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();if(adapter==null||!adapter.isEnabled())return;
  byte[] key=Wire.unhex(new String(Wire.readFile(new File(service.getFilesDir(),"link.key")),"US-ASCII").trim());if(key.length!=32)return;
  wake=((PowerManager)service.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GalaxyServices:wifi-bootstrap");wake.acquire(45000);
  String peer=prefs.getString("peer","@PIXEL_BLUETOOTH@");int attempts=0;
  for(BluetoothDevice device:adapter.getBondedDevices()){
   if(closed||attempts>=2)break;if(peer.length()>0&&!peer.equals(device.getAddress()))continue;attempts++;
   int channel=LegacyDiscovery.channel();
   final BluetoothSocket socket=channel>=1&&channel<=30?(BluetoothSocket)device.getClass().getMethod("createRfcommSocket",int.class).invoke(device,channel):device.createRfcommSocketToServiceRecord(ID);active=socket;
   ScheduledFuture<?> expiry=timeout.schedule(new Runnable(){public void run(){closeSocket(socket);}},12,TimeUnit.SECONDS);
   String stage="Bluetooth connect";
   try{socket.connect();stage="Bluetooth authentication";byte[] random=new byte[32];try(DataInputStream entropy=new DataInputStream(new FileInputStream("/dev/urandom"))){entropy.readFully(random);}String nonce=Wire.hex(random),prefix="GSW1 "+nonce+"\n";
    socket.getOutputStream().write(("GSW1 "+nonce+" "+Wire.hex(Wire.mac(key,prefix,new byte[0]))+"\n").getBytes("US-ASCII"));socket.getOutputStream().flush();
    DataInputStream in=new DataInputStream(socket.getInputStream());int length=in.readInt();if(length<2||length>2048)throw new IOException("Reply size");byte[] body=new byte[length],proof=new byte[32];in.readFully(body);in.readFully(proof);
    if(!MessageDigest.isEqual(proof,Wire.mac(key,"GSW1 reply "+nonce+"\n",body)))throw new IOException("Pair mismatch");
    prefs.edit().putString("peer",device.getAddress()).commit();stage="Wi-Fi configuration";apply(new JSONObject(new String(body,"UTF-8")));break;
   }catch(Exception e){note(stage+": "+e.getClass().getSimpleName()+" channel="+channel+" "+e.getMessage());}finally{expiry.cancel(false);closeSocket(socket);active=null;}
  }
 }catch(Exception e){note("Bootstrap: "+e.getClass().getSimpleName()+" "+e.getMessage());}finally{if(wake!=null&&wake.isHeld())wake.release();busy=false;}}
 static String quoted(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
 void apply(JSONObject data)throws Exception{
  if(closed)return;String mode=data.getString("mode");if(mode.equals("starting")){note("Pixel Wi-Fi starting");return;}
  WifiManager wifi=(WifiManager)service.getSystemService(Context.WIFI_SERVICE);
  String name=mode.equals("private")?data.getString("ssid"):prefs.getString("fallback",service.network);
  if(name==null||name.getBytes("UTF-8").length<1||name.getBytes("UTF-8").length>32)throw new IOException("SSID");
  List<WifiConfiguration> saved=wifi.getConfiguredNetworks();if(saved==null)throw new IOException("Wi-Fi starting");int target=-1;
  if(mode.equals("private")){
   String password=data.getString("password");if(password.length()<8||password.length()>63)throw new IOException("Wi-Fi password");
   int managed=prefs.getInt("networkId",-1);String prior=prefs.getString("managedSsid","");
   String fingerprint=Wire.hex(MessageDigest.getInstance("SHA-256").digest((name+"\n"+password).getBytes("UTF-8")));
   WifiConfiguration existing=null;for(WifiConfiguration c:saved)if(c.networkId==managed&&quoted(prior).equals(c.SSID)){existing=c;break;}
   if(existing!=null&&name.equals(prior)&&fingerprint.equals(prefs.getString("fingerprint","")))target=managed;
   else{
    WifiConfiguration config=existing==null?new WifiConfiguration():WifiConfiguration.class.getConstructor(WifiConfiguration.class).newInstance(existing);config.SSID=quoted(name);config.preSharedKey=quoted(password);config.allowedKeyManagement.clear();config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
    target=existing!=null?wifi.updateNetwork(config):wifi.addNetwork(config);if(target<0)throw new IOException("Save Wi-Fi");
    SharedPreferences.Editor edit=prefs.edit().putInt("networkId",target).putString("managedSsid",name).putString("fingerprint",fingerprint);if(!prefs.contains("fallback"))edit.putString("fallback",service.network);edit.commit();wifi.saveConfiguration();
   }
  }else if(mode.equals("tethered")){for(WifiConfiguration c:saved)if(quoted(name).equals(c.SSID)){target=c.networkId;break;}}
  else throw new IOException("Mode");
  if(target<0)throw new IOException("Saved Wi-Fi missing");
  Wire.writeFile(new File(service.getFilesDir(),"network.txt"),name.getBytes("UTF-8"));service.network=name;
  service.setProxy(true);WifiInfo info=wifi.getConnectionInfo();SupplicantState state=info.getSupplicantState();
  if(failures>=4&&SystemClock.elapsedRealtime()-lastRejoin>180000){
   lastRejoin=SystemClock.elapsedRealtime();failures=0;final WifiManager manager=wifi;wifi.setWifiEnabled(false);
   main.postDelayed(new Runnable(){public void run(){if(!closed)manager.setWifiEnabled(true);}},2000);
  }else if(info.getNetworkId()!=target||state==SupplicantState.DISCONNECTED||state==SupplicantState.INACTIVE||state==SupplicantState.SCANNING){wifi.enableNetwork(target,true);wifi.reconnect();}
  note("Joining Pixel Wi-Fi");main.postDelayed(new Runnable(){public void run(){if(!closed){service.lifecycle.sync();if(service.camera!=null)service.camera.kick();}}},8000);
 }
 static void closeSocket(BluetoothSocket s){if(s!=null)try{s.close();}catch(Exception ignored){}}
 void close(){closed=true;main.removeCallbacksAndMessages(null);closeSocket(active);timeout.shutdownNow();}
}
