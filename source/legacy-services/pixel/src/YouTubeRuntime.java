package org.retrobridge.galaxyservices.pixel;
import android.content.*;import android.content.pm.PackageManager;import java.io.*;import java.net.*;import java.util.concurrent.*;import org.retrobridge.galaxyservices.Wire;
/** Uses Termux's permission-protected background API; no PC process is required. */
final class YouTubeRuntime {
 final Context app;final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
 YouTubeRuntime(Context c){app=c;}
 void note(String s){try{Wire.writeFile(new File(app.getFilesDir(),"youtube-runtime.txt"),s.getBytes("UTF-8"));}catch(Exception ignored){}}
 void start(){worker.scheduleWithFixedDelay(new Runnable(){public void run(){ensure();}},2,120,TimeUnit.SECONDS);}
 void ensure(){HttpURLConnection c=null;try{c=(HttpURLConnection)new URL("http://127.0.0.1:8768/galaxy-health").openConnection(java.net.Proxy.NO_PROXY);c.setConnectTimeout(2000);c.setReadTimeout(2000);if(c.getResponseCode()==200){note("Ready");return;}}catch(Exception ignored){}finally{if(c!=null)c.disconnect();}
  try{if(app.checkSelfPermission("com.termux.permission.RUN_COMMAND")!=PackageManager.PERMISSION_GRANTED){note("Termux permission needed");return;}Intent i=new Intent("com.termux.RUN_COMMAND");i.setClassName("com.termux","com.termux.app.RunCommandService");i.putExtra("com.termux.RUN_COMMAND_PATH","/data/data/com.termux/files/usr/bin/bash");i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",new String[]{"/data/data/com.termux/files/home/galaxy-youtube.sh"});i.putExtra("com.termux.RUN_COMMAND_WORKDIR","/data/data/com.termux/files/home/yt2009/back");i.putExtra("com.termux.RUN_COMMAND_BACKGROUND",true);i.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL","Galaxy YouTube");app.startService(i);note("Starting");}catch(Exception e){note("Start: "+e.getClass().getSimpleName());}
 }
 void close(){worker.shutdownNow();}
}
