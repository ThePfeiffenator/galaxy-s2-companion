package org.retrobridge.callprobe;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.ParcelUuid;
import java.io.*;
import java.util.*;

/** Fixed-bond Pixel MAS client. Blocking API: call only from the separate SMS worker. */
public final class MapClient implements Closeable {
    public static final String PIXEL="@PIXEL_BLUETOOTH@";
    private static final UUID MAS=UUID.fromString("00001132-0000-1000-8000-00805f9b34fb");
    private static final Timer DEADLINES=new Timer("MAP deadlines",true);
    private final Object lock=new Object();
    private final Connector connector;private final long operationMillis,connectMillis;
    private Link link;private MapObex protocol;private Ticket active;private boolean ready;private String lastMseTime;
    /** No retry is safe when true. An accepted handle is also not evidence of carrier success. */
    public static final class PushException extends IOException {
        public final boolean acceptanceUnknown;
        PushException(boolean unknown,String message){super(message);acceptanceUnknown=unknown;}
    }
    interface Link extends Closeable {void connect()throws IOException;InputStream input()throws IOException;OutputStream output()throws IOException;}
    interface Connector {Link create()throws IOException;}
    interface FallbackConnector extends Connector {Link createFallback()throws IOException;}
    public MapClient(){this(new BluetoothConnector(),30000,45000);}
    MapClient(Connector connector,long operationMillis,long connectMillis){
        this.connector=connector;this.operationMillis=operationMillis;this.connectMillis=connectMillis;
    }
    private static final class BluetoothConnector implements FallbackConnector {
        private BluetoothDevice bondedPixel()throws IOException {
            BluetoothAdapter a=BluetoothAdapter.getDefaultAdapter();
            if(a==null||!a.isEnabled())throw new IOException("Bluetooth unavailable");
            BluetoothDevice d=a.getRemoteDevice(PIXEL);
            if(!PIXEL.equals(d.getAddress())||d.getBondState()!=BluetoothDevice.BOND_BONDED)
                throw new IOException("Expected Pixel bond unavailable");
            return d;
        }
        public Link create()throws IOException {
            BluetoothDevice d=bondedPixel();
            return wrap(d.createRfcommSocketToServiceRecord(MAS));
        }
        public Link createFallback()throws IOException {
            BluetoothDevice d=bondedPixel();int cached=-1;
            // ICS's Java SDP cache may omit MAS after an unrelated UUID refresh.
            // A missing/invalid cache uses only this Pixel's previously verified
            // channel 5, never a channel scan or a different Bluetooth device.
            try{Object result=d.getClass().getMethod("getServiceChannel",ParcelUuid.class).invoke(d,new ParcelUuid(MAS));
                if(result instanceof Integer)cached=((Integer)result).intValue();
            }catch(Exception unavailable){}
            d=bondedPixel();
            try{
                // Exact FL24 createRfcommSocket(int) requests auth AND encryption.
                BluetoothSocket s=(BluetoothSocket)d.getClass().getMethod("createRfcommSocket",int.class)
                    .invoke(d,cachedChannel(cached));
                if(s==null)throw new IOException("MAP cached socket unavailable");return wrap(s);
            }catch(Exception e){throw new IOException("MAP cached socket unavailable");}
        }
        private Link wrap(final BluetoothSocket s){
            return new Link(){public void connect()throws IOException{bondedPixel();s.connect();bondedPixel();}
                public InputStream input()throws IOException{return s.getInputStream();}
                public OutputStream output()throws IOException{return s.getOutputStream();}
                public void close()throws IOException{s.close();}};
        }
    }
    static int cachedChannel(int cached){return cached>=1&&cached<=30?cached:5;}
    private final class Ticket extends TimerTask {
        Link owned;boolean expired;
        @Override public void run(){Link close=null;synchronized(lock){
            if(active==this){expired=true;ready=false;protocol=null;close=owned;
                if(link==owned)link=null;}
        }quietClose(close);}
    }
    private Ticket begin(boolean connecting)throws IOException {
        synchronized(lock){
            if(active!=null)throw new IOException("MAP operation already running");
            if(connecting ? link!=null : !ready||link==null||protocol==null)throw new IOException(connecting?"MAP already connected":"MAP offline");
            Ticket t=new Ticket();t.owned=link;active=t;
            DEADLINES.schedule(t,connecting?connectMillis:operationMillis);return t;
        }
    }
    private void ensure(Ticket t)throws IOException {synchronized(lock){if(active!=t||t.expired||link!=t.owned)throw new IOException("MAP operation cancelled");}}
    private void end(Ticket t,boolean ok){Link close=null;synchronized(lock){
        t.cancel();if(active==t){active=null;if(!ok||t.expired){close=t.owned;if(link==t.owned){link=null;protocol=null;ready=false;}}}
    }quietClose(close);DEADLINES.purge();}
    private MapObex session(Ticket t)throws IOException {synchronized(lock){ensure(t);if(protocol==null)throw new IOException("MAP offline");return protocol;}}
    private void adopt(Ticket t,Link created)throws IOException {
        synchronized(lock){ensure(t);t.owned=created;link=created;}
    }
    public void connect()throws IOException {
        Ticket t=begin(true);boolean ok=false;Link created=null;
        try{
            boolean fallback=false;
            try{
                created=connector.create();adopt(t,created);created.connect();
            }catch(IOException first){
                ensure(t);if(!(connector instanceof FallbackConnector))throw first;
                // A close failure must not leave two competing MAP sockets.
                if(created!=null)created.close();ensure(t);
                created=((FallbackConnector)connector).createFallback();adopt(t,created);
                created.connect();fallback=true;
            }
            ensure(t);MapObex p=new MapObex(created.input(),created.output());p.connect(fallback);
            synchronized(lock){ensure(t);protocol=p;lastMseTime=null;ready=true;}ok=true;
        }finally{if(!ok)quietClose(created);end(t,ok);}
    }
    public boolean isConnected(){synchronized(lock){return ready&&link!=null&&protocol!=null;}}
    /** Successful latest listing metadata, including an empty inbox. Null if unavailable. */
    public String getLastMseTime(){synchronized(lock){return ready?lastMseTime:null;}}
    public List<MapMessage> listInbox(int offset,int count)throws IOException {
        if(offset<0||offset>65535||count<1||count>100)throw new IOException("Listing range limit");
        Ticket t=begin(false);boolean ok=false;try{
            synchronized(lock){lastMseTime=null;}
            MapObex p=session(t);List<MapMessage> r=p.listInbox(offset,count);
            synchronized(lock){ensure(t);lastMseTime=p.lastMseTime;}
            ok=true;return r;
        }finally{end(t,ok);}
    }
    public MapMessage getMessage(String handle)throws IOException {
        handle=MapBMessage.handle(handle);Ticket t=begin(false);boolean ok=false;
        try{MapMessage r=session(t).getMessage(handle);ensure(t);ok=true;return r;}finally{end(t,ok);}
    }
    public String pushText(String number,String text)throws IOException {
        byte[] body=MapBMessage.encodeText(number,text);Ticket t=begin(false);boolean ok=false,entered=false;
        try{MapObex p=session(t);entered=true;String handle=p.push(body);ensure(t);ok=true;return handle;}
        catch(PushException e){throw e;}
        catch(IOException e){throw new PushException(entered,"MAP Push interrupted");}
        finally{end(t,ok);}
    }
    /** Immediate local transport teardown, no OBEX mutation, and never an HFP command. */
    @Override public void close(){Link close;synchronized(lock){ready=false;protocol=null;lastMseTime=null;close=link;link=null;
        if(active!=null){active.expired=true;active.cancel();}
    }quietClose(close);DEADLINES.purge();}
    private static void quietClose(Link l){if(l!=null)try{l.close();}catch(IOException ignored){}}
}
