package org.retrobridge.galaxychat.pixel;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import org.json.JSONObject;
import org.retrobridge.galaxychat.protocol.Wire;

/** Operation identity is durable before the first TDLib send; unresolved intents never replay. */
final class SendJournal extends SQLiteOpenHelper {
    SendJournal(Context c){super(c,"send-journal.db",null,1);getWritableDatabase().execSQL("PRAGMA synchronous=FULL");
        getWritableDatabase().execSQL("UPDATE sends SET state='UNCERTAIN',reason='Pixel restarted during submission; inspect this chat before retrying' WHERE state='SUBMITTING'");}
    public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE sends(op TEXT PRIMARY KEY,digest TEXT NOT NULL,chat TEXT NOT NULL,message TEXT NOT NULL DEFAULT '0',state TEXT NOT NULL,reason TEXT NOT NULL DEFAULT '')");}
    public void onUpgrade(SQLiteDatabase db,int a,int b){throw new IllegalStateException("Unsupported journal migration");}
    synchronized JSONObject get(String op){Cursor c=getReadableDatabase().query("sends",null,"op=?",new String[]{op},null,null,null);try{if(!c.moveToFirst())return null;return row(c);}finally{c.close();}}
    private JSONObject row(Cursor c){return Wire.json("operation_id",c.getString(c.getColumnIndexOrThrow("op")),"digest",c.getString(c.getColumnIndexOrThrow("digest")),"chat_id",c.getString(c.getColumnIndexOrThrow("chat")),"message_id",c.getString(c.getColumnIndexOrThrow("message")),"state",c.getString(c.getColumnIndexOrThrow("state")),"reason",c.getString(c.getColumnIndexOrThrow("reason")));}
    synchronized void begin(String op,String digest,String chat){ContentValues v=new ContentValues();v.put("op",op);v.put("digest",digest);v.put("chat",chat);v.put("state","SUBMITTING");getWritableDatabase().insertOrThrow("sends",null,v);}
    synchronized void state(String op,String state,String message,String reason){ContentValues v=new ContentValues();v.put("state",state);v.put("message",message);v.put("reason",reason);if(getWritableDatabase().update("sends",v,"op=?",new String[]{op})!=1)throw new IllegalStateException("Missing send intent");}
    synchronized String operation(String chat,String message){Cursor c=getReadableDatabase().query("sends",new String[]{"op"},"chat=? AND message=? AND state IN ('PENDING','UNCERTAIN')",new String[]{chat,message},null,null,null);try{return c.moveToFirst()?c.getString(0):null;}finally{c.close();}}
    synchronized java.util.List<JSONObject> pending(){java.util.ArrayList<JSONObject> r=new java.util.ArrayList<>();Cursor c=getReadableDatabase().query("sends",null,"state='PENDING'",null,null,null,null,"100");try{while(c.moveToNext())r.add(row(c));return r;}finally{c.close();}}
    static JSONObject publicState(JSONObject state){JSONObject o=Wire.json("operation_id",state.optString("operation_id"),"chat_id",state.optString("chat_id"),"message_id",state.optString("message_id","0"),"state",state.optString("state"),"reason",state.optString("reason"));return o;}
}
