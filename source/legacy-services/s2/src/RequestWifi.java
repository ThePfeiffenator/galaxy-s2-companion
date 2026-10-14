package org.retrobridge.galaxyservices.s2;
import android.content.Context;import android.net.wifi.WifiManager;import android.os.Handler;

/** Keep the old radio awake between streaming chunks, then return to normal power use. */
final class RequestWifi {
 final WifiManager.WifiLock lock;final Handler main=new Handler();int active;boolean closed;
 final Runnable idle=new Runnable(){public void run(){synchronized(RequestWifi.this){if(active==0&&lock.isHeld())lock.release();}}};
 RequestWifi(Context c){lock=((WifiManager)c.getSystemService(Context.WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"GalaxyServices:video");lock.setReferenceCounted(false);}
 synchronized void acquire(){if(closed)return;active++;main.removeCallbacks(idle);if(!lock.isHeld())lock.acquire();}
 synchronized void release(){if(active>0)active--;if(!closed&&active==0){main.removeCallbacks(idle);main.postDelayed(idle,30000);}}
 synchronized void close(){closed=true;main.removeCallbacks(idle);if(lock.isHeld())lock.release();}
}
