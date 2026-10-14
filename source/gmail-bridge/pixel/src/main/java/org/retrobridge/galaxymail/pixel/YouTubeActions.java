package org.retrobridge.galaxymail.pixel;

import android.content.*;
import java.io.*;
import java.security.MessageDigest;
import org.json.*;

/** Durable action receipts prevent automatic retries from creating duplicate public posts. */
final class YouTubeActions {
    static String hash(String s)throws Exception {
        byte[] b=MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));StringBuilder out=new StringBuilder();for(byte n:b)out.append(String.format(java.util.Locale.US,"%02x",n&255));return out.toString();
    }
    static synchronized JSONObject run(Context c,String op,JSONObject params,String requestId,String channel)throws Exception {
        if(requestId==null||!requestId.matches("[0-9a-f-]{36}")||channel==null||!channel.matches("UC[A-Za-z0-9_-]{22}"))throw new IOException("Invalid action identity");
        if(!channel.equals(c.getSharedPreferences("youtube",0).getString("channelId","")))throw new IOException("YouTube account changed. Sign in again on S2");
        YouTubeActionSpec spec=YouTubeActionSpec.parse(op,params);
        // Resolve permission/token before recording an attempt; no request has been sent yet.
        String token=YouTubeAccount.writeToken(c);
        SharedPreferences receipts=c.getSharedPreferences("youtube-actions",0);
        String key=hash(channel+"|"+requestId),fingerprint=hash(spec.method+"|"+spec.path+"|"+(spec.body==null?"":spec.body.toString()));
        String previous=receipts.getString(key,null);
        if(previous!=null){JSONObject old=new JSONObject(previous);if(!fingerprint.equals(old.optString("fingerprint")))throw new IOException("Action identity conflict");if(old.has("result"))return old.getJSONObject("result");throw new IOException(old.optString("error","Previous action outcome unknown. Check YouTube on Pixel before trying again"));}
        if(receipts.getAll().size()>=10000)throw new IOException("Action receipt storage is full");
        JSONObject record=new JSONObject().put("fingerprint",fingerprint).put("time",System.currentTimeMillis());
        if(!receipts.edit().putString(key,record.toString()).commit())throw new IOException("Could not save action receipt");
        try {
            // Never automatically replay writes, including on HTTP 401 or an uncertain disconnect.
            JSONObject result=GoogleApi.write(token,spec);
            record.put("result",result);
            if(!receipts.edit().putString(key,record.toString()).commit())throw new IOException("Action succeeded, but receipt could not be saved. Check Pixel");
            return result;
        }catch(GoogleApi.HttpFailure e){
            String message=e.status==401?"YouTube needs authorization on Pixel":e.getMessage();
            record.put("error",message);receipts.edit().putString(key,record.toString()).commit();if(e.status==401)YouTubeAccount.clearToken();throw new IOException(message);
        }
    }
}
