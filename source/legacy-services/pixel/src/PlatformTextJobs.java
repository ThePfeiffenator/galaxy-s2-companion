package org.retrobridge.galaxyservices.pixel;
import android.content.ContentValues;import android.database.Cursor;import android.database.sqlite.SQLiteDatabase;import java.io.IOException;
/** Per-part outcomes with an atomic no-repeat submission gate. Contains no text. */
final class PlatformTextJobs {
 static void schema(SQLiteDatabase d){d.execSQL("CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,parts INTEGER NOT NULL,state TEXT NOT NULL,created INTEGER NOT NULL)");d.execSQL("CREATE TABLE IF NOT EXISTS results(id TEXT NOT NULL,part INTEGER NOT NULL,result INTEGER NOT NULL,PRIMARY KEY(id,part))");}
 static void recover(SQLiteDatabase d){d.execSQL("UPDATE jobs SET state='UNCERTAIN' WHERE state='SUBMITTING'");}
 static String state(SQLiteDatabase d,String id,String fingerprint)throws IOException{try(Cursor c=d.rawQuery("SELECT fingerprint,state FROM jobs WHERE id=?",new String[]{id})){if(!c.moveToFirst())return null;if(fingerprint!=null&&!fingerprint.equals(c.getString(0)))throw new IOException("Text identity changed");return c.getString(1);}}
 static boolean claim(SQLiteDatabase d,String id,String fingerprint,int parts)throws IOException{if(parts<1||parts>128)throw new IOException("Text size");if(state(d,id,fingerprint)!=null)return false;ContentValues v=new ContentValues();v.put("id",id);v.put("fingerprint",fingerprint);v.put("parts",parts);v.put("state","SUBMITTING");v.put("created",System.currentTimeMillis());return d.insertWithOnConflict("jobs",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1;}
 static void transition(SQLiteDatabase d,String id,String from,String to){ContentValues v=new ContentValues();v.put("state",to);d.update("jobs",v,"id=? AND state=?",new String[]{id,from});}
 static void result(SQLiteDatabase d,String id,int part,int result){d.beginTransaction();try{
  int parts;String state;try(Cursor c=d.rawQuery("SELECT parts,state FROM jobs WHERE id=?",new String[]{id})){if(!c.moveToFirst())return;parts=c.getInt(0);state=c.getString(1);}
  if(part<0||part>=parts||"SENT".equals(state)||"FAILED".equals(state))return;
  ContentValues v=new ContentValues();v.put("id",id);v.put("part",part);v.put("result",result);d.insertWithOnConflict("results",null,v,SQLiteDatabase.CONFLICT_IGNORE);
  int count,errors;try(Cursor c=d.rawQuery("SELECT count(*),sum(CASE WHEN result=-1 THEN 0 ELSE 1 END) FROM results WHERE id=?",new String[]{id})){c.moveToFirst();count=c.getInt(0);errors=c.getInt(1);}
  if(errors>0||count==parts){v.clear();v.put("state",errors>0?"FAILED":"SENT");d.update("jobs",v,"id=?",new String[]{id});}
  d.setTransactionSuccessful();
 }finally{d.endTransaction();}}
 static int selfTest()throws Exception{SQLiteDatabase d=SQLiteDatabase.create(null);int n=0;try{schema(d);MediaJobs.check(claim(d,"a","f",2));n++;MediaJobs.check(!claim(d,"a","f",2));n++;boolean reject=false;try{claim(d,"a","other",2);}catch(IOException good){reject=true;}MediaJobs.check(reject);n++;result(d,"a",0,-1);MediaJobs.check(state(d,"a",null).equals("SUBMITTING"));n++;result(d,"a",0,-1);MediaJobs.check(state(d,"a",null).equals("SUBMITTING"));n++;result(d,"a",2,-1);MediaJobs.check(state(d,"a",null).equals("SUBMITTING"));n++;recover(d);MediaJobs.check(state(d,"a",null).equals("UNCERTAIN")&&!claim(d,"a","f",2));n++;result(d,"a",1,-1);MediaJobs.check(state(d,"a",null).equals("SENT"));n++;transition(d,"a","SUBMITTING","SUBMITTED");MediaJobs.check(state(d,"a",null).equals("SENT"));n++;claim(d,"b","f2",2);result(d,"b",1,5);result(d,"b",0,-1);MediaJobs.check(state(d,"b",null).equals("FAILED")&&!claim(d,"b","f2",2));n++;result(d,"unknown",0,-1);MediaJobs.check(state(d,"unknown",null)==null);n++;return n;}finally{d.close();}}
}
