package org.retrobridge.galaxyservices.pixel;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver {public void onReceive(Context c,Intent i){if(c.getSharedPreferences("settings",0).getBoolean("enabled",true))c.startForegroundService(new Intent(c,LegacyService.class));}}
