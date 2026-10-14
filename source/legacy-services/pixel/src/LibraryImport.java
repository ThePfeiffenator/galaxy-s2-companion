package org.retrobridge.galaxyservices.pixel;
import android.app.Application;import android.net.Uri;import androidx.lifecycle.*;import java.io.*;import java.util.concurrent.*;import org.json.*;
/** Retained import state survives rotation without rereading or committing twice. */
public final class LibraryImport extends AndroidViewModel {
 public static final class State {final String phase,message;final File file;final JSONObject item,old;State(String p,String m,File f,JSONObject a,JSONObject o){phase=p;message=m;file=f;item=a;old=o;}}
 final MutableLiveData<State> state=new MutableLiveData<>(new State("idle","",null,null,null));final ExecutorService worker=Executors.newSingleThreadExecutor();
 public LibraryImport(Application app){super(app);}
 boolean busy(){State s=state.getValue();return s!=null&&!(s.phase.equals("idle")||s.phase.equals("done")||s.phase.equals("error"));}
 void read(Uri uri){if(busy())return;state.setValue(new State("reading","Reading APK…",null,null,null));worker.execute(()->{File f=null;try{f=CatalogLibrary.stage(getApplication(),uri);JSONObject a=CatalogLibrary.inspect(getApplication(),f);state.postValue(new State("confirm","",f,a,CatalogLibrary.existing(getApplication(),a.getString("package"))));}catch(Exception e){if(f!=null)f.delete();state.postValue(new State("error",e.getMessage(),null,null,null));}});}
 void cancel(){State s=state.getValue();if(s!=null&&s.file!=null)s.file.delete();state.setValue(new State("idle","",null,null,null));}
 void save(){final State s=state.getValue();if(s==null||!s.phase.equals("confirm"))return;state.setValue(new State("saving","Saving…",s.file,s.item,s.old));worker.execute(()->{try{CatalogLibrary.commit(getApplication(),s.file,s.item);state.postValue(new State("done","Added to library",null,null,null));}catch(Exception e){s.file.delete();state.postValue(new State("error",e.getMessage(),null,null,null));}});}
 void acknowledge(){state.setValue(new State("idle","",null,null,null));}
 protected void onCleared(){worker.shutdown();State s=state.getValue();if(s!=null&&s.phase.equals("confirm")&&s.file!=null)s.file.delete();}
}
