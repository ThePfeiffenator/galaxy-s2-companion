package org.retrobridge.galaxychat.s2;

import android.graphics.*;
import android.util.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.retrobridge.galaxychat.protocol.Wire;

/** Bounded, private icon cache. Never replaces or loads a system font. */
final class ReactionIcons {
    final ChatService service;
    final File root;
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final LruCache<String,Bitmap> memory=new LruCache<String,Bitmap>(128);
    private final Set<String> queued=new HashSet<String>();
    private final Object fetchLock=new Object();
    private long retryAfter;
    private volatile boolean closed;
    ReactionIcons(ChatService s){service=s;root=new File(s.getCacheDir(),"reaction-icons-v1");root.mkdirs();trim();}
    void close(){closed=true;worker.shutdownNow();}
    File file(String emoji){return new File(root,MediaClient.key(emoji)+".png");}
    synchronized Bitmap get(String emoji){
        Bitmap bitmap=memory.get(emoji);if(bitmap!=null)return bitmap;
        File f=file(emoji);if(!f.isFile())return null;
        try{if(f.length()<1||f.length()>7000)throw new IOException();
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getPath(),bounds);
            if(bounds.outWidth!=64||bounds.outHeight!=64)throw new IOException();
            bitmap=BitmapFactory.decodeFile(f.getPath());if(bitmap!=null)memory.put(emoji,bitmap);return bitmap;
        }catch(Exception e){f.delete();return null;}
    }
    void ensure(JSONArray rows)throws Exception {synchronized(fetchLock){
        LinkedHashSet<String> missing=new LinkedHashSet<String>();
        for(int i=0;i<rows.length()&&i<100;i++){String emoji=rows.getJSONObject(i).getString("emoji");if(emoji.length()>32)throw new IOException();if(get(emoji)==null)missing.add(emoji);}
        Iterator<String> iterator=missing.iterator();
        while(iterator.hasNext()){
            if(closed)throw new InterruptedIOException();
            JSONArray values=new JSONArray();while(iterator.hasNext()&&values.length()<4)values.put(iterator.next());
            JSONArray icons=service.rpc("REACTION_ICONS",Wire.json("emoji",values)).getJSONArray("icons");
            if(icons.length()!=values.length())throw new IOException("Incomplete icons");
            for(int i=0;i<icons.length();i++){
                JSONObject icon=icons.getJSONObject(i);String emoji=values.getString(i),encoded=icon.getString("png");
                if(!emoji.equals(icon.getString("emoji"))||encoded.length()>9400)throw new IOException("Invalid icon");
                byte[] bytes=Base64.decode(encoded,Base64.NO_WRAP);if(bytes.length>7000)throw new IOException("Invalid icon");
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
                if(bounds.outWidth!=64||bounds.outHeight!=64)throw new IOException("Invalid icon dimensions");
                Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(bitmap==null)throw new IOException("Invalid icon");
                File pending=new File(root,"pending.png");FileOutputStream out=new FileOutputStream(pending);
                try{out.write(bytes);out.getFD().sync();}finally{out.close();}
                if(!pending.renameTo(file(emoji))){pending.delete();throw new IOException("Could not cache icon");}
                synchronized(this){memory.put(emoji,bitmap);}
            }
        }
        trim();
    }}
    void prefetch(final JSONArray rows){
        if(rows==null||closed||!service.connected())return;
        final JSONArray batch=new JSONArray();final ArrayList<String> keys=new ArrayList<String>();
        synchronized(this){if(android.os.SystemClock.elapsedRealtime()<retryAfter)return;for(int i=0;i<rows.length()&&i<100;i++){JSONObject r=rows.optJSONObject(i);if(r==null)continue;String e=r.optString("emoji");if(get(e)==null&&queued.add(e)){batch.put(r);keys.add(e);}}}
        if(batch.length()==0)return;
        try{worker.execute(new Runnable(){public void run(){try{ensure(batch);service.mediaChanged();}catch(Exception e){synchronized(ReactionIcons.this){retryAfter=android.os.SystemClock.elapsedRealtime()+15000;}}finally{synchronized(ReactionIcons.this){queued.removeAll(keys);}}}});}catch(RejectedExecutionException e){synchronized(this){queued.removeAll(keys);}}
    }
    private void trim(){File[] files=root.listFiles();if(files==null||files.length<=256)return;Arrays.sort(files,new Comparator<File>(){public int compare(File a,File b){return Long.valueOf(a.lastModified()).compareTo(b.lastModified());}});for(int i=0;i<files.length-256;i++)files[i].delete();}
}
