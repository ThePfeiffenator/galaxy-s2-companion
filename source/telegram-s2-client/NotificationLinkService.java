package org.retrobridge.galaxychat.s2;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/** Bound only by the signed foreground companion; no account or message data. */
public final class NotificationLinkService extends Service {
    private static boolean managed;
    private static Runnable listener;
    private final IBinder binder=new Binder();
    static boolean managed(){return managed;}
    static void attach(Runnable callback){listener=callback;}
    static void detach(Runnable callback){if(listener==callback)listener=null;}
    private static void state(boolean value){
        if(managed==value)return;
        managed=value;
        if(listener!=null)listener.run();
    }
    public IBinder onBind(Intent intent){state(true);return binder;}
    public boolean onUnbind(Intent intent){state(false);return false;}
    public void onDestroy(){state(false);super.onDestroy();}
    protected void dump(FileDescriptor fd,PrintWriter out,String[] args){out.println("notification_coordinator_bound="+managed);}
}
