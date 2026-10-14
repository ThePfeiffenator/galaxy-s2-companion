package org.retrobridge.galaxyservices.s2;
import android.content.*;import android.database.Cursor;import android.provider.CalendarContract;import java.util.*;import java.io.*;import org.retrobridge.galaxyservices.Wire;
/** Reports counts only, never event text. Does not mutate provider data. */
final class CalendarAudit {
 static void run(Context c){try{
  ContentResolver r=c.getContentResolver();ArrayList<String> ids=new ArrayList<String>();Set<String> source=new HashSet<String>();int duplicateCalendars=0;
  Cursor q=r.query(CalendarContract.Calendars.CONTENT_URI,new String[]{"_id","_sync_id"},"account_name=? AND account_type=?",new String[]{CalendarMirror.ACCOUNT,CalendarMirror.TYPE},null);
  if(q==null)throw new IOException("Provider unavailable");try{while(q.moveToNext()){ids.add(q.getString(0));if(!source.add(q.getString(1)))duplicateCalendars++;}}finally{q.close();}
  int events=0,duplicates=0;Set<String> keys=new HashSet<String>();
  for(String id:ids){q=r.query(CalendarContract.Events.CONTENT_URI,new String[]{"_sync_id"},"calendar_id=? AND deleted=0",new String[]{id},null);if(q==null)throw new IOException("Provider unavailable");try{while(q.moveToNext()){events++;if(!keys.add(id+":"+q.getString(0)))duplicates++;}}finally{q.close();}}
  Wire.writeFile(new File(c.getFilesDir(),"calendar-audit.txt"),("Calendars="+ids.size()+" Events="+events+" DuplicateCalendars="+duplicateCalendars+" DuplicateEvents="+duplicates+" Updated="+System.currentTimeMillis()).getBytes("UTF-8"));
 }catch(Exception e){try{Wire.writeFile(new File(c.getFilesDir(),"calendar-audit.txt"),("Audit failed: "+e.getClass().getSimpleName()).getBytes("UTF-8"));}catch(Exception ignored){}}}
}
