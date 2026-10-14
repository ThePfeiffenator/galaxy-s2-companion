package org.retrobridge.galaxyservices.pixel;
import android.content.*;import android.content.pm.PackageManager;import android.database.Cursor;import android.net.Uri;import android.provider.CalendarContract;import org.json.*;import java.io.*;

/** A read-only snapshot; no event details are written to diagnostic logs. */
final class CalendarFeed {
 static JSONObject snapshot(Context c)throws Exception {
  if(c.checkSelfPermission("android.permission.READ_CALENDAR")!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Calendar permission needed");
  long now=System.currentTimeMillis(),begin=now-30L*86400000,end=now+366L*86400000;
  JSONArray calendars=new JSONArray();ContentResolver r=c.getContentResolver();
  String[] cp={"_id","calendar_displayName","calendar_color"};
  try(Cursor cur=r.query(CalendarContract.Calendars.CONTENT_URI,cp,"visible=1 AND sync_events=1",null,null)){
   if(cur==null)throw new IOException("Calendar unavailable");
   while(cur.moveToNext()){if(calendars.length()>=50)throw new IOException("Too many calendars");long cid=cur.getLong(0);JSONArray events=new JSONArray();
    Uri.Builder b=CalendarContract.Instances.CONTENT_URI.buildUpon();ContentUris.appendId(b,begin);ContentUris.appendId(b,end);
    String[] ep={"event_id","begin","end","title","description","eventLocation","allDay","eventTimezone","availability"};
    try(Cursor rows=r.query(b.build(),ep,"calendar_id=?",new String[]{String.valueOf(cid)},"begin ASC")){
     if(rows==null)throw new IOException("Events unavailable");
     while(rows.moveToNext()){if(events.length()>=3000)throw new IOException("Calendar too large");long id=rows.getLong(0),start=rows.getLong(1),finish=rows.getLong(2);JSONArray reminders=new JSONArray();
      try(Cursor remindersCursor=r.query(CalendarContract.Reminders.CONTENT_URI,new String[]{"minutes","method"},"event_id=?",new String[]{String.valueOf(id)},null)){
       if(remindersCursor==null)throw new IOException("Reminders unavailable");while(remindersCursor.moveToNext())if(remindersCursor.getInt(1)==CalendarContract.Reminders.METHOD_ALERT)reminders.put(remindersCursor.getInt(0));}
      events.put(new JSONObject().put("key",id+"_"+start).put("start",start).put("end",finish).put("title",rows.getString(3)).put("description",rows.getString(4)).put("location",rows.getString(5)).put("allDay",rows.getInt(6)).put("timezone",rows.getString(7)).put("availability",rows.getInt(8)).put("reminders",reminders));
     }
    }
    calendars.put(new JSONObject().put("id",cid).put("name",cur.getString(1)).put("color",cur.getInt(2)).put("events",events));
   }
  }
  return new JSONObject().put("version",1).put("complete",true).put("generated",now).put("begin",begin).put("end",end).put("calendars",calendars);
 }
 static void serve(Context c,InputStream in,OutputStream out)throws Exception {
  byte[] h=org.retrobridge.galaxyservices.Wire.header(in);String first=new String(h,"US-ASCII").split("\r\n")[0];String code="200 OK";byte[] data;
  try{if(!first.equals("GET /calendar HTTP/1.1")&&!first.equals("GET /calendar HTTP/1.0"))throw new FileNotFoundException();data=snapshot(c).toString().getBytes("UTF-8");if(data.length>6000000)throw new IOException("Calendar snapshot too large");}
  catch(SecurityException e){code="403 Forbidden";data="Calendar permission needed".getBytes("UTF-8");}
  catch(FileNotFoundException e){code="404 Not Found";data=new byte[0];}
  catch(Exception e){code="503 Service Unavailable";data="Calendar snapshot unavailable".getBytes("UTF-8");}
  out.write(("HTTP/1.1 "+code+"\r\nContent-Type: application/json; charset=UTF-8\r\nContent-Length: "+data.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));out.write(data);out.flush();
 }
}
