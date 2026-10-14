package org.retrobridge.galaxychat.s2;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.*;
import android.text.method.LinkMovementMethod;
import android.text.style.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.retrobridge.galaxychat.protocol.Wire;

final class ReactionUi {
    private static java.lang.ref.WeakReference<Dialog> active=new java.lang.ref.WeakReference<Dialog>(null);
    private static java.lang.ref.WeakReference<Activity> owner=new java.lang.ref.WeakReference<Activity>(null);
    static void dismiss(Activity app){Dialog d=active.get();if(d!=null&&owner.get()==app){d.dismiss();active.clear();owner.clear();}}
    static void show(final Activity app,final ChatService service,final JSONObject message){
        if(service==null||!service.connected()){Toast.makeText(app,"Connect to Pixel",Toast.LENGTH_SHORT).show();return;}
        final String chat=message.optString("chat_id"),id=message.optString("id");
        dismiss(app);final ProgressDialog progress=new ProgressDialog(app);progress.setMessage("Loading reactions…");progress.setCancelable(true);progress.show();active=new java.lang.ref.WeakReference<Dialog>(progress);owner=new java.lang.ref.WeakReference<Activity>(app);
        new Thread(new Runnable(){public void run(){try{
            final JSONArray choices=service.rpc("GET_REACTIONS",Wire.json("chat_id",chat,"message_id",id)).getJSONArray("choices");
            service.reactionIcons.ensure(choices);
            app.runOnUiThread(new Runnable(){public void run(){
                if(app.isFinishing()||!progress.isShowing())return;progress.dismiss();
                if(choices.length()==0){Toast.makeText(app,"No available reactions",Toast.LENGTH_SHORT).show();return;}
                showChoices(app,service,chat,id,choices);
            }});
        }catch(Exception e){app.runOnUiThread(new Runnable(){public void run(){if(app.isFinishing()||!progress.isShowing())return;progress.dismiss();Toast.makeText(app,"Reactions unavailable",Toast.LENGTH_LONG).show();}});}}},"reaction-picker").start();
    }
    static AlertDialog showChoices(final Activity app,final ChatService service,final String chat,final String id,JSONArray choices){
        ScrollView scroll=new ScrollView(app);LinearLayout table=new LinearLayout(app);table.setOrientation(LinearLayout.VERTICAL);table.setPadding(ClassicUi.dp(app,8),ClassicUi.dp(app,4),ClassicUi.dp(app,8),ClassicUi.dp(app,4));scroll.addView(table);
        final AlertDialog dialog=new AlertDialog.Builder(app).setTitle("React").setView(scroll).setNegativeButton("Cancel",null).create();
        LinearLayout row=null;
        for(int i=0;i<choices.length();i++){
            final JSONObject choice=choices.optJSONObject(i);if(choice==null)continue;
            if(i%5==0){row=new LinearLayout(app);table.addView(row,new LinearLayout.LayoutParams(-1,ClassicUi.dp(app,56)));}
            FrameLayout cell=new FrameLayout(app);final ImageButton button=new ImageButton(app);
            button.setImageBitmap(service.reactionIcons.get(choice.optString("emoji")));button.setScaleType(ImageView.ScaleType.FIT_CENTER);int padding=ClassicUi.dp(app,10);button.setPadding(padding,padding,padding,padding);
            button.setBackgroundDrawable(ClassicUi.feedback(0x22527da3));button.setContentDescription(choice.optString("emoji"));cell.addView(button,new FrameLayout.LayoutParams(-1,-1));
            if(choice.optBoolean("chosen")){cell.setBackgroundColor(0x20527da3);TextView check=new TextView(app);check.setText("✓");check.setTextColor(ClassicUi.BLUE);check.setTextSize(13);check.setGravity(Gravity.RIGHT|Gravity.BOTTOM);cell.addView(check,new FrameLayout.LayoutParams(-1,-1));}
            row.addView(cell,new LinearLayout.LayoutParams(0,-1,1));
            button.setOnClickListener(new View.OnClickListener(){public void onClick(View v){dialog.dismiss();set(app,service,chat,id,choice.optString("emoji"),!choice.optBoolean("chosen"));}});
        }
        int remainder=choices.length()%5;if(row!=null&&remainder!=0)for(int i=remainder;i<5;i++)row.addView(new View(app),new LinearLayout.LayoutParams(0,-1,1));
        dialog.show();active=new java.lang.ref.WeakReference<Dialog>(dialog);owner=new java.lang.ref.WeakReference<Activity>(app);return dialog;
    }
    static void set(final Activity app,final ChatService service,final String chat,final String id,final String emoji,final boolean chosen){
        if(service==null||!service.connected()){Toast.makeText(app,"Connect to Pixel",Toast.LENGTH_SHORT).show();return;}
        new Thread(new Runnable(){public void run(){try{service.rpc("SET_REACTION",Wire.json("chat_id",chat,"message_id",id,"emoji",emoji,"chosen",chosen));app.runOnUiThread(new Runnable(){public void run(){service.refresh();}});}catch(Exception e){app.runOnUiThread(new Runnable(){public void run(){if(!app.isFinishing())Toast.makeText(app,"Check the reaction before trying again",Toast.LENGTH_LONG).show();}});}}},"reaction-set").start();
    }
    static void bindCounts(TextView view,final Activity app,final ChatService service,final JSONObject message){
        JSONArray rows=message.optJSONArray("reactions");SpannableStringBuilder text=new SpannableStringBuilder();
        for(int i=0;rows!=null&&i<rows.length();i++){
            final JSONObject reaction=rows.optJSONObject(i);if(reaction==null||reaction.optInt("count")<1)continue;
            if(text.length()>0)text.append("   ");int start=text.length();text.append(" ");
            Bitmap bitmap=service==null?null:service.reactionIcons.get(reaction.optString("emoji"));
            if(bitmap!=null){Drawable icon=new BitmapDrawable(app.getResources(),bitmap);int side=ClassicUi.dp(app,22);icon.setBounds(0,0,side,side);text.setSpan(new ImageSpan(icon,ImageSpan.ALIGN_BOTTOM),start,start+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}else text.replace(start,start+1,"…");
            text.append(" ").append(Integer.toString(reaction.optInt("count")));
            if(reaction.optBoolean("chosen")){text.append(" ✓");text.setSpan(new BackgroundColorSpan(0x20527da3),start,text.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
            text.setSpan(new ClickableSpan(){public void onClick(View v){set(app,service,message.optString("chat_id"),message.optString("id"),reaction.optString("emoji"),!reaction.optBoolean("chosen"));}public void updateDrawState(TextPaint p){p.setColor(ClassicUi.BLUE);p.setUnderlineText(false);}},start,text.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setOnClickListener(null);view.setText(text);view.setMovementMethod(LinkMovementMethod.getInstance());view.setVisibility(text.length()==0?View.GONE:View.VISIBLE);
        if(service!=null)service.reactionIcons.prefetch(rows);
    }
}
