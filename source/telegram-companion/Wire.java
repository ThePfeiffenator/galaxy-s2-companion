package org.retrobridge.galaxychat.protocol;

import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Shared API15+ authenticated framing. Transport owner supplies bounded socket deadlines. */
public final class Wire {
    public static final String SERVICE_UUID="bf067b70-841f-4a63-93ea-724767ddd0bd";
    public static final String PIXEL="@PIXEL_BLUETOOTH@",S2="@S2_BLUETOOTH@";
    public static final int MAX_FRAME=65536,MAX_BODY=44000;
    private static final Charset UTF8=Charset.forName("UTF-8");
    private Wire(){}
    public static byte[] random(int size)throws IOException{
        byte[] value=new byte[size];
        // ICS SecureRandom did not have the later Android initialization fixes.
        FileInputStream f=new FileInputStream("/dev/urandom");
        try{new DataInputStream(f).readFully(value);}finally{f.close();}return value;
    }
    public static String token(byte[] value){return Base64.encodeToString(value,Base64.NO_WRAP);}
    public static byte[] token(String value)throws IOException{
        try{byte[] decoded=Base64.decode(value,Base64.NO_WRAP);if(decoded.length!=32||!token(decoded).equals(value))throw fail();return decoded;}
        catch(IllegalArgumentException e){throw fail();}
    }
    public static byte[] utf8(String value)throws IOException{
        try{ByteBuffer b=UTF8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));byte[] out=new byte[b.remaining()];b.get(out);return out;}
        catch(CharacterCodingException e){throw fail();}
    }
    private static String text(byte[] value)throws IOException{
        try{return UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();}
        catch(CharacterCodingException e){throw fail();}
    }
    public static JSONObject object(byte[] value)throws IOException{
        String s=text(value);int depth=0;boolean quoted=false,escape=false;
        for(int i=0;i<s.length();i++){char c=s.charAt(i);if(quoted){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')quoted=false;}
            else if(c=='"')quoted=true;else if(c=='{'||c=='['){if(++depth>16)throw fail();}else if(c=='}'||c==']'){if(--depth<0)throw fail();}}
        if(depth!=0||quoted||s.length()<2||s.charAt(0)!='{'||s.charAt(s.length()-1)!='}')throw fail();
        try{return new JSONObject(s);}catch(JSONException e){throw fail();}
    }
    public static JSONObject json(Object... pairs){
        JSONObject o=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)o.put((String)pairs[i],pairs[i+1]);return o;}
        catch(JSONException e){throw new IllegalArgumentException("Invalid local JSON");}
    }
    public static String required(JSONObject o,String name)throws IOException{
        Object v=o.opt(name);if(!(v instanceof String))throw fail();return (String)v;
    }
    private static JSONObject readFrame(InputStream in)throws IOException{
        DataInputStream data=new DataInputStream(in);int n=data.readInt();if(n<2||n>MAX_FRAME)throw fail();byte[] b=new byte[n];data.readFully(b);return object(b);
    }
    private static void writeFrame(OutputStream out,JSONObject o)throws IOException{
        byte[] b=utf8(o.toString());if(b.length<2||b.length>MAX_FRAME)throw fail();DataOutputStream data=new DataOutputStream(out);data.writeInt(b.length);data.write(b);data.flush();
    }
    private static void keys(JSONObject o,int n,String kind)throws IOException{if(o.length()!=n||o.optInt("v",-1)!=1||!kind.equals(o.optString("k")))throw fail();}
    private static String hmac(byte[] key,String transcript)throws IOException{
        try{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return token(m.doFinal(utf8(transcript)));}
        catch(GeneralSecurityException e){throw new IOException("Authentication unavailable");}
    }
    private static void verify(byte[] key,String transcript,String received)throws IOException{
        byte[] claimed=token(received),expected=token(hmac(key,transcript));if(!MessageDigest.isEqual(expected,claimed))throw fail();
    }
    public static Session client(InputStream in,OutputStream out,byte[] key)throws IOException{return client(in,out,key,random(32));}
    public static Session client(InputStream in,OutputStream out,byte[] key,byte[] nonce)throws IOException{
        if(key.length!=32||nonce.length!=32)throw fail();String cn=token(nonce);writeFrame(out,json("v",1,"k","HELLO","cn",cn));
        JSONObject challenge=readFrame(in);keys(challenge,6,"CHALLENGE");String sid=required(challenge,"sid"),sn=required(challenge,"sn");
        if(!sid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||!cn.equals(required(challenge,"cn")))throw fail();token(sn);
        verify(key,"GC1\nserver\n"+sid+"\n"+cn+"\n"+sn,required(challenge,"mac"));
        writeFrame(out,json("v",1,"k","PROOF","mac",hmac(key,"GC1\nclient\n"+sid+"\n"+cn+"\n"+sn)));
        return new Session(in,out,key,sid,true);
    }
    public static Session server(InputStream in,OutputStream out,byte[] key)throws IOException{return server(in,out,key,random(32),UUID.randomUUID().toString());}
    public static Session server(InputStream in,OutputStream out,byte[] key,byte[] nonce,String sid)throws IOException{
        if(key.length!=32||nonce.length!=32||!sid.matches("[0-9a-f-]{36}"))throw fail();JSONObject hello=readFrame(in);keys(hello,3,"HELLO");String cn=required(hello,"cn");token(cn);String sn=token(nonce);
        writeFrame(out,json("v",1,"k","CHALLENGE","cn",cn,"sn",sn,"sid",sid,"mac",hmac(key,"GC1\nserver\n"+sid+"\n"+cn+"\n"+sn)));
        JSONObject proof=readFrame(in);keys(proof,3,"PROOF");verify(key,"GC1\nclient\n"+sid+"\n"+cn+"\n"+sn,required(proof,"mac"));
        return new Session(in,out,key,sid,false);
    }
    public static final class Session {
        private final InputStream in;private final OutputStream out;private final byte[] key;private final String sid,txDirection,rxDirection;
        private long tx,rx;private final Object writeLock=new Object();
        private Session(InputStream in,OutputStream out,byte[] key,String sid,boolean client){this.in=in;this.out=out;this.key=key.clone();this.sid=sid;txDirection=client?"C":"S";rxDirection=client?"S":"C";}
        public void send(JSONObject body)throws IOException{
            byte[] bytes=utf8(body.toString());if(bytes.length>MAX_BODY)throw fail();String payload=token(bytes);
            synchronized(writeLock){if(tx==Long.MAX_VALUE)throw fail();String seq=Long.toString(++tx);
                writeFrame(out,json("v",1,"k","DATA","sid",sid,"seq",seq,"body",payload,"mac",hmac(key,"GC1\nframe\n"+sid+"\n"+txDirection+"\n"+seq+"\n"+payload)));}
        }
        /** Exactly one reader per Session. Never interpret a body before this verification. */
        public JSONObject receive()throws IOException{
            JSONObject envelope=readFrame(in);keys(envelope,6,"DATA");String seq=required(envelope,"seq"),payload=required(envelope,"body");
            if(!sid.equals(required(envelope,"sid"))||!seq.matches("[1-9][0-9]{0,18}")||rx==Long.MAX_VALUE)throw fail();
            try{if(Long.parseLong(seq)!=rx+1)throw fail();}catch(NumberFormatException e){throw fail();}
            verify(key,"GC1\nframe\n"+sid+"\n"+rxDirection+"\n"+seq+"\n"+payload,required(envelope,"mac"));
            byte[] bytes;try{bytes=Base64.decode(payload,Base64.NO_WRAP);}catch(IllegalArgumentException e){throw fail();}
            if(bytes.length>MAX_BODY||!token(bytes).equals(payload))throw fail();JSONObject body=object(bytes);rx++;return body;
        }
    }
    private static IOException fail(){return new IOException("Invalid companion frame");}
}
