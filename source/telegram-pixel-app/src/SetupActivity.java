package org.retrobridge.galaxychat.pixel;

import android.app.*;

import android.content.*;

import android.content.pm.PackageManager;

import android.net.Uri;

import android.os.*;

import android.text.InputType;

import android.view.*;

import android.widget.*;

import org.retrobridge.galaxychat.protocol.Wire;

import org.json.JSONObject;

import java.util.*;



/** Credentials are entered here, never through Bluetooth or an exported receiver. */

public final class SetupActivity extends Activity {

    private TelegramEngine engine;private LinearLayout page;private TextView status,result;private EditText api,hash,login;

    private String shown="";private final Handler ui=new Handler(Looper.getMainLooper());private boolean visible;

    protected void onCreate(Bundle state){super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);engine=TelegramEngine.get(this);render();}

    private TextView label(String text,int size){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setPadding(0,12,0,8);page.addView(v);return v;}

    private EditText field(String hint,int type){EditText v=new EditText(this);v.setSingleLine(true);v.setHint(hint);v.setInputType(type);page.addView(v);return v;}

    private void button(String text,Runnable action){Button b=new Button(this);b.setText(text);b.setOnClickListener(v->action.run());page.addView(b);}

    private void render(){

        ScrollView scroll=new ScrollView(this);page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(32,48,32,36);scroll.addView(page);setContentView(scroll);

        label("Telegram Companion",28);



        status=label("",16);result=label("",14);JSONObject state=engine.status();shown=state.optString("auth");

        if(shown.equals("SETUP_REQUIRED")){

            label("1. Your Telegram application credentials",19);button("Open API development tools",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://my.telegram.org/apps"))));

            api=field("API ID",InputType.TYPE_CLASS_NUMBER);hash=field("API hash",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);

            button("Save credentials on this Pixel",()->{try{int id=Integer.parseInt(api.getText().toString().trim());String secret=hash.getText().toString().trim();engine.configure(id,secret,this::answer);hash.setText("");}catch(Exception e){answer("Enter the numeric API ID and 32-character API hash.");}});

        }else if(Arrays.asList("WAIT_PHONE","WAIT_CODE","WAIT_PASSWORD","WAIT_EMAIL","WAIT_EMAIL_CODE").contains(shown)){

            String hint=shown.equals("WAIT_PHONE")?"Phone number with country code":shown.equals("WAIT_PASSWORD")?"Two-step verification password":shown.equals("WAIT_EMAIL")?"Login email":"Telegram login code";

            label("2. Authorize your new session",19);login=field(hint,shown.equals("WAIT_PHONE")?InputType.TYPE_CLASS_PHONE:shown.equals("WAIT_PASSWORD")?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT);

            button("Continue",()->{String value=login.getText().toString();if(!shown.equals("WAIT_PASSWORD"))value=value.trim();engine.authenticate(shown,value,this::answer);login.setText("");});

        }else if(shown.equals("WAIT_OTHER")){

            JSONObject a=engine.localAuthorization();String link=a.optString("link","");if(link.startsWith("tg://login?")||link.startsWith("https://t.me/login"))button("Approve this session in Telegram",()->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(link)));}catch(Exception e){answer("Open your official Telegram app to review the login approval.");}});

            else label("This Telegram authorization step needs attention in the official app. No account registration, premium purchase, or recovery action is automated.",14);

        }

        label("Connection",19);



        button("Allow Nearby devices and notifications",this::permissions);

        button("Start Bluetooth relay",()->{if(!permissionReady()){permissions();return;}try{Startup.enabled(this,true);Startup.start(this);answer("Automatic connection enabled.");}catch(Exception e){answer("Relay could not start. Check Bluetooth and permissions.");}});

        button("Pause Bluetooth relay",()->{Startup.enabled(this,false);stopService(new Intent(this,RelayService.class));answer("Paused until you start it again.");});

        button("Show app-pairing key",()->{try{String key=Wire.token(engine.secrets.pairKey());EditText text=new EditText(this);text.setText(key);text.setSingleLine(false);text.setTextIsSelectable(true);new AlertDialog.Builder(this).setTitle("App pairing only").setMessage("Enter this key in Telegram on your S2 if the apps have not already been paired. This is not a Telegram login code.").setView(text).setPositiveButton("Done",null).show();}catch(Exception e){answer("App pairing could not be opened.");}});

        button("Revoke S2 app pairing",()->new AlertDialog.Builder(this).setTitle("Disconnect this companion?").setMessage("The S2 will need the new app key. Your Telegram session will remain signed in.").setNegativeButton("Cancel",null).setPositiveButton("Revoke",(d,w)->{try{RelayService s=RelayService.active;if(s!=null)s.closePeer();engine.secrets.revokePairing();answer("Old pairing revoked.");}catch(Exception e){answer("Pairing could not be revoked.");}}).show());



        refresh();

    }

    private void answer(String message){ui.post(()->{if(result!=null)result.setText(message);});}

    private boolean permissionReady(){return Build.VERSION.SDK_INT<31||checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}

    private void permissions(){ArrayList<String> needed=new ArrayList<>();if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)needed.add(android.Manifest.permission.BLUETOOTH_CONNECT);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)needed.add(android.Manifest.permission.POST_NOTIFICATIONS);if(!needed.isEmpty())requestPermissions(needed.toArray(new String[0]),11);else answer("Required local permissions are already allowed.");}

    private void refresh(){JSONObject s=engine.status();RelayService service=RelayService.active;status.setText(("READY".equals(s.optString("auth"))?"Telegram connected":s.optString("notice"))+"\n"+(service==null?"Relay paused":service.localStatus()));}

    private final Runnable poll=new Runnable(){public void run(){if(!visible)return;if(!shown.equals(engine.status().optString("auth")))render();else refresh();ui.postDelayed(this,1000);}};

    protected void onResume(){super.onResume();visible=true;if(permissionReady())Startup.start(this);ui.post(poll);}

    protected void onPause(){visible=false;ui.removeCallbacks(poll);super.onPause();}

}

