package org.retrobridge.galaxychat.pixel;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver {
    public void onReceive(Context c,Intent i){
        if(i!=null&&(Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())||Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())))Startup.start(c);
    }
}
