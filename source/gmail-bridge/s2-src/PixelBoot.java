package org.retrobridge.gmail2012.provider;
import android.content.*;import android.accounts.*;import android.os.Bundle;
public final class PixelBoot extends BroadcastReceiver {
 public void onReceive(Context c,Intent i){for(Account a:AccountManager.get(c).getAccountsByType(PixelAuthenticator.TYPE))ContentResolver.requestSync(a,PixelAuthenticator.AUTHORITY,new Bundle());}
}
