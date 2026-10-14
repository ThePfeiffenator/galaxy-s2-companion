package org.retrobridge.callprobe;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.telephony.SmsManager;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Exact stock partition matching; no transmission, provider access, or prefix guessing. */
public final class SmsPartPlan {
    private final Method samsungFragment;
    public static final class Match {
        public final ArrayList<String> parts;
        public final int sequence;
        public final boolean segmented;
        private Match(List<String> parts,int sequence,boolean segmented){
            this.parts=new ArrayList<String>(parts);this.sequence=sequence;this.segmented=segmented;
        }
    }
    public SmsPartPlan(Context context)throws Exception{
        require("SPH-D710".equals(Build.MODEL)&&"IMM76I.FL24".equals(Build.DISPLAY));
        PackageInfo installed=context.getPackageManager().getPackageInfo("com.android.mms",0);
        require(installed.versionCode==15&&"4.0.4-FL24".equals(installed.versionName)&&installed.applicationInfo!=null);
        require((installed.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM)!=0&&"/system/app/Mms.apk".equals(installed.applicationInfo.sourceDir));
        // Loading the static fragmenter does not construct a sender or call MmsConfig.init().
        Context stock=context.createPackageContext("com.android.mms",Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
        Class<?> sender=stock.getClassLoader().loadClass("com.android.mms.transaction.SmsSingleRecipientSender");
        samsungFragment=sender.getMethod("fragmentTextWithEmail",String.class,int.class);
    }
    public Match match(String body,String wireText,int wireTotal,int wireSequence)throws Exception{
        require(body!=null&&body.length()>0&&body.length()<=20000);
        ArrayList<String> ordinary=SmsManager.getDefault().divideMessage(body);
        Object raw=samsungFragment.invoke(null,body,0);require(raw instanceof List<?>);
        ArrayList<String> samsung=new ArrayList<String>();
        for(Object item:(List<?>)raw){require(item instanceof String);samsung.add((String)item);}
        return choose(body,ordinary,samsung,wireText,wireTotal,wireSequence);
    }
    private static void require(boolean ok)throws IOException{if(!ok)throw new IOException("SMS part plan rejected");}
    private static void validate(String body,List<String> parts,boolean segmented)throws IOException{
        require(parts!=null&&!parts.isEmpty()&&parts.size()<=255);StringBuilder joined=new StringBuilder();
        for(int i=0;i<parts.size();i++){
            String part=parts.get(i);require(part!=null&&!part.isEmpty()&&part.length()<=170);
            if(segmented&&parts.size()>1){
                String prefix="("+(i+1)+"/"+parts.size()+") ";require(part.startsWith(prefix));
                joined.append(part.substring(prefix.length()));
            }else joined.append(part);
            require(joined.length()<=20000);
        }
        require(body.equals(joined.toString()));
    }
    private static int membership(List<String> parts,String text,int total,int sequence){
        if(total==0&&sequence==0){for(int i=0;i<parts.size();i++)if(parts.get(i).equals(text))return i+1;return 0;}
        return total==parts.size()&&sequence>=1&&sequence<=parts.size()&&parts.get(sequence-1).equals(text)?sequence:0;
    }
    /** Package visibility permits host checks of ambiguity and exact membership. */
    static Match choose(String body,List<String> ordinary,List<String> samsung,String text,int total,int sequence)throws IOException{
        require(body!=null&&body.length()>0&&body.length()<=20000&&text!=null);
        require((total==0&&sequence==0)||(total>=1&&total<=255&&sequence>=1&&sequence<=total));
        validate(body,ordinary,false);validate(body,samsung,true);
        int normal=membership(ordinary,text,total,sequence),segmented=membership(samsung,text,total,sequence);
        if(normal>0&&segmented>0&&!ordinary.equals(samsung))throw new IOException("SMS part plan ambiguous");
        if(normal>0)return new Match(ordinary,normal,false);
        if(segmented>0)return new Match(samsung,segmented,samsung.size()>1);
        return null;
    }
}
