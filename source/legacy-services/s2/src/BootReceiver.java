package org.retrobridge.galaxyservices.s2;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver {public void onReceive(Context c,Intent i){if(i==null)return;SharedPreferences p=c.getSharedPreferences("settings",0);if(Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())&&!p.getBoolean("start_on_boot",true))return;if(p.getBoolean("enabled",true))c.startService(new Intent(c,LegacyService.class));}}
