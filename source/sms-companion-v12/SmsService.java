package org.retrobridge.callprobe;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.*;
import android.os.*;
import android.telephony.SmsManager;
import android.telephony.PhoneNumberUtils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Stock Messaging -> deadline-bound native request -> paired Pixel MAP. */
public final class SmsService extends Service {
    static final String SOCKET="org.retrobridge.callprobe.smsril.v1";
    private volatile boolean alive=true;
    private volatile Peer peer;
    private volatile MapClient map;
    private volatile LocalServerSocket server;
    private final Object socketLifecycle=new Object();
    private LocalSocket acceptingSocket;
    private SmsJournal journal;
    private SmsPartPlan partPlan;
    private SharedPreferences settings;
    private final ThreadPoolExecutor work=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(16));
    private final ScheduledExecutorService deadlines=Executors.newScheduledThreadPool(1);
    private final AtomicInteger queuedSends=new AtomicInteger();
    private final Object ackLock=new Object();
    private String waitingAck,ackResult;
    private Peer waitingOwner;
    private boolean tickQueued;
    private String lastStatus="";
    private PendingIntent alarmIntent;
    private volatile long nextConnect;
    private volatile boolean receiveBlocked;
    public IBinder onBind(Intent i){return null;}
    public void onCreate(){
        super.onCreate();journal=new SmsJournal(this);settings=getSharedPreferences("sms-relay",MODE_PRIVATE);
        boolean recovered=journal.recoverUncertain();
        // A previous per-segment delivery may already have partially reached
        // Samsung. Never migrate or replay that pending message as a new batch.
        if(!journal.partialIncoming().isEmpty()){journal.pauseReceive();recovered=true;}
        receiveBlocked=journal.receivePaused();
        if(recovered||settings.getBoolean("needs_review",false))review("SMS needs review on Pixel before retrying");
        IntentFilter filter=new IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED);registerReceiver(bluetooth,filter);
        alarmIntent=PendingIntent.getService(this,406,new Intent(this,SmsService.class).setAction("POLL"),PendingIntent.FLAG_UPDATE_CURRENT);
        ((AlarmManager)getSystemService(ALARM_SERVICE)).setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+1000,30000,alarmIntent);
        new Thread(new Runnable(){public void run(){acceptLoop();}},"SMS root socket").start();
        status("SMS starting");
    }
    public int onStartCommand(Intent i,int flags,int id){queueTick();return START_STICKY;}
    private final BroadcastReceiver bluetooth=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
        int state=i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE,-1);
        if(state!=android.bluetooth.BluetoothAdapter.STATE_ON)closeMap();else{nextConnect=0;queueTick();}
    }};
    private synchronized void status(String s){
        if(s.equals(lastStatus))return;lastStatus=s;CompanionService c=CompanionService.active;if(c!=null)c.add(s);
        try{FileOutputStream f=openFileOutput("sms-status.txt",MODE_PRIVATE);try{f.write(s.getBytes("UTF-8"));}finally{f.close();}}catch(Exception ignored){}
    }
    private void review(String s){
        settings.edit().putBoolean("needs_review",true).commit();status(s);
        try{
            Intent open=new Intent(this,ProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent click=PendingIntent.getActivity(this,407,open,PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n=new Notification.Builder(this).setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("SMS needs review").setContentText("Check Pixel before retrying. Automatic retries are paused.")
                .setContentIntent(click).setOngoing(true).getNotification();
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(407,n);
        }catch(Exception ignored){}
    }
    private synchronized void queueTick(){
        if(tickQueued||!alive)return;tickQueued=true;
        try{work.execute(new Runnable(){public void run(){try{tick();}finally{synchronized(SmsService.this){tickQueued=false;}}}});}
        catch(RejectedExecutionException e){tickQueued=false;}
    }
    private PowerManager.WakeLock wake(String name,int timeout){
        PowerManager.WakeLock w=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GalaxySms:"+name);
        w.setReferenceCounted(false);w.acquire(timeout);return w;
    }
    private boolean allowed(){Peer p=peer;return alive&&CompanionService.messagingAllowed()&&p!=null&&!p.closed;}
    private void closeMap(){MapClient c;synchronized(this){c=map;map=null;}if(c!=null)c.close();}
    private void closeMap(MapClient expected){if(expected==null)return;synchronized(this){if(map==expected)map=null;}expected.close();}
    private static final class PollDeferred extends IOException {}
    private void pollCheck(MapClient c,long end)throws IOException{
        if(!allowed()||map!=c||queuedSends.get()>0||SystemClock.uptimeMillis()>=end)throw new PollDeferred();
    }
    private void tick(){
        if(!allowed()){closeMap();status("SMS waiting for Pixel call bridge");return;}
        if(queuedSends.get()>0||SystemClock.elapsedRealtime()<nextConnect)return;
        PowerManager.WakeLock w=wake("poll",70000);
        try{
            if(new RcsClient(this).configured()&&!receiveBlocked&&!journal.receivePaused()){try{receiveRcs();}catch(Exception e){status("RCS relay waiting ("+e.getClass().getSimpleName()+")");}}
            if(map==null||!map.isConnected()){
                closeMap();MapClient c=new MapClient();synchronized(this){map=c;}
                try{c.connect();}catch(Exception e){closeMap(c);throw e;}
            }
            if(!allowed()){closeMap();return;}
            if(receiveBlocked||journal.receivePaused()){status("Incoming SMS paused after uncertain delivery; check Pixel");return;}
            receiveNew(map,SystemClock.uptimeMillis()+45000);
            status(settings.getBoolean("needs_review",false)?"SMS connected; check uncertain text on Pixel before retrying":"SMS connected to Pixel");
        }catch(PollDeferred e){/* Give actual SEND requests priority over further polling. */}
        catch(Exception e){closeMap();nextConnect=SystemClock.elapsedRealtime()+15000;status(receiveBlocked?"Incoming SMS paused after uncertain delivery; check Pixel":"SMS polling deferred ("+e.getClass().getSimpleName()+")");}
        finally{if(w.isHeld())w.release();}
    }
    private void acceptLoop(){
        while(alive){
            LocalServerSocket owned=null;
            try{
                owned=new LocalServerSocket(SOCKET);
                synchronized(socketLifecycle){if(!alive)throw new EOFException();server=owned;}
                while(alive){
                    LocalSocket socket=owned.accept();Peer p=null;
                    try{
                        synchronized(socketLifecycle){if(!alive)throw new EOFException();acceptingSocket=socket;}
                        if(socket.getPeerCredentials().getUid()!=0)continue;
                        p=new Peer(socket);socket.setSoTimeout(5000);
                        if(!"HELLO\t2".equals(p.read())||peer!=null)continue;
                        synchronized(socketLifecycle){if(!alive)throw new EOFException();peer=p;acceptingSocket=null;}
                        socket.setSoTimeout(0);p.send("READY\t2");queueTick();p.run();
                    }catch(Exception ignored){}
                    finally{
                        synchronized(socketLifecycle){if(acceptingSocket==socket)acceptingSocket=null;}
                        if(peer==p){peer=null;closeMap();}
                        if(p!=null)p.close();else SmsSocketShutdown.close(socket);
                        synchronized(ackLock){ackLock.notifyAll();}
                    }
                }
            }catch(Exception e){if(alive)status("SMS local connection retrying");}
            finally{
                synchronized(socketLifecycle){if(server==owned)server=null;}
                if(!SmsSocketShutdown.close(owned)&&alive)status("SMS listener shutdown failed");
            }
            if(alive)try{Thread.sleep(2000);}catch(InterruptedException e){return;}
        }
    }
    private final class Peer {
        final LocalSocket socket;final InputStream input;final OutputStream output;
        final AtomicBoolean closeClaim=new AtomicBoolean();
        volatile boolean closed;long lastId;
        Peer(LocalSocket s)throws IOException{socket=s;input=s.getInputStream();output=s.getOutputStream();}
        void close(){if(!closeClaim.compareAndSet(false,true))return;closed=true;SmsSocketShutdown.close(socket);synchronized(ackLock){ackLock.notifyAll();}}
        synchronized void send(String s)throws IOException{if(closed)throw new EOFException();output.write((s+"\n").getBytes("US-ASCII"));output.flush();}
        String read()throws IOException{
            ByteArrayOutputStream b=new ByteArrayOutputStream();
            for(;;){int v=input.read();if(v<0)throw new EOFException();if(v==10)return b.toString("US-ASCII");if((v!=9&&(v<32||v>126))||b.size()>=1800)throw new IOException("Invalid SMS frame");b.write(v);}
        }
        void run()throws Exception{
            while(alive&&!closed){
                String[] f=read().split("\t",-1);
                if(f.length==5&&f[0].equals("SEND")&&f[1].matches("[1-9][0-9]{0,9}")&&f[2].matches("[1-9][0-9]{0,4}")
                    &&f[3].matches("[1-9][0-9]{0,18}")&&f[4].matches("[0-9a-f]{744}")){
                    long id=Long.parseLong(f[1]),remaining=Long.parseLong(f[2]),absolute=Long.parseLong(f[3]);
                    if(id>2147483647L||id<=lastId||remaining>90000)throw new IOException("Invalid SMS request");
                    lastId=id;
                    long end=Math.min(SystemClock.uptimeMillis()+remaining,absolute)-2000;
                    SendTask task=new SendTask(this,f[1],unhex(f[4]),end);
                    queuedSends.incrementAndGet();
                    try{task.timer=deadlines.schedule(task.timeout,Math.max(0,end-SystemClock.uptimeMillis()),TimeUnit.MILLISECONDS);work.execute(task);}
                    catch(RejectedExecutionException e){queuedSends.decrementAndGet();task.finish("ERROR");}
                }else if(f.length==3&&f[0].equals("BATCH_RESULT")&&f[1].matches("[0-9a-f]{32}")
                    &&(f[2].equals("OK")||f[2].equals("ERROR")||f[2].equals("UNKNOWN"))){
                    synchronized(ackLock){if(this==waitingOwner&&f[1].equals(waitingAck)){ackResult=f[2];ackLock.notifyAll();}}
                }else throw new IOException("Unknown SMS frame");
            }
        }
    }
    private static final class Outbox {
        String job,body,fingerprint;ArrayList<String> parts;
        int total,sequence,reference;boolean referenceIsEightBits;
    }
    /** First matching membership position; it is not inferred wire ordering. */
    static int matchingPartIndex(List<String> parts,String text,int total,int sequence){
        if(total==0&&sequence==0){
            for(int i=0;i<parts.size();i++)if(parts.get(i).equals(text))return i+1;
            return 0;
        }
        if(total!=parts.size()||sequence<1||sequence>parts.size())return 0;
        return parts.get(sequence-1).equals(text)?sequence:0;
    }
    private String normalize(String number){String n=number==null?null:PhoneNumberUtils.stripSeparators(number);return n==null?"":n;}
    private Outbox matchOutbox(CdmaCodec.Outgoing message)throws Exception{
        if(partPlan==null)partPlan=new SmsPartPlan(this);
        Outbox match=null;int visited=0;
        Cursor rows=getContentResolver().query(Uri.parse("content://sms/outbox"),new String[]{"_id","address","body"},null,null,"_id ASC");
        if(rows==null)throw new IOException("Outbox unavailable");
        try{while(rows.moveToNext()){
            if(++visited>1000)throw new IOException("Outbox scan bound");
            String number=rows.getString(1),body=rows.getString(2);
            if(body==null||body.length()==0||body.length()>20000||!normalize(number).equals(normalize(message.number)))continue;
            SmsPartPlan.Match selected=partPlan.match(body,message.text,message.total,message.sequence);
            if(selected==null)continue;
            ArrayList<String> parts=selected.parts;
            int total=message.total,seq=message.sequence,reference=message.concatReference;
            boolean referenceIsEightBits=message.concatReferenceIsEightBits;
            seq=selected.sequence;
            if(message.total==0&&message.sequence==0){
                // Samsung omits multipart UDH. Match exact membership in one
                // stock row; SendTask permits first submission only if the text
                // equals parts[0]. After SENT, every matching part is an ACK of
                // that complete body, so repeated parts need no order inference.
                total=parts.size();
                // -1 is a local journal marker, never a wire concat reference.
                if(total>1){reference=-1;referenceIsEightBits=false;}
            }
            if(match!=null)throw new IOException("Outbox match not unique");
            match=new Outbox();match.body=body;match.parts=parts;match.fingerprint=digest(normalize(number)+"\n"+body);match.job=rows.getLong(0)+":"+match.fingerprint;
            match.total=total;match.sequence=seq;match.reference=reference;match.referenceIsEightBits=referenceIsEightBits;
        }}finally{rows.close();}
        if(match==null)throw new IOException("Outbox match missing");return match;
    }
    private final class SendTask implements Runnable {
        final Peer owner;final String id;final byte[] wire;final long end;
        final AtomicBoolean replied=new AtomicBoolean();
        volatile RcsClient operationRcs;volatile boolean pushAttempted,expired;volatile MapClient operationMap;volatile ScheduledFuture<?> timer;
        final Runnable timeout=new Runnable(){public void run(){
            boolean attempted;MapClient timedMap;
            synchronized(SendTask.this){
                if(!replied.compareAndSet(false,true))return;
                expired=true;attempted=pushAttempted;timedMap=operationMap;
            }
            RcsClient timedRcs=operationRcs;if(timedRcs!=null)timedRcs.cancel();
            if(attempted){closeMap(timedMap);review("SMS result uncertain; check Pixel before retrying");}
            writeResult(attempted?"UNKNOWN":"ERROR");
        }};
        SendTask(Peer owner,String id,byte[] wire,long end){this.owner=owner;this.id=id;this.wire=wire;this.end=end;}
        void check()throws IOException{
            if(replied.get()||expired||SystemClock.uptimeMillis()>=end||peer!=owner||owner.closed||!allowed())throw new IOException("SMS deadline or link changed");
        }
        void finish(String result){
            if(!replied.compareAndSet(false,true))return;
            ScheduledFuture<?> t=timer;if(t!=null)t.cancel(false);
            writeResult(result);
        }
        void writeResult(String result){
            try{if(peer==owner&&!owner.closed)owner.send("RESULT\t"+id+"\t"+result);}catch(IOException e){owner.close();}
        }
        public void run(){
            PowerManager.WakeLock w=null;String result="ERROR",job=null,stage="prepare";boolean journaled=false;
            try{
                check();w=wake("send",85000);
                stage="decode";CdmaCodec.Outgoing message=CdmaCodec.decode(wire);
                stage="match";Outbox box=matchOutbox(message);job=box.job;stage="prepare";
                int total=box.total,seq=box.sequence;
                SmsJournal.Out prior=journal.outgoing(job);
                if(prior!=null&&"NOT_SUBMITTED".equals(prior.state)){journal.forgetNotSubmitted(job);prior=null;}
                if(prior!=null){
                    if(prior.parts!=total||prior.ref!=box.reference||prior.ref8!=(box.referenceIsEightBits?1:0)){
                        review("SMS retry identity changed; check Pixel before retrying");result="UNKNOWN";
                    }else if("SENT".equals(prior.state))result="OK";
                    else{review("SMS result uncertain; check Pixel before retrying");result="UNKNOWN";}
                }else{
                    if(journal.hasUncertainOutgoing(box.fingerprint)){review("A matching SMS has an uncertain result; check Pixel before retrying");result="UNKNOWN";}
                    else{
                        if(seq!=1)throw new IOException("Missing first SMS segment");
                        check();RcsClient rcs=new RcsClient(SmsService.this);operationRcs=rcs;
                        JSONObject route=rcs.configured()?rcs.route(message.number):new JSONObject().put("state","none");
                        String mode=route.getString("state");check();
                        if("reply".equals(mode)){
                            if(end-SystemClock.uptimeMillis()<15000)throw new IOException("RCS send window unavailable");
                            journal.begin(job,box.fingerprint,box.reference,box.referenceIsEightBits,total);journaled=true;
                            synchronized(this){check();pushAttempted=true;}
                            stage="RCS submit";String response=rcs.send(digest(job),message.number,box.body,route.getString("route"));
                            if("SUBMITTED".equals(response)){journal.outgoingState(job,"SENT","rcs:"+digest(job));result="OK";}
                            else if("REJECTED".equals(response)||"STALE".equals(response)){
                                journal.outgoingState(job,"NOT_SUBMITTED",null);pushAttempted=false;result="ERROR";status("RCS reply unavailable; use Pixel");
                            }else{journal.outgoingState(job,"UNCERTAIN",null);review("RCS reply uncertain; check Pixel before retrying");result="UNKNOWN";}
                        }else if("none".equals(mode)){
                            check();MapClient c=map;
                        if(c==null||!c.isConnected()||end-SystemClock.uptimeMillis()<15000)throw new IOException("SMS send window unavailable");
                        journal.begin(job,box.fingerprint,box.reference,box.referenceIsEightBits,total);journaled=true;
                        synchronized(this){check();operationMap=c;pushAttempted=true;}
                        stage="submit";String handle=c.pushText(message.number,box.body);journal.outgoingState(job,"ACCEPTED",handle);
                        stage="confirm";
                        long confirmEnd=Math.min(end,SystemClock.uptimeMillis()+35000);
                        while(SystemClock.uptimeMillis()<confirmEnd){
                            check();MapMessage sent=c.getMessage(handle);
                            if(folder(sent.folder,"sent")){
                                journal.outgoingState(job,"SENT",handle);result="OK";break;
                            }
                            Thread.sleep(Math.min(1000,Math.max(1,confirmEnd-SystemClock.uptimeMillis())));
                        }
                        if(!"OK".equals(result)){journal.outgoingState(job,"UNCERTAIN",handle);review("SMS result uncertain; check Pixel before retrying");result="UNKNOWN";}
                        }else throw new IOException("RCS conversation unavailable; use Pixel");
                    }
                }
                check();
            }catch(Exception e){
                if(pushAttempted){if(job!=null)try{SmsJournal.Out known=journal.outgoing(job);if(known==null||!"SENT".equals(known.state))journal.outgoingState(job,"UNCERTAIN",null);}catch(Exception ignored){}
                    review("SMS result uncertain during "+stage+"; check Pixel before retrying");result="UNKNOWN";closeMap(operationMap);
                }else if(journaled){try{journal.outgoingState(job,"NOT_SUBMITTED",null);}catch(Exception ignored){}status("SMS request expired before submission ("+stage+")");}
                else if(!replied.get())status("SMS could not send at "+stage+" ("+e.getClass().getSimpleName()+")");
            }finally{
                finish(result);queuedSends.decrementAndGet();if(w!=null&&w.isHeld())w.release();
            }
        }
    }
    private void receiveRcs()throws Exception{
        RcsClient client=new RcsClient(this);JSONArray messages=client.pending();if(messages.length()>8)throw new IOException("RCS batch too large");
        for(int i=0;i<messages.length();i++){
            if(!allowed()||queuedSends.get()>0||receiveBlocked||journal.receivePaused())return;
            JSONObject m=messages.getJSONObject(i);String id=m.getString("id"),number=m.getString("number"),body=m.getString("body");long time=m.getLong("time");
            if(!id.matches("[0-9a-f]{64}")||!number.matches("\\+[1-9][0-9]{4,14}")||body.length()<1||body.length()>16000||time<=0||time>System.currentTimeMillis()+120000)throw new IOException("Invalid RCS message");
            String handle="RCS:"+id;String prior=journal.incomingState(handle);
            if("RECEIVED".equals(prior)){client.ack(id);continue;}
            if(prior!=null)throw new IOException("RCS delivery needs review");
            int messageId=digest(handle).hashCode()&65535;byte[][] parts=CdmaCodec.encodeParts(number,body,time,messageId,0);
            try{if(parts.length>1){int reference=journal.reserveIncomingReference(handle,digest(normalize(number)));parts=CdmaCodec.encodeParts(number,body,time,messageId,reference);}journal.beginIncoming(handle,parts);}
            catch(Exception e){journal.releaseUnstartedReference(handle);throw e;}
            deliverIncoming(handle,map);client.ack(id);
        }
    }
    private static boolean folder(String actual,String name){return actual!=null&&(actual.equals(name)||actual.equals("telecom/msg/"+name)||actual.equals("/telecom/msg/"+name));}
    private static List<String> handles(List<MapMessage> page){ArrayList<String> out=new ArrayList<String>();for(MapMessage m:page)out.add(m.handle);return out;}

    /** Offset pages overlap. Any shift or duplicate aborts before journal/delivery. */
    private List<MapMessage> scanInbox(MapClient c,long end)throws Exception{
        ArrayList<MapMessage> unseen=new ArrayList<MapMessage>();HashSet<String> seen=new HashSet<String>();
        List<String> first=null,previous=null;int offset=0;boolean reached=false;
        long cutoff=journal.baselineCutoff(),knownBoundary=Long.MIN_VALUE,lastTime=Long.MAX_VALUE;
        for(int pageIndex=0;pageIndex<64;pageIndex++){
            pollCheck(c,end);List<MapMessage> page=c.listInbox(offset,50);List<String> current=handles(page);
            if(first==null)first=current;
            int start=0;
            if(previous!=null){
                if(current.size()<10||!previous.subList(previous.size()-10,previous.size()).equals(current.subList(0,10)))throw new IOException("Inbox changed during pagination");
                start=10;
            }
            HashSet<String> pageSeen=new HashSet<String>();
            for(String h:current)if(h==null||!pageSeen.add(h))throw new IOException("Duplicate inbox handle");
            for(int i=start;i<page.size();i++){
                MapMessage m=page.get(i);
                if(!seen.add(m.handle))throw new IOException("Repeated inbox page");
                if(m.timestampMillis<0||m.timestampMillis>lastTime)throw new IOException("Inbox timestamp order unavailable");
                lastTime=m.timestampMillis;
                // MAP is newest-first, but equal-date ordering is unspecified.
                // Consume the entire timestamp group containing a known anchor.
                if(m.timestampMillis<=cutoff||(knownBoundary!=Long.MIN_VALUE&&m.timestampMillis<knownBoundary)){reached=true;break;}
                if(journal.incomingState(m.handle)!=null){if(knownBoundary==Long.MIN_VALUE)knownBoundary=m.timestampMillis;continue;}
                unseen.add(m);
            }
            if(reached||page.size()<50){reached=true;break;}
            previous=current;offset+=40;
        }
        if(!reached)throw new IOException("Inbox exceeds bounded scan");
        pollCheck(c,end);List<String> head=handles(c.listInbox(0,50));
        if(!first.equals(head))throw new IOException("Inbox changed during scan");
        return unseen;
    }
    private void receiveNew(MapClient c,long end)throws Exception{
        if(receiveBlocked||journal.receivePaused())return;
        if(!journal.partialIncoming().isEmpty()){
            receiveBlocked=true;journal.pauseReceive();review("Incoming SMS paused with an unfinished older delivery; check Pixel");return;
        }
        if(!journal.baselineReady()){
            pollCheck(c,end);List<MapMessage> initial=c.listInbox(0,50);
            long cutoff=MapBMessage.timestamp(c.getLastMseTime(),null);
            if(cutoff<=0)throw new IOException("Pixel baseline time unavailable");
            List<String> initialHandles=handles(initial);
            if(new HashSet<String>(initialHandles).size()!=initialHandles.size()||initialHandles.contains(null))throw new IOException("Duplicate baseline handle");
            pollCheck(c,end);if(!initialHandles.equals(handles(c.listInbox(0,50))))throw new IOException("Inbox changed during baseline");
            // Historical or later backdated messages at/before this trusted
            // Pixel cutoff are intentionally excluded, even if anchors disappear.
            journal.establishBaseline(initialHandles,cutoff);status("SMS ready; forwarding texts dated after setup");return;
        }
        List<MapMessage> unseen=scanInbox(c,end);
        Collections.reverse(unseen);
        for(MapMessage meta:unseen){
            pollCheck(c,end);if(receiveBlocked||journal.receivePaused())return;
            if(journal.incomingState(meta.handle)!=null)continue;
            if(meta.type==null||!meta.type.startsWith("SMS_")){journal.incomingState(meta.handle,"UNSUPPORTED",0);continue;}
            MapMessage message=c.getMessage(meta.handle);
            if(message.sender==null||message.body==null||!folder(message.folder,"inbox"))throw new IOException("SMS body unavailable");
            if(meta.timestampMillis<0)throw new IOException("SMS timestamp offset unavailable");
            pollCheck(c,end);
            int messageId=digest(meta.handle).hashCode()&65535;
            byte[][] parts=CdmaCodec.encodeParts(message.sender,message.body,meta.timestampMillis,messageId,0);
            try{
                if(parts.length>1){
                    int reference=journal.reserveIncomingReference(meta.handle,digest(normalize(message.sender)));
                    parts=CdmaCodec.encodeParts(message.sender,message.body,meta.timestampMillis,messageId,reference);
                }
                journal.beginIncoming(meta.handle,parts);
            }catch(Exception e){journal.releaseUnstartedReference(meta.handle);throw e;}
            // Once durable intent exists, finish this one bounded batch before
            // yielding to SEND. No per-part resume can enter the vendor path.
            deliverIncoming(meta.handle,c);
        }
    }
    private void deliverIncoming(String handle,MapClient c)throws Exception{
        Peer current=peer;PowerManager.WakeLock w=null;String result=null;
        try{
            if(receiveBlocked||journal.receivePaused()||!allowed()||map!=c||current==null||current.closed)throw new IOException("SMS batch link unavailable");
            byte[][] parts=journal.incomingWire(handle);
            if(journal.incomingPart(handle)!=0||parts.length<1||parts.length>255)throw new IOException("Invalid receive batch journal");
            String uuid=digest(handle+":batch-v2").substring(0,32);
            w=wake("receive-batch",60000);
            synchronized(ackLock){waitingAck=uuid;ackResult=null;waitingOwner=current;}
            current.send("BEGIN\t"+uuid+"\t"+parts.length);
            for(int i=0;i<parts.length;i++){
                if(peer!=current||current.closed||!allowed()||map!=c)throw new IOException("SMS batch link changed");
                current.send("PART\t"+uuid+"\t"+(i+1)+"\t"+hex(parts[i]));
            }
            // COMMIT is the first frame that may publish. Durable INFLIGHT
            // always precedes it; process loss will recover as UNCERTAIN.
            journal.incomingState(handle,"INFLIGHT",0);
            if(peer!=current||current.closed||!allowed()||map!=c)throw new IOException("SMS batch link changed");
            current.send("COMMIT\t"+uuid);
            long end=SystemClock.uptimeMillis()+40000;
            synchronized(ackLock){
                while(ackResult==null&&peer==current&&!current.closed&&SystemClock.uptimeMillis()<end)ackLock.wait(Math.max(1,end-SystemClock.uptimeMillis()));
                result=ackResult;
            }
            if(!"OK".equals(result))throw new IOException("SMS batch completion unavailable");
            // v2 OK requires both ordered-broadcast completion and the helper's
            // unique matching new provider row for the complete original text.
            journal.incomingState(handle,"RECEIVED",parts.length);
        }catch(Exception e){
            receiveBlocked=true;
            try{journal.incomingState(handle,"UNCERTAIN",0);journal.pauseReceive();}catch(Exception ignored){}
            if(current!=null)current.close();
            review("ERROR".equals(result)?"Incoming SMS rejected before delivery; check Pixel":"Incoming SMS paused after uncertain delivery; check Pixel");
            throw e;
        }finally{
            synchronized(ackLock){waitingAck=null;ackResult=null;waitingOwner=null;}
            if(w!=null&&w.isHeld())w.release();
        }
    }
    private static String digest(String s)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")));}
    static String hex(byte[] b){char[] d="0123456789abcdef".toCharArray();char[] r=new char[b.length*2];for(int i=0;i<b.length;i++){r[i*2]=d[(b[i]&255)>>4];r[i*2+1]=d[b[i]&15];}return new String(r);}
    static byte[] unhex(String s){byte[] r=new byte[s.length()/2];for(int i=0;i<r.length;i++)r[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return r;}
    public void onDestroy(){
        LocalSocket pending;LocalServerSocket listening;Peer p;
        synchronized(socketLifecycle){alive=false;pending=acceptingSocket;listening=server;p=peer;}
        if(p!=null)p.close();SmsSocketShutdown.close(pending);
        if(!SmsSocketShutdown.close(listening))status("SMS listener shutdown failed");
        closeMap();
        ((AlarmManager)getSystemService(ALARM_SERVICE)).cancel(alarmIntent);unregisterReceiver(bluetooth);deadlines.shutdownNow();work.shutdownNow();super.onDestroy();
    }
}

/** Exact ICS shutdown paths; no descriptor alias or finalizer can close a reused FD. */
final class SmsSocketShutdown {
    static void close(LocalSocket socket){
        if(socket==null)return;
        synchronized(socket){
            if(socket.getFileDescriptor()==null)return;
            // Serialize duplicate teardown before native close can reuse this FD.
            // Reads and Peer.send use other monitors; shutdown can still wake them.
            try{socket.shutdownInput();}catch(IOException ignored){}
            try{socket.shutdownOutput();}catch(IOException ignored){}
            try{socket.close();}catch(IOException ignored){}
        }
    }
    static boolean close(LocalServerSocket server){
        if(server==null)return true;
        synchronized(server){
            if(server.getFileDescriptor()==null)return true;
            boolean stopped=false;
            try{
                java.lang.reflect.Field field=LocalServerSocket.class.getDeclaredField("impl");field.setAccessible(true);
                Object impl=field.get(server);
                java.lang.reflect.Method input=impl.getClass().getDeclaredMethod("shutdownInput");input.setAccessible(true);
                // Exact FL24 method calls shutdown(fd, SHUT_RD) directly, with no
                // read/accept monitor. SHUT_RD wakes the outstanding accept.
                try{input.invoke(impl);stopped=true;}catch(Exception ignored){}
                try{java.lang.reflect.Method output=impl.getClass().getDeclaredMethod("shutdownOutput");output.setAccessible(true);output.invoke(impl);}catch(Exception ignored){}
            }catch(Exception ignored){}
            try{server.close();}catch(IOException failed){return false;}
            return stopped;
        }
    }
}
