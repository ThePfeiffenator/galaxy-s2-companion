package org.retrobridge.galaxyservices.pixel;
import android.app.*;import android.os.*;import android.content.*;import android.net.Uri;import android.view.*;import android.widget.*;import java.io.*;import org.json.*;

/** Android's file chooser grants access only to the owner's chosen APK. */
public final class CatalogActivity extends Activity {
 LinearLayout content;TextView status;Button add;boolean busy;
 public void onCreate(Bundle state){super.onCreate(state);startActivity(new Intent(this,MainActivity.class).putExtra("page","library"));finish();}
 void legacyView(){setTitle("App library");ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(1);content.setPadding(24,24,24,24);scroll.addView(content);setContentView(scroll);inset(this,scroll,0);show();}
 static void inset(Activity activity,View view,int padding){view.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(padding+insets.getSystemWindowInsetLeft(),padding+insets.getSystemWindowInsetTop(),padding+insets.getSystemWindowInsetRight(),padding+insets.getSystemWindowInsetBottom());return insets;});view.requestApplyInsets();}
 void show(){
  content.removeAllViews();add=new Button(this);add.setText("Add APK");content.addView(add);add.setOnClickListener(v->{if(busy)return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");startActivityForResult(i,1);});
  status=new TextView(this);status.setPadding(0,12,0,20);content.addView(status);
  try{JSONArray list=CatalogLibrary.catalog(this).getJSONArray("apps");long bytes=0;int available=0;
   for(int n=0;n<list.length();n++){JSONObject a=list.getJSONObject(n);File f=new File(getFilesDir(),"catalog-apks/"+a.getString("sha256")+".apk");boolean stored=f.isFile()&&f.length()==a.getLong("size");if(stored){available++;bytes+=f.length();}TextView row=new TextView(this);row.setText(a.getString("name")+"  "+a.getString("version")+"\n"+(stored?"Stored on Pixel":"Download on demand"));row.setTextSize(16);row.setPadding(0,12,0,12);content.addView(row);}
   status.setText(available+" stored apps Â· "+(bytes/1048576)+" MB");
  }catch(Exception e){status.setText("Library unavailable: "+e.getMessage());}
 }
 protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=1||result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();busy=true;add.setEnabled(false);status.setText("Reading APKâ€¦");
  new Thread(()->{File staged=null;try{staged=CatalogLibrary.stage(this,uri);JSONObject item=CatalogLibrary.inspect(this,staged),old=CatalogLibrary.existing(this,item.getString("package"));final File chosen=staged;
   runOnUiThread(()->{if(isFinishing()||isDestroyed()){chosen.delete();return;}String detail="Version "+item.optString("version")+" Â· "+(chosen.length()/1048576)+" MB";
    if(old!=null)detail+="\nReplaces stored version "+old.optString("version")+".";
    if(old!=null&&!old.optString("signerSha256").equals(item.optString("signerSha256")))detail+="\nDifferent signing certificate. It cannot update the existing S2 installation.";
    new AlertDialog.Builder(this).setTitle(item.optString("name")).setMessage(detail).setPositiveButton(old==null?"Add to library":"Replace stored version",(d,w)->save(chosen,item)).setNegativeButton("Cancel",(d,w)->{chosen.delete();busy=false;show();}).setOnCancelListener(d->{chosen.delete();busy=false;show();}).show();});
  }catch(Exception e){if(staged!=null)staged.delete();runOnUiThread(()->{busy=false;show();status.setText(e.getMessage());});}},"Store import").start();
 }
 void save(File staged,JSONObject item){status.setText("Savingâ€¦");new Thread(()->{try{CatalogLibrary.commit(this,staged,item);runOnUiThread(()->{busy=false;show();Toast.makeText(this,"Added. Refresh the Store on the S2.",Toast.LENGTH_LONG).show();});}catch(Exception e){staged.delete();runOnUiThread(()->{busy=false;show();status.setText(e.getMessage());});}},"Store save").start();}
}
