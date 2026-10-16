package org.retrobridge.galaxyservices.s2;
import android.app.*;import android.os.*;import android.content.*;import android.widget.*;import org.json.*;import java.io.*;
import org.retrobridge.galaxyservices.Wire;
/** Original-style ICS detail screen. Remote dismissal requires an explicit tap. */
public final class ForwardedNoticeActivity extends Activity {
 private boolean destroyed,busy;private int screen;
 protected void onCreate(Bundle state){super.onCreate(state);show();}
 protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);show();}
 protected void onDestroy(){destroyed=true;super.onDestroy();}
 void show(){
  final int generation=++screen;busy=false;final String key=getIntent().getStringExtra("key");JSONObject row=null;
  try{JSONObject cache=new JSONObject(new String(Wire.readFile(new File(getFilesDir(),"forwarded-notifications.json")),"UTF-8"));row=cache.optJSONObject(key);}catch(Exception ignored){}
  setTitle(row==null?"Notification":row.optString("app","Notification"));
  LinearLayout root=new LinearLayout(this);root.setOrientation(1);int pad=(int)(16*getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);
  TextView title=new TextView(this);title.setTextSize(22);title.setText(row==null?"Notification dismissed":row.optString("title"));root.addView(title);
  TextView text=new TextView(this);text.setTextSize(18);text.setPadding(0,pad,0,pad);text.setText(row==null?"":row.optString("text"));root.addView(text);
  if(row!=null){
   final String revision=row.optString("revision");final Button clear=new Button(this);clear.setText("Clear on phone");root.addView(clear);
   final TextView status=new TextView(this);status.setPadding(0,pad/2,0,0);root.addView(status);
   boolean supported=key!=null&&key.matches("[0-9a-f]{64}")&&revision.matches("[0-9a-f]{64}");
   clear.setEnabled(supported&&row.optBoolean("clearable",false));
   if(!supported)status.setText("Update Galaxy Companion on your phone.");else if(!row.optBoolean("clearable",false))status.setText("This notification can't be cleared.");
   clear.setOnClickListener(v->{
    if(busy)return;busy=true;clear.setEnabled(false);status.setText("Clearing\u2026");
    final Context app=getApplicationContext();
    new Thread(()->{
     String outcome="unavailable";PowerManager.WakeLock wake=null;
     try{
      wake=((PowerManager)app.getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GalaxyServices:clear-notification");wake.acquire(45000);
      outcome=CompanionRequest.send(app,"notifications/"+key+"/dismiss",new JSONObject().put("revision",revision),4096).getString("status");
     }catch(Exception ignored){}finally{if(wake!=null&&wake.isHeld())wake.release();}
     final String result=outcome;
     runOnUiThread(()->{
      if(destroyed||generation!=screen)return;busy=false;
      if("cleared".equals(result)||"already_gone".equals(result)){
       ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(key,710);
       Toast.makeText(this,"cleared".equals(result)?"Cleared on phone":"Already cleared on phone",Toast.LENGTH_SHORT).show();
       LegacyService service=LegacyService.active;if(service!=null&&service.companion!=null)service.companion.kick();finish();
      }else if("changed".equals(result)){status.setText("Notification changed. Open its latest alert.");}
      else if("not_clearable".equals(result)){status.setText("This notification can't be cleared.");}
      else if("disabled".equals(result)){status.setText("Notification forwarding is turned off.");}
      else if("not_forwarded".equals(result)){status.setText("This notification is no longer being forwarded.");}
      else{status.setText("Couldn't clear it. Check your phone connection and try again.");clear.setEnabled(true);}
     });
    },"clear-notification").start();
   });
  }
  ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
  if(key!=null)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(key,710);
 }
}
