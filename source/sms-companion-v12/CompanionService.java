package org.retrobridge.callprobe;

import android.app.Service;
import android.app.Notification;
import android.app.PendingIntent;
import android.os.*;
import android.bluetooth.*;
import android.content.*;
import android.media.*;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class CompanionService extends Service {
    static volatile CompanionService active;
    boolean hasActiveCall(){HfpConnection h=connection;return voice!=null||(h!=null&&h.stockCallPresent);}
    static boolean messagingAllowed() {
        CompanionService s=active;
        if(s==null) return false;
        HfpConnection h=s.connection;
        return h!=null && h.ready && s.admitted(h.epoch) && s.stockAdmitted(h);
    }
    static { System.loadLibrary("probe"); }
    static final String ACTION_BOOT="org.retrobridge.callprobe.BOOT";
    static final String ACTION_RESUME="org.retrobridge.callprobe.RESUME";
    static final String ACTION_LEGACY="org.retrobridge.callprobe.LEGACY_RFCOMM";
    static final String ACTION_SDP="org.retrobridge.callprobe.SDP_RFCOMM";
    static final String PIXEL_ADDRESS="@PIXEL_BLUETOOTH@";
    static final UUID HFP_GATEWAY=UUID.fromString("0000111f-0000-1000-8000-00805f9b34fb");
    native String nativeProbe();
    native int listenVoice(String address);
    native int acceptVoice(String expectedAddress);
    native String voicePeerDiagnostic();
    native int readVoice(int fd,byte[] b);
    native int writeVoice(int fd,byte[] b,int n);
    native void stopVoice(int fd);
    native void releaseVoice(int fd);
    native void closeListener();
    volatile boolean alive=true,routeReady,listenerReady;
    final Object connectionLock=new Object(),peerLock=new Object();
    final AtomicLong transportEpoch=new AtomicLong();
    final ScheduledExecutorService deadlines=Executors.newScheduledThreadPool(2);
    final ScheduledExecutorService lifecycle=Executors.newSingleThreadScheduledExecutor();
    final ReconnectPolicy retries=new ReconnectPolicy();
    volatile LocalServerSocket stockServer;
    volatile LocalSocket routeSocket;
    volatile RootPeer rootPeer;
    volatile HfpConnection connection;
    volatile VoiceSession voice;
    AudioManager audioManager;
    SharedPreferences settings;
    String pixelAddress;
    boolean lifecycleStarted;
    final Handler main=new Handler();
    String report="";
    String statusText="Starting companion";
    String notifiedStatus="";
    NotificationCoordinator notificationGroup;
    final Set<Callback> callbacks=new HashSet<Callback>();
    interface Callback { void changed(String state,String report); }
    public final class LocalBinder extends Binder { CompanionService service() { return CompanionService.this; } }
    private final LocalBinder binder=new LocalBinder();
    public IBinder onBind(Intent intent) { return binder; }
    void observe(Callback callback) { callbacks.add(callback); callback.changed(statusText,report); }
    void unobserve(Callback callback) { callbacks.remove(callback); }
    public void onCreate() {
        super.onCreate();
        active=this;
        settings=getSharedPreferences("companion",MODE_PRIVATE);
        pixelAddress=settings.getString("pixel_address",null);
        if(pixelAddress==null) { pixelAddress=PIXEL_ADDRESS; settings.edit().putString("pixel_address",pixelAddress).commit(); }
        retries.pause(settings.getBoolean("paused",false));
        audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
        updateNotification();
        notificationGroup=new NotificationCoordinator(this);notificationGroup.start();
        registerReceiver(bluetoothReceiver,new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        add("Galaxy companion 0.10 - shared notification");
        add(nativeProbe()); startStockServer(); listenAudio();
        startService(new Intent(this,SmsService.class));
    }
    public int onStartCommand(Intent intent,int flags,int startId) {
        // A sticky null-intent restart is never permission to turn Bluetooth on.
        if(intent!=null) {
            String action=intent.getAction();
            if(ACTION_RESUME.equals(action)) resume();
            else if(ACTION_BOOT.equals(action) && !retries.paused()) enableBluetooth();
            else if(ACTION_LEGACY.equals(action) || ACTION_SDP.equals(action)) {
                settings.edit().putBoolean("legacy_rfcomm",ACTION_LEGACY.equals(action)).commit();
                invalidateTransport("Bluetooth connection method changed"); retries.dueNow();
            }
        }
        if(!lifecycleStarted) {
            lifecycleStarted=true;
            lifecycle.scheduleWithFixedDelay(new Runnable() { public void run() { lifecycleTick(); }},0,1,TimeUnit.SECONDS);
        }
        return START_STICKY;
    }
    void resume() {
        settings.edit().putBoolean("paused",false).commit(); retries.pause(false);
        enableBluetooth(); refreshStatus();
    }
    void enableBluetooth() {
        BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
        if(adapter!=null && !adapter.isEnabled()) {
            boolean accepted=adapter.enable(); add(accepted?"Bluetooth enable requested":"Bluetooth enable request failed");
        }
    }
    void pause() {
        settings.edit().putBoolean("paused",true).commit(); retries.pause(true);
        invalidateTransport("Paused by user"); refreshStatus();
    }
    final BroadcastReceiver bluetoothReceiver=new BroadcastReceiver() {
        public void onReceive(Context context,Intent intent) {
            if(!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int state=intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.ERROR);
            if(state==BluetoothAdapter.STATE_TURNING_OFF || state==BluetoothAdapter.STATE_OFF) invalidateTransport("Bluetooth switched off");
            if(state==BluetoothAdapter.STATE_ON) { invalidateTransport("Bluetooth is on"); retries.dueNow(); }
            refreshStatus();
        }
    };
    PowerManager.WakeLock newWakeLock(String suffix) {
        PowerManager manager=(PowerManager)getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wake=manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GalaxyCompanion:"+suffix);
        wake.setReferenceCounted(false); return wake;
    }
    boolean admitted(long epoch) {
        BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
        return alive && routeReady && listenerReady && epoch==transportEpoch.get() && !retries.paused() && adapter!=null && adapter.isEnabled();
    }
    void closeRouteSocket() {
        LocalSocket socket=routeSocket;
        if(socket!=null) try { socket.close(); } catch(IOException ignored) { }
    }
    void invalidateTransport(String reason) {
        transportEpoch.incrementAndGet(); routeReady=false; closeRouteSocket();
        VoiceSession current=voice; if(current!=null) current.stop();
        HfpConnection hfp=connection;
        // Abort a blocked connect promptly; full cleanup runs on the lifecycle worker.
        if(hfp!=null && hfp.socket!=null) try { hfp.socket.close(); } catch(IOException ignored) { }
        add(reason); refreshStatus();
    }
    void lifecycleTick() {
        if(!alive) return;
        try {
            BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
            boolean on=adapter!=null && adapter.isEnabled();
            if(!routeReady || retries.paused() || !on) {
                disconnectTransport();
                if(listenerReady) { closeListener(); listenerReady=false; }
            }
            if(!retries.allowed(on,SystemClock.elapsedRealtime())) { refreshStatus(); return; }
            if(!PIXEL_ADDRESS.equals(pixelAddress)) throw new IOException("Configured Pixel identity differs");
            if(adapter.getRemoteDevice(pixelAddress).getBondState()!=BluetoothDevice.BOND_BONDED)
                throw new IOException("Configured Pixel is not paired");
            if(!routeReady) {
                long epoch=transportEpoch.get();
                if(!verifyRootRoute(epoch)) throw new IOException("Root audio route unavailable");
                if(epoch!=transportEpoch.get() || retries.paused() || !adapter.isEnabled() || !alive) return;
                int result=listenVoice(adapter.getAddress());
                if(result!=0) throw new IOException("SCO listener bind failed");
                listenerReady=true; routeReady=true;
                if(epoch!=transportEpoch.get()) { routeReady=false; return; }
                add("Root audio route verified; SCO listener ready");
            }
            synchronized(connectionLock) {
                if(connection==null && alive && routeReady && !retries.paused()) {
                    connection=new HfpConnection(); new Thread(connection,"HFP control").start();
                }
            }
            refreshStatus();
        } catch(Exception failure) {
            routeReady=false; retries.failed(SystemClock.elapsedRealtime());
            add("Reconnect deferred: "+failure.getClass().getSimpleName()); refreshStatus();
        }
    }
    boolean verifyRootRoute(long epoch) {
        final LocalSocket socket=new LocalSocket(); routeSocket=socket;
        PowerManager.WakeLock wake=newWakeLock("Route setup"); wake.acquire(12000);
        ScheduledFuture<?> timeout=deadlines.schedule(new Runnable() { public void run() {
            try { socket.close(); } catch(IOException ignored) { }
        }},10,TimeUnit.SECONDS);
        try {
            socket.connect(new LocalSocketAddress("org.retrobridge.callprobe.route.v1",LocalSocketAddress.Namespace.ABSTRACT));
            if(socket.getPeerCredentials().getUid()!=0) throw new IOException("Route peer is not root");
            socket.setSoTimeout(10000);
            OutputStream output=socket.getOutputStream(); output.write("ROUTE\t1\n".getBytes("US-ASCII")); output.flush();
            InputStream input=socket.getInputStream(); ByteArrayOutputStream reply=new ByteArrayOutputStream(); int value;
            while((value=input.read())!=-1 && value!=10) {
                if(reply.size()>=32 || (value<32 && value!=9) || value>126) throw new IOException("Invalid route reply");
                reply.write(value);
            }
            return value==10 && "ROUTE\tOK".equals(reply.toString("US-ASCII")) && input.read()==-1 && epoch==transportEpoch.get();
        } catch(IOException failure) { return false; }
        finally {
            timeout.cancel(false); try { socket.close(); } catch(IOException ignored) { }
            if(routeSocket==socket) routeSocket=null;
            if(wake.isHeld()) wake.release();
        }
    }
    void disconnectTransport() {
        HfpConnection current;
        synchronized(connectionLock) { current=connection; connection=null; }
        if(current!=null) current.close();
        VoiceSession active=voice; if(active!=null) active.stop();
    }
    void manual(String operation,String argument) {
        if(!(operation.equals("ANSWER") || operation.equals("HANGUP") || (operation.equals("DIAL") && argument.matches("\\+?[0-9]{1,20}")))) return;
        HfpConnection current=connection;
        if(current==null || !current.ready || !admitted(current.epoch) || !stockAdmitted(current)) { add("Wait for the Pixel and stock Phone connection before call controls"); return; }
        current.request(operation,argument,new Completion() { public void finish(boolean ok) { add(ok?"Call control completed":"Call control failed or unsupported"); }});
    }
    void add(final String line) {
        if(!alive) return;
        main.post(new Runnable() { public void run() {
            report+=line+"\n";
            if(report.length()>24000) report=report.substring(report.indexOf('\n',report.length()-20000)+1);
            try { FileOutputStream file=openFileOutput("report.txt",MODE_PRIVATE); file.write(report.getBytes("UTF-8")); file.close(); } catch(IOException ignored) { }
            notifyCallbacks();
        }});
    }
    void refreshStatus() {
        if(!alive) return;
        main.post(new Runnable() { public void run() {
            BluetoothAdapter bt=BluetoothAdapter.getDefaultAdapter(); HfpConnection hfp=connection;
            statusText=retries.paused()?"Paused":bt==null?"Bluetooth unavailable":!bt.isEnabled()?"Bluetooth off":
                !routeReady?"Waiting for root audio route":hfp==null?"Pixel disconnected - retrying":!hfp.ready?"Connecting to Pixel":
                stockAdmitted(hfp)?"Pixel connected - stock Phone linked":"Pixel connected - waiting for stock Phone";
            updateNotification(); notifyCallbacks();
        }});
    }
    void notifyCallbacks() { for(Callback callback:new ArrayList<Callback>(callbacks)) callback.changed(statusText,report); }
    void updateNotification() {
        if(statusText.equals(notifiedStatus)) return; notifiedStatus=statusText;
        Intent open=new Intent(this,ProbeActivity.class);
        PendingIntent pending=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification=new Notification.Builder(this).setSmallIcon(R.drawable.service_notification)
            .setContentTitle("Galaxy Companion").setContentText(notificationGroup!=null&&notificationGroup.allBound()&&statusText.equals("Pixel connected - stock Phone linked")?"Calls, texts, internet & Telegram":statusText).setContentIntent(pending).setOngoing(true).getNotification();
        startForeground(42,notification);
    }
    boolean stockAdmitted(HfpConnection owner) {
        RootPeer peer=rootPeer;
        return peer!=null && peer.owner==owner && peer.hello && peer.activated && !peer.closed && !peer.ending;
    }

    interface Completion { void finish(boolean ok); }
    static class AtResponse {
        final String command;
        final List<String> lines = new ArrayList<String>();
        boolean done, ok;
        AtResponse(String value) { command=value; }
    }
    class HfpConnection implements Runnable {
        final Object responseLock = new Object(), stateLock = new Object(), refreshLock = new Object();
        final MicrophoneGate microphoneGate = new MicrophoneGate();
        final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(32));
        volatile BluetoothSocket socket;
        volatile String peerAddress;
        volatile boolean ready, closed;
        volatile boolean stockCallPresent;
        AtResponse pending;
        List<String> indicatorNames = Collections.emptyList();
        final Map<String,Integer> indicators = new HashMap<String,Integer>();
        List<HfpProtocol.Call> snapshot = Collections.emptyList();
        boolean refreshQueued, refreshDirty;
        volatile long carrierLosses;
        volatile Operation executing;
        final long epoch=transportEpoch.get();
        PowerManager.WakeLock setupWake;
        void releaseSetupWake() { if(setupWake!=null && setupWake.isHeld()) setupWake.release(); }
        public void run() {
            try {
                setupWake=newWakeLock("HFP setup"); setupWake.acquire(35000);
                BluetoothAdapter bt=BluetoothAdapter.getDefaultAdapter(); bt.cancelDiscovery();
                BluetoothDevice pixel=bt.getRemoteDevice(pixelAddress);
                if(pixel.getBondState()!=BluetoothDevice.BOND_BONDED) throw new IOException("Configured Pixel is not paired");
                peerAddress=pixel.getAddress();
                BluetoothSocket candidate=discoveredRfcomm(pixel);
                synchronized (this) {
                    if (closed || !admitted(epoch)) { candidate.close(); return; }
                    socket = candidate;
                }
                final ScheduledFuture<?> timeout=deadlines.schedule(new Runnable() { public void run() {
                    if (!ready && !closed) { add("Connection/setup timeout"); close(); }
                }}, 30, TimeUnit.SECONDS);
                add("Connecting to Pixel with live HFP discovery");
                candidate.connect();
                add("RFCOMM connected");
                worker.execute(new Runnable() { public void run() {
                    try {
                        String features=oneLine(transact("AT+BRSF=0"),"+BRSF:");
                        HfpProtocol.integer(features.substring(6).trim(),0,Integer.MAX_VALUE);
                        List<String> definitions=transact("AT+CIND=?");
                        List<String> names=HfpProtocol.indicatorNames(oneLine(definitions,"+CIND:"));
                        synchronized(stateLock) { indicatorNames=names; }
                        List<String> current=transact("AT+CIND?");
                        Map<String,Integer> values=HfpProtocol.indicatorValues(names,oneLine(current,"+CIND:"));
                        synchronized(stateLock) { indicators.clear(); indicators.putAll(values); updateCallPresence(); }
                        transact("AT+CMER=3,0,0,1");
                        queryCalls();
                        synchronized(stateLock) { if(closed || !admitted(epoch)) throw new IOException("Disconnected"); ready=true; }
                        timeout.cancel(false); releaseSetupWake(); retries.connected(); add("HFP READY: indicators and initial CLCC verified");
                        publishState(); refreshStatus(); scheduleRefresh();
                    } catch(Exception failure) { add("HFP setup failed: "+failure.getClass().getSimpleName()); close(); }
                }});
                readEvents(candidate.getInputStream());
            } catch (Exception error) {
                if (!closed) add("Connection result: " + error.getClass().getSimpleName());
            } finally {
                close(); releaseSetupWake();
                VoiceSession active;
                synchronized (connectionLock) {
                    if (connection == this) { connection = null; retries.failed(SystemClock.elapsedRealtime()); }
                    active = voice;
                }
                if (active != null && active.owner == this) active.stop();
                refreshStatus();
            }
        }
        BluetoothSocket discoveredRfcomm(BluetoothDevice pixel) throws Exception {
            // Query the bonded Pixel's exact HFP gateway record on every reconnect.
            // RFCOMM channel numbers can change whenever Pixel Bluetooth restarts.
            int channel=HfpDiscovery.channel();
            if(channel<1||channel>30)throw new IOException("HFP service discovery unavailable");
            add("Live HFP service channel: "+channel);
            return (BluetoothSocket)pixel.getClass().getMethod("createRfcommSocket",int.class).invoke(pixel,channel);
        }
        String oneLine(List<String> lines,String prefix) throws IOException {
            String found=null;
            for(String line:lines) if(line.startsWith(prefix)) {
                if(found!=null) throw new IOException("Duplicate response"); found=line;
            }
            if(found==null) throw new IOException("Missing response"); return found;
        }
        void readEvents(InputStream input) throws IOException {
            StringBuilder line=new StringBuilder(); int c;
            while(!closed && (c=input.read())>=0) {
                if(c==10 || c==13) {
                    if(line.length()==0) continue;
                    String event=line.toString().trim(); line.setLength(0); handleEvent(event);
                } else {
                    // Optional caller-name bytes can be non-ASCII. They remain
                    // unlogged and never enter the numeric stock IPC fields.
                    if(c<32 || line.length()>=2048) throw new IOException("Malformed HFP record");
                    line.append((char)c);
                }
            }
        }
        void handleEvent(String event) throws IOException {
            // Unsolicited events never wait for the FIFO worker or a command result.
            if(event.startsWith("+CIEV:")) {
                String[] fields=event.substring(6).trim().split(",",-1); String name;
                if(fields.length!=2) throw new IOException("Malformed indicator event");
                synchronized(stateLock) {
                    int index=HfpProtocol.integer(fields[0].trim(),1,indicatorNames.size());
                    name=indicatorNames.get(index-1);
                    indicators.put(name,HfpProtocol.indicatorValue(name,fields[1]));
                    updateCallPresence();
                }
                publishState();
                if(name.equals("call") || name.equals("callsetup") || name.equals("callheld")) scheduleRefresh();
                return;
            }
            if(event.equals("RING") || event.startsWith("+CLIP:") || event.startsWith("+CCWA:")) {
                add(event.equals("RING") ? "Incoming ring" : "Call identity event (redacted)");
                scheduleRefresh(); return;
            }
            if(event.equals("NO CARRIER")) {
                carrierLosses++;
                scheduleRefresh();
                synchronized(responseLock) {
                    if(pending!=null && (pending.command.startsWith("ATD") || pending.command.equals("ATA"))) {
                        pending.done=true; pending.ok=false; responseLock.notifyAll();
                    }
                }
                return;
            }
            synchronized(responseLock) {
                if(pending==null) return;
                if(event.equals("OK") || event.equals("ERROR") || event.startsWith("+CME ERROR:") ||
                    event.equals("BUSY") || event.equals("NO ANSWER")) {
                    pending.done=true; pending.ok=event.equals("OK"); responseLock.notifyAll();
                } else if((pending.command.equals("AT+CLCC") && event.startsWith("+CLCC:")) ||
                    (pending.command.startsWith("AT+CIND") && event.startsWith("+CIND:")) ||
                    (pending.command.startsWith("AT+BRSF") && event.startsWith("+BRSF:"))) {
                    if(pending.lines.size()>=32) throw new IOException("Excessive HFP response");
                    pending.lines.add(event);
                }
            }
        }
        List<String> transact(String command) throws IOException {
            final AtResponse response=new AtResponse(command);
            synchronized(responseLock) {
                if(closed || socket==null || pending!=null) throw new IOException("HFP unavailable");
                pending=response;
            }
            ScheduledFuture<?> timeout=deadlines.schedule(new Runnable() { public void run() {
                synchronized(responseLock) {
                    if(pending!=response || response.done) return;
                }
                add("HFP command timeout; closing uncertain session"); close();
            }},8,TimeUnit.SECONDS);
            try {
                add("S2: "+(command.startsWith("ATD") ? "ATD [number redacted];" :
                    command.startsWith("AT+VTS=") ? "AT+VTS=[digit redacted]" : command));
                OutputStream output=socket.getOutputStream();
                output.write((command+"\r").getBytes("US-ASCII")); output.flush();
                long until=SystemClock.elapsedRealtime()+8000;
                synchronized(responseLock) {
                    while(!response.done && !closed) {
                        long left=until-SystemClock.elapsedRealtime();
                        if(left<=0) break;
                        try { responseLock.wait(left); }
                        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted"); }
                    }
                    if(!response.done || closed) throw new IOException("HFP command incomplete");
                    if(!response.ok) throw new IOException("HFP command rejected");
                    return new ArrayList<String>(response.lines);
                }
            } finally {
                timeout.cancel(false);
                boolean incomplete;
                synchronized(responseLock) { incomplete=!response.done; if(pending==response) pending=null; }
                // Closing prevents a late OK from being attributed to a later command.
                if(incomplete) close();
            }
        }
        int indicator(String name) { Integer value=indicators.get(name); return value==null?0:value; }
        String stateFrame() {
            synchronized(stateLock) {
                return "STATE\t"+(ready&&!closed?1:0)+"\t"+indicator("service")+"\t"+
                    indicator("signal")+"\t"+indicator("roam")+"\n";
            }
        }
        void publishState() {
            RootPeer peer=rootPeer;
            if(peer!=null && peer.owner==this && peer.hello) peer.offer(stateFrame(),false);
        }
        boolean hasVoiceCalls(List<HfpProtocol.Call> calls) {
            for(HfpProtocol.Call call:calls) if(call.mode==0) return true;
            return false;
        }
        void updateCallPresence() {
            // Called with stateLock held. A volatile summary lets peer teardown
            // inspect call ownership without reversing the state/peer lock order.
            stockCallPresent=hasVoiceCalls(snapshot) || indicator("call")!=0 ||
                indicator("callsetup")!=0 || indicator("callheld")!=0;
        }
        void changeMicrophoneMute(boolean value) throws IOException {
            if(microphoneGate.setMuted(value)) add(value ? "Microphone PCM muted" : "Microphone PCM live");
        }
        List<HfpProtocol.Call> queryCalls() throws IOException {
            List<HfpProtocol.Call> fresh;
            try { fresh=HfpProtocol.calls(transact("AT+CLCC")); }
            catch(IOException failure) {
                RootPeer active=rootPeer;
                if(active!=null && active.owner==this && active.hello) {
                    add("Stock CLCC failed; closing bridge instead of retaining stale call state"); close();
                }
                throw failure;
            }
            synchronized(stateLock) { if(closed) throw new IOException("Disconnected"); snapshot=fresh; updateCallPresence(); }
            RootPeer peer=rootPeer;
            if(peer!=null && peer.owner==this && peer.hello) peer.offer(HfpProtocol.callFrame(fresh),false);
            add("Complete CLCC snapshot: "+fresh.size()+" call(s)"); return fresh;
        }
        void scheduleRefresh() {
            synchronized(refreshLock) {
                refreshDirty=true;
                if(!ready || closed || refreshQueued) return;
                refreshQueued=true;
            }
            try { worker.execute(new Runnable() { public void run() {
                synchronized(refreshLock) { refreshDirty=false; }
                try { if(ready && !closed) queryCalls(); }
                catch(IOException failure) { add(closed ? "CLCC refresh ended with session" :
                    "Diagnostic CLCC refresh failed; previous complete snapshot retained"); }
                finally {
                    boolean again;
                    synchronized(refreshLock) { refreshQueued=false; again=refreshDirty; }
                    if(again) scheduleRefresh();
                }
            }}); } catch(RejectedExecutionException e) {
                synchronized(refreshLock) { refreshQueued=false; }
                if(!closed) close();
            }
        }
        void request(String operation,String argument,Completion completion) {
            request(operation,argument,completion,null);
        }
        void request(String operation,String argument,Completion completion,RootPeer source) {
            Operation task=new Operation(operation,argument,completion,source);
            if(closed || !ready) { task.finish(false); return; }
            task.timer=deadlines.schedule(new Runnable() { public void run() {
                task.expired=true; task.finish(false);
                if(executing==task) close();
            }},20,TimeUnit.SECONDS);
            try { worker.execute(task); }
            catch(RejectedExecutionException e) { task.finish(false); }
        }
        class Operation implements Runnable {
            final String operation, argument; final Completion completion;
            final RootPeer source;
            volatile boolean expired; boolean completed;
            volatile ScheduledFuture<?> timer;
            Operation(String op,String arg,Completion done,RootPeer peer) { operation=op; argument=arg; completion=done; source=peer; }
            void finish(boolean ok) {
                synchronized(this) {
                    if(completed) return; completed=true;
                    if(timer!=null) timer.cancel(false);
                }
                // Completion can close a backpressured peer. Never hold the
                // operation monitor while entering that peer's completion lock.
                completion.finish(ok);
            }
            synchronized boolean cancelForPeerLoss() {
                if(completed) return false;
                expired=true; return true;
            }
            void check() throws IOException {
                if(expired || closed || !ready || (source!=null && (source.closed || source.ending)))
                    throw new IOException("Expired operation");
            }
            public void run() {
                executing=this;
                try {
                    check();
                    if(operation.equals("QUERY")) { queryCalls(); check(); finish(true); return; }
                    List<HfpProtocol.Call> before=queryCalls(); check();
                    int count=0; HfpProtocol.Call selected=null;
                    for(HfpProtocol.Call c:before) if(c.mode==0) { count++; selected=c; }
                    String command;
                    if(operation.equals("DIAL")) {
                        if(!HfpProtocol.safeNumber(argument).equals(argument) || argument.isEmpty() || count!=0)
                            throw new IOException("Invalid dial or call already exists");
                        synchronized(stateLock) {
                            if(indicator("call")!=0 || indicator("callsetup")!=0 || indicator("callheld")!=0)
                                throw new IOException("Call state busy");
                        }
                        command="ATD"+argument+";";
                    } else if(operation.equals("ANSWER")) {
                        if(count!=1 || selected.state!=4 || selected.multiparty!=0) throw new IOException("Unsupported answer state");
                        command="ATA";
                    } else if(operation.equals("HANGUP")) {
                        if(count!=1 || selected.state==6 || selected.multiparty!=0) throw new IOException("Unsupported hangup state");
                        command="AT+CHUP";
                    } else if(operation.equals("DTMF")) {
                        if(count!=1 || selected.state!=0 || selected.multiparty!=0 || !argument.matches("[0-9*#]"))
                            throw new IOException("Invalid DTMF state");
                        command="AT+VTS="+argument;
                    } else throw new IOException("Unknown operation");
                    long carrierBefore=carrierLosses;
                    check(); transact(command); check();
                    List<HfpProtocol.Call> after=queryCalls(); check();
                    if(source!=null && operation.equals("DIAL")) {
                        long until=SystemClock.elapsedRealtime()+3000;
                        while(!hasOutgoing(after)) {
                            if(carrierLosses!=carrierBefore || SystemClock.elapsedRealtime()>=until)
                                throw new IOException("No outgoing call appeared");
                            try { Thread.sleep(150); }
                            catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted"); }
                            check(); after=queryCalls(); check();
                        }
                    }
                    finish(true);
                } catch(IOException failure) { finish(false); }
                finally { executing=null; }
            }
            boolean hasOutgoing(List<HfpProtocol.Call> calls) {
                int voiceCalls=0; boolean outgoing=false;
                for(HfpProtocol.Call call:calls) if(call.mode==0) {
                    voiceCalls++;
                    outgoing=call.direction==0 && call.state<=3 && call.multiparty==0;
                }
                return voiceCalls==1 && outgoing;
            }
        }
        void close() {
            synchronized(this) { if(closed) return; closed=true; }
            VoiceSession audio=voice;
            if(audio!=null && audio.owner==this) audio.stop();
            try { changeMicrophoneMute(false); }
            catch(IOException failure) { add("Disconnected microphone gate reset unavailable; audio stopped"); }
            synchronized(stateLock) { ready=false; snapshot=Collections.emptyList(); stockCallPresent=false; }
            synchronized(responseLock) { responseLock.notifyAll(); }
            try { if(socket!=null) socket.close(); } catch(IOException ignored) { }
            for(Runnable task:worker.shutdownNow()) if(task instanceof Operation) ((Operation)task).finish(false);
            Operation active=executing; if(active!=null) active.finish(false);
            RootPeer peer=rootPeer; if(peer!=null && peer.owner==this) peer.hfpFailed();
        }
    }

    void startStockServer() {
        new Thread(new Runnable() { public void run() {
          int retry=2000;
          while(alive) {
            LocalServerSocket server=null;
            try {
                server=new LocalServerSocket("org.retrobridge.callprobe.stockril.v1");
                stockServer=server;
                if(!alive) return;
                retry=2000;
                add("Stock bridge socket listening (root peer only)");
                while(alive) {
                    LocalSocket socket=server.accept();
                    try {
                        if(socket.getPeerCredentials().getUid()!=0) { socket.close(); continue; }
                        synchronized(peerLock) {
                            if(rootPeer!=null) { socket.close(); continue; }
                            rootPeer=new RootPeer(socket); rootPeer.start();
                        }
                    } catch(IOException failure) { try { socket.close(); } catch(IOException ignored) { } }
                }
            } catch(IOException failure) { if(alive) add("Stock bridge listener failed: "+failure.getClass().getSimpleName()); }
            finally {
                if(server!=null) try { server.close(); } catch(IOException ignored) { }
                if(stockServer==server) stockServer=null;
            }
            if(alive) { SystemClock.sleep(retry); retry=Math.min(30000,retry*2); }
          }
        }},"Stock root accept").start();
    }
    static class Frame {
        final String text; final boolean terminal;
        Frame(String value,boolean last) { text=value; terminal=last; }
    }
    class RootPeer implements Runnable {
        final LocalSocket socket;
        final ArrayBlockingQueue<Frame> output=new ArrayBlockingQueue<Frame>(64);
        final Set<Long> pending=new HashSet<Long>();
        volatile boolean closed, hello, ending, baselineMute, activated;
        volatile HfpConnection owner;
        Thread writer;
        RootPeer(LocalSocket value) { socket=value; }
        void start() {
            writer=new Thread(new Runnable() { public void run() { writeFrames(); }},"Stock root writer");
            writer.start(); new Thread(this,"Stock root reader").start();
        }
        void offer(String text,boolean terminal) {
            if(closed || (!terminal && ending)) return;
            if(!output.offer(new Frame(text,terminal))) close();
        }
        void writeFrames() {
            try {
                OutputStream stream=socket.getOutputStream();
                while(!closed) {
                    Frame frame=output.take();
                    ScheduledFuture<?> timeout=deadlines.schedule(new Runnable() { public void run() { close(); }},3,TimeUnit.SECONDS);
                    try { stream.write(frame.text.getBytes("UTF-8")); stream.flush(); }
                    finally { timeout.cancel(false); }
                    if(frame.terminal) return;
                }
            } catch(Exception ignored) { }
            finally { close(); }
        }
        String readLine(InputStream input) throws IOException {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); int c;
            while((c=input.read())>=0) {
                if(c==10) return bytes.toString("UTF-8");
                // Protocol inputs are an ASCII subset of UTF-8; reject hidden control bytes.
                if((c<32 && c!=9) || c>126 || bytes.size()>=1024) throw new IOException("Invalid IPC record");
                bytes.write(c);
            }
            if(bytes.size()!=0) throw new IOException("Truncated IPC record"); return null;
        }
        public void run() {
            try {
                socket.setSoTimeout(5000); InputStream input=socket.getInputStream();
                if(!"HELLO\t1".equals(readLine(input))) throw new IOException("Invalid greeting");
                HfpConnection current=connection;
                if(current==null) { offer("STATE\t0\t0\t0\t0\nCALLS\t0\n",true); ending=true; return; }
                synchronized(current.stateLock) {
                    if(!current.ready || current.closed || !admitted(current.epoch)) { offer("STATE\t0\t0\t0\t0\nCALLS\t0\n",true); ending=true; return; }
                    // A cached empty snapshot is not a fresh idle proof. Preserve
                    // the gate on HELLO; the native fresh QUERY then explicit
                    // MUTE0 preflight establishes its acknowledged idle baseline.
                    owner=current; hello=true;
                    offer(current.stateFrame()+HfpProtocol.callFrame(current.snapshot),false);
                }
                add("Root stock bridge peer authenticated and ready"); socket.setSoTimeout(0);
                String line;
                while(!closed && !ending && (line=readLine(input))!=null) receive(line);
            } catch(IOException failure) { if(!closed && !ending) add("Stock bridge peer closed or invalid"); }
            finally { if(!ending) close(); }
        }
        void receive(String line) throws IOException {
            String[] fields=line.split("\t",-1);
            if(fields.length<3 || !fields[0].equals("REQUEST")) throw new IOException("Invalid request");
            final long id=HfpProtocol.integer(fields[1],1,Integer.MAX_VALUE);
            synchronized(this) {
                if(pending.contains(id) || pending.size()>=16) throw new IOException("Duplicate or excessive request");
                pending.add(id);
            }
            String op=fields[2], arg=fields.length==4?fields[3]:"";
            boolean valid=(op.equals("QUERY") || op.equals("ANSWER") || op.equals("HANGUP")) ? fields.length==3 :
                op.equals("DIAL") ? fields.length==4 && !arg.isEmpty() && HfpProtocol.safeNumber(arg).equals(arg) :
                op.equals("DTMF") ? fields.length==4 && arg.matches("[0-9*#]") :
                op.equals("MUTE") && fields.length==4 && (arg.equals("0") || arg.equals("1"));
            if(!valid || owner==null || !owner.ready || owner.closed || !admitted(owner.epoch)) {
                result(id,false);
                if(op.equals("MUTE") && owner!=null) owner.close();
                return;
            }
            if(op.equals("MUTE")) { applyLocalMute(id,arg.equals("1")); return; }
            final String operation=op;
            owner.request(op,arg,new Completion() { public void finish(boolean ok) {
                HfpConnection current=owner;
                if(ok && operation.equals("QUERY") && baselineMute && current!=null && !closed && !ending) {
                    synchronized(current.stateLock) {
                        // Use the same successful CLCC snapshot as the native
                        // preflight. A later incoming CIEV is a new call, not a
                        // reason to permanently withhold the established session.
                        if(!current.hasVoiceCalls(current.snapshot) && current.ready && !current.closed && admitted(current.epoch)) activated=true;
                    }
                }
                result(id,ok);
            }},this);
        }
        void applyLocalMute(long id,boolean value) {
            HfpConnection current=owner;
            try {
                if(closed || ending || !hello || current==null || !current.ready || current.closed)
                    throw new IOException("Mute peer unavailable");
                // Local PCM gating must not wait behind AT/CLCC commands. This
                // single IPC reader preserves local request order; the fair gate
                // lock waits at most 1.5 seconds for an in-flight packet write.
                current.changeMicrophoneMute(value);
                if(closed || ending || owner!=current || !current.ready || current.closed)
                    throw new IOException("Mute session ended");
                if(!activated) baselineMute=!value;
                result(id,true);
            } catch(IOException failure) {
                result(id,false);
                if(current!=null) current.close();
            }
        }
        synchronized void result(long id,boolean ok) {
            if(pending.remove(id)) offer("RESULT\t"+id+"\t"+(ok?"OK":"ERROR")+"\n",false);
        }
        synchronized void hfpFailed() {
            if(closed || ending) return;
            StringBuilder end=new StringBuilder();
            for(Long id:pending) end.append("RESULT\t").append(id).append("\tERROR\n");
            pending.clear(); end.append("STATE\t0\t0\t0\t0\nCALLS\t0\n");
            ending=true; offer(end.toString(),true);
        }
        void close() {
            synchronized(this) { if(closed) return; closed=true; }
            try { socket.close(); } catch(IOException ignored) { }
            if(writer!=null) writer.interrupt();
            synchronized(peerLock) { if(rootPeer==this) rootPeer=null; }
            HfpConnection current=owner;
            if(hello && current!=null && !current.closed) invalidateTransport("Root control link lost");
            if(current!=null) {
                VoiceSession audio=voice;
                boolean ownedAudio=audio!=null && audio.owner==current && audio.stockPeer==this && audio.running;
                if(ownedAudio || current.stockCallPresent) {
                    // Losing the stock control path must not leave the S2's
                    // microphone transmitting as an invisible diagnostic call.
                    current.close(); return;
                }
                HfpConnection.Operation active=current.executing;
                if(active!=null && active.source==this && active.cancelForPeerLoss()) current.close();
            }
        }
    }
    void listenAudio() {
        new Thread(new Runnable() { public void run() {
            while (alive) {
                HfpConnection expectedOwner=connection;
                if(expectedOwner==null || !expectedOwner.ready || expectedOwner.closed || expectedOwner.peerAddress==null || !admitted(expectedOwner.epoch) || !listenerReady || !stockAdmitted(expectedOwner)) {
                    // Do not spin or attach incoming audio before HFP setup has
                    // established this session's owner and complete call state.
                    SystemClock.sleep(200); continue;
                }
                int fd = acceptVoice(expectedOwner.peerAddress);
                if (fd == -11 || fd == -4 || fd == -103) continue;
                if (fd == -13 || fd == -90 || fd == -97) { add(voicePeerDiagnostic()); continue; }
                if (fd < 0) { if (alive) { add("SCO accept: " + fd); invalidateTransport("SCO listener needs renewal"); } continue; }
                add(voicePeerDiagnostic());
                VoiceSession session;
                synchronized (connectionLock) {
                    // HFP may have disconnected/reconnected during the one-second
                    // native accept. The address alone cannot identify a generation.
                    if(!alive || connection!=expectedOwner || !expectedOwner.ready || expectedOwner.closed || !admitted(expectedOwner.epoch) || !stockAdmitted(expectedOwner)) {
                        stopVoice(fd); releaseVoice(fd); continue;
                    }
                    session = new VoiceSession(fd, expectedOwner);
                    voice = session;
                }
                if (!alive) session.stop(); session.run();
                if (voice == session) voice = null;
            }
        }}, "SCO listener").start();
    }
    class VoiceSession implements Runnable {
        final int fd;
        final HfpConnection owner;
        final RootPeer stockPeer;
        volatile boolean running = true;
        boolean released, stockAudio;
        long nextRouteCheck, nextWakeRenewal;
        PowerManager.WakeLock audioWake;
        AudioRecord recorder; AudioTrack earpiece; Thread microphone, capture;
        MicrophoneBuffer microphoneBuffer;
        int microphonePacketBytes;
        volatile long maxCaptureNanos;
        final MicrophoneGate.FrameWriter microphoneWriter = new MicrophoneGate.FrameWriter() {
            public int write(byte[] pcm,int count) {
                if(!running || !alive || owner.closed || !routeReady || owner.epoch!=transportEpoch.get() || !stockAdmitted(owner)) return -32;
                return writeVoice(fd,pcm,count);
            }
        };
        VoiceSession(int descriptor, HfpConnection control) {
            fd=descriptor; owner=control;
            RootPeer peer=rootPeer;
            stockPeer=peer!=null && peer.owner==control && peer.hello && !peer.closed && !peer.ending ? peer : null;
        }
        synchronized void stop() {
            running = false; if (!released) stopVoice(fd);
        }
        boolean hasStockPeer() {
            RootPeer peer=rootPeer;
            return peer!=null && peer.owner==owner && peer.hello && !peer.closed && !peer.ending;
        }
        void setRealCallOff() throws Exception {
            AudioManager.class.getMethod("setParameters",String.class).invoke(audioManager,"realcall=off");
        }
        void maintainStockAudio() throws Exception {
            long now=SystemClock.elapsedRealtime();
            if(running && alive && audioWake!=null && now>=nextWakeRenewal) { audioWake.acquire(120000); nextWakeRenewal=now+60000; }
            if(now<nextRouteCheck || !running || !hasStockPeer()) return;
            nextRouteCheck=now+250;
            int mode=audioManager.getMode();
            if(!stockAudio || mode!=AudioManager.MODE_IN_COMMUNICATION) {
                stockAudio=true;
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                setRealCallOff();
                add("Stock SCO audio mode "+mode+" -> "+audioManager.getMode()+", realcall off");
            }
        }
        public void run() {
            boolean previousSpeaker = audioManager.isSpeakerphoneOn();
            boolean changedSpeaker = false;
            try {
                if (!running || owner.closed || !admitted(owner.epoch) || !stockAdmitted(owner)) return;
                audioWake=newWakeLock("SCO audio"); audioWake.acquire(120000); nextWakeRenewal=SystemClock.elapsedRealtime()+60000;
                add("SCO VOICE LINK CONNECTED");
                boolean stock=hasStockPeer();
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                if(stock) maintainStockAudio();
                else { audioManager.setSpeakerphoneOn(false); changedSpeaker=true; }
                int outSize = Math.max(4096, AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT));
                earpiece = new AudioTrack(AudioManager.STREAM_VOICE_CALL, 8000, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, outSize, AudioTrack.MODE_STREAM);
                if (earpiece.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("Earpiece unavailable");
                earpiece.play();
                int inSize = Math.max(8192, AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 8000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, inSize);
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone unavailable");
                microphonePacketBytes=HfpDiscovery.voiceMtu(fd);
                if(microphonePacketBytes<2)throw new IOException("SCO packet size unavailable: "+microphonePacketBytes);
                int target=(2560+microphonePacketBytes-1)/microphonePacketBytes;
                int capacity=(6400+microphonePacketBytes-1)/microphonePacketBytes;
                microphoneBuffer=new MicrophoneBuffer(microphonePacketBytes,target,capacity);
                add("Mic buffered mtu="+microphonePacketBytes+" target_ms="+(target*microphonePacketBytes/16)+" capacity_ms="+(capacity*microphonePacketBytes/16));
                recorder.startRecording();
                capture=new Thread(new Runnable(){public void run(){captureMicrophone();}},"SCO capture");
                capture.start();
                microphone = new Thread(new Runnable() { public void run() { pumpMicrophone(); }}, "SCO microphone");
                microphone.start();
                byte[] buffer = new byte[1024];
                long received = 0, started = System.currentTimeMillis(); boolean warned = false;
                while (running && alive) {
                    maintainStockAudio();
                    int count = readVoice(fd, buffer);
                    if (count == -11 || count == -4) {
                        if (!warned && received == 0 && System.currentTimeMillis() - started > 7000) {
                            add("Voice link open, no incoming packets after 7 seconds"); warned = true;
                        }
                        continue;
                    }
                    if (count <= 0) break;
                    if (received == 0) add("First incoming voice packet: " + count + " bytes");
                    received += count; int offset = 0;
                    while (running && offset < count) {
                        int written = earpiece.write(buffer, offset, count - offset);
                        if (written <= 0) throw new IOException("Earpiece write failed");
                        offset += written;
                    }
                }
                add("Voice bytes received: " + received);
            } catch (Exception failure) {
                add("Audio result: " + failure.getClass().getSimpleName());
            } finally {
                stop();
                if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) { } }
                // Never recycle this fd while the microphone thread could still use it.
                if (microphone != null) {
                    boolean joined = false;
                    while (!joined) { try { microphone.join(); joined = true; } catch (InterruptedException ignored) { } }
                }
                if(capture!=null) {
                    boolean joined=false;
                    while(!joined){try{capture.join();joined=true;}catch(InterruptedException ignored){}}
                }
                if (recorder != null) recorder.release();
                if (earpiece != null) { try { earpiece.stop(); } catch (Exception ignored) { } earpiece.release(); }
                synchronized (this) { released = true; releaseVoice(fd); }
                // The stock Phone owns its current speaker toggle. Do not replace
                // a user's newer choice with the value captured at SCO accept.
                if(changedSpeaker && !stockAudio) audioManager.setSpeakerphoneOn(previousSpeaker);
                // MODE_NORMAL releases this PID's mode request; restoring Phone's
                // captured IN_CALL/RINGTONE mode would leave a stale app-owned entry.
                if(stockAudio) {
                    try { setRealCallOff(); }
                    catch(Exception failure) { add("Stock audio cleanup failed: "+failure.getClass().getSimpleName()); }
                }
                audioManager.setMode(AudioManager.MODE_NORMAL);
                if(audioWake!=null && audioWake.isHeld()) audioWake.release();
                add("Voice session closed");
            }
        }
        void audioPriority() {
            try{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);}
            catch(RuntimeException failure){add("Mic audio priority unavailable: "+failure.getClass().getSimpleName());}
        }
        void captureMicrophone() {
            audioPriority();byte[] frame=new byte[microphonePacketBytes];
            try {
                while(running && alive) {
                    long generation=owner.microphoneGate.generation(),before=System.nanoTime();
                    int used=0;
                    while(used<frame.length && running && alive) {
                        int count=recorder.read(frame,used,frame.length-used);
                        if(count<=0){if(running)add("Mic capture ended: "+count);return;}
                        used+=count;
                    }
                    maxCaptureNanos=Math.max(maxCaptureNanos,System.nanoTime()-before);
                    if(used==frame.length && running && alive) {
                        if(generation!=owner.microphoneGate.generation())generation=-1;
                        microphoneBuffer.offer(frame,generation);
                    }
                }
            }catch(RuntimeException failure){if(running)add("Mic capture failure: "+failure.getClass().getSimpleName());}
            finally{stop();}
        }
        void pumpMicrophone() {
            audioPriority();byte[] buffer = new byte[microphonePacketBytes]; long sent = 0;
            long started=System.nanoTime(),lastWrite=0,nextReport=started+10000000000L,maxWrite=0,maxGap=0,retries=0,underruns=0;
            MicrophonePacer pacer=null;
            try {
                while(running && alive && !microphoneBuffer.awaitPrimed()){}
                pacer=new MicrophonePacer(buffer.length,System.nanoTime());
                while (running && alive) {
                    long remaining;
                    while(running && alive && (remaining=pacer.remaining(System.nanoTime()))>0)
                        java.util.concurrent.locks.LockSupport.parkNanos(remaining);
                    if(!running || !alive)break;
                    long generation=microphoneBuffer.take(buffer);
                    if(generation==MicrophoneBuffer.EMPTY){java.util.Arrays.fill(buffer,(byte)0);underruns++;}
                    long before=System.nanoTime();int count=buffer.length;
                    int written;
                    do { written = owner.microphoneGate.write(buffer,count,generation,microphoneWriter);if(written==-11||written==-4)retries++; }
                    while ((written == -11 || written == -4) && running && alive);
                    long now=System.nanoTime();maxWrite=Math.max(maxWrite,now-before);
                    if(lastWrite!=0)maxGap=Math.max(maxGap,now-lastWrite);lastWrite=now;pacer.sent(now);
                    if (written != count) { if (running) add("Voice write ended: " + written); break; }
                    sent += written;
                    if(now>=nextReport){add("Mic timing ms="+((now-started)/1000000)+" bytes="+sent+" max_read_us="+(maxCaptureNanos/1000)+" max_write_us="+(maxWrite/1000)+" max_gap_us="+(maxGap/1000)+" retries="+retries+" empty="+underruns+" drop="+microphoneBuffer.dropped()+" queued="+microphoneBuffer.depth()+" resets="+pacer.resets);nextReport=now+10000000000L;}
                }
            } catch(IOException failure) {
                add("Microphone gate failed; closing voice session"); owner.close();
            } catch(InterruptedException failure){Thread.currentThread().interrupt();}
            finally { stop(); add("Voice bytes sent: " + sent);add("Mic final ms="+((System.nanoTime()-started)/1000000)+" bytes="+sent+" max_read_us="+(maxCaptureNanos/1000)+" max_write_us="+(maxWrite/1000)+" max_gap_us="+(maxGap/1000)+" retries="+retries+" empty="+underruns+" drop="+microphoneBuffer.dropped()+" resets="+(pacer==null?0:pacer.resets)); }
        }
    }
    public void onDestroy() {
        if(notificationGroup!=null)notificationGroup.close();
        alive=false; transportEpoch.incrementAndGet(); routeReady=false;
        if(active==this) active=null;
        stopService(new Intent(this,SmsService.class));
        closeRouteSocket(); disconnectTransport(); closeListener(); listenerReady=false;
        RootPeer peer=rootPeer; if(peer!=null) peer.close();
        try { if(stockServer!=null) stockServer.close(); } catch(IOException ignored) { }
        try { unregisterReceiver(bluetoothReceiver); } catch(IllegalArgumentException ignored) { }
        lifecycle.shutdownNow(); deadlines.shutdownNow(); main.removeCallbacksAndMessages(null);
        callbacks.clear(); stopForeground(true); super.onDestroy();
    }
}
