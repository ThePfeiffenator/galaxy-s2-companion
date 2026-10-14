package org.retrobridge.galaxyservices.pixel;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.IOException;
/** Durable exactly-once dispatch gate. SUBMITTING never grants permission to retry. */
final class RcsJobs {
 static void recover(SQLiteDatabase db){db.execSQL("UPDATE jobs SET state='UNCERTAIN' WHERE state='SUBMITTING'");}
 static String existing(SQLiteDatabase db,String id,String fingerprint)throws IOException{
  try(Cursor c=db.rawQuery("SELECT fingerprint,state FROM jobs WHERE id=?",new String[]{id})){
   if(!c.moveToFirst())return null;
   if(!fingerprint.equals(c.getString(0)))throw new IOException("Reply identity changed");return c.getString(1);
  }
 }
 static String claim(SQLiteDatabase db,String id,String fingerprint)throws IOException{
  db.beginTransaction();try{
   String prior=existing(db,id,fingerprint);if(prior!=null){db.setTransactionSuccessful();return prior;}
   ContentValues v=new ContentValues();v.put("id",id);v.put("fingerprint",fingerprint);v.put("state","SUBMITTING");v.put("created",System.currentTimeMillis());db.insertOrThrow("jobs",null,v);
   db.setTransactionSuccessful();return "NEW";
  }finally{db.endTransaction();}
 }
 static void finish(SQLiteDatabase db,String id,String state)throws IOException{
  if(!state.equals("SUBMITTED")&&!state.equals("REJECTED")&&!state.equals("UNCERTAIN"))throw new IOException("Invalid dispatch result");
  ContentValues v=new ContentValues();v.put("state",state);if(db.update("jobs",v,"id=? AND state='SUBMITTING'",new String[]{id})!=1)throw new IOException("Reply dispatch state changed");
 }
 static int selfTest()throws Exception{
  int checks=0;SQLiteDatabase db=SQLiteDatabase.create(null);
  try{
   db.execSQL("CREATE TABLE jobs (id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL,state TEXT NOT NULL,created INTEGER NOT NULL)");
   require("NEW".equals(claim(db,"a","fingerprint")));checks++;
   require("SUBMITTING".equals(claim(db,"a","fingerprint")));checks++;
   boolean rejected=false;try{claim(db,"a","different");}catch(IOException e){rejected=true;}require(rejected);checks++;
   finish(db,"a","SUBMITTED");require("SUBMITTED".equals(claim(db,"a","fingerprint")));checks++;
   require("NEW".equals(claim(db,"b","second")));checks++;
   recover(db);require("UNCERTAIN".equals(claim(db,"b","second")));checks++;
   require("SUBMITTED".equals(existing(db,"a","fingerprint")));checks++;
   require("NEW".equals(claim(db,"c","third")));finish(db,"c","REJECTED");require("REJECTED".equals(claim(db,"c","third")));checks++;
   rejected=false;try{finish(db,"b","SUBMITTED");}catch(IOException e){rejected=true;}require(rejected);checks++;
  }finally{db.close();}return checks;
 }
 private static void require(boolean value){if(!value)throw new AssertionError("RCS dispatch journal test");}
}
