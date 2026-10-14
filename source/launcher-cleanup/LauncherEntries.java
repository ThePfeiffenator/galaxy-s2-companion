import android.content.*;
import android.content.pm.*;
import org.json.*;
import java.util.*;

/** Explicit original/replacement pairs; no package, data or APK changes. */
public final class LauncherEntries {
    static final String[] ORIGINALS={
        "com.google.android.youtube/com.google.android.youtube.app.honeycomb.Shell$HomeActivity",
        "com.google.android.googlequicksearchbox/com.google.android.googlequicksearchbox.SearchActivity",
        "com.google.android.music/com.android.music.activitymanagement.TopLevelActivity",
        "com.google.android.gm/com.google.android.gm.ConversationListActivityGmail",
        "com.android.vending/com.android.vending.AssetBrowserActivity"
    };
    static final String[] REPLACEMENTS={
        "org.retrobridge.youtube2012/org.retrobridge.youtube2012.app.honeycomb.Shell$HomeActivity",
        "org.retrobridge.google2012/org.retrobridge.google2012.SearchActivity",
        "org.retrobridge.playmusic2012/com.android.music.activitymanagement.TopLevelActivity",
        "org.retrobridge.gmail2012/org.retrobridge.gmail2012.ConversationListActivityGmail",
        "org.retrobridge.play2012/org.retrobridge.play2012.AssetBrowserActivity"
    };
    // Preserve the observed explicit-enabled Store entry when restoring it.
    static final int[] RESTORE_STATES={0,0,0,0,1};
    static boolean listed(PackageManager pm,ComponentName component){
        Intent i=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(component.getPackageName());
        for(ResolveInfo r:pm.queryIntentActivities(i,0))if(r.activityInfo.name.equals(component.getClassName()))return true;
        return false;
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1 || !(args[0].equals("inspect")||args[0].equals("hide")||args[0].equals("restore")))throw new IllegalArgumentException("inspect, hide or restore");
        android.os.Looper.prepareMainLooper();Class<?> at=Class.forName("android.app.ActivityThread");Object thread=at.getMethod("systemMain").invoke(null);Context c=(Context)at.getMethod("getSystemContext").invoke(thread);PackageManager pm=c.getPackageManager();
        String mode=args[0];
        // Validate every pair before changing anything; already-hidden entries are safe to retain.
        for(int n=0;n<ORIGINALS.length;n++){
            ComponentName old=ComponentName.unflattenFromString(ORIGINALS[n]),replacement=ComponentName.unflattenFromString(REPLACEMENTS[n]);
            ActivityInfo a=pm.getActivityInfo(old,PackageManager.GET_DISABLED_COMPONENTS);
            if((a.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM)==0)throw new IllegalStateException("Original is not a system app");
            if(!listed(pm,replacement))throw new IllegalStateException("Working replacement not available");
            int state=pm.getComponentEnabledSetting(old);
            if(!mode.equals("inspect") && state!=RESTORE_STATES[n] && state!=PackageManager.COMPONENT_ENABLED_STATE_DISABLED)throw new IllegalStateException("Unexpected original entry state");
        }
        if(!mode.equals("inspect")){
            for(int n=0;n<ORIGINALS.length;n++){int state=mode.equals("hide")?PackageManager.COMPONENT_ENABLED_STATE_DISABLED:RESTORE_STATES[n];ComponentName component=ComponentName.unflattenFromString(ORIGINALS[n]);if(pm.getComponentEnabledSetting(component)!=state)pm.setComponentEnabledSetting(component,state,PackageManager.DONT_KILL_APP);}
        }
        JSONObject output=new JSONObject();output.put("action",mode);JSONArray pairs=new JSONArray();
        for(int n=0;n<ORIGINALS.length;n++){
            ComponentName old=ComponentName.unflattenFromString(ORIGINALS[n]),replacement=ComponentName.unflattenFromString(REPLACEMENTS[n]);JSONObject pair=new JSONObject();
            pair.put("original",ORIGINALS[n]);pair.put("original_component_state",pm.getComponentEnabledSetting(old));pair.put("original_package_state",pm.getApplicationEnabledSetting(old.getPackageName()));pair.put("original_in_launcher",listed(pm,old));
            pair.put("replacement",REPLACEMENTS[n]);pair.put("replacement_in_launcher",listed(pm,replacement));pairs.put(pair);
        }
        output.put("pairs",pairs);JSONArray launcher=new JSONArray();
        for(ResolveInfo r:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)){
            JSONObject row=new JSONObject();row.put("label",r.loadLabel(pm).toString());row.put("component",new ComponentName(r.activityInfo.packageName,r.activityInfo.name).flattenToShortString());launcher.put(row);
        }
        output.put("launcher",launcher);System.out.println("LAUNCHER_JSON="+output.toString());System.exit(0);
    }
}
