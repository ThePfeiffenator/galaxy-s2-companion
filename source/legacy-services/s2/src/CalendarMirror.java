package org.retrobridge.galaxyservices.s2;
import android.content.*;import android.database.Cursor;import android.net.Uri;import android.provider.CalendarContract;import org.json.*;import java.io.*;import java.net.*;import java.util.*;import javax.net.ssl.HttpsURLConnection;import org.retrobridge.galaxyservices.Wire;

/** Owned local calendars only. Incoming failure never clears a previously good mirror. */
final class CalendarMirror {
 static final String ACCOUNT="Galaxy Services - Pixel",TYPE="LOCAL",PREFIX="GSC_";
 static boolean busy;
 static Uri sync(Uri uri){return uri.buildUpon().appendQueryParameter("caller_is_syncadapter","true").appendQueryParameter("account_name",ACCOUNT).appendQueryParameter("account_type",TYPE).build();}
 static void status(Context c,String s){try{Wire.writeFile(new File(c.getFilesDir(),"calendar-status.txt"),s.getBytes("UTF-8"));}catch(Exception ignored){}}
 static JSONObject fetch()throws Exception {
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://galaxy-services.invalid/calendar").openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",Wire.LOCAL)));
  c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setInstanceFollowRedirects(false);
  try{int code=c.getResponseCode();if(code!=200)throw new IOException("Calendar HTTP "+code);ByteArrayOutputStream b=new ByteArrayOutputStream();InputStream in=c.getInputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(b.size()+n>6000000)throw new IOException("Snapshot too large");b.write(buf,0,n);}return new JSONObject(b.toString("UTF-8"));}finally{c.disconnect();}
 }
 static boolean run(Context c){synchronized(CalendarMirror.class){if(busy)return false;busy=true;}try{
  JSONObject feed=fetch();if(feed.getInt("version")!=1||!feed.getBoolean("complete")||Math.abs(System.currentTimeMillis()-feed.getLong("generated"))>300000)throw new IOException("Invalid snapshot");
  JSONArray calendars=feed.getJSONArray("calendars");ContentResolver r=c.getContentResolver();int count=0;Set<String> currentCalendars=new HashSet<String>();
  // Validate the entire snapshot before changing any calendar.
  for(int a=0;a<calendars.length();a++){JSONObject cal=calendars.getJSONObject(a);if(!currentCalendars.add(PREFIX+cal.getLong("id")))throw new IOException("Duplicate calendar");JSONArray events=cal.getJSONArray("events");Set<String> keys=new HashSet<String>();for(int b=0;b<events.length();b++){JSONObject e=events.getJSONObject(b);if(e.getLong("end")<e.getLong("start")||!keys.add(PREFIX+e.getString("key")))throw new IOException("Invalid event");e.getJSONArray("reminders");}}
  for(int a=0;a<calendars.length();a++){JSONObject cal=calendars.getJSONObject(a);String source=PREFIX+cal.getLong("id");long local=-1;
   Cursor cur=r.query(CalendarContract.Calendars.CONTENT_URI,new String[]{"_id"},"account_name=? AND account_type=? AND _sync_id=?",new String[]{ACCOUNT,TYPE,source},null);if(cur==null)throw new IOException("Calendar provider unavailable");try{if(cur.moveToFirst())local=cur.getLong(0);}finally{cur.close();}
   ContentValues cv=new ContentValues();cv.put("account_name",ACCOUNT);cv.put("account_type",TYPE);cv.put("name",source);cv.put("_sync_id",source);cv.put("calendar_displayName",cal.optString("name","Calendar")+" (Pixel)");cv.put("calendar_color",cal.getInt("color"));cv.put("calendar_access_level",CalendarContract.Calendars.CAL_ACCESS_READ);cv.put("ownerAccount",ACCOUNT);cv.put("calendar_timezone",TimeZone.getDefault().getID());cv.put("visible",1);cv.put("sync_events",1);cv.put("allowedReminders","1");
   if(local<0){Uri inserted=r.insert(sync(CalendarContract.Calendars.CONTENT_URI),cv);if(inserted==null)throw new IOException("Calendar create failed");local=ContentUris.parseId(inserted);}else r.update(sync(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI,local)),cv,null,null);
   HashMap<String,Long> existing=new HashMap<String,Long>();cur=r.query(CalendarContract.Events.CONTENT_URI,new String[]{"_id","_sync_id"},"calendar_id=?",new String[]{String.valueOf(local)},null);if(cur==null)throw new IOException("Events unavailable");try{while(cur.moveToNext())if(cur.getString(1)!=null&&cur.getString(1).startsWith(PREFIX))existing.put(cur.getString(1),cur.getLong(0));}finally{cur.close();}
   JSONArray events=cal.getJSONArray("events");ArrayList<ContentProviderOperation> ops=new ArrayList<ContentProviderOperation>();
   for(int b=0;b<events.length();b++){JSONObject e=events.getJSONObject(b);String id=PREFIX+e.getString("key");Long old=existing.remove(id);ContentValues ev=new ContentValues();ev.put("calendar_id",local);ev.put("_sync_id",id);ev.put("dtstart",e.getLong("start"));ev.put("dtend",e.getLong("end"));ev.put("title",e.optString("title",""));ev.put("description",e.isNull("description")?"":e.getString("description"));ev.put("eventLocation",e.isNull("location")?"":e.getString("location"));ev.put("allDay",e.getInt("allDay"));ev.put("eventTimezone",e.getInt("allDay")==1?"UTC":e.optString("timezone",TimeZone.getDefault().getID()));ev.put("availability",e.getInt("availability"));ev.put("eventStatus",CalendarContract.Events.STATUS_CONFIRMED);ev.put("dirty",0);ev.put("deleted",0);ev.put("hasAlarm",e.getJSONArray("reminders").length()>0?1:0);
    int index=ops.size();if(old==null)ops.add(ContentProviderOperation.newInsert(sync(CalendarContract.Events.CONTENT_URI)).withValues(ev).build());else{ops.add(ContentProviderOperation.newUpdate(sync(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,old))).withValues(ev).build());ops.add(ContentProviderOperation.newDelete(sync(CalendarContract.Reminders.CONTENT_URI)).withSelection("event_id=?",new String[]{String.valueOf(old)}).build());}
    JSONArray reminders=e.getJSONArray("reminders");for(int d=0;d<reminders.length();d++){ContentProviderOperation.Builder op=ContentProviderOperation.newInsert(sync(CalendarContract.Reminders.CONTENT_URI)).withValue("minutes",reminders.getInt(d)).withValue("method",CalendarContract.Reminders.METHOD_ALERT);if(old==null)op.withValueBackReference("event_id",index);else op.withValue("event_id",old);ops.add(op.build());}
    count++;if(ops.size()>180){r.applyBatch(CalendarContract.AUTHORITY,ops);ops.clear();}
   }
   for(Long old:existing.values())ops.add(ContentProviderOperation.newDelete(sync(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,old))).build());if(!ops.isEmpty())r.applyBatch(CalendarContract.AUTHORITY,ops);
  }
  // Retired source calendars are hidden; their local data stays recoverable.
  Cursor owned=r.query(CalendarContract.Calendars.CONTENT_URI,new String[]{"_id","_sync_id"},"account_name=? AND account_type=?",new String[]{ACCOUNT,TYPE},null);if(owned!=null)try{while(owned.moveToNext())if(!currentCalendars.contains(owned.getString(1))){ContentValues hidden=new ContentValues();hidden.put("visible",0);r.update(sync(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI,owned.getLong(0))),hidden,null,null);}}finally{owned.close();}
  status(c,"Synced "+calendars.length()+" calendars, "+count+" occurrences\nUpdated="+System.currentTimeMillis());
 return true;}catch(Exception e){status(c,"Waiting: "+e.getClass().getSimpleName()+" "+e.getMessage());return false;}finally{synchronized(CalendarMirror.class){busy=false;}}}
}
