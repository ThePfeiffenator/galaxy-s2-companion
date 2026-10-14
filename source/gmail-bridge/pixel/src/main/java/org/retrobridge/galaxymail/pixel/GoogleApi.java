package org.retrobridge.galaxymail.pixel;

import android.content.Context;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.tasks.Tasks;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.io.*;
import java.util.concurrent.TimeUnit;

/** Fixed Google endpoints; never returns credentials to the companion provider. */
final class GoogleApi {
    interface TokenSource { String get() throws Exception; default void clear() {} }
    static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int value){super("Google HTTP "+value);status=value;}
        HttpFailure(int value,String reason){super("Google HTTP "+value+(reason.isEmpty()?"":" ("+reason+")"));status=value;}
    }
    static HttpFailure failure(HttpsURLConnection c,int status){
        String reason="";
        try(InputStream in=c.getErrorStream()){
            if(in!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1&&out.size()+n<=16384)out.write(b,0,n);
                reason=new JSONObject(out.toString("UTF-8")).getJSONObject("error").getJSONArray("errors").getJSONObject(0).optString("reason","");if(!reason.matches("[A-Za-z0-9_-]{1,80}"))reason="";}
        }catch(Exception ignored){}return new HttpFailure(status,reason);
    }
    static JSONObject read(Context context,TokenSource source,String url)throws Exception {
        String token=source.get();
        try{return read(token,url);}catch(HttpFailure e){
            if(e.status!=401)throw e;
            Tasks.await(Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build()),15,TimeUnit.SECONDS);
            source.clear();
            return read(source.get(),url);
        }
    }
    static JSONObject read(String token,String url)throws Exception {
        URL target=new URL(url);
        if(!"https".equals(target.getProtocol())||target.getPort()!=-1||target.getUserInfo()!=null||target.getRef()!=null||!(target.getHost().equals("gmail.googleapis.com")||target.getHost().equals("www.googleapis.com")))throw new IOException("Invalid Google endpoint");
        HttpsURLConnection c=(HttpsURLConnection)target.openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Accept","application/json");
        try{int status=c.getResponseCode();if(status!=200)throw failure(c,status);
            int limit=target.getHost().equals("gmail.googleapis.com")?8000000:200000;
            ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Google reply too large");out.write(b,0,n);}}
            return new JSONObject(out.toString("UTF-8"));
        }finally{c.disconnect();}
    }
    static JSONObject write(String token,YouTubeActionSpec spec)throws Exception {
        HttpsURLConnection c=(HttpsURLConnection)new URL("https://www.googleapis.com/youtube/v3/"+spec.path).openConnection();
        c.setRequestMethod(spec.method);c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
        c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Accept","application/json");
        try {
            if(!spec.method.equals("DELETE")){
                byte[] data=(spec.body==null?"":spec.body.toString()).getBytes("UTF-8");
                c.setDoOutput(true);c.setFixedLengthStreamingMode(data.length);c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
                try(OutputStream out=c.getOutputStream()){out.write(data);}
            }
            int status=c.getResponseCode();if(status!=200&&status!=201&&status!=204)throw failure(c,status);
            if(status==204)return new JSONObject();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>200000)throw new IOException("Google reply too large");bytes.write(b,0,n);}}
            return bytes.size()==0?new JSONObject():new JSONObject(bytes.toString("UTF-8"));
        }finally{c.disconnect();}
    }
}
