package org.retrobridge.galaxyservices.pixel;

import android.Manifest;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import android.util.SparseIntArray;
import org.retrobridge.galaxyservices.Wire;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** A bonded, authenticated Bluetooth bootstrap for Android's private Wi-Fi link. */
final class HotspotLink {
 static final UUID ID=UUID.fromString("42cf658f-88bb-4a16-aed5-f78d6999c610");
 final Context app;final Handler main=new Handler(Looper.getMainLooper());
 final ScheduledExecutorService timeout=Executors.newSingleThreadScheduledExecutor();
 volatile boolean closed,tethered,starting;volatile WifiManager.LocalOnlyHotspotReservation reservation;
 volatile BluetoothServerSocket server;volatile BluetoothSocket active;
 TetheringManager tether;TetheringManager.TetheringEventCallback callback;
 HotspotLink(Context c){app=c;}
 void note(String s){try{Wire.writeFile(new File(app.getFilesDir(),"hotspot-status.txt"),s.getBytes("UTF-8"));}catch(Exception ignored){}}
 void diagnostic(String s){try{Wire.writeFile(new File(app.getFilesDir(),"wifi-bootstrap-status.txt"),s.getBytes("UTF-8"));}catch(Exception ignored){}}
 void start(){
  if(Build.VERSION.SDK_INT<36){note("Automatic Wi-Fi needs Android 16");return;}
  tether=app.getSystemService(TetheringManager.class);
  callback=new TetheringManager.TetheringEventCallback(){public void onTetheredInterfacesChanged(Set<TetheringInterface> interfaces){
   boolean on=false;for(TetheringInterface item:interfaces)if(item.getType()==TetheringManager.TETHERING_WIFI)on=true;
   tethered=on;if(on){release();note("Using phone hotspot");}else ensure();
  }};
  tether.registerTetheringEventCallback(app.getMainExecutor(),callback);
  main.postDelayed(new Runnable(){public void run(){if(closed)return;ensure();main.postDelayed(this,30000);}},5000);
  new Thread(()->listen(),"wifi-bootstrap").start();
 }
 void ensure(){
  if(closed||tethered||reservation!=null||starting)return;
  if(app.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED){note("Nearby devices permission needed");return;}
  try{
   starting=true;SparseIntArray bands=new SparseIntArray();bands.put(SoftApConfiguration.BAND_2GHZ,0);
   WifiManager.LocalOnlyHotspotCallback started=new WifiManager.LocalOnlyHotspotCallback(){
    public void onStarted(WifiManager.LocalOnlyHotspotReservation r){starting=false;if(closed||tethered){r.close();return;}reservation=r;note("Private Wi-Fi ready");}
    public void onStopped(){starting=false;reservation=null;note("Private Wi-Fi reconnecting");}
    public void onFailed(int reason){starting=false;note("Private Wi-Fi waiting ("+reason+")");}
   };
   WifiManager manager=app.getSystemService(WifiManager.class);
   // Android 16 ignores non-channel customization for ordinary app requestors,
   // including the system overload. Let Android choose the 2.4 GHz channel.
   manager.startLocalOnlyHotspotWithConfiguration(new SoftApConfiguration.Builder().setChannels(bands).build(),app.getMainExecutor(),started);
  }catch(Exception e){starting=false;note("Private Wi-Fi: "+e.getClass().getSimpleName());}
 }
 void listen(){while(!closed){try{
   BluetoothAdapter adapter=app.getSystemService(BluetoothManager.class).getAdapter();
   if(adapter==null||!adapter.isEnabled()||app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){Thread.sleep(5000);continue;}
   server=adapter.listenUsingRfcommWithServiceRecord("Galaxy Wi-Fi",ID);diagnostic("Listening");
   while(!closed){final BluetoothSocket socket=server.accept();active=socket;
    ScheduledFuture<?> expiry=timeout.schedule(()->closeSocket(socket),15,TimeUnit.SECONDS);
    try{diagnostic("Accepted");serve(socket);diagnostic("Replied");}catch(Exception e){diagnostic("Request: "+e.getClass().getSimpleName());}finally{expiry.cancel(false);closeSocket(socket);active=null;}
   }
  }catch(Exception e){diagnostic("Listener: "+e.getClass().getSimpleName());if(!closed)try{Thread.sleep(5000);}catch(InterruptedException ignored){}}finally{if(server!=null)try{server.close();}catch(Exception ignored){}server=null;}}
 }
 void serve(BluetoothSocket socket)throws Exception{
  if(socket.getRemoteDevice().getBondState()!=BluetoothDevice.BOND_BONDED)return;
  byte[] key=Wire.unhex(new String(Wire.readFile(new File(app.getFilesDir(),"link.key")),"US-ASCII").trim());if(key.length!=32)return;
  String[] req=Wire.line(socket.getInputStream()).split(" ");
  if(req.length!=3||!req[0].equals("GSW1")||!req[1].matches("[0-9a-f]{64}"))return;
  if(!MessageDigest.isEqual(Wire.unhex(req[2]),Wire.mac(key,"GSW1 "+req[1]+"\n",new byte[0])))return;
  JSONObject data=new JSONObject();WifiManager.LocalOnlyHotspotReservation r=reservation;
  if(tethered)data.put("mode","tethered");
  else if(r!=null){SoftApConfiguration c=r.getSoftApConfiguration();
   if(c.getSecurityType()!=SoftApConfiguration.SECURITY_TYPE_WPA2_PSK&&c.getSecurityType()!=SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION)throw new IOException("Incompatible Wi-Fi security");
   data.put("mode","private").put("ssid",c.getSsid()).put("password",c.getPassphrase());
  }else{data.put("mode","starting");main.post(()->ensure());}
  byte[] body=data.toString().getBytes("UTF-8");DataOutputStream out=new DataOutputStream(socket.getOutputStream());
  out.writeInt(body.length);out.write(body);out.write(Wire.mac(key,"GSW1 reply "+req[1]+"\n",body));out.flush();
 }
 void release(){WifiManager.LocalOnlyHotspotReservation r=reservation;reservation=null;if(r!=null)r.close();}
 static void closeSocket(BluetoothSocket s){if(s!=null)try{s.close();}catch(Exception ignored){}}
 void close(){closed=true;main.removeCallbacksAndMessages(null);release();closeSocket(active);if(server!=null)try{server.close();}catch(Exception ignored){}if(tether!=null&&callback!=null)tether.unregisterTetheringEventCallback(callback);timeout.shutdownNow();}
}
