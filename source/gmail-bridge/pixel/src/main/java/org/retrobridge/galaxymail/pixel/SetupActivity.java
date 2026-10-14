package org.retrobridge.galaxymail.pixel;

import android.view.View;import android.app.*;import android.os.*;import android.content.*;import android.widget.*;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.ApiException;

public final class SetupActivity extends androidx.appcompat.app.AppCompatActivity {
    TextView gmailStatus,youtubeStatus;Button gmailButton,youtubeButton,controlsButton;boolean busy;int active;
    public void onCreate(Bundle saved){getDelegate().setLocalNightMode(getIntent().getIntExtra("night",-1));com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);super.onCreate(saved);if(saved!=null)active=saved.getInt("active",0);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        LinearLayout root=org.retrobridge.companion.ui.Ui.column(this);root.setBackgroundColor(org.retrobridge.companion.ui.Ui.surface(this));
        com.google.android.material.appbar.MaterialToolbar bar=new com.google.android.material.appbar.MaterialToolbar(this);bar.setTitle("Google accounts");bar.setNavigationIcon(new org.retrobridge.companion.ui.Ui.Glyph("back",org.retrobridge.companion.ui.Ui.textColor(this)));bar.setNavigationOnClickListener(v->finish());root.addView(bar,new LinearLayout.LayoutParams(-1,org.retrobridge.companion.ui.Ui.dp(this,72)));
        ScrollView scroll=new ScrollView(this);LinearLayout column=org.retrobridge.companion.ui.Ui.column(this);int pad=org.retrobridge.companion.ui.Ui.dp(this,22);column.setPadding(pad,pad,pad,pad);scroll.addView(column);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        gmailStatus=status(column);gmailButton=button(column,"Connect Gmail",71);youtubeStatus=status(column);youtubeButton=button(column,"Connect YouTube",72);controlsButton=button(column,"Enable YouTube controls",73);
        String section=getIntent().getStringExtra("section");if("gmail".equals(section)){bar.setTitle("Gmail");youtubeStatus.setVisibility(View.GONE);youtubeButton.setVisibility(View.GONE);controlsButton.setVisibility(View.GONE);}else if("youtube".equals(section)){bar.setTitle("YouTube");gmailStatus.setVisibility(View.GONE);gmailButton.setVisibility(View.GONE);}
        setContentView(root);androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,(v,i)->{androidx.core.graphics.Insets in=i.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(in.left,in.top,in.right,in.bottom);return androidx.core.view.WindowInsetsCompat.CONSUMED;});androidx.core.view.ViewCompat.requestApplyInsets(root);showStatus();
    }
    TextView status(LinearLayout column){TextView view=org.retrobridge.companion.ui.Ui.text(this,"",16,org.retrobridge.companion.ui.Ui.textColor(this));view.setPadding(0,24,0,24);column.addView(view);return view;}
    Button button(LinearLayout column,String label,int operation){Button b=org.retrobridge.companion.ui.Ui.button(this,label,()->connect(operation));column.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
    protected void onSaveInstanceState(Bundle out){out.putInt("active",active);super.onSaveInstanceState(out);}
    TextView current(){return active>=72?youtubeStatus:gmailStatus;}
    void showStatus(){String gmail=MailAccount.email(this),youtube=YouTubeAccount.email(this);gmailStatus.setText(gmail.isEmpty()?"Gmail: not connected":"Gmail\n"+gmail);youtubeStatus.setText(youtube.isEmpty()?"YouTube: not connected":"YouTube\n"+YouTubeAccount.channel(this)+"\n"+youtube+(YouTubeAccount.controls(this)?"\nAccount controls enabled":"\nRead-only"));}
    void setBusy(boolean value){busy=value;gmailButton.setEnabled(!value);youtubeButton.setEnabled(!value);controlsButton.setEnabled(!value);}
    void connect(int operation){if(busy)return;active=operation;setBusy(true);current().setText("Connecting\u2026");
        AuthorizationRequest request=operation>=72?YouTubeAccount.request(this,operation==72,operation==73):MailAccount.request(this,true);
        Identity.getAuthorizationClient(this).authorize(request).addOnSuccessListener(this,result->{
            if(result.hasResolution()){try{startIntentSenderForResult(result.getPendingIntent().getIntentSender(),active,null,0,0,0);}catch(Exception e){failure(e);}}
            else accept(result);
        }).addOnFailureListener(this,this::failure);
    }
    protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=71&&request!=72&&request!=73)return;active=request;
        if(result!=RESULT_OK||data==null){setBusy(false);showStatus();return;}
        setBusy(true);try{accept(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data));}catch(Exception e){failure(e);}
    }
    void accept(AuthorizationResult result){final int operation=active;new Thread(()->{try{if(operation>=72)YouTubeAccount.accept(this,result,operation==73);else MailAccount.accept(this,result);runOnUiThread(()->{setBusy(false);showStatus();});}catch(Exception e){runOnUiThread(()->failure(e));}},"google-authorization").start();}
    void failure(Exception e){setBusy(false);String text=e instanceof ApiException?"Google setup error "+((ApiException)e).getStatusCode():e instanceof java.io.IOException?e.getMessage():"Could not connect Google";current().setText(text);}
}
