package org.retrobridge.galaxymail.pixel;

import android.content.*;import android.database.Cursor;import android.net.Uri;import android.os.Bundle;import android.os.Binder;import org.json.JSONObject;

/** Callable only by companion APKs with the same signing certificate. */
public final class MailProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    public Bundle call(String method,String arg,Bundle extras){
        getContext().enforceCallingOrSelfPermission("org.retrobridge.galaxymail.permission.BRIDGE","Companion permission required");
        long identity=Binder.clearCallingIdentity();Bundle result=new Bundle();
        try{
            if(method.equals("status"))result.putString("json",new JSONObject().put("connected",!MailAccount.email(getContext()).isEmpty()).put("youtubeConnected",!YouTubeAccount.email(getContext()).isEmpty()).put("youtubeControls",YouTubeAccount.controls(getContext())).put("youtubeCacheKey",getContext().getSharedPreferences("youtube",0).getString("channelId","")).toString());
            else if(method.equals("read"))result.putString("json",MailAccount.read(getContext(),arg).toString());
            else if(method.equals("gmail"))result.putString("json",GmailBridge.run(getContext(),new JSONObject(extras.getString("request"))).toString());
            else if(method.equals("youtube"))result.putString("json",YouTubeAccount.read(getContext(),arg).toString());
            else if(method.equals("youtubeWrite")){
                if(extras==null)throw new java.io.IOException("Missing action");
                result.putString("json",YouTubeActions.run(getContext(),arg,new JSONObject(extras.getString("params")),extras.getString("requestId"),extras.getString("channel")).toString());
            }
            else throw new IllegalArgumentException("Unknown operation");
        }catch(Exception e){result.putString("error",e instanceof java.io.IOException?e.getMessage():"Google bridge unavailable ("+e.getClass().getSimpleName()+")");}finally{Binder.restoreCallingIdentity(identity);}return result;
    }
    public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    public String getType(Uri u){return null;}
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
