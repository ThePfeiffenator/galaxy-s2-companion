package org.retrobridge.galaxychat.pixel;

import android.app.*;

import android.bluetooth.*;

import android.content.*;

import android.os.*;

import org.retrobridge.galaxychat.protocol.Wire;

import org.json.*;

import java.io.*;

import java.util.*;

import java.util.concurrent.*;

import java.util.concurrent.atomic.*;



/** Persistent messaging relay; it never modifies HFP, SCO, MAP, or the existing call bridge. */

public final class RelayService extends Service {

    static volatile RelayService active;

    private volatile boolean running;

    private BluetoothAdapter adapter;private volatile BluetoothProfile headset;

    private volatile BluetoothServerSocket server;private volatile Peer peer;

    private TelegramEngine engine;

    private final ScheduledExecutorService timers=Executors.newSingleThreadScheduledExecutor();

    private final Object lifecycle=new Object();

    public IBinder onBind(Intent i){return null;}

    public void onCreate(){super.onCreate();engine=TelegramEngine.get(this);adapter=((BluetoothManager)getSystemService(BLUETOOTH_SERVICE)).getAdapter();}

    public int onStartCommand(Intent intent,int flags,int id){

        if(intent!=null&&"STOP".equals(intent.getAction())){Startup.enabled(this,false);stopSelf();return START_NOT_STICKY;}

        if(!Startup.enabled(this)){stopSelf();return START_NOT_STICKY;}

        if(running)return START_STICKY;

        if(android.os.Build.VERSION.SDK_INT>=31&&checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED){stopSelf();return START_NOT_STICKY;}

        if(adapter==null){stopSelf();return START_NOT_STICKY;}

        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.createNotificationChannel(new NotificationChannel("relay","Telegram companion connection",NotificationManager.IMPORTANCE_LOW));

        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,SetupActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        Notification n=new Notification.Builder(this,"relay").setSmallIcon(getResources().getIdentifier("notification","drawable",getPackageName())).setContentTitle("Telegram Companion").setContentText("Reconnects automatically when the S2 is available").setContentIntent(open).setOngoing(true).build();

        if(Build.VERSION.SDK_INT>=34)startForeground(710,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);else startForeground(710,n);

        running=true;active=this;

        adapter.getProfileProxy(this,new BluetoothProfile.ServiceListener(){public void onServiceConnected(int profile,BluetoothProfile proxy){headset=proxy;}public void onServiceDisconnected(int profile){headset=null;closePeer();}},BluetoothProfile.HEADSET);

        IntentFilter filter=new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);

        if(Build.VERSION.SDK_INT>=33)registerReceiver(state,filter,Context.RECEIVER_EXPORTED);else registerReceiver(state,filter);

        new Thread(()->listen(),"Galaxy Chat RFCOMM").start();return START_STICKY;

    }

    private final BroadcastReceiver state=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(!ready())closePeer();if(!adapter.isEnabled()){BluetoothServerSocket listening=server;if(listening!=null)try{listening.close();}catch(IOException ignored){}}}};

    boolean ready(){try{if(!running||adapter==null||!adapter.isEnabled()||headset==null)return false;BluetoothDevice s2=adapter.getRemoteDevice(Wire.S2);if(s2.getBondState()!=BluetoothDevice.BOND_BONDED)return false;for(BluetoothDevice d:headset.getConnectedDevices())if(Wire.S2.equals(d.getAddress()))return true;return false;}catch(Exception denied){return false;}}

    String localStatus(){return !running?"Relay paused":peer!=null&&peer.authenticated&&!peer.closed.get()?"S2 app authenticated":ready()?"Call link ready; waiting for S2 chat app":"Waiting for the S2 call link";}

    protected void dump(FileDescriptor fd,PrintWriter out,String[] args){JSONObject state=engine.status();Peer current=peer;

        out.println("native_selfcheck="+engine.nativeReady());out.println("auth="+state.optString("auth"));out.println("authorization_type="+engine.localAuthorization().optString("@type"));out.println("connection="+state.optString("connection"));

        out.println("auto_connect="+Startup.enabled(this));out.println("start_error="+getSharedPreferences("connection",0).getString("start_error","NONE"));out.println("relay_running="+running);out.println("hfp_ready="+ready());out.println("peer_authenticated="+(current!=null&&current.authenticated&&!current.closed.get()));}

    private void listen(){while(running){BluetoothServerSocket own=null;

        try{if(!adapter.isEnabled())throw new IOException("Bluetooth off");own=adapter.listenUsingRfcommWithServiceRecord("Galaxy Chat v1",UUID.fromString(Wire.SERVICE_UUID));synchronized(lifecycle){if(!running)throw new IOException("Stopped");server=own;}

            while(running){BluetoothSocket socket=own.accept();Peer current=null;

                try{if(!ready()||!Wire.S2.equals(socket.getRemoteDevice().getAddress())||socket.getRemoteDevice().getBondState()!=BluetoothDevice.BOND_BONDED)throw new IOException("Peer not admitted");

                    current=new Peer(socket);synchronized(lifecycle){if(!running||peer!=null)throw new IOException("Busy");peer=current;}current.run();

                }catch(Exception ignored){}finally{if(current!=null)current.close();else try{socket.close();}catch(IOException ignored){}synchronized(lifecycle){if(peer==current)peer=null;}}

            }

        }catch(Exception offline){}finally{synchronized(lifecycle){if(server==own)server=null;}if(own!=null)try{own.close();}catch(IOException ignored){}}

        if(running)try{Thread.sleep(3000);}catch(InterruptedException e){break;}

    }}

    void closePeer(){Peer p=peer;if(p!=null)p.close();}

    private final class Peer implements TelegramEngine.Listener {

        final BluetoothSocket socket;final AtomicBoolean closed=new AtomicBoolean();

        final ArrayBlockingQueue<JSONObject> outgoing=new ArrayBlockingQueue<>(128);

        final AtomicInteger requests=new AtomicInteger();final Set<String> usedIds=Collections.synchronizedSet(new HashSet<>());

        volatile Wire.Session wire;volatile boolean subscribed,authenticated;volatile long opened;

        Peer(BluetoothSocket s){socket=s;}

        void run()throws Exception{

            ScheduledFuture<?> timeout=timers.schedule(()->close(),15,TimeUnit.SECONDS);

            try{wire=Wire.server(socket.getInputStream(),socket.getOutputStream(),engine.secrets.pairKey());if(!ready()||closed.get())throw new IOException("Call link unavailable");authenticated=true;}finally{timeout.cancel(false);}

            Thread writer=new Thread(()->{while(!closed.get())try{JSONObject value=outgoing.poll(1,TimeUnit.SECONDS);if(value==null)continue;ScheduledFuture<?> expiry=timers.schedule(()->close(),35,TimeUnit.SECONDS);try{wire.send(value);}finally{expiry.cancel(false);}}catch(Exception failed){close();}},"Galaxy Chat writer");writer.start();

            offer(Wire.json("kind","ready","protocol",1,"epoch",engine.epoch,"status",engine.status()));engine.addListener(this);

            while(!closed.get()){

                timeout=timers.schedule(()->close(),35,TimeUnit.SECONDS);JSONObject q;try{q=wire.receive();}finally{timeout.cancel(false);}

                if(!ready())throw new IOException("Call link unavailable");

                if(!"request".equals(q.optString("kind"))||q.length()!=4)throw new IOException("Invalid request");String id=Wire.required(q,"id"),method=Wire.required(q,"method");JSONObject params=q.optJSONObject("params");

                if(!id.matches("[A-Za-z0-9_-]{1,64}")||params==null||!usedIds.add(id)||usedIds.size()>10000||requests.incrementAndGet()>16)throw new IOException("Request limit");

                final long deadline=SystemClock.uptimeMillis()+30000;

                engine.worker.execute(()->{try{

                    if(closed.get()||!ready()||SystemClock.uptimeMillis()>=deadline)throw new TelegramEngine.Problem("UNAVAILABLE","Companion connection is no longer active.");

                    JSONObject result;

                    if(method.equals("SUBSCRIBE_UPDATES")){

                        String requested=Wire.required(params,"epoch"),after=Wire.required(params,"after_seq");if(!after.matches("0|[1-9][0-9]{0,18}"))throw new TelegramEngine.Problem("BAD_REQUEST","Invalid event sequence.");

                        List<JSONObject> replay;boolean resync=false;try{replay=engine.replay(requested,Long.parseLong(after));}catch(TelegramEngine.Problem gap){replay=new ArrayList<>();resync=true;}

                        JSONObject snapshot=engine.resync();result=Wire.json("epoch",engine.epoch,"next_seq",snapshot.optString("next_seq"),"resync_required",resync);subscribed=!resync;

                        offer(Wire.json("kind","response","id",id,"ok",true,"result",result));if(!resync)for(JSONObject event:replay)offer(event);return;

                    }

                    if((method.equals("VIEW_MESSAGES")||method.equals("TYPING"))&&(opened==0||!Long.toString(opened).equals(params.optString("chat_id"))))throw new TelegramEngine.Problem("FORBIDDEN","Open this chat before sending view or typing state.");

                    result=engine.guardedRequest(method,params,()->{long left=deadline-SystemClock.uptimeMillis();if(closed.get()||!ready()||left<=0)throw new TelegramEngine.Problem("UNAVAILABLE","Companion request expired.");return left;});

                    if(method.equals("OPEN_CHAT")){long next=params.optBoolean("open",true)?Long.parseLong(params.getString("chat_id")):0;if(opened!=0&&opened!=next)engine.closeChat(opened);opened=next;}

                    offer(Wire.json("kind","response","id",id,"ok",true,"result",result));

                }catch(Exception e){String code=e instanceof TelegramEngine.Problem?((TelegramEngine.Problem)e).code:"INTERNAL";int retry=e instanceof TelegramEngine.Problem?((TelegramEngine.Problem)e).retry:0;

                    offer(Wire.json("kind","response","id",id,"ok",false,"error",Wire.json("code",code,"message",TelegramEngine.friendly(e),"retry_after",retry)));

                }finally{requests.decrementAndGet();}});

            }

        }

        public void event(JSONObject e){if(subscribed)offer(e);}

        void offer(JSONObject q){if(!closed.get()&&!outgoing.offer(q))close();}

        void close(){if(!closed.compareAndSet(false,true))return;engine.removeListener(this);try{socket.close();}catch(IOException ignored){}outgoing.clear();engine.closeChat(opened);opened=0;}

    }

    public void onDestroy(){running=false;active=null;closePeer();BluetoothServerSocket listening=server;if(listening!=null)try{listening.close();}catch(IOException ignored){}try{unregisterReceiver(state);}catch(Exception ignored){}

        if(adapter!=null&&headset!=null)adapter.closeProfileProxy(BluetoothProfile.HEADSET,headset);timers.shutdownNow();super.onDestroy();}

}

