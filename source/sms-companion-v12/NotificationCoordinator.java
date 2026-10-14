package org.retrobridge.callprobe;

import android.content.*;
import android.os.*;

/** Shares this foreground process's importance with its two signed helper apps. */
final class NotificationCoordinator {
    private final CompanionService service;
    private final Handler handler=new Handler();
    private boolean closed;
    private final Peer[] peers;
    NotificationCoordinator(CompanionService s){
        service=s;
        peers=new Peer[]{new Peer("org.retrobridge.galaxyservices.s2"),new Peer("org.retrobridge.galaxychat.s2")};
    }
    void start(){handler.post(retry);}
    boolean allBound(){for(Peer peer:peers)if(!peer.connected)return false;return true;}
    private void changed(){service.notifiedStatus="";service.updateNotification();}
    private final Runnable retry=new Runnable(){public void run(){
        if(closed)return;
        for(Peer peer:peers)if(!peer.connected)peer.bind();
        handler.postDelayed(this,30000);
    }};
    void close(){closed=true;handler.removeCallbacksAndMessages(null);for(Peer peer:peers)peer.release();}
    private final class Peer implements ServiceConnection {
        final String name;boolean requested,connected;
        Peer(String p){name=p;}
        void bind(){
            release();
            try{
                requested=true;
                boolean accepted=service.bindService(new Intent().setClassName(name,name+".NotificationLinkService"),this,Context.BIND_AUTO_CREATE|Context.BIND_IMPORTANT);
                if(!accepted)release();
            }catch(SecurityException failure){release();}
        }
        void release(){
            if(requested)try{service.unbindService(this);}catch(IllegalArgumentException ignored){}
            requested=false;connected=false;
        }
        public void onServiceConnected(ComponentName component,IBinder binder){if(closed){release();return;}connected=true;changed();}
        public void onServiceDisconnected(ComponentName component){connected=false;changed();}
    }
}
