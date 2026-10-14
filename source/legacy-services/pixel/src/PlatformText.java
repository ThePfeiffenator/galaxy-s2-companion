package org.retrobridge.galaxyservices.pixel;
import android.app.*;import android.content.*;import android.content.pm.PackageManager;import android.database.sqlite.SQLiteDatabase;import android.net.Uri;import android.os.Build;import android.telephony.*;import org.retrobridge.galaxyservices.Wire;import java.security.MessageDigest;import java.util.*;

/** Public SmsManager handoff; Android 17's default Messages app chooses RCS/SMS. */
final class PlatformText {
 static final String MESSAGES="com.google.android.apps.messaging";private static PlatformText instance;
 static synchronized PlatformText get(Context c){if(instance==null)instance=new PlatformText(c.getApplicationContext());return instance;}
 final Context app;final SQLiteDatabase db;
 private PlatformText(Context c){app=c;db=c.openOrCreateDatabase("platform-text.db",0,null);PlatformTextJobs.schema(db);PlatformTextJobs.recover(db);}
 static boolean available(Context c){return Build.VERSION.SDK_INT>=37&&c.checkSelfPermission(android.Manifest.permission.SEND_SMS)==PackageManager.PERMISSION_GRANTED&&c.getSharedPreferences("media-relay",0).getBoolean("enabled",false)&&MESSAGES.equals(android.provider.Telephony.Sms.getDefaultSmsPackage(c))&&!c.getPackageManager().queryIntentServices(new Intent("android.service.messaging.AlternativeMessageTransportService").setPackage(MESSAGES),0).isEmpty();}
 static String route(String phone)throws Exception{return Wire.hex(MessageDigest.getInstance("SHA-256").digest(("platform-text-v1:"+phone).getBytes("UTF-8")));}
 static String wireState(String state){return "SENT".equals(state)?"SUBMITTED":"UNCERTAIN";}
 synchronized String send(String id,String phone,String body)throws Exception{
  String fingerprint=Wire.hex(MessageDigest.getInstance("SHA-256").digest((phone+":"+body).getBytes("UTF-8")));String prior=PlatformTextJobs.state(db,id,fingerprint);if(prior!=null)return wireState(prior);
  if(!available(app))return "STALE";int sub=SubscriptionManager.getDefaultSmsSubscriptionId();if(!SubscriptionManager.isValidSubscriptionId(sub))return "REJECTED";
  SmsManager sms=app.getSystemService(SmsManager.class).createForSubscriptionId(sub);ArrayList<String> parts=sms.divideMessage(body);if(parts.isEmpty()||parts.size()>128)return "REJECTED";
  ArrayList<PendingIntent> sent=new ArrayList<>();for(int i=0;i<parts.size();i++){Intent result=new Intent(app,PlatformTextResult.class).setData(Uri.parse("galaxy-text-result:"+id+":"+i));sent.add(PendingIntent.getBroadcast(app,0,result,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE));}
  if(!PlatformTextJobs.claim(db,id,fingerprint,parts.size()))return wireState(PlatformTextJobs.state(db,id,fingerprint));
  try{if(parts.size()==1)sms.sendTextMessage(phone,null,body,sent.get(0),null);else sms.sendMultipartTextMessage(phone,null,parts,sent,null);PlatformTextJobs.transition(db,id,"SUBMITTING","SUBMITTED");}
  catch(Exception e){PlatformTextJobs.transition(db,id,"SUBMITTING","UNCERTAIN");}
  // Bound the wait below the existing S2 HTTP timeout. wait() releases this lock
  // so the explicit result receiver can record results and notify us.
  long until=android.os.SystemClock.elapsedRealtime()+8000;
  while("SUBMITTED".equals(PlatformTextJobs.state(db,id,null))&&android.os.SystemClock.elapsedRealtime()<until)wait(Math.min(750,Math.max(1,until-android.os.SystemClock.elapsedRealtime())));
  // Only confirmed per-part success becomes the legacy protocol's success ACK.
  // A late or ambiguous outcome never permits another automatic submission.
  return wireState(PlatformTextJobs.state(db,id,fingerprint));
 }
 synchronized void result(String id,int part,int result){PlatformTextJobs.result(db,id,part,result);notifyAll();}
}
