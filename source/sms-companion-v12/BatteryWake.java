package org.retrobridge.callprobe;
import android.app.*;import android.content.*;import android.os.*;
/** A short wake tick lets the native limiter resume even in deep sleep.
 * It does not own or start the call connection and has no foreground notice. */
public final class BatteryWake extends BroadcastReceiver {
 static final String TICK="org.retrobridge.callprobe.CHARGE_TICK";
 static boolean installed(){return new java.io.File("/system/bin/galaxy-charge-manager").isFile();}
 static PendingIntent pendingTick(Context c){return PendingIntent.getBroadcast(c,80015,new Intent(c,BatteryWake.class).setAction(TICK),PendingIntent.FLAG_UPDATE_CURRENT);}
 static void cancel(Context c){((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).cancel(pendingTick(c));}
 static void schedule(Context c){if(!installed()){cancel(c);return;}AlarmManager a=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);a.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+60000,60000,pendingTick(c));}
 static void update(Context c,org.json.JSONObject state){if(state==null||(state.optBoolean("enabled")&&state.optBoolean("plugged"))||state.optBoolean("paused")){schedule(c);return;}cancel(c);}
 public void onReceive(final Context c,Intent i){if(i==null)return;if(!installed()){cancel(c);return;}if(!TICK.equals(i.getAction()))schedule(c);
  final PendingResult pending=goAsync();PowerManager pm=(PowerManager)c.getSystemService(Context.POWER_SERVICE);
  final PowerManager.WakeLock wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Galaxy:Charging");wake.setReferenceCounted(false);wake.acquire(12000);
  BatteryControl.request("STATUS",new BatteryControl.Callback(){public void completed(org.json.JSONObject result){try{update(c,result);if(wake.isHeld())wake.release();}finally{pending.finish();}}});
 }
}
