package org.retrobridge.galaxyservices.pixel;
import android.content.*;import android.database.*;import android.database.sqlite.*;import java.io.*;

/** At-most-once platform dispatch. A lost outcome never permits automatic resend. */
final class MediaJobs {
 static void schema(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, digest TEXT NOT NULL, state TEXT NOT NULL, created INTEGER NOT NULL, result INTEGER NOT NULL DEFAULT 0)");}
 static void recover(SQLiteDatabase db){db.execSQL("UPDATE jobs SET state='UNCERTAIN' WHERE state='SUBMITTING'");}
 static String state(SQLiteDatabase db,String id,String digest)throws IOException{
  try(Cursor c=db.rawQuery("SELECT digest,state FROM jobs WHERE id=?",new String[]{id})){if(!c.moveToFirst())return "MISSING";if(digest!=null&&!digest.equals(c.getString(0)))throw new IOException("conflict");return c.getString(1);}
 }
 static void stage(SQLiteDatabase db,String id,String digest)throws IOException{
  String s=state(db,id,digest);if(!s.equals("MISSING"))return;ContentValues v=new ContentValues();v.put("id",id);v.put("digest",digest);v.put("state","STAGED");v.put("created",System.currentTimeMillis());db.insertOrThrow("jobs",null,v);
 }
 static boolean claim(SQLiteDatabase db,String id){ContentValues v=new ContentValues();v.put("state","SUBMITTING");return db.update("jobs",v,"id=? AND state='STAGED'",new String[]{id})==1;}
 static void transition(SQLiteDatabase db,String id,String from,String to){ContentValues v=new ContentValues();v.put("state",to);db.update("jobs",v,"id=? AND state=?",new String[]{id,from});}
 static void result(SQLiteDatabase db,String id,int result){ContentValues v=new ContentValues();v.put("state",result==-1?"SENT":"FAILED");v.put("result",result);db.update("jobs",v,"id=? AND state IN ('SUBMITTING','SUBMITTED','UNCERTAIN')",new String[]{id});}
 static int selfTest()throws Exception{
  SQLiteDatabase db=SQLiteDatabase.create(null);int n=0;try{schema(db);stage(db,"a","one");check(state(db,"a","one").equals("STAGED"));n++;check(claim(db,"a"));n++;check(!claim(db,"a"));n++;boolean conflict=false;try{stage(db,"a","two");}catch(IOException e){conflict=true;}check(conflict);n++;recover(db);check(state(db,"a",null).equals("UNCERTAIN")&&!claim(db,"a"));n++;result(db,"a",-1);check(state(db,"a",null).equals("SENT"));n++;stage(db,"a","one");check(!claim(db,"a"));n++;stage(db,"b","two");claim(db,"b");result(db,"b",-1);transition(db,"b","SUBMITTING","SUBMITTED");check(state(db,"b",null).equals("SENT"));n++;stage(db,"c","three");claim(db,"c");result(db,"c",4);check(state(db,"c",null).equals("FAILED")&&!claim(db,"c"));n++;result(db,"missing",-1);check(state(db,"missing",null).equals("MISSING"));n++;return n;}finally{db.close();}
 }
 static void check(boolean b){if(!b)throw new AssertionError("Media job invariant");}
}
