package org.retrobridge.galaxyservices.s2;

import android.app.Service;
import android.content.*;
import android.net.wifi.*;
import android.os.*;
import java.io.File;
import org.retrobridge.galaxyservices.Wire;

/** Small signature-protected control surface; never exports messages, keys or account data. */
public final class CompanionControlService extends Service {
    public static final int STATUS=1, CONNECTION=2, CAMERA=3, BOOT=4;
    private final Messenger incoming=new Messenger(new Handler(){
        public void handleMessage(Message message){
            Bundle result=new Bundle();
            try {
                switch(message.what){
                case STATUS: break;
                case CONNECTION:
                    boolean enabled=message.arg1==1;
                    if(!getSharedPreferences("settings",0).edit().putBoolean("enabled",enabled).commit())throw new Exception();
                    if(enabled)startService(new Intent(CompanionControlService.this,LegacyService.class));
                    else stopService(new Intent(CompanionControlService.this,LegacyService.class));
                    break;
                case CAMERA:
                    boolean backup=message.arg1==1;
                    SharedPreferences p=getSharedPreferences("camera-backup",0);
                    SharedPreferences.Editor edit=p.edit().putBoolean("enabled",backup);
                    if(!p.contains("since"))edit.putLong("since",System.currentTimeMillis());
                    if(!edit.commit())throw new Exception();
                    if(backup&&getSharedPreferences("settings",0).getBoolean("enabled",true))startService(new Intent(CompanionControlService.this,LegacyService.class).putExtra("camera",true));
                    break;
                case BOOT:
                    if(!getSharedPreferences("settings",0).edit().putBoolean("start_on_boot",message.arg1==1).commit())throw new Exception();
                    break;
                default: throw new IllegalArgumentException();
                }
                result=status();
            }catch(Exception error){result.putString("error","Could not update the connection");}
            if(message.replyTo!=null)try{Message reply=Message.obtain(null,message.what);reply.arg1=message.arg1;reply.setData(result);message.replyTo.send(reply);}catch(RemoteException ignored){}
        }
    });
    public IBinder onBind(Intent intent){return incoming.getBinder();}
    private String read(String name){try{File f=new File(getFilesDir(),name);if(f.length()>8192)return "";return new String(Wire.readFile(f),"UTF-8").trim();}catch(Exception e){return "";}}
    private Bundle status(){
        Bundle b=new Bundle();
        b.putBoolean("running",LegacyService.active!=null);
        b.putBoolean("enabled",getSharedPreferences("settings",0).getBoolean("enabled",true));
        b.putBoolean("boot",getSharedPreferences("settings",0).getBoolean("start_on_boot",true));
        b.putBoolean("camera",getSharedPreferences("camera-backup",0).getBoolean("enabled",false));
        b.putInt("photos",getSharedPreferences("camera-sent",0).getInt("count",0));
        b.putBoolean("media",getSharedPreferences("media-relay",0).getBoolean("enabled",false));
        b.putBoolean("calendar",read("calendar-status.txt").startsWith("Synced "));
        // Association alone can survive a stalled hotspot. Require the existing
        // transport and reachability checks before reporting a working link.
        try{WifiInfo w=((WifiManager)getSystemService(WIFI_SERVICE)).getConnectionInfo();String actual=w==null?"":w.getSSID();if(actual!=null&&actual.startsWith("\"")&&actual.endsWith("\""))actual=actual.substring(1,actual.length()-1);String expected=read("network.txt");b.putBoolean("wifi",LegacyService.active!=null&&w!=null&&w.getSupplicantState()==SupplicantState.COMPLETED&&expected.length()>0&&expected.equals(actual)&&read("status.txt").equals("Connected")&&read("hotspot-status.txt").equals("Connected"));}catch(Exception ignored){}
        return b;
    }
}
