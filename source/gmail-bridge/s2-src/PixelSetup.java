package org.retrobridge.gmail2012.provider;
import android.app.*;import android.accounts.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;
/** Open the original inbox immediately once the Pixel account has been paired. */
public final class PixelSetup extends Activity {
 TextView status;
 void inbox(String email){Intent i=new Intent();i.setClassName(this,"org.retrobridge.gmail2012.GmailActivity");i.putExtra("account",email);i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);startActivity(i);Bundle extras=new Bundle();extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL,true);ContentResolver.requestSync(new Account(email,PixelAuthenticator.TYPE),PixelAuthenticator.AUTHORITY,extras);finish();}
 @Override public void onCreate(Bundle b){super.onCreate(b);setTitle("Gmail");
  AccountManager am=AccountManager.get(this);for(Account a:am.getAccountsByType(PixelAuthenticator.TYPE))if(a.name.equals("sample@galaxy.invalid"))am.removeAccount(a,null,null);
  String cached=getSharedPreferences("pixel",0).getString("account","");
  if(!cached.isEmpty()&&!cached.endsWith("@galaxy.invalid")){inbox(cached);return;}
  LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(24,40,24,24);status=new TextView(this);status.setText("Connecting to Pixel...");root.addView(status);setContentView(root);
  new Thread(new Runnable(){public void run(){try{final String email=PixelMail.connect(PixelSetup.this,false);runOnUiThread(new Runnable(){public void run(){inbox(email);}});}catch(final Exception e){runOnUiThread(new Runnable(){public void run(){status.setText("Unable to connect to Pixel.\n"+PixelMail.safeError(e));Button retry=new Button(PixelSetup.this);retry.setText("Retry");((LinearLayout)status.getParent()).addView(retry);retry.setOnClickListener(new View.OnClickListener(){public void onClick(View v){Intent i=getIntent();finish();startActivity(i);}});}});}}},"Pixel Gmail setup").start();
 }
}
