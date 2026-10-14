package org.retrobridge.galaxychat.s2;



import android.app.*;

import android.bluetooth.*;

import android.content.*;

import android.os.*;

import org.retrobridge.galaxychat.protocol.Wire;

import java.io.*;

import java.util.*;

import java.util.concurrent.ArrayBlockingQueue;

import java.util.concurrent.Semaphore;

import org.json.*;



/** Persistent Bluetooth messaging connection, independent of the call bridge. */

public final class ChatService extends Service {

    public interface Observer { void changed(); }

    private interface Reply { void done(JSONObject result,String error) throws IOException; }

    public final class LocalBinder extends Binder { ChatService service(){return ChatService.this;} }

    private final IBinder binder=new LocalBinder();

    private final Handler ui=new Handler();

    private final Object lock=new Object();

    private final ArrayList<Observer> observers=new ArrayList<Observer>();

    private ChatStore store;

    MediaClient media;
    ReactionIcons reactionIcons;

    private BluetoothAdapter adapter;

    private PowerManager.WakeLock setupWake;

    private AlarmManager alarms;

    private PendingIntent heartbeat;

    private volatile Peer peer;

    private volatile boolean wanted,destroyed;

    private volatile String summary="Paused — cached chats available",openChat="",epoch="";

    private volatile JSONObject serverStatus=Wire.json("auth","SETUP_REQUIRED","connection","Offline","send_enabled",false,"notice","");

    private long eventSeq,lastTyping;

    private volatile long connectAfter;

    private int retry;

    private boolean connecting;

    private volatile boolean foreground,viewing,archived,resyncing;

    private String cursor="",before="0";

    private int chatPageGeneration,historyGeneration;

    private boolean moreChats,moreHistory;

    private volatile int discoveredChannel;

    private volatile long attempts,authenticated,heartbeats,lastHeartbeat,lastConnected,lastFailure;

    private volatile String lastStage="IDLE",lastError="NONE";



    private final Runnable notificationMode=new Runnable(){public void run(){if(wanted&&!destroyed)notification();}};

    public void onCreate() {

        super.onCreate();NotificationLinkService.attach(notificationMode);store=new ChatStore(this);store.recover();media=new MediaClient(this);reactionIcons=new ReactionIcons(this);importPairKey();adapter=BluetoothAdapter.getDefaultAdapter();

        setupWake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GalaxyChat:Setup");setupWake.setReferenceCounted(false);

        alarms=(AlarmManager)getSystemService(ALARM_SERVICE);heartbeat=PendingIntent.getService(this,0,new Intent(this,ChatService.class).setAction("org.retrobridge.galaxychat.s2.HEARTBEAT"),PendingIntent.FLAG_UPDATE_CURRENT);

        registerReceiver(bluetooth,new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));ui.post(watchdog);

    }

    public int onStartCommand(Intent i,int flags,int id) {

        if(!autoConnect()||!pairedKey()){stopSession();return START_NOT_STICKY;}

        if(i!=null&&"BOOT".equals(i.getAction()))connectAfter=Math.max(connectAfter,now()+30000);

        resumeSession();

        if(i!=null&&"org.retrobridge.galaxychat.s2.HEARTBEAT".equals(i.getAction())){

            heartbeats++;lastHeartbeat=now();

            String action=ClientRules.heartbeatAction(wanted,adapter!=null&&adapter.isEnabled(),connected());

            if("STATUS".equals(action)){setupWake.acquire(35000);heartbeatStatus();}

            else if("CONNECT".equals(action))attempt();

            scheduleHeartbeat();

        }

        return START_STICKY;

    }

    public IBinder onBind(Intent i){return binder;}

    protected void dump(FileDescriptor fd,PrintWriter out,String[] args){

        Peer p=peer;boolean active; synchronized(lock){active=connecting;}

        out.println("auto_connect="+autoConnect());out.println("manual_session="+wanted);out.println("connecting="+active);out.println("ready="+connected());out.println("destroyed="+destroyed);out.println("foreground="+foreground);

        out.println("notification_coordinator_bound="+NotificationLinkService.managed());
        out.println("stage="+(p==null?lastStage:p.stage));out.println("close_stage="+(p==null?"NO_PEER":p.closeStage));out.println("last_error="+lastError);

        out.println("discovered_channel="+discoveredChannel);out.println("attempts="+attempts);out.println("authenticated="+authenticated);out.println("heartbeats="+heartbeats);

        out.println("heartbeat_age_ms="+(lastHeartbeat==0?-1:now()-lastHeartbeat));out.println("connected_age_ms="+(lastConnected==0?-1:now()-lastConnected));out.println("failure_age_ms="+(lastFailure==0?-1:now()-lastFailure));

        out.println("deadline_remaining_ms="+(p==null?-1:p.deadline-now()));out.println("wake_held="+(setupWake!=null&&setupWake.isHeld()));

        int pending=0,queued=0;if(p!=null){synchronized(p.pending){pending=p.pending.size();}queued=p.queue.size();}out.println("pending="+pending);out.println("queued="+queued);

    }

    public void onDestroy(){destroyed=true;NotificationLinkService.detach(notificationMode);if(media!=null)media.close();if(reactionIcons!=null)reactionIcons.close();stopSession();ui.removeCallbacksAndMessages(null);unregisterReceiver(bluetooth);if(setupWake.isHeld())setupWake.release();store.close();super.onDestroy();}

    private final BroadcastReceiver bluetooth=new BroadcastReceiver(){public void onReceive(Context c,Intent i){

        int state=i.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1);

        if(state==BluetoothAdapter.STATE_ON){connectAfter=Math.max(connectAfter,now()+10000);if(wanted){summary="Waiting for Pixel…";scheduleHeartbeat();scheduleAttempt(10000);changed();}}

        else if(state==BluetoothAdapter.STATE_OFF||state==BluetoothAdapter.STATE_TURNING_OFF){Peer p=peer;if(p!=null)p.close();alarms.cancel(heartbeat);summary="Waiting for Bluetooth…";changed();}

    }};

    public void observe(Observer o){synchronized(observers){if(!observers.contains(o))observers.add(o);}}

    public void unobserve(Observer o){synchronized(observers){observers.remove(o);}}

    private void changed(){ui.post(new Runnable(){public void run(){ArrayList<Observer> a;synchronized(observers){a=new ArrayList<Observer>(observers);}for(Observer o:a)o.changed();if(wanted&&!destroyed)notification();}});}

    public String status(){return summary;}

    public boolean connected(){Peer p=peer;return p!=null&&p.ready&&!p.closed;}

    public boolean sessionStarted(){return wanted;}

    public boolean needsPixelSetup(){return connected()&&!"READY".equals(serverStatus.optString("auth"));}

    public boolean canSend(String chat){JSONObject c=store.chat(chat);return connected()&&"READY".equals(serverStatus.optString("auth"))&&serverStatus.optBoolean("send_enabled")&&c!=null&&c.optBoolean("can_send");}

    public boolean pairedKey(){return getSharedPreferences("pair",0).contains("key");}

    public void pairingKey(String key)throws IOException{Wire.token(key);if(wanted)throw new IOException("Disconnect before changing the pairing key");if(!getSharedPreferences("pair",0).edit().putString("key",key).commit())throw new IOException("Pairing key could not be saved");}

    private void importPairKey(){

        File f=new File(getFilesDir(),"pair-key.txt");if(!f.isFile())return;

        try{if(f.length()!=44&&f.length()!=45)throw new IOException("Invalid bootstrap");byte[] b=new byte[(int)f.length()];FileInputStream in=new FileInputStream(f);try{new DataInputStream(in).readFully(b);if(in.read()!=-1)throw new IOException("Changed bootstrap");}finally{in.close();}

            if(b.length==45&&b[44]!=10)throw new IOException("Invalid bootstrap");String key=new String(b,0,44,"US-ASCII");Wire.token(key);String old=getSharedPreferences("pair",0).getString("key","");if(old.length()>0&&!old.equals(key))throw new IOException("Existing key differs");

            if(!getSharedPreferences("pair",0).edit().putString("key",key).commit())throw new IOException("Commit failed");if(!f.delete())summary="Pair key saved; private bootstrap cleanup pending";

        }catch(Exception e){summary="Private pairing key could not be imported";}

    }

    public ArrayList<JSONObject> chats(boolean archive){return store.chats(archive);}

    public ArrayList<JSONObject> messages(String chat){return store.messages(chat);}

    public ArrayList<JSONObject> operations(String chat){return store.operations(chat);}

    public JSONObject chat(String id){return store.chat(id);}

    public String[] draft(String chat){return store.draft(chat);}

    public void draft(String chat,String text,String reply){store.draft(chat,text,reply);}

    public void clearCache(){store.clearDisplayCache();cursor="";before="0";changed();if(connected())refresh();}

    public boolean hasMore(){return openChat.length()==0?moreChats:moreHistory;}

    private boolean autoConnect(){return getSharedPreferences("connection",0).getBoolean("enabled",true);}

    public void connect() throws IOException {

        if(adapter==null)throw new IOException("Bluetooth unavailable");

        if(!pairedKey())throw new IOException("Enter the pairing key shown by Telegram Companion on the Pixel first");

        if(!getSharedPreferences("connection",0).edit().putBoolean("enabled",true).commit())throw new IOException("Connection setting could not be saved");

        startService(new Intent(this,ChatService.class));resumeSession();

    }

    private void resumeSession(){

        synchronized(lock){if(wanted)return;wanted=true;retry=0;}

        summary=adapter!=null&&adapter.isEnabled()?"Waiting for Pixel…":"Waiting for Bluetooth…";

        notification();scheduleHeartbeat();

        scheduleAttempt(Math.max(0,connectAfter-now()));changed();

    }

    public void disconnect(){getSharedPreferences("connection",0).edit().putBoolean("enabled",false).commit();stopSession();}

    private void stopSession(){synchronized(lock){wanted=false;retry=0;ui.removeCallbacks(reconnect);}if(alarms!=null&&heartbeat!=null)alarms.cancel(heartbeat);Peer p=peer;if(p!=null)p.close();if(setupWake!=null&&setupWake.isHeld())setupWake.release();summary="Paused — cached chats available";if(foreground){foreground=false;stopForeground(true);}stopSelf();changed();}

    private final Runnable reconnect=new Runnable(){public void run(){attempt();}};

    private void scheduleAttempt(long delay){synchronized(lock){ui.removeCallbacks(reconnect);if(wanted&&!destroyed)ui.postDelayed(reconnect,delay);}}

    private void scheduleHeartbeat(){if(wanted&&!destroyed&&adapter!=null&&adapter.isEnabled())alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,now()+20000,heartbeat);}

    private void heartbeatStatus(){Peer p=peer;if(p==null||!p.ready||p.closed)return;p.lastStatus=now();request("STATUS",Wire.json(),new Reply(){public void done(JSONObject r,String e)throws IOException{if(r!=null){setStatus(r);changed();}}});}

    private void attempt(){

        synchronized(lock){if(!wanted||destroyed||peer!=null||connecting||adapter==null||!adapter.isEnabled()||now()<connectAfter)return;ui.removeCallbacks(reconnect);connecting=true;}

        new Thread(new Runnable(){public void run(){Peer p=null;

            try{

                attempts++;lastStage="CREATE";setupWake.acquire(35000);

                BluetoothDevice d=adapter.getRemoteDevice(Wire.PIXEL);

                if(!adapter.isEnabled()||d.getBondState()!=BluetoothDevice.BOND_BONDED)throw new IOException("Bluetooth unavailable");

                BluetoothSocket candidate;

                int channel=LegacyDiscovery.channel();discoveredChannel=channel;

                if(channel>=1&&channel<=30)candidate=(BluetoothSocket)d.getClass().getMethod("createRfcommSocket",int.class).invoke(d,channel);

                else candidate=d.createRfcommSocketToServiceRecord(UUID.fromString(Wire.SERVICE_UUID));

                p=new Peer(candidate);

                synchronized(lock){if(!wanted||destroyed||peer!=null){p.close();return;}peer=p;}

                summary="Connecting to Pixel…";changed();p.deadline=now()+15000;p.stage="CONNECT";

                p.socket.connect();if(!Wire.PIXEL.equals(p.socket.getRemoteDevice().getAddress())||d.getBondState()!=BluetoothDevice.BOND_BONDED)throw new IOException("Peer changed");

                p.stage="AUTH";

                p.session=Wire.client(p.socket.getInputStream(),p.socket.getOutputStream(),Wire.token(getSharedPreferences("pair",0).getString("key","")));

                p.stage="READY_WAIT";

                JSONObject ready=p.session.receive();if(!"ready".equals(ready.optString("kind"))||ready.optInt("protocol")!=1)throw new IOException("Expected ready");

                setStatus(ready.getJSONObject("status"));epoch=ClientRules.string(ready,"epoch",64);

                synchronized(lock){if(peer!=p||!wanted||p.closed)throw new IOException("Session stopped");p.ready=true;p.deadline=now()+35000;retry=0;}

                authenticated++;lastConnected=now();lastError="NONE";p.startWriter();if(setupWake.isHeld())setupWake.release();resync();changed();

                while(!p.closed){p.stage="READ";JSONObject body=p.session.receive();if(p.closed||peer!=p)break;p.deadline=now()+35000;p.stage="DISPATCH";receive(p,body);}

            }catch(Exception e){lastFailure=now();lastStage=p==null?"CREATE":p.stage;lastError=transportError(e);}

            finally{if(setupWake.isHeld())setupWake.release();if(p!=null)p.close();boolean again;

                synchronized(lock){if(peer==p)peer=null;connecting=false;again=wanted&&!destroyed;}

                if(!destroyed){store.recover();resyncing=false;summary=again?"Waiting for Pixel and call connection…":"Paused — cached chats available";changed();}

                if(again){final int seconds=Math.min(30,2<<Math.min(retry++,4));scheduleAttempt(seconds*1000L);}

            }

        }},"GalaxyChat-connect").start();

    }

    private static long now(){return SystemClock.elapsedRealtime();}

    private static String transportError(Exception e){String message=e.getMessage();if("Unable to start Service Discovery".equals(message))return "SDP_START_FAILED";if("Service discovery failed".equals(message))return "SDP_FAILED";if(e instanceof EOFException)return "END_OF_STREAM";if(e instanceof IOException)return "IO";if(e instanceof SecurityException)return "PERMISSION";if(e instanceof JSONException)return "JSON";return "LOCAL_STATE";}

    private final Runnable watchdog=new Runnable(){public void run(){

        if(destroyed)return;Peer p=peer;

        if(p!=null&&!p.closed){

            if(now()>p.deadline){lastError="DEADLINE";lastFailure=now();p.close();}

            else if(p.ready){

                boolean overdue=false;synchronized(p.pending){for(Request r:p.pending.values())if(now()-r.created>35000)overdue=true;}

                if(overdue){lastError="REQUEST_DEADLINE";lastFailure=now();p.close();}

                else if(now()-p.lastStatus>20000)heartbeatStatus();

            }

        }

        ui.postDelayed(this,1000);

    }};

    private void setStatus(JSONObject s)throws IOException{

        ClientRules.string(s,"auth",32);ClientRules.string(s,"connection",128);ClientRules.bool(s,"send_enabled");ClientRules.string(s,"notice",512);boolean becameReady=!"READY".equals(serverStatus.optString("auth"))&&"READY".equals(s.optString("auth"));serverStatus=s;

        summary="READY".equals(s.optString("auth"))?"Pixel connected · "+s.optString("connection"):"Finish Telegram setup on your Pixel";

        if(s.optString("notice").length()>0)summary+=" · "+s.optString("notice");

        if(becameReady&&connected())ui.post(new Runnable(){public void run(){resync();}});

    }

    private Notification makeNotification(){Intent i=new Intent(this,ChatActivity.class);PendingIntent pi=PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this).setSmallIcon(getResources().getIdentifier("notification","drawable",getPackageName())).setContentTitle("Telegram").setContentText(summary).setContentIntent(pi).setOngoing(true).getNotification();}

    private void notification(){
        if(NotificationLinkService.managed()){
            if(foreground){foreground=false;stopForeground(true);}
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(701);
        }else{foreground=true;startForeground(701,makeNotification());}
    }

    private void newMessageNotice(JSONObject m){if(m.optBoolean("outgoing")||(viewing&&openChat.equals(m.optString("chat_id"))))return;Intent i=new Intent(this,ChatActivity.class).putExtra("chat",m.optString("chat_id"));PendingIntent pi=PendingIntent.getActivity(this,1,i,PendingIntent.FLAG_UPDATE_CURRENT);Notification n=new Notification.Builder(this).setSmallIcon(getResources().getIdentifier("notification","drawable",getPackageName())).setContentTitle("Telegram").setContentText("New Telegram message").setContentIntent(pi).setAutoCancel(true).getNotification();n.defaults=Notification.DEFAULT_LIGHTS|Notification.DEFAULT_SOUND;((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(702,n);}

    private static final class Request{final String id,method;final JSONObject params;final Reply reply;final long created=now();volatile boolean sent;Request(String m,JSONObject p,Reply r)throws IOException{id=ClientRules.uuid(Wire.random(16));method=m;params=p;reply=r;}}

    private boolean request(String method,JSONObject params,Reply reply){Peer p=peer;if(p==null||!p.ready||p.closed)return false;Request r;try{r=new Request(method,params,reply);}catch(IOException e){p.close();return false;}synchronized(p.pending){if(p.closed||p.pending.size()>=64)return false;if("STATUS".equals(method)||"VIEW_MESSAGES".equals(method)||("TYPING".equals(method)&&params.optBoolean("active"))){for(Request old:p.pending.values())if(old.method.equals(method)&&old.params.optString("chat_id").equals(params.optString("chat_id")))return false;}p.pending.put(r.id,r);setupWake.acquire(35000);}if(!p.queue.offer(r)){synchronized(p.pending){p.pending.remove(r.id);}p.close();return false;}return true;}

    private final class Peer {

        final BluetoothSocket socket;final ArrayBlockingQueue<Request> queue=new ArrayBlockingQueue<Request>(64);final HashMap<String,Request> pending=new HashMap<String,Request>();

        final Semaphore slots=new Semaphore(3);

        volatile boolean closed,ready;volatile long deadline=now()+15000,lastStatus=now();Wire.Session session;Thread writer;

        volatile String stage="CREATE",closeStage="OPEN";

        Peer(BluetoothSocket s){socket=s;}

        void startWriter(){writer=new Thread(new Runnable(){public void run(){try{while(!closed){Request r=queue.take();slots.acquire();if(closed||peer!=Peer.this)break;r.sent=true;session.send(Wire.json("kind","request","id",r.id,"method",r.method,"params",r.params));}}catch(Exception e){close();}}},"GalaxyChat-write");writer.start();}

        void close(){synchronized(this){if(closed)return;closed=true;ready=false;closeStage="ABORTING";}try{socket.close();}catch(IOException ignored){}closeStage="CLOSED";if(writer!=null)writer.interrupt();queue.clear();synchronized(pending){pending.clear();}}

    }

    private void receive(Peer p,JSONObject body)throws Exception {

        String kind=ClientRules.string(body,"kind",16);

        if("response".equals(kind)){String id=ClientRules.string(body,"id",64);Request r;synchronized(p.pending){r=p.pending.remove(id);}if(r==null||!r.sent)throw new IOException("Unknown request");p.slots.release();

            if(body.optBoolean("ok")){JSONObject result=body.getJSONObject("result");if(r.reply!=null)r.reply.done(result,null);}

            else{JSONObject error=body.getJSONObject("error");String code=ClientRules.string(error,"code",32);if(r.reply!=null)r.reply.done(null,code);}

            synchronized(p.pending){if(p.pending.isEmpty()&&setupWake.isHeld())setupWake.release();}

        }else if("event".equals(kind)){

            String e=ClientRules.string(body,"epoch",64),seq=ClientRules.id(body,"seq",false);

            if(!ClientRules.eventNext(epoch,eventSeq,e,seq)){resync();return;}eventSeq=Long.parseLong(seq);

            String event=ClientRules.string(body,"event",32);JSONObject data=body.getJSONObject("data");

            if("STATUS".equals(event))setStatus(data);

            else if("CHAT_CHANGED".equals(event))store.putChat(data);

            else if("MESSAGE_NEW".equals(event)){store.putMessage(data);newMessageNotice(data);}

            else if("MESSAGE_CHANGED".equals(event))store.putMessage(data);

            else if("MESSAGES_DELETED".equals(event))store.removeMessages(ClientRules.id(data,"chat_id",true),data.getJSONArray("message_ids"));

            else if("SEND_CHANGED".equals(event))store.updateOperation(data);

            else if("RESYNC_REQUIRED".equals(event)){resync();return;}

            else throw new IOException("Unknown event");changed();

        }else throw new IOException("Unknown payload");

    }

    private synchronized void resync(){if(resyncing||!connected())return;resyncing=true;

        if(!request("RESYNC",Wire.json(),new Reply(){public void done(JSONObject r,String error)throws IOException{

            resyncing=false;if(r==null){summary="Pixel unavailable · "+error;changed();return;}

            epoch=ClientRules.string(r,"epoch",64);long next=Long.parseLong(ClientRules.id(r,"next_seq",false));if(next<1)throw new IOException("Invalid sequence");eventSeq=next-1;

            try{setStatus(r.getJSONObject("status"));}catch(JSONException e){throw new IOException("Invalid status");}

            // A gap may contain deletions; do not retain stale history after a new authoritative snapshot.

            store.invalidateHistory();

            request("SUBSCRIBE_UPDATES",Wire.json("epoch",epoch,"after_seq",Long.toString(eventSeq)),new Reply(){public void done(JSONObject s,String e)throws IOException{if(s!=null&&s.optBoolean("resync_required"))resync();}});

            refresh();queryOutstanding(store.unresolved(),0);changed();

        }})){resyncing=false;}

    }

    private void queryOutstanding(final ArrayList<String> ids,final int index){if(index>=ids.size())return;final String id=ids.get(index);

        request("QUERY_SEND",Wire.json("operation_id",id),new Reply(){public void done(JSONObject r,String e)throws IOException{if(r!=null)store.updateOperation(r);else store.uncertain(id,"NOT_FOUND".equals(e)?"Pixel has no confirmed record. Check Pixel before sending again.":"Send status unavailable; check Pixel.");changed();queryOutstanding(ids,index+1);}});

    }

    public void showList(boolean archive){String previous=openChat;openChat="";archived=archive;viewing=false;if(previous.length()>0)request("OPEN_CHAT",Wire.json("chat_id",previous,"open",false),null);loadChats(false);changed();}

    public void showChat(String chat){if(!ClientRules.decimal(chat,true))return;String previous=openChat;openChat=chat;before="0";moreHistory=false;

        if(previous.length()>0&&!previous.equals(chat))request("OPEN_CHAT",Wire.json("chat_id",previous,"open",false),null);

        openOnPixel(chat);loadHistory(false);changed();}

    public void viewing(boolean active){if(!active)typing(false);viewing=active;if(active)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(702);}

    private void openOnPixel(final String chat){request("OPEN_CHAT",Wire.json("chat_id",chat,"open",true),new Reply(){public void done(JSONObject r,String e)throws IOException{if(r!=null&&r.optJSONObject("chat")!=null){JSONObject c=r.optJSONObject("chat");if(!chat.equals(c.optString("id")))throw new IOException("Chat changed");store.putChat(c);changed();}}});}

    public void refresh(){if(!"READY".equals(serverStatus.optString("auth")))return;if(openChat.length()>0){openOnPixel(openChat);loadHistory(false);}else loadChats(false);}

    public void more(){if(openChat.length()>0)loadHistory(true);else loadChats(true);}

    private void loadChats(boolean next){if(!"READY".equals(serverStatus.optString("auth")))return;final boolean target=archived;final String token=next?cursor:"";if(next&&!moreChats)return;final int generation=++chatPageGeneration;

        request("LIST_CHATS",Wire.json("archived",target,"limit",20,"cursor",token),new Reply(){public void done(JSONObject r,String e)throws IOException{

            if(generation!=chatPageGeneration)return;if(r==null){summary="Could not load chats · "+e;changed();return;}JSONArray rows=r.optJSONArray("chats");if(rows==null||rows.length()>30)throw new IOException("Invalid chat page");for(int i=0;i<rows.length();i++){JSONObject o=rows.optJSONObject(i);if(o==null||o.optBoolean("archived")!=target)throw new IOException("Invalid chat");store.putChat(o);}if(target==archived){cursor=ClientRules.string(r,"next_cursor",256);moreChats=ClientRules.bool(r,"has_more");}changed();

        }});

    }

    private void loadHistory(final boolean next){if(!"READY".equals(serverStatus.optString("auth")))return;final String target=openChat;final String older=next?before:"0";if(target.length()==0||(next&&!moreHistory))return;final int generation=++historyGeneration;

        request("GET_HISTORY",Wire.json("chat_id",target,"limit",20,"before_id",older),new Reply(){public void done(JSONObject r,String e)throws IOException{

            if(generation!=historyGeneration||!target.equals(openChat))return;if(r==null){summary="Could not load history · "+e;changed();return;}JSONArray rows=r.optJSONArray("messages");if(rows==null||rows.length()>30)throw new IOException("Invalid history page");store.history(target,rows,next);before=ClientRules.id(r,"next_before_id",false);moreHistory=ClientRules.bool(r,"has_more");changed();

        }});

    }

    public interface DataResult {void done(JSONObject data,String error);}

    JSONObject rpc(String method,JSONObject params)throws IOException{

        final java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);final JSONObject[] result={null};final String[] error={null};

        if(!request(method,params,new Reply(){public void done(JSONObject r,String e){result[0]=r;error[0]=e;done.countDown();}}))throw new IOException("Pixel disconnected");

        try{if(!done.await(32,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Pixel timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Transfer interrupted");}

        if(result[0]==null)throw new IOException("Attachment unavailable ("+error[0]+")");return result[0];

    }

    void mediaChanged(){changed();}

    String prepareMedia(String chat,String text,String reply)throws IOException{if(!canSend(chat))throw new IOException("Connect to Pixel before sending");String op=store.prepare(chat,text,reply);changed();return op;}

    void mediaState(JSONObject s)throws IOException{store.updateOperation(s);changed();}

    void mediaFailure(String op,boolean uncertain,String reason){if(uncertain)store.uncertain(op,reason);else store.fail(op,reason);changed();}

    public interface SavedCallback {void ready(String id,String error);}

    public void savedMessages(final SavedCallback callback){if(!request("SAVED_MESSAGES",Wire.json(),new Reply(){public void done(JSONObject r,final String error)throws IOException{final String id;if(r!=null){JSONObject c=r.optJSONObject("chat");store.putChat(c);id=c.optString("id");}else id="";ui.post(new Runnable(){public void run(){callback.ready(id,error);}});}}))callback.ready("","Connect to Pixel");}

    public boolean visible(String chat,JSONArray ids){if(!viewing||!openChat.equals(chat)||ids.length()==0||ids.length()>50)return false;return request("VIEW_MESSAGES",Wire.json("chat_id",chat,"message_ids",ids),null);}

    public void typing(boolean active){if(openChat.length()==0||!viewing||!canSend(openChat))return;long n=now();if(active&&n-lastTyping<4000)return;lastTyping=n;request("TYPING",Wire.json("chat_id",openChat,"active",active),null);}

    public String send(final String chat,String text,String reply)throws IOException{

        if(!canSend(chat))throw new IOException("Connect to Pixel before sending");ClientRules.textToSend(text);if(!ClientRules.decimal(reply,false))throw new IOException("Invalid reply");

        final String op=store.prepare(chat,text,reply); // Durable intent precedes any socket write.

        if(!request("SEND_TEXT",Wire.json("operation_id",op,"chat_id",chat,"text",text,"reply_to_id",reply),new Reply(){public void done(JSONObject r,String e)throws IOException{

            if(r!=null)store.updateOperation(r);else store.uncertain(op,"Send outcome needs checking on Pixel ("+e+")");changed();

        }}))store.uncertain(op,"Connection changed before confirmation; check Pixel");

        typing(false);store.draft(chat,"","0");changed();return op;

    }

}

