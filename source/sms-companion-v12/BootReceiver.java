package org.retrobridge.callprobe;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver {
 public void onReceive(Context c,Intent i){
  if(i==null)return;
  if(Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())&&c.getSharedPreferences("companion",0).getBoolean("start_on_boot",true))c.startService(new Intent(c,CompanionService.class).setAction(CompanionService.ACTION_BOOT));
  else if(Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction()))c.startService(new Intent(c,CompanionService.class));
 }
}
