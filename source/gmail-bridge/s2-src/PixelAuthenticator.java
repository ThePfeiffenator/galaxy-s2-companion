package org.retrobridge.gmail2012.provider;
import android.accounts.*;import android.app.Service;import android.content.*;import android.os.*;
public final class PixelAuthenticator extends Service {
 static final String TYPE="org.retrobridge.gmail2012.pixel",AUTHORITY="galaxy-gmail-ls";
 @Override public IBinder onBind(Intent i){return new Auth(this).getIBinder();}
 private static final class Auth extends AbstractAccountAuthenticator {
  final Context c;Auth(Context c){super(c);this.c=c;}
  Bundle setup(AccountAuthenticatorResponse response){Bundle b=new Bundle();Intent i=new Intent(c,PixelSetup.class);i.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE,response);b.putParcelable(AccountManager.KEY_INTENT,i);return b;}
  public Bundle addAccount(AccountAuthenticatorResponse r,String t,String at,String[] f,Bundle o){return setup(r);}
  public Bundle confirmCredentials(AccountAuthenticatorResponse r,Account a,Bundle o){Bundle b=new Bundle();b.putBoolean(AccountManager.KEY_BOOLEAN_RESULT,true);return b;}
  public Bundle editProperties(AccountAuthenticatorResponse r,String t){return setup(r);}
  public Bundle getAuthToken(AccountAuthenticatorResponse r,Account a,String t,Bundle o){Bundle b=new Bundle();b.putInt(AccountManager.KEY_ERROR_CODE,AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION);b.putString(AccountManager.KEY_ERROR_MESSAGE,"Google authorization stays on Pixel");return b;}
  public String getAuthTokenLabel(String t){return "Pixel";}
  public Bundle updateCredentials(AccountAuthenticatorResponse r,Account a,String t,Bundle o){return setup(r);}
  public Bundle hasFeatures(AccountAuthenticatorResponse r,Account a,String[] f){Bundle b=new Bundle();b.putBoolean(AccountManager.KEY_BOOLEAN_RESULT,true);return b;}
 }
}
