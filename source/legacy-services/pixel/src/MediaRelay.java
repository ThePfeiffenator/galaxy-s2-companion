package org.retrobridge.galaxyservices.pixel;
import android.app.*;import android.content.*;import android.content.pm.PackageManager;import android.database.*;import android.database.sqlite.*;import android.net.Uri;import android.telephony.*;
import org.retrobridge.galaxyservices.*;import java.io.*;import java.security.*;import java.util.Locale;import org.json.*;

final class MediaRelay {
 private static MediaRelay instance;
 static synchronized MediaRelay get(Context c)throws IOException{if(instance==null)instance=new MediaRelay(c.getApplicationContext());return instance;}
 final Context app;final SQLiteDatabase db;final File folder;
 private MediaRelay(Context c)throws IOException{app=c;folder=new File(c.getFilesDir(),"message-media");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("storage");db=c.openOrCreateDatabase("message-media.db",0,null);MediaJobs.schema(db);MediaJobs.recover(db);}
 boolean enabled(){return app.getSharedPreferences("media-relay",0).getBoolean("enabled",false);}
 synchronized JSONObject status(String id)throws Exception{return new JSONObject().put("id",id).put("state",MediaJobs.state(db,id,null));}
 synchronized JSONObject stage(String id,String digest,byte[] data)throws Exception{
  if(!enabled())throw new IOException("disabled");if(!digest.equals(Wire.hex(MessageDigest.getInstance("SHA-256").digest(data))))throw new IOException("digest");
  String state=MediaJobs.state(db,id,digest);if(!state.equals("MISSING"))return status(id);
  try(Cursor c=db.rawQuery("SELECT count(*) FROM jobs WHERE state IN ('STAGED','SUBMITTING','SUBMITTED','UNCERTAIN')",null)){c.moveToFirst();if(c.getInt(0)>=16)throw new IOException("queue full");}
  MediaPayload payload=MediaPayload.decode(data);String number=payload.number;
  if(!number.startsWith("+")){TelephonyManager tm=app.getSystemService(TelephonyManager.class);String country=tm.getSimCountryIso();if(country==null||country.length()!=2)throw new IOException("country unavailable");number=PhoneNumberUtils.formatNumberToE164(number,country.toUpperCase(Locale.US));}
  if(number==null||!number.matches("\\+[1-9][0-9]{4,14}"))throw new IOException("recipient");
  byte[] pdu=MediaPdu.encode(app,id,new MediaPayload(number,payload.mime,payload.caption,payload.media));File target=new File(folder,id+".pdu"),tmp=new File(folder,id+".tmp");
  Wire.writeFile(tmp,pdu);if(!tmp.renameTo(target))throw new IOException("storage");MediaJobs.stage(db,id,digest);return status(id);
 }
 synchronized JSONObject send(String id)throws Exception{
  if(!enabled())throw new IOException("disabled");
  if(!MediaJobs.state(db,id,null).equals("STAGED"))return status(id);
  if(app.checkSelfPermission(android.Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED)return status(id).put("waiting","permission");
  int sub=SubscriptionManager.getDefaultSmsSubscriptionId();if(!SubscriptionManager.isValidSubscriptionId(sub))return status(id).put("waiting","default_sim");
  if(!new File(folder,id+".pdu").isFile())throw new IOException("payload missing");
  Intent result=new Intent(app,MediaResultReceiver.class).setData(Uri.parse("galaxy-media-result:"+id));
  PendingIntent callback=PendingIntent.getBroadcast(app,0,result,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
  if(!MediaJobs.claim(db,id))return status(id);
  try{app.getSystemService(SmsManager.class).createForSubscriptionId(sub).sendMultimediaMessage(app,Uri.parse("content://org.retrobridge.galaxyservices.pixel.media/"+id),null,null,callback);MediaJobs.transition(db,id,"SUBMITTING","SUBMITTED");}
  catch(Exception e){MediaJobs.transition(db,id,"SUBMITTING","UNCERTAIN");}
  return status(id);
 }
 synchronized void result(String id,int result)throws Exception{MediaJobs.result(db,id,result);String s=MediaJobs.state(db,id,null);if(s.equals("SENT")||s.equals("FAILED")){new File(folder,id+".pdu").delete();app.revokeUriPermission(Uri.parse("content://org.retrobridge.galaxyservices.pixel.media/"+id),Intent.FLAG_GRANT_READ_URI_PERMISSION);}}
}
