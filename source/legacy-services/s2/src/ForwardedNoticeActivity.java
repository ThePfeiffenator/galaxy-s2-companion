package org.retrobridge.galaxyservices.s2;
import android.app.*;import android.os.Bundle;import android.content.*;import android.widget.*;import org.json.*;import java.io.*;
import org.retrobridge.galaxyservices.Wire;
/** A native ICS notification detail, with no remote actions or credentials. */
public final class ForwardedNoticeActivity extends Activity {
 protected void onCreate(Bundle state){super.onCreate(state);show();}
 protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);show();}
 void show(){String key=getIntent().getStringExtra("key");JSONObject row=null;try{JSONObject cache=new JSONObject(new String(Wire.readFile(new File(getFilesDir(),"forwarded-notifications.json")),"UTF-8"));row=cache.optJSONObject(key);}catch(Exception ignored){}
  setTitle(row==null?"Notification":row.optString("app","Notification"));LinearLayout root=new LinearLayout(this);root.setOrientation(1);int pad=(int)(16*getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);TextView title=new TextView(this);title.setTextSize(22);title.setText(row==null?"Notification dismissed":row.optString("title"));root.addView(title);TextView text=new TextView(this);text.setTextSize(18);text.setPadding(0,pad,0,pad);text.setText(row==null?"":row.optString("text"));root.addView(text);ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);if(key!=null)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(key,710);
 }
}
