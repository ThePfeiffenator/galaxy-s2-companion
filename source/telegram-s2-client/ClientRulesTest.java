package org.retrobridge.galaxychat.s2;
import org.json.*;
import java.io.IOException;
import java.util.*;
public final class ClientRulesTest {
    static int count;
    static void check(boolean b){count++;if(!b)throw new AssertionError("Check "+count);}
    interface Action {void run()throws Exception;}
    static void reject(Action a)throws Exception {boolean denied=false;try{a.run();}catch(IOException expected){denied=true;}check(denied);}
    static JSONObject json(Object...v)throws Exception{JSONObject o=new JSONObject();for(int i=0;i<v.length;i+=2)o.put((String)v[i],v[i+1]);return o;}
    static String repeat(String s,int n){StringBuilder b=new StringBuilder();while(n-->0)b.append(s);return b.toString();}
    public static void main(String[] args)throws Exception{
        check(ClientRules.decimal("9223372036854775807",false));check(ClientRules.decimal("-9223372036854775808",true));check(!ClientRules.decimal("9223372036854775808",false));check(!ClientRules.decimal("-1",false));check(!ClientRules.decimal("01",true));check(!ClientRules.decimal("1.0",true));check(!ClientRules.decimal("1e2",true));check(!ClientRules.decimal(" 1",true));check(!ClientRules.decimal("",true));check(ClientRules.decimal("0",false));
        for(String state:new String[]{"SUBMITTING","PENDING","UNCERTAIN"}){check(ClientRules.unresolved(state));check(ClientRules.recover(state).equals("UNCERTAIN"));check(ClientRules.reconnectAction(state).equals("QUERY_SEND"));}
        for(String state:new String[]{"SENT","FAILED"}){check(!ClientRules.unresolved(state));check(ClientRules.recover(state).equals(state));check(ClientRules.reconnectAction(state).equals("NONE"));for(String update:new String[]{"SUBMITTING","PENDING","UNCERTAIN","SENT","FAILED"})check(ClientRules.mergeState(state,update).equals(state));}
        check(ClientRules.mergeState("UNCERTAIN","SENT").equals("SENT"));check(ClientRules.mergeState("UNCERTAIN","FAILED").equals("FAILED"));reject(new Action(){public void run()throws Exception{ClientRules.mergeState("PENDING","RETRY");}});
        ClientRules.textToSend(repeat("x",4096));ClientRules.textToSend(repeat("\ud83d\ude42",4096));check(true);
        reject(new Action(){public void run()throws Exception{ClientRules.textToSend(repeat("x",4097));}});reject(new Action(){public void run()throws Exception{ClientRules.textToSend("");}});reject(new Action(){public void run()throws Exception{ClientRules.textToSend("bad\ud800");}});reject(new Action(){public void run()throws Exception{ClientRules.textToSend("bad\udc00");}});reject(new Action(){public void run()throws Exception{ClientRules.textToSend("bad\u0000text");}});
        check(ClientRules.eventNext("a",0,"a","1"));check(!ClientRules.eventNext("a",0,"b","1"));check(!ClientRules.eventNext("a",1,"a","1"));check(!ClientRules.eventNext("a",1,"a","3"));check(!ClientRules.eventNext("a",Long.MAX_VALUE,"a","1"));
        for(boolean session:new boolean[]{false,true})for(boolean bluetooth:new boolean[]{false,true})for(boolean connected:new boolean[]{false,true})check(ClientRules.heartbeatAction(session,bluetooth,connected).equals(!session?"STOP":!bluetooth?"WAIT":connected?"STATUS":"CONNECT"));
        byte[] zeros=new byte[16];check(ClientRules.uuid(zeros).equals("00000000-0000-4000-8000-000000000000"));check(zeros[6]==0&&zeros[8]==0);byte[] ff=new byte[16];Arrays.fill(ff,(byte)255);check(ClientRules.uuid(ff).equals("ffffffff-ffff-4fff-bfff-ffffffffffff"));
        final JSONObject c=json("id","-1001234567890","title","Example","unread",1,"last_text","Preview","last_date",1700000000,"order","9223372036854775807","archived",false,"can_send",true,"blocked_reason","");ClientRules.chat(c);check(true);c.put("id",123);reject(new Action(){public void run()throws Exception{ClientRules.chat(c);}});c.put("id","2");c.put("archived","false");reject(new Action(){public void run()throws Exception{ClientRules.chat(c);}});
        final JSONObject m=json("id","9007199254740000","chat_id","-1001234567890","date",1700000000,"text","Hi 🙂","outgoing",false,"sender","Person","reply_to_id","0","edited",false,"content_type","text");ClientRules.message(m);check(true);m.put("text",repeat("é",8193));reject(new Action(){public void run()throws Exception{ClientRules.message(m);}});
        m.put("text","Test");
        final JSONObject media=json("kind","photo","name","Photo.jpg","mime","image/jpeg","file_id",12,"size",612,"width",640,"height",480,"duration",0,"preview",true);m.put("media",media);ClientRules.message(m);check(true);
        media.put("file_id",0);reject(new Action(){public void run()throws Exception{ClientRules.message(m);}});media.put("file_id",12);
        media.put("mime","../unsafe");reject(new Action(){public void run()throws Exception{ClientRules.message(m);}});media.put("mime","image/jpeg");
        media.put("kind","other");reject(new Action(){public void run()throws Exception{ClientRules.message(m);}});media.put("kind","photo");
        media.put("size",-1);reject(new Action(){public void run()throws Exception{ClientRules.message(m);}});media.put("size",612);
        media.put("preview","true");reject(new Action(){public void run()throws Exception{ClientRules.message(m);}});
        System.out.println("CLIENT_RULES_PASS "+count);
    }
}
