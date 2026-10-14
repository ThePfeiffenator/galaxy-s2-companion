package org.retrobridge.galaxychat.s2;

import java.io.IOException;
import java.nio.charset.Charset;
import org.json.*;

/** Bounded protocol models and no-replay decisions, with no Android dependencies. */
public final class ClientRules {
    private ClientRules() {}
    public static boolean decimal(String s, boolean signed) {
        return s != null && s.matches(signed ? "-?(0|[1-9][0-9]{0,18})" : "0|[1-9][0-9]{0,18}") && fitsLong(s);
    }
    private static boolean fitsLong(String s) { try { Long.parseLong(s); return true; } catch (RuntimeException e) { return false; } }
    public static String uuid(byte[] random) {
        if(random.length!=16)throw new IllegalArgumentException("UUID needs 16 random bytes");byte[] b=random.clone();b[6]=(byte)((b[6]&15)|64);b[8]=(byte)((b[8]&63)|128);long hi=0,lo=0;for(int i=0;i<8;i++)hi=(hi<<8)|(b[i]&255L);for(int i=8;i<16;i++)lo=(lo<<8)|(b[i]&255L);return new java.util.UUID(hi,lo).toString();
    }
    public static String id(JSONObject o, String key, boolean signed) throws IOException {
        Object v = o.opt(key); if (!(v instanceof String) || !decimal((String)v, signed)) throw invalid(); return (String)v;
    }
    public static String string(JSONObject o, String key, int max) throws IOException {
        Object v = o.opt(key); if (!(v instanceof String) || ((String)v).length() > max) throw invalid(); return (String)v;
    }
    public static boolean bool(JSONObject o, String key) throws IOException { Object v=o.opt(key); if (!(v instanceof Boolean)) throw invalid(); return (Boolean)v; }
    public static void chat(JSONObject o) throws IOException {
        id(o,"id",true); id(o,"order",false); string(o,"title",256); string(o,"last_text",240);
        string(o,"blocked_reason",256); bool(o,"archived"); bool(o,"can_send");
        if (o.optLong("unread",-1)<0 || o.optLong("last_date",-1)<0) throw invalid();
    }
    public static void message(JSONObject o) throws IOException {
        id(o,"id",false); id(o,"chat_id",true); id(o,"reply_to_id",false);
        String text=string(o,"text",16384); if (text.getBytes(Charset.forName("UTF-8")).length>16384) throw invalid();
        string(o,"sender",128); string(o,"content_type",64); bool(o,"outgoing"); bool(o,"edited");
        if (o.optLong("date",-1)<0) throw invalid();
        JSONObject media=o.optJSONObject("media");if(media!=null){
            String kind=string(media,"kind",16);if(!java.util.Arrays.asList("photo","video","animation","voice","audio","document","sticker").contains(kind))throw invalid();
            string(media,"name",120);String mime=string(media,"mime",128);if(!mime.matches("[a-zA-Z0-9][a-zA-Z0-9.+-]*/[a-zA-Z0-9][a-zA-Z0-9.+-]*"))throw invalid();
            if(media.optLong("file_id",0)<=0||media.optLong("size",-1)<0||media.optLong("width",-1)<0||media.optLong("height",-1)<0||media.optLong("duration",-1)<0)throw invalid();bool(media,"preview");
        }
    }
    public static void textToSend(String text) throws IOException {
        if(text==null || text.length()==0 || text.codePointCount(0,text.length())>4096 || text.getBytes(Charset.forName("UTF-8")).length>16384) throw invalid();
        for(int i=0;i<text.length();i++) { char c=text.charAt(i); if(c==0)throw invalid();if(Character.isHighSurrogate(c)) { if(++i>=text.length() || !Character.isLowSurrogate(text.charAt(i))) throw invalid(); } else if(Character.isLowSurrogate(c)) throw invalid(); }
    }
    public static boolean unresolved(String state) { return "SUBMITTING".equals(state)||"PENDING".equals(state)||"UNCERTAIN".equals(state); }
    public static boolean sendState(String state) { return unresolved(state)||"SENT".equals(state)||"FAILED".equals(state); }
    public static String recover(String state) { return unresolved(state) ? "UNCERTAIN" : state; }
    /** Query only. An uncertain attempt is never converted into a second SEND. */
    public static String reconnectAction(String state) { return unresolved(state) ? "QUERY_SEND" : "NONE"; }
    public static String heartbeatAction(boolean enabled,boolean bluetoothOn,boolean connected){return !enabled?"STOP":!bluetoothOn?"WAIT":connected?"STATUS":"CONNECT";}
    public static String mergeState(String oldState,String update) throws IOException {
        if(!sendState(update)) throw invalid();
        if("SENT".equals(oldState)) return "SENT";
        if("FAILED".equals(oldState)) return "FAILED";
        return update;
    }
    public static boolean eventNext(String expectedEpoch,long lastSeq,String epoch,String seq) {
        return expectedEpoch.equals(epoch) && decimal(seq,false) && lastSeq<Long.MAX_VALUE && Long.parseLong(seq)==lastSeq+1;
    }
    private static IOException invalid() { return new IOException("Invalid companion data"); }
}
