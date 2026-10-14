package org.retrobridge.galaxymail.pixel;

import android.accounts.Account;
import android.content.Context;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Google credentials stay on Pixel. Never log tokens or API response bodies. */
final class MailAccount {
    static final String SCOPE="https://www.googleapis.com/auth/gmail.modify";
    static String email(Context c){return c.getSharedPreferences("mail",0).getString("account","");}
    static AuthorizationRequest request(Context c,boolean choose){
        AuthorizationRequest.Builder b=AuthorizationRequest.builder().setRequestedScopes(Collections.singletonList(new Scope(SCOPE)));
        String account=email(c);if(!choose&&!account.isEmpty())b.setAccount(new Account(account,"com.google"));return b.build();
    }
    static String token(Context c)throws Exception {
        if(email(c).isEmpty())throw new IOException("Connect Gmail on Pixel");
        AuthorizationResult result=Tasks.await(Identity.getAuthorizationClient(c).authorize(request(c,false)),30,TimeUnit.SECONDS);
        if(result.hasResolution()||result.getAccessToken()==null)throw new IOException("Gmail needs authorization on Pixel");
        return result.getAccessToken();
    }
    static JSONObject profile(String token)throws Exception{return GoogleApi.read(token,endpoint("profile"));}
    static String endpoint(String suffix)throws IOException {
        if(suffix==null||!(suffix.equals("profile")||suffix.equals("labels")||suffix.matches("messages\\?maxResults=20(&pageToken=[A-Za-z0-9_-]{1,512})?")||suffix.matches("messages/[a-f0-9]{1,32}\\?format=full")))throw new IOException("Invalid mail request");
        return "https://gmail.googleapis.com/gmail/v1/users/me/"+suffix;
    }
    static JSONObject read(Context c,String suffix)throws Exception{return GoogleApi.read(c,()->token(c),endpoint(suffix));}
    static void accept(Context c,AuthorizationResult result)throws Exception {
        if(result.hasResolution()||result.getAccessToken()==null||!result.getGrantedScopes().contains(SCOPE))throw new IOException("Gmail permission was not granted");
        String email=profile(result.getAccessToken()).getString("emailAddress");
        if(!email.contains("@")||email.length()>320)throw new IOException("Invalid account");
        if(!c.getSharedPreferences("mail",0).edit().putString("account",email).commit())throw new IOException("Could not save account");
    }
}
