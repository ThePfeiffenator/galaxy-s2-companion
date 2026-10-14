package org.retrobridge.galaxychat.s2;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.IOException;
import java.util.*;
import org.json.*;
import org.retrobridge.galaxychat.protocol.Wire;

/** Private bounded display cache. Operation history is independent of cache clearing. */
final class ChatStore extends SQLiteOpenHelper {
    ChatStore(Context c) { super(c,"galaxy-chat.db",null,1); }
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE chats(id TEXT PRIMARY KEY,archived INTEGER,ord INTEGER,data TEXT)");
        db.execSQL("CREATE TABLE messages(chat TEXT,id TEXT,date INTEGER,data TEXT,PRIMARY KEY(chat,id))");
        db.execSQL("CREATE TABLE drafts(chat TEXT PRIMARY KEY,text TEXT,reply TEXT)");
        db.execSQL("CREATE TABLE operations(id TEXT PRIMARY KEY,chat TEXT,text TEXT,reply TEXT,state TEXT,message TEXT,reason TEXT,created INTEGER)");
    }
    public void onUpgrade(SQLiteDatabase db,int a,int b) { throw new IllegalStateException("Unknown database version"); }
    public void onOpen(SQLiteDatabase db) { super.onOpen(db); db.execSQL("PRAGMA synchronous=FULL"); }
    synchronized void recover() { getWritableDatabase().execSQL("UPDATE operations SET state='UNCERTAIN',reason='Connection interrupted; checking Pixel' WHERE state IN ('SUBMITTING','PENDING')"); }
    synchronized void putChat(JSONObject o) throws IOException {
        ClientRules.chat(o); ContentValues v=new ContentValues(); v.put("id",o.optString("id"));v.put("archived",o.optBoolean("archived")?1:0);v.put("ord",Long.parseLong(o.optString("order")));v.put("data",o.toString());
        SQLiteDatabase d=getWritableDatabase();d.insertWithOnConflict("chats",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        if(o.optString("blocked_reason").length()>0){d.delete("messages","chat=?",new String[]{o.optString("id")});ContentValues redacted=new ContentValues();redacted.put("text","[Message content no longer cached]");d.update("operations",redacted,"chat=?",new String[]{o.optString("id")});}
        // Keep recently loaded rows so paging past the first 400 can advance the window.
        d.execSQL("DELETE FROM chats WHERE rowid NOT IN(SELECT rowid FROM chats ORDER BY rowid DESC LIMIT 400)");
    }
    synchronized void putMessage(JSONObject o) throws IOException {
        ClientRules.message(o);String chat=o.optString("chat_id");JSONObject c=chat(chat);if(c!=null&&c.optString("blocked_reason").length()>0)return;SQLiteDatabase d=getWritableDatabase();insertMessage(d,o);trimMessages(d,chat,false);
    }
    private void insertMessage(SQLiteDatabase d,JSONObject o){ContentValues v=new ContentValues();v.put("chat",o.optString("chat_id"));v.put("id",o.optString("id"));v.put("date",o.optLong("date"));v.put("data",o.toString());d.insertWithOnConflict("messages",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    synchronized void history(String chat,JSONArray rows,boolean older)throws IOException{
        if(rows.length()>30)throw new IOException("Invalid history");for(int i=0;i<rows.length();i++){JSONObject o=rows.optJSONObject(i);if(o==null||!chat.equals(o.optString("chat_id")))throw new IOException("Invalid history");ClientRules.message(o);}
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{if(!older)d.delete("messages","chat=?",new String[]{chat});for(int i=0;i<rows.length();i++)insertMessage(d,rows.optJSONObject(i));trimMessages(d,chat,older);d.setTransactionSuccessful();}finally{d.endTransaction();}
    }
    private void trimMessages(SQLiteDatabase d,String chat,boolean older){
        String direction=older?"ASC":"DESC";
        d.execSQL("DELETE FROM messages WHERE chat=? AND rowid NOT IN(SELECT rowid FROM messages WHERE chat=? ORDER BY date "+direction+",CAST(id AS INTEGER) "+direction+" LIMIT 200)",new Object[]{chat,chat});
        d.execSQL("DELETE FROM messages WHERE chat<>? AND rowid NOT IN(SELECT rowid FROM messages WHERE chat<>? ORDER BY date DESC LIMIT 1800)",new Object[]{chat,chat});
    }
    synchronized void removeMessages(String chat,JSONArray ids) throws IOException {
        if(!ClientRules.decimal(chat,true)||ids.length()>200)throw new IOException("Invalid deletion update");
        SQLiteDatabase d=getWritableDatabase(); d.beginTransaction();try{for(int i=0;i<ids.length();i++){String id=ids.optString(i);if(!ClientRules.decimal(id,false))throw new IOException("Invalid message ID");d.delete("messages","chat=? AND id=?",new String[]{chat,id});ContentValues redacted=new ContentValues();redacted.put("text","");d.update("operations",redacted,"chat=? AND message=? AND state='SENT'",new String[]{chat,id});}d.setTransactionSuccessful();}finally{d.endTransaction();}
    }
    synchronized ArrayList<JSONObject> chats(boolean archived) { return query("SELECT data FROM chats WHERE archived=? AND ord>0 ORDER BY ord DESC LIMIT 400",new String[]{archived?"1":"0"}); }
    synchronized ArrayList<JSONObject> messages(String chat) { ArrayList<JSONObject> out=query("SELECT data FROM messages WHERE chat=? ORDER BY date DESC,CAST(id AS INTEGER) DESC LIMIT 200",new String[]{chat});Collections.reverse(out);return out; }
    synchronized JSONObject chat(String id) { ArrayList<JSONObject> rows=query("SELECT data FROM chats WHERE id=?",new String[]{id});return rows.isEmpty()?null:rows.get(0); }
    private ArrayList<JSONObject> query(String sql,String[] args) {
        ArrayList<JSONObject> out=new ArrayList<JSONObject>();Cursor c=getReadableDatabase().rawQuery(sql,args);try{while(c.moveToNext()){try{out.add(new JSONObject(c.getString(0)));}catch(JSONException e){/* Corrupt cache rows are display-only. */}}}finally{c.close();}return out;
    }
    synchronized void draft(String chat,String text,String reply) { if(chat.length()==0)return;ContentValues v=new ContentValues();v.put("chat",chat);v.put("text",text);v.put("reply",reply);getWritableDatabase().insertWithOnConflict("drafts",null,v,SQLiteDatabase.CONFLICT_REPLACE); }
    synchronized String[] draft(String chat) { Cursor c=getReadableDatabase().rawQuery("SELECT text,reply FROM drafts WHERE chat=?",new String[]{chat});try{return c.moveToFirst()?new String[]{c.getString(0),c.getString(1)}:new String[]{"","0"};}finally{c.close();} }
    synchronized String prepare(String chat,String text,String reply) throws IOException {
        ClientRules.textToSend(text);SQLiteDatabase d=getWritableDatabase();Cursor n=d.rawQuery("SELECT COUNT(*) FROM operations WHERE state IN ('SUBMITTING','PENDING','UNCERTAIN')",null);try{n.moveToFirst();if(n.getInt(0)>=64)throw new IOException("Resolve pending sends on Pixel first");}finally{n.close();}
        String id=ClientRules.uuid(Wire.random(16));ContentValues v=new ContentValues();v.put("id",id);v.put("chat",chat);v.put("text",text);v.put("reply",reply);v.put("state","SUBMITTING");v.put("message","0");v.put("reason","Submitting to Pixel");v.put("created",System.currentTimeMillis());d.insertOrThrow("operations",null,v);
        d.execSQL("DELETE FROM operations WHERE state IN ('SENT','FAILED') AND rowid NOT IN(SELECT rowid FROM operations ORDER BY created DESC LIMIT 512)");return id;
    }
    synchronized void fail(String id,String reason){ContentValues v=new ContentValues();v.put("state","FAILED");v.put("reason",reason);getWritableDatabase().update("operations",v,"id=? AND state NOT IN ('SENT','FAILED')",new String[]{id});}
    synchronized void uncertain(String id,String reason) { ContentValues v=new ContentValues();v.put("state","UNCERTAIN");v.put("reason",reason);getWritableDatabase().update("operations",v,"id=? AND state NOT IN ('SENT','FAILED')",new String[]{id}); }
    synchronized void updateOperation(JSONObject state) throws IOException {
        String id=ClientRules.string(state,"operation_id",36),chat=ClientRules.id(state,"chat_id",true),message=ClientRules.id(state,"message_id",false),next=ClientRules.string(state,"state",16),reason=ClientRules.string(state,"reason",512);
        SQLiteDatabase d=getWritableDatabase();Cursor c=d.rawQuery("SELECT chat,state FROM operations WHERE id=?",new String[]{id});try{if(!c.moveToFirst())return;if(!chat.equals(c.getString(0)))throw new IOException("Operation chat changed");next=ClientRules.mergeState(c.getString(1),next);}finally{c.close();}
        ContentValues v=new ContentValues();v.put("state",next);v.put("message",message);v.put("reason",reason);if("SENT".equals(next))v.put("text","");d.update("operations",v,"id=?",new String[]{id});
    }
    synchronized ArrayList<String> unresolved() { ArrayList<String> a=new ArrayList<String>();Cursor c=getReadableDatabase().rawQuery("SELECT id FROM operations WHERE state IN ('SUBMITTING','PENDING','UNCERTAIN') ORDER BY created LIMIT 64",null);try{while(c.moveToNext())a.add(c.getString(0));}finally{c.close();}return a; }
    synchronized ArrayList<JSONObject> operations(String chat) {
        ArrayList<JSONObject> a=new ArrayList<JSONObject>();Cursor c=getReadableDatabase().rawQuery("SELECT id,text,state,message,reason,created FROM operations WHERE chat=? ORDER BY created DESC LIMIT 30",new String[]{chat});
        try{while(c.moveToNext()){JSONObject o=new JSONObject();try{o.put("operation_id",c.getString(0));o.put("text",c.getString(1));o.put("state",c.getString(2));o.put("message_id",c.getString(3));o.put("reason",c.getString(4));o.put("created",c.getLong(5));}catch(JSONException ignored){}a.add(o);}}finally{c.close();}Collections.reverse(a);return a;
    }
    synchronized void clearDisplayCache() { SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{d.delete("chats",null,null);d.delete("messages",null,null);d.delete("drafts",null,null);d.execSQL("UPDATE operations SET text=''");d.setTransactionSuccessful();}finally{d.endTransaction();} }
    synchronized void invalidateHistory() { SQLiteDatabase d=getWritableDatabase();d.delete("messages",null,null);d.delete("chats",null,null); }
}
