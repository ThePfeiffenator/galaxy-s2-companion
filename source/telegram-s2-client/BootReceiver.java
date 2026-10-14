package org.retrobridge.galaxychat.s2;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver {
    public void onReceive(Context c,Intent i){
        if(i!=null&&(Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())||Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction()))
            &&c.getSharedPreferences("connection",0).getBoolean("enabled",true)&&c.getSharedPreferences("pair",0).contains("key"))
            c.startService(new Intent(c,ChatService.class).setAction(Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())?"BOOT":"RESUME"));
    }
}
