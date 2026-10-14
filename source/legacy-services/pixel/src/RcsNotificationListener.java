package org.retrobridge.galaxyservices.pixel;
import android.os.*;
import android.service.notification.*;
public final class RcsNotificationListener extends NotificationListenerService {
 private HandlerThread worker;private Handler queue;
 public void onCreate(){super.onCreate();worker=new HandlerThread("Message relay");worker.start();queue=new Handler(worker.getLooper());}
 public void onListenerConnected(){RcsRelay.get(this).listener=this;queue.post(()->{try{for(StatusBarNotification n:getActiveNotifications())RcsRelay.get(this).observe(n);}catch(Exception ignored){}});}
 public void onNotificationPosted(StatusBarNotification n){if(RcsRelay.MESSAGES.equals(n.getPackageName()))queue.postDelayed(()->{if(isActive(n.getKey()))RcsRelay.get(this).observe(n);},1500);}
 public void onNotificationRemoved(StatusBarNotification n){if(RcsRelay.MESSAGES.equals(n.getPackageName()))RcsRelay.get(this).removed(n.getKey());}
 public void onListenerDisconnected(){RcsRelay.get(this).disconnected();}
 boolean isActive(String key){try{StatusBarNotification[] all=getActiveNotifications(new String[]{key});return all!=null&&all.length==1&&RcsRelay.MESSAGES.equals(all[0].getPackageName());}catch(Exception e){return false;}}
 public void onDestroy(){RcsRelay.get(this).disconnected();if(worker!=null)worker.quitSafely();super.onDestroy();}
}
