package org.retrobridge.galaxymail.pixel;

import android.accounts.Account;
import android.content.Context;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import org.json.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class YouTubeAccount {
    static final String SCOPE="https://www.googleapis.com/auth/youtube.readonly";
    static final String CONTROLS="https://www.googleapis.com/auth/youtube.force-ssl";
    static final String EMAIL="https://www.googleapis.com/auth/userinfo.email";
    static String email(Context c){return c.getSharedPreferences("youtube",0).getString("account","");}
    static String channel(Context c){return c.getSharedPreferences("youtube",0).getString("channel","");}
    static AuthorizationRequest request(Context c,boolean choose){
        return request(c,choose,false);
    }
    static boolean controls(Context c){return c.getSharedPreferences("youtube",0).getBoolean("controls",false);}
    static AuthorizationRequest request(Context c,boolean choose,boolean write){
        List<Scope> scopes=new ArrayList<Scope>(Arrays.asList(new Scope(SCOPE),new Scope(EMAIL)));if(write)scopes.add(new Scope(CONTROLS));
        AuthorizationRequest.Builder b=AuthorizationRequest.builder().setRequestedScopes(scopes);
        String account=email(c);if(!choose&&!account.isEmpty())b.setAccount(new Account(account,"com.google"));return b.build();
    }
    private static String cachedToken, cachedAccount;
    private static long cachedAt;
    private static String cachedWrite;private static long writeAt;
    static synchronized void clearToken() { cachedToken=null; cachedAccount=null; cachedAt=0;cachedWrite=null;writeAt=0; }
    static synchronized String writeToken(Context c)throws Exception {
        if(email(c).isEmpty()||!controls(c))throw new IOException("Enable YouTube controls in Google Bridge on Pixel");
        if(cachedWrite!=null&&android.os.SystemClock.elapsedRealtime()-writeAt<300000)return cachedWrite;
        AuthorizationResult r=Tasks.await(Identity.getAuthorizationClient(c).authorize(request(c,false,true)),30,TimeUnit.SECONDS);
        if(r.hasResolution()||r.getAccessToken()==null||!r.getGrantedScopes().contains(CONTROLS))throw new IOException("Enable YouTube controls in Google Bridge on Pixel");
        cachedWrite=r.getAccessToken();writeAt=android.os.SystemClock.elapsedRealtime();return cachedWrite;
    }
    static synchronized String token(Context c)throws Exception {
        String account=email(c);
        if(account.isEmpty())throw new IOException("Connect YouTube on Pixel");
        if(account.equals(cachedAccount)&&cachedToken!=null&&android.os.SystemClock.elapsedRealtime()-cachedAt<300000)return cachedToken;
        AuthorizationResult r=Tasks.await(Identity.getAuthorizationClient(c).authorize(request(c,false)),30,TimeUnit.SECONDS);
        if(r.hasResolution()||r.getAccessToken()==null||!r.getGrantedScopes().contains(SCOPE))throw new IOException("YouTube needs authorization on Pixel");
        cachedToken=r.getAccessToken();cachedAccount=account;cachedAt=android.os.SystemClock.elapsedRealtime();return cachedToken;
    }
    static boolean commentEndpoint(String suffix)throws IOException {
        if(suffix==null||!suffix.startsWith("commentThreads?"))return false;
        int cut=suffix.indexOf("&pageToken=");
        String base=cut<0?suffix:suffix.substring(0,cut);
        if(!base.matches("commentThreads\\?part=snippet&videoId=[A-Za-z0-9_-]{11}&maxResults=50&order=time&textFormat=plainText"))throw new IOException("Invalid comment request");
        if(cut<0)return true;
        String encoded=suffix.substring(cut+11);
        if(encoded.isEmpty()||encoded.length()>12288)throw new IOException("Invalid comment token length");
        for(int i=0;i<encoded.length();i++){
            char ch=encoded.charAt(i);
            if(ch=='%'){
                if(i+2>=encoded.length()||Character.digit(encoded.charAt(i+1),16)<0||Character.digit(encoded.charAt(i+2),16)<0)throw new IOException("Invalid comment token encoding");
                i+=2;
            }else if(!((ch>='A'&&ch<='Z')||(ch>='a'&&ch<='z')||(ch>='0'&&ch<='9')||"_*.+-".indexOf(ch)>=0))throw new IOException("Invalid comment token encoding");
        }
        String decoded=java.net.URLDecoder.decode(encoded,"UTF-8");
        if(decoded.length()>4096)throw new IOException("Comment token too large");
        for(int i=0;i<decoded.length();i++)if(decoded.charAt(i)<32||decoded.charAt(i)>126)throw new IOException("Invalid comment token");
        return true;
    }
    static String endpoint(String suffix)throws IOException {
        if(suffix!=null&&suffix.matches("playlists\\?part=snippet,contentDetails,status&channelId=UC[A-Za-z0-9_-]{22}&maxResults=50(&pageToken=[A-Za-z0-9_-]{1,512})?"))return "https://www.googleapis.com/youtube/v3/"+suffix;
        if(commentEndpoint(suffix))return "https://www.googleapis.com/youtube/v3/"+suffix;
        if(suffix!=null&&suffix.matches("search\\?part=snippet&type=channel&maxResults=50&safeSearch=(none|moderate|strict)&q=([A-Za-z0-9_*.+-]|%[0-9A-Fa-f]{2}){1,1200}(&pageToken=[A-Za-z0-9_-]{1,512})?"))return "https://www.googleapis.com/youtube/v3/"+suffix+"&fields=nextPageToken,pageInfo,items(id,snippet(title,publishedAt,channelId,thumbnails))";
        if("videos?part=snippet,contentDetails,statistics&chart=mostPopular&regionCode=US&maxResults=50".equals(suffix))return "https://www.googleapis.com/youtube/v3/"+suffix+"&fields=items(id,snippet(channelId))";
        if(suffix!=null&&suffix.matches("videos/getRating\\?id=[A-Za-z0-9_-]{11}"))return "https://www.googleapis.com/youtube/v3/"+suffix;
        if(suffix!=null&&suffix.matches("playlists\\?part=snippet,contentDetails,status&mine=true&maxResults=50(&pageToken=[A-Za-z0-9_-]{1,512})?"))return "https://www.googleapis.com/youtube/v3/"+suffix+"&fields=nextPageToken,pageInfo,items(id,snippet(title,description,channelTitle,publishedAt,thumbnails),contentDetails(itemCount),status(privacyStatus))";
        if(suffix!=null&&suffix.matches("subscriptions\\?part=snippet&mine=true&maxResults=50&forChannelId=UC[A-Za-z0-9_-]{22}"))return "https://www.googleapis.com/youtube/v3/"+suffix;
        if(suffix!=null&&suffix.matches("videos\\?part=snippet,contentDetails,statistics&myRating=like&maxResults=50(&pageToken=[A-Za-z0-9_-]{1,512})?"))return "https://www.googleapis.com/youtube/v3/"+suffix+"&fields=nextPageToken,pageInfo,items(id,snippet(title,channelId,channelTitle,publishedAt,thumbnails,categoryId),contentDetails(duration),statistics(viewCount,likeCount,commentCount))";
        if(suffix!=null&&suffix.matches("channels\\?part=snippet,contentDetails,statistics&id=UC[A-Za-z0-9_-]{22}(,UC[A-Za-z0-9_-]{22}){0,49}"))return "https://www.googleapis.com/youtube/v3/"+suffix+"&fields=items(id,snippet(title,description,publishedAt,thumbnails),contentDetails,statistics)";
        if(suffix!=null&&suffix.matches("playlistItems\\?part=snippet,contentDetails&playlistId=[A-Za-z0-9_-]{10,80}&maxResults=(2|10|50)(&pageToken=[A-Za-z0-9_-]{1,512})?"))return "https://www.googleapis.com/youtube/v3/"+suffix+"&fields=nextPageToken,pageInfo,items(id,snippet(title,channelId,channelTitle,resourceId,thumbnails,publishedAt),contentDetails)";
        if(suffix!=null&&suffix.matches("videos\\?part=snippet,contentDetails,statistics&id=[A-Za-z0-9_-]{11}(,[A-Za-z0-9_-]{11}){0,49}"))return "https://www.googleapis.com/youtube/v3/"+suffix+"&fields=items(id,snippet(title,channelId,channelTitle,publishedAt,thumbnails,categoryId),contentDetails(duration),statistics(viewCount,likeCount,commentCount))";
        if(suffix==null||!(suffix.equals("channels?part=snippet&mine=true")||suffix.matches("subscriptions\\?part=snippet&mine=true&maxResults=50&order=alphabetical(&pageToken=[A-Za-z0-9_-]{1,512})?")))throw new IOException("Invalid YouTube request");
        return "https://www.googleapis.com/youtube/v3/"+suffix;
    }
    static JSONObject read(final Context c,String suffix)throws Exception {
        final boolean extended=suffix!=null&&suffix.startsWith("commentThreads?")&&controls(c);
        return GoogleApi.read(c,new GoogleApi.TokenSource(){public String get()throws Exception{return extended?writeToken(c):token(c);}public void clear(){clearToken();}},endpoint(suffix));
    }
    static boolean controlsGranted(AuthorizationResult r){return r.getGrantedScopes().contains(CONTROLS);}
    static void accept(Context c,AuthorizationResult result)throws Exception {accept(c,result,false);}
    static void accept(Context c,AuthorizationResult result,boolean upgrading)throws Exception {
        clearToken();
        if(result.hasResolution()||result.getAccessToken()==null||!result.getGrantedScopes().contains(SCOPE)||!result.getGrantedScopes().contains(EMAIL))throw new IOException("YouTube permission was not granted");
        String token=result.getAccessToken();
        String account=GoogleApi.read(token,"https://www.googleapis.com/oauth2/v1/userinfo?alt=json").getString("email");
        if(!account.contains("@")||account.length()>320)throw new IOException("Invalid account");
        JSONArray channels=GoogleApi.read(token,endpoint("channels?part=snippet&mine=true")).optJSONArray("items");
        if(channels==null||channels.length()!=1)throw new IOException("Select an account with one YouTube channel");
        JSONObject channel=channels.getJSONObject(0);String name=channel.getJSONObject("snippet").getString("title");
        if(upgrading&&(!controlsGranted(result)||!channel.getString("id").equals(c.getSharedPreferences("youtube",0).getString("channelId",""))))throw new IOException("Approve controls for the currently connected YouTube channel");
        if(!c.getSharedPreferences("youtube",0).edit().putString("account",account).putString("channel",name).putString("channelId",channel.getString("id")).putBoolean("controls",result.getGrantedScopes().contains(CONTROLS)).commit())throw new IOException("Could not save account");
    }
}
