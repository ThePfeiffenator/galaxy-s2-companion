package org.retrobridge.callprobe;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

/** Durable intent before external effects. Ambiguous submissions never auto-replay. */
final class SmsJournal extends SQLiteOpenHelper {
    SmsJournal(Context c){super(c,"sms-relay.db",null,2);}
    public void onCreate(SQLiteDatabase d){
        d.execSQL("CREATE TABLE outgoing (job TEXT PRIMARY KEY, state TEXT NOT NULL, handle TEXT, fingerprint TEXT NOT NULL, ref INTEGER, ref8 INTEGER NOT NULL, parts INTEGER)");
        d.execSQL("CREATE TABLE incoming (handle TEXT PRIMARY KEY, state TEXT NOT NULL, part INTEGER NOT NULL DEFAULT 0, wire TEXT)");
        createMetadata(d);
    }
    private void createMetadata(SQLiteDatabase d){
        d.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
        d.execSQL("CREATE TABLE concat_refs (sender TEXT NOT NULL, ref INTEGER NOT NULL, handle TEXT NOT NULL UNIQUE, held_until INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(sender,ref))");
    }
    public void onUpgrade(SQLiteDatabase d,int old,int next){
        if(old!=1||next!=2)throw new IllegalStateException("Unsupported SMS journal version");
        d.execSQL("ALTER TABLE outgoing ADD COLUMN fingerprint TEXT");
        d.execSQL("ALTER TABLE outgoing ADD COLUMN ref8 INTEGER NOT NULL DEFAULT -1");
        Cursor c=d.query("outgoing",new String[]{"job"},null,null,null,null,null);
        try{while(c.moveToNext()){String job=c.getString(0);int colon=job.indexOf(':');ContentValues v=new ContentValues();v.put("fingerprint",colon<0?job:job.substring(colon+1));d.update("outgoing",v,"job=?",new String[]{job});}}finally{c.close();}
        createMetadata(d);
    }
    public void onOpen(SQLiteDatabase d){super.onOpen(d);if(!d.isReadOnly())d.execSQL("PRAGMA synchronous=FULL");}
    static final class Out {String state,handle,fingerprint;int ref,parts,ref8;}
    synchronized Out outgoing(String job){
        Cursor c=getReadableDatabase().query("outgoing",new String[]{"state","handle","ref","parts","ref8","fingerprint"},"job=?",new String[]{job},null,null,null);
        try{if(!c.moveToFirst())return null;Out o=new Out();o.state=c.getString(0);o.handle=c.getString(1);o.ref=c.getInt(2);o.parts=c.getInt(3);o.ref8=c.getInt(4);o.fingerprint=c.getString(5);return o;}finally{c.close();}
    }
    synchronized boolean hasUncertainOutgoing(String fingerprint){
        Cursor c=getReadableDatabase().query("outgoing",new String[]{"job"},"fingerprint=? AND state IN ('SUBMITTING','ACCEPTED','UNCERTAIN')",new String[]{fingerprint},null,null,null,"1");
        try{return c.moveToFirst();}finally{c.close();}
    }
    synchronized void begin(String job,String fingerprint,int ref,boolean ref8,int count){
        ContentValues v=new ContentValues();v.put("job",job);v.put("fingerprint",fingerprint);v.put("state","SUBMITTING");v.put("ref",ref);v.put("ref8",ref8?1:0);v.put("parts",count);
        getWritableDatabase().insertOrThrow("outgoing",null,v);
    }
    synchronized void outgoingState(String job,String state,String handle){
        ContentValues v=new ContentValues();v.put("state",state);if(handle!=null)v.put("handle",handle);
        if(getWritableDatabase().update("outgoing",v,"job=?",new String[]{job})!=1)throw new IllegalStateException("Unknown SMS job");
    }
    synchronized void forgetNotSubmitted(String job){getWritableDatabase().delete("outgoing","job=? AND state='NOT_SUBMITTED'",new String[]{job});}
    synchronized String incomingState(String handle){
        Cursor c=getReadableDatabase().query("incoming",new String[]{"state"},"handle=?",new String[]{handle},null,null,null);
        try{return c.moveToFirst()?c.getString(0):null;}finally{c.close();}
    }
    synchronized int incomingPart(String handle){
        Cursor c=getReadableDatabase().query("incoming",new String[]{"part"},"handle=?",new String[]{handle},null,null,null);
        try{if(!c.moveToFirst())throw new IllegalStateException("Missing receive journal");return c.getInt(0);}finally{c.close();}
    }
    synchronized void incomingState(String handle,String state,int part){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try{
            ContentValues v=new ContentValues();v.put("state",state);v.put("part",part);
            if(d.update("incoming",v,"handle=?",new String[]{handle})==0){v.put("handle",handle);d.insertOrThrow("incoming",null,v);}
            if("RECEIVED".equals(state)){
                // ACK precedes the stock asynchronous provider commit. Do not
                // immediately recycle a just-completed multipart reference.
                ContentValues hold=new ContentValues();hold.put("held_until",System.currentTimeMillis()+86400000L);
                d.update("concat_refs",hold,"handle=?",new String[]{handle});
            }
            if("UNCERTAIN".equals(state))putMeta(d,"rx_paused","1");
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
    }
    synchronized void beginIncoming(String handle,byte[][] parts){
        if(parts==null||parts.length<1||parts.length>255)throw new IllegalArgumentException("Incoming part count");
        StringBuilder b=new StringBuilder();for(byte[] part:parts){if(part==null||part.length!=372)throw new IllegalArgumentException("Incoming wire length");if(b.length()>0)b.append('|');b.append(SmsService.hex(part));}
        ContentValues v=new ContentValues();v.put("handle",handle);v.put("state","PARTIAL");v.put("part",0);v.put("wire",b.toString());getWritableDatabase().insertOrThrow("incoming",null,v);
    }
    synchronized byte[][] incomingWire(String handle){
        Cursor c=getReadableDatabase().query("incoming",new String[]{"wire"},"handle=?",new String[]{handle},null,null,null);
        try{if(!c.moveToFirst()||c.isNull(0))throw new IllegalStateException("Missing receive journal");String[] wires=c.getString(0).split("\\|",-1);if(wires.length<1||wires.length>255)throw new IllegalStateException("Corrupt receive count");byte[][] result=new byte[wires.length][];for(int i=0;i<wires.length;i++){if(!wires[i].matches("[0-9a-f]{744}"))throw new IllegalStateException("Corrupt receive journal");result[i]=SmsService.unhex(wires[i]);}return result;}finally{c.close();}
    }
    synchronized List<String> partialIncoming(){
        ArrayList<String> result=new ArrayList<String>();Cursor c=getReadableDatabase().query("incoming",new String[]{"handle"},"state='PARTIAL'",null,null,null,"rowid ASC");
        try{while(c.moveToNext())result.add(c.getString(0));return result;}finally{c.close();}
    }
    synchronized boolean baselineReady(){return "1".equals(meta("baseline"))&&meta("baseline_cutoff")!=null;}
    synchronized long baselineCutoff(){long cutoff=Long.parseLong(meta("baseline_cutoff"));if(cutoff<=0)throw new IllegalStateException("Invalid baseline cutoff");return cutoff;}
    synchronized void establishBaseline(List<String> handles,long cutoff){
        if(cutoff<=0)throw new IllegalArgumentException("Invalid baseline cutoff");
        if(baselineReady())throw new IllegalStateException("Baseline already established");
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try{for(String handle:handles){ContentValues v=new ContentValues();v.put("handle",handle);v.put("state","BASELINE");v.put("part",0);d.insertWithOnConflict("incoming",null,v,SQLiteDatabase.CONFLICT_IGNORE);}putMeta(d,"baseline_cutoff",Long.toString(cutoff));putMeta(d,"baseline","1");d.setTransactionSuccessful();}finally{d.endTransaction();}
    }
    synchronized boolean receivePaused(){return "1".equals(meta("rx_paused"));}
    synchronized void pauseReceive(){putMeta(getWritableDatabase(),"rx_paused","1");}
    private String meta(String name){Cursor c=getReadableDatabase().query("metadata",new String[]{"value"},"name=?",new String[]{name},null,null,null);try{return c.moveToFirst()?c.getString(0):null;}finally{c.close();}}
    private void putMeta(SQLiteDatabase d,String name,String value){ContentValues v=new ContentValues();v.put("name",name);v.put("value",value);if(d.insertWithOnConflict("metadata",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("SMS metadata write failed");}

    /** Pending/uncertain refs never expire; acknowledged sets wait a further day. */
    synchronized int reserveIncomingReference(String handle,String senderKey){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try{
            Cursor old=d.query("concat_refs",new String[]{"ref","sender"},"handle=?",new String[]{handle},null,null,null);
            try{if(old.moveToFirst()){if(!senderKey.equals(old.getString(1)))throw new IllegalStateException("Reference sender changed");int ref=old.getInt(0);d.setTransactionSuccessful();return ref;}}finally{old.close();}
            d.delete("concat_refs","held_until>0 AND held_until<=? AND handle IN (SELECT handle FROM incoming WHERE state='RECEIVED')",new String[]{Long.toString(System.currentTimeMillis())});
            boolean[] used=new boolean[256];Cursor c=d.query("concat_refs",new String[]{"ref"},"sender=?",new String[]{senderKey},null,null,null);
            try{while(c.moveToNext()){int ref=c.getInt(0);if(ref<0||ref>255)throw new IllegalStateException("Corrupt reference");used[ref]=true;}}finally{c.close();}
            for(int ref=0;ref<256;ref++)if(!used[ref]){ContentValues v=new ContentValues();v.put("sender",senderKey);v.put("ref",ref);v.put("handle",handle);v.put("held_until",0);d.insertOrThrow("concat_refs",null,v);d.setTransactionSuccessful();return ref;}
            throw new IllegalStateException("Incoming multipart references busy");
        }finally{d.endTransaction();}
    }
    synchronized void releaseUnstartedReference(String handle){getWritableDatabase().delete("concat_refs","handle=? AND handle NOT IN (SELECT handle FROM incoming)",new String[]{handle});}
    synchronized boolean recoverUncertain(){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try{
            ContentValues v=new ContentValues();v.put("state","UNCERTAIN");
            int n=d.update("incoming",v,"state='INFLIGHT'",null);
            int out=d.update("outgoing",v,"state IN ('SUBMITTING','ACCEPTED')",null);
            Cursor c=d.query("incoming",new String[]{"handle"},"state='UNCERTAIN'",null,null,null,null,"1");
            boolean uncertain;try{uncertain=c.moveToFirst();}finally{c.close();}
            Cursor pending=d.query("outgoing",new String[]{"job"},"state IN ('SUBMITTING','ACCEPTED','UNCERTAIN')",null,null,null,null,"1");
            boolean outgoingUncertain;try{outgoingUncertain=pending.moveToFirst();}finally{pending.close();}
            if(uncertain)putMeta(d,"rx_paused","1");
            d.delete("concat_refs","handle NOT IN (SELECT handle FROM incoming)",null);
            d.setTransactionSuccessful();return n>0||out>0||uncertain||outgoingUncertain;
        }finally{d.endTransaction();}
    }
}
