package org.retrobridge.galaxyservices.pixel;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.*;
import android.net.Uri;
import android.os.*;
import android.provider.ContactsContract;
import android.service.notification.StatusBarNotification;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Personal notification relay. Bodies, contact references and reply tokens are never logged. */
final class RcsRelay extends SQLiteOpenHelper {
 static final String MESSAGES="com.google.android.apps.messaging";
 private static RcsRelay instance;
 static synchronized RcsRelay get(Context c){if(instance==null)instance=new RcsRelay(c.getApplicationContext());return instance;}
 final Context app;
 volatile RcsNotificationListener listener;
 private final Map<String,Target> active=new HashMap<>();
 private static final class Target {String phone,route,key;Notification.Action action;RemoteInput input;}
 private RcsRelay(Context c){super(c,"rcs-relay.db",null,1);app=c;RcsJobs.recover(getWritableDatabase());}
 public void onCreate(SQLiteDatabase d){
  d.execSQL("CREATE TABLE incoming (id TEXT PRIMARY KEY, phone TEXT NOT NULL, body TEXT NOT NULL, stamp INTEGER NOT NULL, route TEXT NOT NULL, received INTEGER NOT NULL DEFAULT 0)");
  d.execSQL("CREATE TABLE routes (route TEXT PRIMARY KEY, phone TEXT NOT NULL, seen INTEGER NOT NULL)");
  d.execSQL("CREATE TABLE jobs (id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, state TEXT NOT NULL, created INTEGER NOT NULL)");
 }
 public void onUpgrade(SQLiteDatabase d,int old,int next){throw new IllegalStateException("Unsupported relay journal");}
 public void onOpen(SQLiteDatabase d){super.onOpen(d);if(!d.isReadOnly())d.execSQL("PRAGMA synchronous=FULL");}
 boolean enabled(){return app.getSharedPreferences("rcs-relay",0).getBoolean("enabled",false);}
 boolean permissions(){return app.checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED&&app.checkSelfPermission(Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED;}
 static String hash(String value)throws Exception{return org.retrobridge.galaxyservices.Wire.hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8")));}
 String number(String value){
  if(value==null)return null;String country="";
  try{country=((TelephonyManager)app.getSystemService(Context.TELEPHONY_SERVICE)).getNetworkCountryIso();}catch(Exception ignored){}
  if(country==null||country.isEmpty())country=Locale.getDefault().getCountry();
  String n=PhoneNumberUtils.formatNumberToE164(value,country.toUpperCase(Locale.US));
  return n!=null&&n.matches("\\+[1-9][0-9]{4,14}")?n:null;
 }
 private String phone(Person person)throws Exception{
  if(person==null||person.getUri()==null)return null;
  Uri uri=Uri.parse(person.getUri());if("tel".equals(uri.getScheme()))return number(uri.getSchemeSpecificPart());
  if(!"content".equals(uri.getScheme())||!ContactsContract.AUTHORITY.equals(uri.getAuthority()))return null;
  Uri contact=ContactsContract.Contacts.lookupContact(app.getContentResolver(),uri);if(contact==null)return null;
  long id=ContentUris.parseId(contact);HashSet<String> numbers=new HashSet<>();
  try(Cursor c=app.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},ContactsContract.CommonDataKinds.Phone.CONTACT_ID+"=?",new String[]{Long.toString(id)},null)){
   if(c==null)return null;while(c.moveToNext()){String n=number(c.getString(0));if(n!=null)numbers.add(n);}
  }return numbers.size()==1?numbers.iterator().next():null;
 }
 private boolean ordinarySms(String phone,String body,long stamp)throws Exception{
  try(Cursor c=app.getContentResolver().query(Uri.parse("content://sms/inbox"),new String[]{"address","body"},"date>=? AND date<=?",new String[]{Long.toString(stamp-10000),Long.toString(stamp+10000)},null)){
   if(c==null)throw new IOException("SMS classification unavailable");int count=0;while(c.moveToNext()){
    if(++count>256)throw new IOException("SMS classification limit");
    if(phone.equals(number(c.getString(0)))&&body.equals(c.getString(1)))return true;
   }
  }return false;
 }
 synchronized void removed(String key){active.remove(key);}
 synchronized void disconnected(){active.clear();listener=null;note("Notification connection unavailable");}
 synchronized void observe(StatusBarNotification sbn){
  if(!MESSAGES.equals(sbn.getPackageName()))return;
  active.remove(sbn.getKey());
  if(!enabled()||!permissions()){note("Message relay needs permissions");return;}
  try{
   Notification n=sbn.getNotification();
   if((n.flags&Notification.FLAG_GROUP_SUMMARY)!=0||n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION))return;
   String shortcut=n.getShortcutId();if(shortcut==null||shortcut.isEmpty())return;
   Parcelable[] bundles=n.extras.getParcelableArray(Notification.EXTRA_MESSAGES);if(bundles==null)return;
   List<Notification.MessagingStyle.Message> messages=Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles);
   if(messages.isEmpty()||messages.size()>100)return;
   String sender=null;
   for(Notification.MessagingStyle.Message m:messages){
    if(m.getSenderPerson()==null)continue;
    String current=phone(m.getSenderPerson());if(current==null){note("Contact has no unique phone number; use Pixel");return;}
    if(sender!=null&&!sender.equals(current)){note("Group conversation stays on Pixel");return;}sender=current;
   }if(sender==null)return;
   Target t=new Target();t.phone=sender;t.key=sbn.getKey();t.route=hash("google-messages:"+shortcut+":"+sender);
   if(n.actions!=null)for(Notification.Action a:n.actions){
    if(a.getSemanticAction()!=Notification.Action.SEMANTIC_ACTION_REPLY||a.actionIntent==null||!MESSAGES.equals(a.actionIntent.getCreatorPackage())||(Build.VERSION.SDK_INT>=31&&a.isAuthenticationRequired()))continue;
    RemoteInput[] inputs=a.getRemoteInputs();if(inputs==null)continue;
    for(RemoteInput input:inputs)if(input.getAllowFreeFormInput()){
     if(t.action!=null)throw new IOException("Ambiguous reply actions");t.action=a;t.input=input;
    }
   }
   SQLiteDatabase d=getWritableDatabase();boolean rcs=false;long now=System.currentTimeMillis();
   for(Notification.MessagingStyle.Message m:messages){
    if(m.getSenderPerson()==null)continue;
    if(m.getDataUri()!=null){try{if(IncomingMedia.get(app).notification(t.route,sender,m.getTimestamp(),m.getText()==null?"":m.getText().toString(),m.getDataMimeType(),m.getDataUri()))rcs=true;}catch(Exception attachment){note("Attachment unavailable: "+attachment.getClass().getSimpleName());}continue;}
    if(m.getText()==null)continue;
    String body=m.getText().toString();long stamp=m.getTimestamp();
    if(body.isEmpty()||body.length()>16000||stamp<=0||stamp>now+60000||now-stamp>86400000L)continue;
    if(ordinarySms(sender,body,stamp))continue;
    rcs=true;String id=hash(t.route+":"+stamp+":"+body);
    ContentValues v=new ContentValues();v.put("id",id);v.put("phone",sender);v.put("body",body);v.put("stamp",stamp);v.put("route",t.route);
    d.insertWithOnConflict("incoming",null,v,SQLiteDatabase.CONFLICT_IGNORE);
   }
   if(rcs){ContentValues v=new ContentValues();v.put("route",t.route);v.put("phone",sender);v.put("seen",now);d.insertWithOnConflict("routes",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
   // A retained RCS route can use an updated reply action even after inline history changes.
   if(t.action!=null&&knownRoute(t.route,sender))active.put(t.key,t);
   d.delete("incoming","received=1 AND stamp<?",new String[]{Long.toString(now-14*86400000L)});
   note("Message relay ready");
  }catch(Exception e){note("Message relay waiting: "+e.getClass().getSimpleName());}
 }
 private boolean knownRoute(String route,String phone){try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM routes WHERE route=? AND phone=?",new String[]{route,phone})){return c.moveToFirst();}}
 private boolean knownPhone(String phone){try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM routes WHERE phone=? LIMIT 1",new String[]{phone})){return c.moveToFirst();}}
 private Target target(String phone){Target found=null;for(Target t:active.values())if(t.phone.equals(phone)){if(found!=null)return null;found=t;}return found;}
 private JSONObject route(String raw)throws Exception{
  String phone=number(raw);if(phone==null)return new JSONObject().put("state","invalid");
  if(!enabled()||!permissions())return new JSONObject().put("state","unavailable");
  if(knownPhone(phone)&&PlatformText.available(app))return new JSONObject().put("state","reply").put("route",PlatformText.route(phone));
  if(listener==null)return new JSONObject().put("state","unavailable");
  Target t=target(phone);return new JSONObject().put("state",t!=null?"reply":knownPhone(phone)?"stale":"none").put("route",t==null?"":t.route);
 }
 synchronized JSONObject request(JSONObject request)throws Exception{
  String op=request.getString("op");
  if(op.equals("route"))return route(request.getString("number"));
  if(!enabled()||!permissions())return new JSONObject().put("state","disabled");
  if(op.equals("list")){
   JSONArray rows=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,phone,body,stamp FROM incoming WHERE received=0 ORDER BY stamp,rowid LIMIT 8",null)){
    while(c.moveToNext())rows.put(new JSONObject().put("id",c.getString(0)).put("number",c.getString(1)).put("body",c.getString(2)).put("time",c.getLong(3)));
   }return new JSONObject().put("state","ok").put("messages",rows);
  }
  if(op.equals("ack")){
   String id=request.getString("id");if(!id.matches("[0-9a-f]{64}"))throw new IOException("Invalid receipt");
   ContentValues v=new ContentValues();v.put("received",1);getWritableDatabase().update("incoming",v,"id=?",new String[]{id});return new JSONObject().put("state","ok");
  }
  if(op.equals("send"))return send(request);
  throw new IOException("Unknown operation");
 }
 private JSONObject send(JSONObject request)throws Exception{
  String id=request.getString("id"),phone=number(request.getString("number")),body=request.getString("body"),route=request.getString("route");
  if(!id.matches("[0-9a-f]{64}")||phone==null||body.isEmpty()||body.length()>16000||!route.matches("[0-9a-f]{64}"))throw new IOException("Invalid reply");
  String fingerprint=hash(phone+":"+route+":"+body);SQLiteDatabase d=getWritableDatabase();
  String prior=RcsJobs.existing(d,id,fingerprint);if(prior!=null)return new JSONObject().put("state",prior);
  if(knownPhone(phone)&&PlatformText.route(phone).equals(route))return new JSONObject().put("state",PlatformText.get(app).send(id,phone,body));
  Target t=target(phone);RcsNotificationListener owner=listener;
  if(owner==null||t==null||!route.equals(t.route)||!owner.isActive(t.key))return new JSONObject().put("state","STALE");
  String claim=RcsJobs.claim(d,id,fingerprint);if(!"NEW".equals(claim))return new JSONObject().put("state",claim);
  String state;
  try{
   Intent fill=new Intent();Bundle results=new Bundle();results.putCharSequence(t.input.getResultKey(),body);
   RemoteInput.addResultsToIntent(new RemoteInput[]{t.input},fill,results);RemoteInput.setResultsSource(fill,RemoteInput.SOURCE_FREE_FORM_INPUT);
   t.action.actionIntent.send(app,0,fill);state="SUBMITTED";
  }catch(PendingIntent.CanceledException e){state="REJECTED";}catch(Exception e){state="UNCERTAIN";}
  RcsJobs.finish(d,id,state);note("Reply "+state.toLowerCase(Locale.US));
  return new JSONObject().put("state",state);
 }
 void note(String status){try(FileOutputStream f=app.openFileOutput("rcs-status.txt",Context.MODE_PRIVATE)){f.write(status.getBytes("UTF-8"));}catch(Exception ignored){}}
}
