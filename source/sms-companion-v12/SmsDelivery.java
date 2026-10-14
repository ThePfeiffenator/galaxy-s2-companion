package org.retrobridge.callprobe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.telephony.SmsMessage;
import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Root child of the pinned native worker. Only --deliver publishes a broadcast. */
public final class SmsDelivery {
    static final int MAGIC=0x534d5342,VERSION=1,MAX_PARTS=255;
    private static final String SMS_DB="/data/data/com.android.providers.telephony/databases/mmssms.db";
    private static final String SMS_ACTION="android.provider.Telephony.SMS_RECEIVED";
    private static final String SANITY_ACTION="org.retrobridge.smsprobe.INGRESS_SANITY";
    private static final DeliveryGate gate=new DeliveryGate();
    private static String identifier="00000000000000000000000000000000",mode;
    private static long deadline;
    private static volatile String diagnosticStage="arguments";

    static final class Batch {
        byte[][] pdus;
        String address,rawAddress,body;
        long timestamp;
    }
    static void require(boolean ok)throws Exception{if(!ok)throw new Exception("REJECTED");}
    private static int intAt(byte[] b,int p){return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|(b[p+3]<<24);}
    private static int fieldInt(Object o,String name)throws Exception{return o.getClass().getField(name).getInt(o);}
    private static Object field(Object o,String name)throws Exception{return o.getClass().getField(name).get(o);}
    private static void checkDeadline()throws Exception{require(SystemClock.uptimeMillis()<deadline&&!gate.finished());}

    /** Exact FL24 FilterCLRF: trim terminal CR/LF while preserving character zero. */
    static String storedBody(String text){int end=text.length();while(end>1&&(text.charAt(end-1)=='\r'||text.charAt(end-1)=='\n'))end--;return text.substring(0,end);}
    private static Object fromWire(byte[] w)throws Exception{
        CdmaCodec.Wire parsed=CdmaCodec.readWire(w);
        require(parsed.teleservice==4098&&parsed.servicePresent==0&&parsed.serviceCategory==0&&parsed.subaddressCount==0);
        CdmaCodec.decodeAddress(parsed,false);
        Parcel p=Parcel.obtain();
        try{
            p.writeInt(intAt(w,0));p.writeByte(w[4]);p.writeInt(intAt(w,8));
            for(int offset=12;offset<=24;offset+=4)p.writeInt(intAt(w,offset));
            int count=w[28]&255;p.writeByte(w[28]);for(int i=0;i<count;i++)p.writeByte(w[29+i]);
            p.writeInt(intAt(w,68));p.writeByte(w[72]);count=w[73]&255;p.writeByte(w[73]);for(int i=0;i<count;i++)p.writeByte(w[74+i]);
            count=intAt(w,112);p.writeInt(count);for(int i=0;i<count;i++)p.writeByte(w[116+i]);
            p.setDataPosition(0);Class<?> type=Class.forName("com.android.internal.telephony.cdma.SmsMessage");
            Object message=type.getMethod("newFromParcel",Parcel.class).invoke(null,p);
            require(message!=null&&p.dataAvail()==0);
            Method parse=type.getDeclaredMethod("parseSms");parse.setAccessible(true);parse.invoke(message);
            Field bearerField=type.getDeclaredField("mBearerData");bearerField.setAccessible(true);
            Object bearer=bearerField.get(message);require(bearer!=null&&fieldInt(bearer,"messageType")==1);
            return message;
        }finally{p.recycle();}
    }
    /** All bytes are decoded and cross-checked before any delivery side effect. */
    static Batch readBatch(DataInputStream input)throws Exception{
        require(input.readInt()==MAGIC&&input.readInt()==VERSION);
        int count=input.readInt();require(count>=1&&count<=MAX_PARTS);
        Batch batch=new Batch();batch.pdus=new byte[count][];StringBuilder body=new StringBuilder();
        int reference=-1,firstMessageId=-1;byte[] firstAddress=null;
        Method headerMethod=SmsMessage.class.getMethod("getUserDataHeader");
        for(int i=0;i<count;i++){
            checkDeadline();byte[] wire=new byte[372];input.readFully(wire);
            Object internal=fromWire(wire);Class<?> type=internal.getClass();
            byte[] pdu=(byte[])type.getMethod("getPdu").invoke(internal);require(pdu!=null&&pdu.length>0&&pdu.length<1024);
            SmsMessage message=SmsMessage.createFromPdu(pdu,"3gpp2");require(message!=null);
            String part=message.getDisplayMessageBody(),address=message.getOriginatingAddress();
            String internalBody=(String)type.getMethod("getMessageBody").invoke(internal);
            require(part!=null&&message.getMessageBody()!=null&&message.getMessageBody().equals(internalBody)&&address!=null&&address.length()>0&&address.length()<=72);
            long timestamp=message.getTimestampMillis();require(timestamp>=0);
            int messageId=type.getField("messageRef").getInt(internal);
            byte[] wireAddress=Arrays.copyOfRange(wire,12,65);
            if(i==0){
                batch.rawAddress=address;batch.address=message.getDisplayOriginatingAddress();
                require(batch.address!=null&&batch.address.length()>0&&batch.address.length()<=320);
                batch.timestamp=timestamp;firstAddress=wireAddress;firstMessageId=messageId;
            }
            else require(batch.rawAddress.equals(address)&&batch.timestamp==timestamp&&Arrays.equals(firstAddress,wireAddress)&&messageId==((firstMessageId+i)&65535));
            Object header=headerMethod.invoke(message),concat=header==null?null:field(header,"concatRef");
            if(header!=null)require(field(header,"portAddrs")==null);
            if(count==1)require(concat==null);
            else{
                require(concat!=null&&concat.getClass().getField("isEightBits").getBoolean(concat));
                int ref=fieldInt(concat,"refNumber");require(ref>=0&&ref<=255&&fieldInt(concat,"msgCount")==count&&fieldInt(concat,"seqNumber")==i+1);
                if(i==0)reference=ref;else require(reference==ref);
            }
            body.append(part);require(body.length()<=32768);batch.pdus[i]=pdu;
        }
        require(input.read()==-1);batch.body=storedBody(body.toString());return batch;
    }
    private static Context systemContext()throws Exception{
        Class<?> activityThread=Class.forName("android.app.ActivityThread");
        Method main=activityThread.getDeclaredMethod("systemMain");main.setAccessible(true);
        Object thread=main.invoke(null);Method context=activityThread.getDeclaredMethod("getSystemContext");context.setAccessible(true);
        return (Context)context.invoke(thread);
    }
    private static long newestId()throws Exception{
        SQLiteDatabase db=SQLiteDatabase.openDatabase(SMS_DB,null,SQLiteDatabase.OPEN_READONLY);
        try{
            Cursor c=db.query("sms",new String[]{"_id"},"type = 1",null,null,null,"_id DESC","1");require(c!=null);
            try{return c.moveToFirst()?c.getLong(0):0;}finally{c.close();}
        }finally{db.close();}
    }
    private static int persisted(Batch batch,long baseline,long started)throws Exception{
        SQLiteDatabase db=SQLiteDatabase.openDatabase(SMS_DB,null,SQLiteDatabase.OPEN_READONLY);
        try{
        Cursor c=db.query("sms",new String[]{"_id","address","body","date"},"type = 1 AND _id > ?",new String[]{Long.toString(baseline)},null,null,"_id ASC","1025");require(c!=null);
        int matches=0,rows=0;long now=System.currentTimeMillis();
        // A wall-clock discontinuity cannot give reliable receive-time proof.
        require(now>=started&&now-started<=120000);
        try{while(c.moveToNext()){
            require(++rows<=1024);long date=c.getLong(3);
            if(batch.address.equals(c.getString(1))&&batch.body.equals(c.getString(2))&&date>=started&&date<=now)matches++;
        }}finally{c.close();}return matches;
        }finally{db.close();}
    }
    /** FL24 IIntentReceiver transaction1, six fields. Final result does not require finishReceiver. */
    private static final class OrderedResult extends Binder {
        static final String DESCRIPTOR="android.content.IIntentReceiver";
        final String expectedAction;
        final CountDownLatch latch=new CountDownLatch(1);
        volatile int result=Integer.MIN_VALUE;
        OrderedResult(String action){expectedAction=action;attachInterface(null,DESCRIPTOR);}
        protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(DESCRIPTOR);return true;}
            if(code!=1)return super.onTransact(code,data,reply,flags);
            // Only system_server may complete a production ordered broadcast.
            if(Binder.getCallingUid()!=1000&&Binder.getCallingUid()!=0)throw new SecurityException("CALLER");
            data.enforceInterface(DESCRIPTOR);
            Intent received=data.readInt()!=0?Intent.CREATOR.createFromParcel(data):null;
            int value=data.readInt();data.readString();
            if(data.readInt()!=0)Bundle.CREATOR.createFromParcel(data);
            data.readInt();data.readInt();
            if(data.dataAvail()!=0||received==null||!expectedAction.equals(received.getAction()))throw new SecurityException("CALLBACK");
            synchronized(this){if(latch.getCount()!=0){result=value;latch.countDown();}}
            return true;
        }
        Object asReceiver()throws Exception{
            return Class.forName("android.content.IIntentReceiver$Stub").getMethod("asInterface",IBinder.class).invoke(null,this);
        }
    }
    private static Method broadcastMethod()throws Exception{
        return Class.forName("android.app.IActivityManager").getMethod("broadcastIntent",Class.forName("android.app.IApplicationThread"),Intent.class,String.class,Class.forName("android.content.IIntentReceiver"),int.class,String.class,Bundle.class,String.class,boolean.class,boolean.class);
    }
    private static void publishOrdered(Intent intent,OrderedResult ordered)throws Exception{
        Object receiver=ordered.asReceiver();Method broadcast=broadcastMethod();
        Object manager=Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);require(manager!=null);
        // Null IApplicationThread is supported for this root Binder caller. The
        // systemContext applicationThread is not registered with ActivityManager.
        Object accepted=broadcast.invoke(manager,null,intent,null,receiver,Activity.RESULT_OK,null,null,"android.permission.RECEIVE_SMS",true,false);
        require(accepted instanceof Integer&&((Integer)accepted).intValue()==0);
        require(ordered.latch.await(Math.max(1,deadline-SystemClock.uptimeMillis()),TimeUnit.MILLISECONDS));
        require(ordered.result==Activity.RESULT_OK||ordered.result==1);
    }
    /** No SMS broadcast: local ABI test followed by an empty probe-package intent. */
    private static void inspectBinder()throws Exception{
        broadcastMethod();OrderedResult receiver=new OrderedResult(SANITY_ACTION);require(receiver.asReceiver()!=null);
        Parcel data=Parcel.obtain();
        try{
            data.writeInterfaceToken(OrderedResult.DESCRIPTOR);data.writeInt(1);
            new Intent(SANITY_ACTION).writeToParcel(data,0);
            data.writeInt(Activity.RESULT_OK);data.writeString(null);data.writeInt(0);data.writeInt(1);data.writeInt(0);data.setDataPosition(0);
            require(receiver.onTransact(1,data,null,IBinder.FLAG_ONEWAY)&&receiver.latch.getCount()==0&&receiver.result==Activity.RESULT_OK);
        }finally{data.recycle();}
        checkDeadline();diagnosticStage="emptyOrderedIpc";
        Intent sanity=new Intent(SANITY_ACTION);sanity.setPackage("org.retrobridge.smsprobe");
        publishOrdered(sanity,new OrderedResult(SANITY_ACTION));
    }
    private static void deliver(final Batch batch)throws Exception{
        checkDeadline();long baseline=newestId();checkDeadline();
        final OrderedResult ordered=new OrderedResult(SMS_ACTION);
        Intent intent=new Intent(SMS_ACTION);
        intent.putExtra("pdus",batch.pdus);intent.putExtra("format","3gpp2");
        long started=System.currentTimeMillis();checkDeadline();
        // Timeout and publication claim the same lock. A reported pre-publication
        // ERROR can never race with a later broadcast from this child.
        require(gate.begin(SystemClock.uptimeMillis(),deadline));
        publishOrdered(intent,ordered);
        while(SystemClock.uptimeMillis()<deadline){
            int count=persisted(batch,baseline,started);
            require(count<=1);if(count==1){finish("OK");return;}
            Thread.sleep(Math.min(100,Math.max(1,deadline-SystemClock.uptimeMillis())));
        }
        throw new Exception("UNCONFIRMED");
    }
    private static void finish(String result){
        result=gate.finish(result);if(result==null)return;
        String prefix="--inspect".equals(mode)?"SMSINSPECT":"--validate".equals(mode)?"SMSVALIDATE":"SMSDELIVERY";
        System.out.print(prefix+"\t1\t"+identifier+"\t"+result+"\n");System.out.flush();
        System.exit("OK".equals(result)?0:"UNKNOWN".equals(result)?2:1);
    }
    private static void diagnose(Throwable error){
        if(!"--inspect".equals(mode)&&!"--validate".equals(mode))return;
        Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
        System.err.println("SMS_DIAGNOSTIC stage="+diagnosticStage+" class="+cause.getClass().getName());
        StackTraceElement[] trace=cause.getStackTrace();
        for(int i=0;i<trace.length&&i<5;i++)System.err.println("SMS_DIAGNOSTIC at="+trace[i].getClassName()+"."+trace[i].getMethodName()+":"+trace[i].getLineNumber());
    }
    public static void main(String[] args){
        try{
            if(args!=null&&args.length>0)mode=args[0];diagnosticStage="identity";
            require(android.os.Process.myUid()==0&&"SPH-D710".equals(Build.MODEL)&&"IMM76I.FL24".equals(Build.DISPLAY));
            diagnosticStage="arguments";
            require(args!=null&&(args.length>=1&&args.length<=3));mode=args[0];
            long now=SystemClock.uptimeMillis();
            if("--inspect".equals(mode)){require(args.length==1);deadline=now+10000;}
            else{
                require(("--validate".equals(mode)&&(args.length==2||args.length==3))||("--deliver".equals(mode)&&args.length==3));
                require(args[1].matches("[0-9a-f]{32}"));identifier=args[1];
                if(args.length==2)deadline=now+20000;
                else{require(args[2].matches("[1-9][0-9]{0,18}"));deadline=Long.parseLong(args[2]);require(deadline>now&&deadline-now<=60000);}
            }
            new Thread(new Runnable(){public void run(){
                try{while(SystemClock.uptimeMillis()<deadline)Thread.sleep(Math.max(1,deadline-SystemClock.uptimeMillis()));}catch(InterruptedException ignored){}
                finish("ERROR");
            }},"SMS ingress deadline").start();
            diagnosticStage="mainLooper";if(Looper.myLooper()==null)Looper.prepareMainLooper();
            diagnosticStage="systemContext";final Context context=systemContext();require(context!=null);
            new Thread(new Runnable(){public void run(){PowerManager.WakeLock wake=null;
                try{
                    diagnosticStage="wakeLock";
                    checkDeadline();PowerManager pm=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
                    wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GalaxySmsIngress");wake.setReferenceCounted(false);wake.acquire(Math.max(1,deadline-SystemClock.uptimeMillis()));
                    if("--inspect".equals(mode)){
                        diagnosticStage="readOnlyInbox";newestId();diagnosticStage="localResultBinder";inspectBinder();finish("OK");return;
                    }
                    diagnosticStage="readAndDecodeBatch";Batch batch=readBatch(new DataInputStream(System.in));checkDeadline();
                    if("--validate".equals(mode)){finish("OK");return;}
                    diagnosticStage="delivery";
                    deliver(batch);
                }catch(Throwable error){diagnose(error);finish("ERROR");}
                finally{if(wake!=null&&wake.isHeld())wake.release();}
            }},"SMS ingress").start();
            Looper.loop();
        }catch(Throwable error){diagnose(error);finish("ERROR");}
    }
}

/** Completion owns the same lock as entering the external broadcast operation. */
final class DeliveryGate {
    private boolean published,done;
    synchronized boolean finished(){return done;}
    synchronized boolean begin(long now,long deadline){
        if(done||published||now>=deadline)return false;
        published=true;return true;
    }
    synchronized String finish(String requested){
        if(done)return null;done=true;
        return "ERROR".equals(requested)&&published?"UNKNOWN":requested;
    }
}
