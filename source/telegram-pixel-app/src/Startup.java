package org.retrobridge.galaxychat.pixel;
import android.content.*;
import android.os.*;

/** Resume the user's saved connection choice after unlock, update, or process recovery. */
final class Startup {
    static boolean enabled(Context c){return c.getSharedPreferences("connection",0).getBoolean("enabled",true);}
    static void enabled(Context c,boolean value){c.getSharedPreferences("connection",0).edit().putBoolean("enabled",value).commit();}
    static void start(Context c){
        if(!enabled(c)||!((UserManager)c.getSystemService(Context.USER_SERVICE)).isUserUnlocked())return;
        try{c.startForegroundService(new Intent(c,RelayService.class));c.getSharedPreferences("connection",0).edit().remove("start_error").apply();}
        catch(RuntimeException e){c.getSharedPreferences("connection",0).edit().putString("start_error",e.getClass().getSimpleName()).apply();}
    }
}
