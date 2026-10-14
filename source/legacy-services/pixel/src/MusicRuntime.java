package org.retrobridge.galaxyservices.pixel;
import android.content.*;import android.content.pm.PackageManager;import java.io.*;import java.net.*;import java.util.concurrent.*;import org.retrobridge.galaxyservices.Wire;
final class MusicRuntime {
 final Context app;final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
 MusicRuntime(Context c){app=c;}
 void start(){worker.scheduleWithFixedDelay(new Runnable(){public void run(){ensure();}},5,120,TimeUnit.SECONDS);}
 void note(String value){try{Wire.writeFile(new File(app.getFilesDir(),"music-status.txt"),value.getBytes("UTF-8"));}catch(Exception ignored){}}
 void ensure(){File key=new File(app.getFilesDir(),"music-relay.key");if(!key.exists())return;HttpURLConnection c=null;try{String token=new String(Wire.readFile(key),"US-ASCII").trim();if(!token.matches("[0-9a-f]{64}"))return;c=(HttpURLConnection)new URL("http://127.0.0.1:8769/health").openConnection(Proxy.NO_PROXY);c.setRequestProperty("X-Galaxy-Music-Key",token);c.setConnectTimeout(2000);c.setReadTimeout(2000);if(c.getResponseCode()==200){note("Ready");return;}}catch(Exception ignored){}finally{if(c!=null)c.disconnect();}
  try{if(app.checkSelfPermission("com.termux.permission.RUN_COMMAND")!=PackageManager.PERMISSION_GRANTED){note("Termux permission needed");return;}Intent intent=new Intent("com.termux.RUN_COMMAND");intent.setClassName("com.termux","com.termux.app.RunCommandService");intent.putExtra("com.termux.RUN_COMMAND_PATH","/data/data/com.termux/files/usr/bin/bash");intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",new String[]{"/data/data/com.termux/files/home/galaxy-music/start.sh"});intent.putExtra("com.termux.RUN_COMMAND_WORKDIR","/data/data/com.termux/files/home/galaxy-music");intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND",true);intent.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL","Galaxy Music");app.startService(intent);note("Starting");}catch(Exception e){note("Start: "+e.getClass().getSimpleName());}
 }
 void close(){worker.shutdownNow();}
}
