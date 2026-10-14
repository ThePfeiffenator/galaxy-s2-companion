package org.retrobridge.galaxychat.s2;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.*;

/** Retained data and work only. No Activity, View, or Dialog may belong to a job. */
final class AttachmentJob {
    interface Observer { void changed(); }
    Observer observer; // Attached only while the current Activity is resumed.
    String kind="photo", name="attachment", mime="application/octet-stream", caption="", error;
    File camera, file;
    Uri source;
    boolean busy;
    private volatile int generation;
    private final Handler main=new Handler(Looper.getMainLooper());

    void save(Bundle b) {
        b.putString("attachment_kind",kind);
        if(camera!=null)b.putString("camera_file",camera.getPath());
        if(source!=null)b.putString("attachment_source",source.toString());
        if(file!=null)b.putString("attachment_file",file.getPath());
        b.putString("attachment_name",name); b.putString("attachment_mime",mime);
        b.putString("attachment_caption",caption); b.putString("attachment_error",error);
    }
    void restore(Context context,Bundle b) {
        if(b==null)return;
        kind=b.getString("attachment_kind","photo");
        String path=b.getString("camera_file","");if(path.length()>0)camera=new File(path);
        name=b.getString("attachment_name","attachment"); mime=b.getString("attachment_mime","application/octet-stream");
        caption=b.getString("attachment_caption","");error=b.getString("attachment_error");
        path=b.getString("attachment_file","");
        if(path.length()>0){file=new File(path);if(!file.isFile()){file=null;error="Please select the attachment again.";}}
        String uri=b.getString("attachment_source","");
        if(file==null&&uri.length()>0)start(context,Uri.parse(uri),kind);
    }
    void clear(boolean deleteFile) {
        generation++;busy=false;source=null;
        if(deleteFile&&file!=null)file.delete();
        file=null;caption="";error=null;
    }
    void start(Context context,final Uri uri,final String selectedKind) {
        clear(true);kind=selectedKind;source=uri;busy=true;
        final int token=generation;
        final Context app=context.getApplicationContext();
        notifyObserver();
        new Thread(new Runnable(){public void run(){
            File copy=null,compressed=null;String filename="attachment",type="application/octet-stream",problem=null;
            try {
                type=app.getContentResolver().getType(uri);
                if(type==null)type=selectedKind.equals("photo")?"image/jpeg":selectedKind.equals("video")?"video/mp4":"application/octet-stream";
                if("file".equals(uri.getScheme())){
                    filename=new File(uri.getPath()).getName();
                    String guess=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString()).toLowerCase(Locale.US));
                    if(guess!=null)type=guess;
                }else{
                    Cursor c=app.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null);
                    try{if(c!=null&&c.moveToFirst())filename=c.getString(0);}finally{if(c!=null)c.close();}
                }
                File dir=new File(app.getCacheDir(),"outgoing");
                if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Attachment storage unavailable");
                copy=new File(dir,UUID.randomUUID().toString()+".data");
                InputStream in=app.getContentResolver().openInputStream(uri);
                if(in==null)throw new IOException("Could not open attachment");
                try{FileOutputStream out=new FileOutputStream(copy);try{
                    byte[] bytes=new byte[16384];long size=0;
                    for(int n;(n=in.read(bytes))!=-1;){
                        if(token!=generation)throw new IOException("Cancelled");
                        size+=n;if(size>MediaClient.MAX)throw new IOException("Attachments are limited to 100 MB");
                        out.write(bytes,0,n);
                    }
                }finally{out.close();}}finally{in.close();}
                if(copy.length()==0)throw new IOException("Attachment is empty");
                if(selectedKind.equals("photo")){
                    compressed=new File(dir,UUID.randomUUID().toString()+".jpg");
                    AttachmentUi.photo(copy,compressed);copy.delete();copy=compressed;compressed=null;
                    filename="Photo.jpg";type="image/jpeg";
                }
            }catch(Exception e){problem=e.getMessage()==null?"Could not open attachment":e.getMessage();}
            catch(OutOfMemoryError e){problem="Not enough memory for this picture. Try a smaller image.";}
            if(compressed!=null)compressed.delete();
            if(problem!=null&&copy!=null){copy.delete();copy=null;}
            final File completed=copy;final String completedName=filename,completedMime=type,failure=problem;
            main.post(new Runnable(){public void run(){
                if(token!=generation){if(completed!=null)completed.delete();return;}
                busy=false;source=null;file=completed;name=completedName;mime=completedMime;error=failure;
                notifyObserver();
            }});
        }},"GalaxyChat-prepare").start();
    }
    private void notifyObserver(){if(observer!=null)observer.changed();}
}
