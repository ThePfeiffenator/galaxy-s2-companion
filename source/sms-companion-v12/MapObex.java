package org.retrobridge.callprobe;

import java.io.*;
import java.util.*;

/** Bounded lock-step OBEX over an already authenticated byte stream. */
final class MapObex {
    static final int MAX_PACKET=4096, MAX_PACKETS=256;
    static final byte[] EMPTY=new byte[0];
    static final byte[] TARGET={(byte)0xbb,0x58,0x2b,0x40,0x42,0x0c,0x11,(byte)0xdb,
        (byte)0xb0,(byte)0xde,8,0,0x20,0x0c,(byte)0x9a,0x66};
    final InputStream in;final OutputStream out;
    int peerMax=255;byte[] connection=EMPTY;String lastMseTime;
    MapObex(InputStream in,OutputStream out){this.in=in;this.out=out;}
    static final class ResponseException extends IOException {
        final int response;
        ResponseException(int code){super("OBEX response "+Integer.toHexString(code));response=code;}
    }
    static final class Headers {
        byte[] connection,who; String name,mseTime; boolean endBody;
        final ByteArrayOutputStream body=new ByteArrayOutputStream();
    }
    static byte[] join(byte[]... all)throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        for(byte[] a:all){if(a==null||b.size()+a.length>MapBMessage.MAX_BODY+2048)throw new IOException("Buffer limit");b.write(a);}
        return b.toByteArray();
    }
    static byte[] header(int id,byte[] b)throws IOException {
        int n=b.length+3;if(n>65535||(id&0xc0)==0x80||(id&0xc0)==0xc0)throw new IOException("Header size/type");
        return join(new byte[]{(byte)id,(byte)(n>>8),(byte)n},b);
    }
    static byte[] name(String s)throws IOException {return header(1,(s+'\0').getBytes("UTF-16BE"));}
    static byte[] type(String s)throws IOException {return header(0x42,(s+'\0').getBytes("US-ASCII"));}
    static byte[] frame(int opcode,byte[] payload,int maximum)throws IOException {
        int n=payload.length+3;if(n>maximum||n>MAX_PACKET)throw new IOException("Outbound packet limit");
        return join(new byte[]{(byte)opcode,(byte)(n>>8),(byte)n},payload);
    }
    static void readFully(InputStream in,byte[] b,int at,int n)throws IOException {
        while(n>0){int got=in.read(b,at,n);if(got<0)throw new EOFException("OBEX stream ended");
            if(got==0)throw new IOException("OBEX read made no progress");at+=got;n-=got;}
    }
    static byte[] readPacket(InputStream in)throws IOException {
        byte[] prefix=new byte[3];readFully(in,prefix,0,3);
        int n=((prefix[1]&255)<<8)|(prefix[2]&255);
        if(n<3||n>MAX_PACKET)throw new IOException("Inbound packet limit");
        byte[] b=new byte[n];System.arraycopy(prefix,0,b,0,3);readFully(in,b,3,n-3);return b;
    }
    static Headers headers(byte[] b,int pos)throws IOException {
        Headers h=new Headers();
        if(pos>b.length)throw new IOException("Truncated fixed response");
        while(pos<b.length){
            int id=b[pos]&255,kind=id&0xc0,len;
            if(kind==0||kind==0x40){
                if(pos+3>b.length)throw new IOException("Truncated header");
                len=((b[pos+1]&255)<<8)|(b[pos+2]&255);
                if(len<3||len>b.length-pos)throw new IOException("Invalid header length");
                if(id==0x48||id==0x49){
                    if(h.endBody)throw new IOException("Data after End-of-Body");
                    h.body.write(b,pos+3,len-3);if(id==0x49)h.endBody=true;
                }else if(id==1){
                    if(h.name!=null||len<5||(len-3)%2!=0||b[pos+len-1]!=0||b[pos+len-2]!=0)
                        throw new IOException("Invalid Name header");
                    h.name=MapBMessage.decode(b,pos+3,len-5,"UTF-16BE");
                    if(h.name.indexOf(0)>=0)throw new IOException("Invalid Name value");
                }else if(id==0x4a){if(h.who!=null)throw new IOException("Duplicate Who");h.who=Arrays.copyOfRange(b,pos+3,pos+len);}
                else if(id==0x4c){
                    int p=pos+3,end=pos+len;
                    while(p<end){if(p+2>end)throw new IOException("Truncated application parameter");int tag=b[p]&255,n=b[p+1]&255;p+=2;
                        if(n>end-p)throw new IOException("Invalid application parameter");
                        if(tag==0x19){
                            if(h.mseTime!=null||n>20)throw new IOException("Invalid MSETime");
                            h.mseTime=MapBMessage.decode(b,p,n,"US-ASCII");
                            if(MapBMessage.timestamp(h.mseTime,null)==-1)throw new IOException("Invalid MSETime");
                        }
                        p+=n;}
                }
            }else{
                len=kind==0x80?2:5;if(len>b.length-pos)throw new IOException("Truncated fixed header");
                if(id==0xcb){if(h.connection!=null)throw new IOException("Duplicate Connection-ID");h.connection=Arrays.copyOfRange(b,pos,pos+len);}
            }
            pos+=len;
        }
        return h;
    }
    private Headers checkHeaders(byte[] b,int at)throws IOException {
        Headers h=headers(b,at);
        if(h.connection!=null&&!Arrays.equals(connection,h.connection))throw new IOException("Changed Connection-ID");return h;
    }
    private byte[] exchange(int opcode,byte[] payload)throws IOException {
        byte[] p=frame(opcode,payload,peerMax);out.write(p);out.flush();return readPacket(in);
    }
    static void success(byte[] p)throws IOException {if((p[0]&255)!=0xa0)throw new ResponseException(p[0]&255);}
    void connect()throws IOException {
        connect(false);
    }
    void connect(boolean requireWho)throws IOException {
        byte[] p=exchange(0x80,join(new byte[]{0x10,0,0x10,0},header(0x46,TARGET),
            header(0x4c,new byte[]{0x29,4,0,4,0,0x0c})));
        success(p);if(p.length<7||p[3]!=0x10||p[4]!=0)throw new IOException("Invalid OBEX CONNECT");
        int advertised=((p[5]&255)<<8)|(p[6]&255);
        if(advertised<255)throw new IOException("Invalid peer packet limit");peerMax=Math.min(advertised,MAX_PACKET);
        Headers h=headers(p,7);if((requireWho&&h.who==null)||(h.who!=null&&!Arrays.equals(h.who,TARGET)))
            throw new IOException("Unexpected OBEX target");
        if(h.body.size()!=0)throw new IOException("Unexpected CONNECT body");connection=h.connection==null?EMPTY:h.connection;
        setPath("telecom");setPath("msg");
    }
    private void setPath(String folder)throws IOException {
        byte[] p=exchange(0x85,join(new byte[]{2,0},connection,name(folder)));success(p);checkHeaders(p,3);
    }
    byte[] get(String type,String name,byte[] params)throws IOException {
        lastMseTime=null;
        byte[] request=join(connection,type(type),name(name),header(0x4c,params));
        ByteArrayOutputStream body=new ByteArrayOutputStream();
        for(int count=0;count<MAX_PACKETS;count++){
            byte[] p=exchange(0x83,request);int code=p[0]&255;
            if(code!=0x90&&code!=0xa0)throw new ResponseException(code);
            Headers h=checkHeaders(p,3);
            if(h.mseTime!=null){if(lastMseTime!=null&&!lastMseTime.equals(h.mseTime))throw new IOException("Changed MSETime");lastMseTime=h.mseTime;}
            if(body.size()+h.body.size()>MapBMessage.MAX_BODY)throw new IOException("Response body limit");
            h.body.writeTo(body);
            if(code==0xa0)return body.toByteArray();
            if(h.endBody)throw new IOException("Continue after End-of-Body");request=connection;
        }
        throw new IOException("OBEX continuation limit");
    }
    List<MapMessage> listInbox(int offset,int count)throws IOException {
        if(offset<0||offset>65535||count<1||count>100)throw new IOException("Listing range limit");
        byte[] params={1,2,(byte)(count>>8),(byte)count,2,2,(byte)(offset>>8),(byte)offset,
            3,1,0x0c,0x10,4,0,0,0,0x4a}; // datetime, sender-address, type; never subject
        byte[] listing=get("x-bt/MAP-msg-listing","inbox",params);
        return MapBMessage.listing(listing,count,lastMseTime);
    }
    MapMessage getMessage(String handle)throws IOException {
        handle=MapBMessage.handle(handle);
        return MapBMessage.parse(handle,get("x-bt/message",handle,new byte[]{0x14,1,1,0x0a,1,0}));
    }
    String push(byte[] bmessage)throws MapClient.PushException {
        boolean attempted=false;
        try{
            byte[] first=join(connection,type("x-bt/message"),name("outbox"),
                header(0x4c,new byte[]{0x14,1,1,0x0b,1,0,0x0c,1,0}),
                new byte[]{(byte)0xc3,(byte)(bmessage.length>>24),(byte)(bmessage.length>>16),(byte)(bmessage.length>>8),(byte)bmessage.length});
            // Validate the header packet before the conservative possible-submission boundary.
            frame(0x02,first,peerMax);attempted=true;
            byte[] p=exchange(0x02,first);int code=p[0]&255;
            checkHeaders(p,3);if(code!=0x90)throw new ResponseException(code);
            int capacity=peerMax-3-connection.length-3;
            if(capacity<=0)throw new IOException("No PUT body capacity");
            for(int pos=0,packets=0;pos<bmessage.length;packets++){
                if(packets>=MAX_PACKETS)throw new IOException("PUT continuation limit");
                int n=Math.min(capacity,bmessage.length-pos);boolean last=pos+n==bmessage.length;
                byte[] fragment=Arrays.copyOfRange(bmessage,pos,pos+n);
                p=exchange(last?0x82:0x02,join(connection,header(last?0x49:0x48,fragment)));
                Headers h=checkHeaders(p,3);code=p[0]&255;
                if(last){if(code!=0xa0)throw new ResponseException(code);return MapBMessage.handle(h.name);}
                if(code!=0x90)throw new ResponseException(code);pos+=n;
            }
            throw new IOException("Empty PUT body");
        }catch(IOException e){throw new MapClient.PushException(attempted,"MAP Push incomplete");}
    }
}
