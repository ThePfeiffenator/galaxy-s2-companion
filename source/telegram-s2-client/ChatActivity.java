package org.retrobridge.galaxychat.s2;



import android.app.*;

import android.content.*;

import android.graphics.*;

import android.net.Uri;

import java.io.File;

import android.os.*;

import android.text.*;

import android.text.method.PasswordTransformationMethod;

import android.view.*;

import android.widget.*;

import java.util.*;

import org.json.*;



/** A native Holo interface for the S2. Telegram credentials are entered only on Pixel. */

public final class ChatActivity extends Activity implements ChatService.Observer {

    private ChatService service;

    private boolean bound,resumed,archive,restoring;
    private boolean chatListTopPending=true;

    private String chat="",draftChat="",reply="0",title="Telegram",readFingerprint="";private String menuState="";

    private TextView status,replyBar,empty,toolbarTitle,toolbarSubtitle;
    private ImageButton back,overflow;
    private FrameLayout content;
    private android.graphics.drawable.Drawable wallpaper;

    private Button more;
    private ImageButton send,attach,sticker;

    private AttachmentUi attachments;

    private final android.util.LruCache<String,Bitmap> images=new android.util.LruCache<String,Bitmap>(4*1024*1024){protected int sizeOf(String key,Bitmap b){return b.getRowBytes()*b.getHeight();}};

    private BaseAdapter stickerAdapter;

    private EditText composer;

    private LinearLayout composition;

    private ListView list;

    private final ArrayList<JSONObject> rows=new ArrayList<JSONObject>();

    private final RowAdapter adapter=new RowAdapter();

    private final Handler handler=new Handler();

    private final ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){service=((ChatService.LocalBinder)b).service();service.observe(ChatActivity.this);service.viewing(resumed);String requested=getIntent().getStringExtra("chat");if(requested!=null&&ClientRules.decimal(requested,true))open(requested);else if(chat.length()>0)open(chat);else service.showList(archive);changed();}public void onServiceDisconnected(ComponentName n){service=null;changed();}};

    public void onCreate(Bundle state){

        super.onCreate(state);getActionBar().hide();

        attachments=new AttachmentUi(this,new AttachmentUi.Sender(){public void send(File file,String name,String mime,String kind,String caption){if(service==null||!service.canSend(chat))throw new IllegalStateException("Connect to Pixel first");service.media.send(chat,file,name,mime,kind,caption,reply);reply="0";updateReply();}});attachments.restore(state);

        if(state!=null){chat=state.getString("chat","");archive=state.getBoolean("archive");reply=state.getString("reply","0");}

        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);
        root.addView(toolbar(),new LinearLayout.LayoutParams(-1,dp(56)));
        wallpaper=ClassicUi.wallpaper(this);

        status=text(13,0xff384a56);status.setPadding(dp(12),dp(8),dp(12),dp(8));root.addView(status);

        more=new Button(this);more.setText("Earlier");more.setPadding(0,0,0,0);more.setMinHeight(0);more.setTextSize(13);more.setTextColor(ClassicUi.BLUE);more.setBackgroundDrawable(ClassicUi.feedback(0x20000000));more.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(service!=null){if(chat.length()>0)list.setSelection(0);service.more();}}});root.addView(more,new LinearLayout.LayoutParams(-1,dp(32)));

        content=new FrameLayout(this);list=new ListView(this);list.setDivider(null);list.setDividerHeight(0);list.setCacheColorHint(Color.TRANSPARENT);list.setSelector(new android.graphics.drawable.ColorDrawable(0x18000000));list.setAdapter(adapter);list.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_DISABLED);content.addView(list,new FrameLayout.LayoutParams(-1,-1));

        empty=text(18,0xff687780);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(28),dp(16),dp(28),dp(16));content.addView(empty,new FrameLayout.LayoutParams(-1,-1));list.setEmptyView(empty);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));

        list.setOnItemClickListener(new AdapterView.OnItemClickListener(){public void onItemClick(AdapterView<?> p,View v,int pos,long id){if(chat.length()==0){JSONObject row=rows.get(pos);String blocked=row.optString("blocked_reason");if(blocked.length()>0){message("This chat",blocked);return;}open(row.optString("id"));}else chooseReply(rows.get(pos));}});

        list.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView v,int s){if(s==SCROLL_STATE_IDLE)markVisible();}public void onScroll(AbsListView v,int first,int count,int total){handler.removeCallbacks(markRead);handler.postDelayed(markRead,500);}});

        composition=new LinearLayout(this);composition.setOrientation(LinearLayout.VERTICAL);composition.setBackgroundResource(R.drawable.classic_compose_panel);replyBar=text(13,0xff0876a8);replyBar.setPadding(dp(12),dp(6),dp(12),dp(6));replyBar.setOnClickListener(new View.OnClickListener(){public void onClick(View v){reply="0";updateReply();saveDraft();}});composition.addView(replyBar);

        LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.BOTTOM);line.setPadding(0,0,0,dp(1));
        sticker=ClassicUi.icon(this,R.drawable.classic_ic_msg_panel_smiles,"Stickers");line.addView(sticker,new LinearLayout.LayoutParams(dp(40),dp(48)));sticker.setOnClickListener(new View.OnClickListener(){public void onClick(View v){stickers();}});
        composer=new EditText(this);composer.setHint("Message");composer.setTextColor(0xff222222);composer.setHintTextColor(0xffa0a0a0);composer.setTextSize(17);composer.setBackgroundColor(Color.TRANSPARENT);composer.setPadding(dp(2),dp(13),dp(2),dp(12));composer.setMinLines(1);composer.setMaxLines(5);composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);composer.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8192)});line.addView(composer,new LinearLayout.LayoutParams(0,-2,1));
        attach=ClassicUi.icon(this,R.drawable.classic_ic_ab_attach,"Attach photo, video or file");line.addView(attach,new LinearLayout.LayoutParams(dp(40),dp(48)));attach.setOnClickListener(new View.OnClickListener(){public void onClick(View v){attachments.choose();}});
        send=ClassicUi.icon(this,R.drawable.classic_ic_send,"Send");line.addView(send,new LinearLayout.LayoutParams(dp(44),dp(48)));composition.addView(line);root.addView(composition);setContentView(root);


        composer.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){if(!restoring&&service!=null){service.typing(s.length()>0);handler.removeCallbacks(save);handler.postDelayed(save,500);}}public void afterTextChanged(Editable e){updateSend();}});

        send.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(service==null)return;try{service.send(chat,composer.getText().toString(),reply);restoring=true;composer.setText("");restoring=false;reply="0";updateReply();saveDraft();}catch(Exception e){message("Message not sent",e.getMessage()==null?"Check the Pixel connection.":e.getMessage());}}});

        changed();

    }

    protected void onStart(){super.onStart();startService(new Intent(this,ChatService.class));bindService(new Intent(this,ChatService.class),connection,BIND_AUTO_CREATE);bound=true;}

    protected void onResume(){super.onResume();resumed=true;if(chat.length()==0){resetChatListPosition();changed();}attachments.resume();if(service!=null)service.viewing(true);handler.postDelayed(markRead,500);}

    protected void onPause(){ReactionUi.dismiss(this);attachments.pause();saveDraft();resumed=false;handler.removeCallbacks(markRead);if(service!=null)service.viewing(false);super.onPause();}

    protected void onStop(){if(service!=null)service.unobserve(this);if(bound){unbindService(connection);bound=false;}service=null;super.onStop();}

    public Object onRetainNonConfigurationInstance(){return attachments.retain();}

    protected void onDestroy(){attachments.destroy();handler.removeCallbacksAndMessages(null);super.onDestroy();}

    protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);attachments.result(request,result,data);}

    protected void onSaveInstanceState(Bundle b){attachments.save(b);b.putString("chat",chat);b.putBoolean("archive",archive);b.putString("reply",reply);super.onSaveInstanceState(b);}

    protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);String id=i.getStringExtra("chat");if(id!=null&&ClientRules.decimal(id,true)&&service!=null)open(id);}

    public void onBackPressed(){if(chat.length()>0){backToList();return;}if(archive){archive=false;backToList();return;}super.onBackPressed();}

    private void resetChatListPosition(){
        chatListTopPending=true;
        if(list!=null){
            list.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_DISABLED);
            // Reset the ListView's layout/selection state synchronously. Keeping
            // the conversation's old children until the next layout flashes its
            // bottom position and can overwrite the queued top selection.
            list.setAdapter(adapter);
        }
    }

    private void backToList(){saveDraft();chat="";resetChatListPosition();draftChat="";reply="0";readFingerprint="";title=archive?"Archived chats":"Telegram";getIntent().removeExtra("chat");if(service!=null)service.showList(archive);changed();}

    private void open(String id){if(service==null||!ClientRules.decimal(id,true))return;saveDraft();chat=id;JSONObject c=service.chat(id);title=c==null?"Conversation":c.optString("title","Conversation");String[] d=service.draft(chat);restoring=true;composer.setText(d[0]);restoring=false;draftChat=chat;reply=d[1];readFingerprint="";service.showChat(chat);service.viewing(resumed);changed();list.post(new Runnable(){public void run(){if(adapter.getCount()>0)list.setSelection(adapter.getCount()-1);}});}

    private void chooseReply(final JSONObject row){if(row.has("operation_id")){message("Send status",row.optString("state")+"\n"+row.optString("reason")+"\n\nReference: "+row.optString("operation_id")+"\nInterrupted sends are checked with the Pixel and are never resent automatically.");return;}if(!ClientRules.decimal(row.optString("id"),false))return;

        new AlertDialog.Builder(this).setTitle("Message").setItems(new String[]{"Reply","Copy text","React"},new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){if(which==0){reply=row.optString("id");updateReply();saveDraft();composer.requestFocus();}else if(which==2){ReactionUi.show(ChatActivity.this,service,row);}else{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Message",row.optString("text")));}}}).show();

    }

    private void updateReply(){replyBar.setVisibility(chat.length()>0&&!"0".equals(reply)?View.VISIBLE:View.GONE);replyBar.setText("Reply to message · tap to cancel");}

    private void updateSend(){sticker.setEnabled(service!=null&&service.canSend(chat));attach.setEnabled(service!=null&&service.canSend(chat)&&!service.media.uploading);send.setEnabled(service!=null&&chat.length()>0&&service.canSend(chat)&&composer.getText().length()>0);send.setAlpha(send.isEnabled()?1f:0.35f);attach.setAlpha(attach.isEnabled()?1f:0.35f);sticker.setAlpha(sticker.isEnabled()?1f:0.35f);}

    public void changed(){

        if(status==null)return;String transfer=service==null?"":service.media.progress;status.setText(transfer);status.setVisibility(transfer.length()>0?View.VISIBLE:View.GONE);String subtitle=service==null?"Opening…":service.connected()?(service.needsPixelSetup()?"Sign in on Pixel":""):service.sessionStarted()?"Connecting…":"Offline";toolbarSubtitle.setText(subtitle);toolbarSubtitle.setVisibility(subtitle.length()>0?View.VISIBLE:View.GONE);if(stickerAdapter!=null)stickerAdapter.notifyDataSetChanged();toolbarTitle.setText(chat.length()>0?title:(archive?"Archived chats":"Telegram"));back.setVisibility(chat.length()>0||archive?View.VISIBLE:View.GONE);content.setBackgroundDrawable(chat.length()>0?wallpaper:new android.graphics.drawable.ColorDrawable(Color.WHITE));list.setPadding(0,chat.length()>0?dp(4):0,0,chat.length()>0?dp(4):0);composition.setVisibility(chat.length()>0?View.VISIBLE:View.GONE);

        int first=list.getFirstVisiblePosition();View top=list.getChildAt(0);int offset=top==null?0:top.getTop();boolean bottom=list.getCount()==0||list.getLastVisiblePosition()>=list.getCount()-2;

        rows.clear();if(service!=null){if(chat.length()==0)rows.addAll(service.chats(archive));else{rows.addAll(service.messages(chat));HashSet<String> delivered=new HashSet<String>();for(JSONObject r:rows)delivered.add(r.optString("id"));for(JSONObject op:service.operations(chat)){if("SENT".equals(op.optString("state"))&&op.optString("text").length()==0)continue;if(!delivered.contains(op.optString("message_id")))rows.add(op);}}}

        // A conversation and the chat list share this ListView. Never carry the
        // conversation's bottom position/transcript behavior into the chat list.
        list.setTranscriptMode(chat.length()>0?AbsListView.TRANSCRIPT_MODE_NORMAL:AbsListView.TRANSCRIPT_MODE_DISABLED);
        adapter.notifyDataSetChanged();
        if(chat.length()==0&&chatListTopPending){
            list.setSelectionFromTop(0,0);
            if(!rows.isEmpty())chatListTopPending=false;
        }else if(chat.length()>0&&bottom&&rows.size()>0)list.setSelection(rows.size()-1);
        else list.setSelectionFromTop(first,offset);

        more.setVisibility(service!=null&&service.connected()&&service.hasMore()?View.VISIBLE:View.GONE);more.setText(chat.length()>0?"Earlier":"More");

        empty.setText(service!=null&&service.needsPixelSetup()?"Sign in on Pixel":chat.length()>0?"No messages yet":"No chats yet");updateReply();updateSend();String menuNow=(chat.length()>0)+":"+(service!=null&&service.sessionStarted())+":"+(service!=null&&service.connected());if(!menuNow.equals(menuState)){menuState=menuNow;invalidateOptionsMenu();}handler.removeCallbacks(markRead);handler.postDelayed(markRead,500);

    }

    private final Runnable save=new Runnable(){public void run(){saveDraft();}};

    private final Runnable markRead=new Runnable(){public void run(){markVisible();}};

    private void saveDraft(){if(service!=null&&composer!=null&&chat.length()>0&&chat.equals(draftChat))service.draft(chat,composer.getText().toString(),reply);}

    private void markVisible(){if(service==null||!resumed||chat.length()==0||!service.connected())return;JSONArray ids=new JSONArray();StringBuilder fingerprint=new StringBuilder(chat);int a=Math.max(0,list.getFirstVisiblePosition()),b=Math.min(rows.size()-1,list.getLastVisiblePosition());for(int i=a;i<=b&&ids.length()<50;i++){JSONObject m=rows.get(i);String id=m.optString("id");if(!m.has("operation_id")&&ClientRules.decimal(id,false)){ids.put(id);fingerprint.append(':').append(id);}}if(ids.length()>0&&!fingerprint.toString().equals(readFingerprint)){if(service.visible(chat,ids))readFingerprint=fingerprint.toString();else handler.postDelayed(markRead,1000);}}

    public boolean onCreateOptionsMenu(Menu menu){menu.add(0,7,0,"Saved Messages");menu.add(0,8,1,"Stickers");menu.add(0,1,1,"Connect");menu.add(0,2,1,"Refresh");menu.add(0,3,2,"Archived chats");menu.add(0,4,3,"Pairing key");menu.add(0,5,4,"Clear cached chats");menu.add(0,6,5,"About");return true;}

    public boolean onPrepareOptionsMenu(Menu menu){menu.findItem(8).setVisible(chat.length()>0);menu.findItem(1).setTitle(service!=null&&service.sessionStarted()?"Pause connection":"Connect");menu.findItem(2).setEnabled(service!=null&&service.connected());return true;}

    public boolean onOptionsItemSelected(MenuItem item){if(item.getItemId()==android.R.id.home){onBackPressed();return true;}if(service==null)return true;switch(item.getItemId()){

        case 8:stickers();break;

        case 7:service.savedMessages(new ChatService.SavedCallback(){public void ready(String id,String error){if(id.length()>0)open(id);else message("Saved Messages",error);}});break;

        case 1:if(service.sessionStarted())service.disconnect();else try{service.connect();}catch(Exception e){message("Connect",e.getMessage());}break;

        case 2:readFingerprint="";service.refresh();break;

        case 3:saveDraft();archive=!archive;backToList();break;

        case 4:pairDialog();break;

        case 5:new AlertDialog.Builder(this).setTitle("Clear local cache?").setMessage("Removes cached chats, messages, and drafts on this S2. Your Telegram account and durable send records are kept.").setNegativeButton("Cancel",null).setPositiveButton("Clear",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){service.clearCache();restoring=true;composer.setText("");restoring=false;reply="0";changed();}}).show();break;

        case 6:message("Telegram","Personal Telegram companion · 0.5.0\n\n"+(service==null?"Offline":service.status())+"\n\nReconnects automatically. Account settings stay on Pixel.");break;

        default:return super.onOptionsItemSelected(item);

    }return true;}

    private void pairDialog(){final EditText e=new EditText(this);e.setSingleLine(true);e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);e.setTransformationMethod(PasswordTransformationMethod.getInstance());new AlertDialog.Builder(this).setTitle("App pairing key").setMessage(service.pairedKey()?"A private key is already installed. Replace it only when pairing again with Telegram Companion on the Pixel.":"Enter the app pairing key shown on your Pixel. This is not your Telegram password.").setView(e).setNegativeButton("Cancel",null).setPositiveButton("Save",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){try{service.pairingKey(e.getText().toString().trim());Toast.makeText(ChatActivity.this,"Pairing key saved",Toast.LENGTH_SHORT).show();}catch(Exception ex){message("Pairing key",ex.getMessage());}}}).show();}

    private void message(String title,String body){new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("OK",null).show();}

    private View toolbar(){
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setBackgroundColor(ClassicUi.BLUE);
        back=ClassicUi.icon(this,R.drawable.classic_ic_ab_back,"Back");bar.addView(back,new LinearLayout.LayoutParams(dp(48),-1));back.setOnClickListener(new View.OnClickListener(){public void onClick(View v){onBackPressed();}});
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.setGravity(Gravity.CENTER_VERTICAL);labels.setPadding(dp(16),0,0,0);toolbarTitle=text(20,Color.WHITE);toolbarTitle.setSingleLine();toolbarTitle.setEllipsize(TextUtils.TruncateAt.END);toolbarTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);toolbarSubtitle=text(13,0xffd5e8f7);toolbarSubtitle.setSingleLine();toolbarSubtitle.setEllipsize(TextUtils.TruncateAt.END);labels.addView(toolbarTitle);labels.addView(toolbarSubtitle);bar.addView(labels,new LinearLayout.LayoutParams(0,-1,1));
        overflow=ClassicUi.icon(this,R.drawable.classic_ic_ab_other,"More options");bar.addView(overflow,new LinearLayout.LayoutParams(dp(48),-1));overflow.setOnClickListener(new View.OnClickListener(){public void onClick(View v){PopupMenu menu=new PopupMenu(ChatActivity.this,overflow);onCreateOptionsMenu(menu.getMenu());onPrepareOptionsMenu(menu.getMenu());menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener(){public boolean onMenuItemClick(MenuItem item){return onOptionsItemSelected(item);}});menu.show();}});return bar;
    }

    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}

    private TextView text(int size,int color){TextView t=new TextView(this);t.setTextSize(size);t.setTextColor(color);return t;}

    private Bitmap picture(File f){if(f==null)return null;String key=f.getPath();Bitmap b=images.get(key);if(b==null){BitmapFactory.Options o=new BitmapFactory.Options();o.inPreferredConfig=Bitmap.Config.RGB_565;b=BitmapFactory.decodeFile(key,o);if(b!=null)images.put(key,b);}return b;}

    private void openMedia(final JSONObject row){if(service==null)return;service.media.open(row,new MediaClient.Result(){public void done(File f,String mime,String name,String error){if(!resumed||isFinishing())return;if(error!=null){message("Attachment",error);return;}Uri uri=new Uri.Builder().scheme("content").authority("org.retrobridge.galaxychat.s2.media").appendPath(f.getName()).appendQueryParameter("mime",mime).appendQueryParameter("name",name).build();Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra("name",name);if(mime.startsWith("image/")||mime.startsWith("video/")||mime.startsWith("audio/")||mime.startsWith("text/"))i.setClass(ChatActivity.this,MediaActivity.class);try{startActivity(i);}catch(Exception e){message("Attachment","No viewer is installed for this file type.");}}});}

    private void stickers(){if(service==null||!service.canSend(chat)){message("Stickers","Connect to Pixel first");return;}service.media.stickerSets(new ChatService.DataResult(){public void done(JSONObject data,String error){if(!resumed||isFinishing())return;if(error!=null){message("Stickers",error);return;}final ArrayList<String> ids=new ArrayList<String>(),names=new ArrayList<String>();ids.add("recent");names.add("Recent");ids.add("favorites");names.add("Favorites");JSONArray sets=data.optJSONArray("sets");for(int i=0;sets!=null&&i<sets.length();i++){JSONObject set=sets.optJSONObject(i);ids.add(set.optString("id"));names.add(set.optString("title"));}new AlertDialog.Builder(ChatActivity.this).setTitle("Stickers").setItems(names.toArray(new String[0]),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int at){stickerPage(ids.get(at),0);}}).show();}});}

    private void stickerPage(final String set,final int offset){if(service==null)return;service.media.stickers(set,offset,new ChatService.DataResult(){public void done(final JSONObject data,String error){if(!resumed||isFinishing())return;if(error!=null){message("Stickers",error);return;}final JSONArray items=data.optJSONArray("stickers");if(items==null||items.length()==0){message("Stickers","No stickers in this set yet");return;}GridView grid=new GridView(ChatActivity.this);grid.setNumColumns(3);stickerAdapter=new BaseAdapter(){public int getCount(){return items.length();}public Object getItem(int p){return items.optJSONObject(p);}public long getItemId(int p){return p;}public View getView(int p,View recycled,android.view.ViewGroup parent){JSONObject item=items.optJSONObject(p);ImageView image=recycled instanceof ImageView?(ImageView)recycled:new ImageView(ChatActivity.this);image.setLayoutParams(new AbsListView.LayoutParams(-1,dp(100)));image.setPadding(dp(5),dp(5),dp(5),dp(5));image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImageBitmap(service==null?null:picture(service.media.stickerPreview(item.optInt("file_id"))));image.setContentDescription(item.optString("emoji","Sticker"));return image;}};grid.setAdapter(stickerAdapter);AlertDialog.Builder builder=new AlertDialog.Builder(ChatActivity.this).setTitle("Stickers").setView(grid).setNegativeButton("Close",null);if(data.optBoolean("more"))builder.setNeutralButton("More",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){stickerPage(set,data.optInt("next"));}});final AlertDialog dialog=builder.create();grid.setOnItemClickListener(new AdapterView.OnItemClickListener(){public void onItemClick(AdapterView<?> a,View v,int at,long id){if(service==null)return;service.media.sendSticker(chat,items.optJSONObject(at).optInt("file_id"),reply);dialog.dismiss();}});dialog.setOnDismissListener(new DialogInterface.OnDismissListener(){public void onDismiss(DialogInterface d){stickerAdapter=null;}});dialog.show();}});}

    private final class Row extends FrameLayout {
        final ImageView avatar,preview;final LinearLayout column;final TextView head,body,foot,time,badge,reactions;final Button media;final View separator;
        Row(){super(ChatActivity.this);avatar=new ClassicUi.Avatar(ChatActivity.this);addView(avatar);column=new LinearLayout(ChatActivity.this);column.setOrientation(LinearLayout.VERTICAL);addView(column);head=text(17,0xff212121);head.setTypeface(Typeface.DEFAULT,Typeface.BOLD);body=text(16,0xff000000);body.setLinkTextColor(0xff2678b6);foot=text(11,0xffa1aab3);foot.setGravity(Gravity.RIGHT);preview=new ImageView(ChatActivity.this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setAdjustViewBounds(true);media=new Button(ChatActivity.this);media.setTextColor(ClassicUi.BLUE);media.setTextSize(14);media.setMinHeight(0);media.setPadding(0,0,0,0);media.setBackgroundDrawable(ClassicUi.feedback(0x16000000));column.addView(head);column.addView(preview);column.addView(body);column.addView(media,new LinearLayout.LayoutParams(-1,dp(36)));reactions=text(14,ClassicUi.BLUE);reactions.setPadding(0,dp(4),0,dp(4));column.addView(reactions);column.addView(foot);time=text(12,0xffa8a8a8);time.setGravity(Gravity.RIGHT);addView(time);badge=text(13,Color.WHITE);badge.setGravity(Gravity.CENTER);badge.setBackgroundResource(R.drawable.classic_dialogs_badge);addView(badge);separator=new View(ChatActivity.this);separator.setBackgroundColor(0xffe8e8e8);addView(separator);setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);}
        void layout(boolean conversation,boolean own){
            setBackgroundColor(Color.TRANSPARENT);setPadding(0,0,0,0);setMinimumHeight(conversation?0:dp(72));time.setVisibility(conversation?View.GONE:View.VISIBLE);badge.setVisibility(View.GONE);separator.setVisibility(conversation?View.GONE:View.VISIBLE);
            FrameLayout.LayoutParams icon=new FrameLayout.LayoutParams(dp(52),dp(52),Gravity.LEFT|Gravity.TOP);icon.leftMargin=dp(10);icon.topMargin=dp(10);avatar.setLayoutParams(icon);
            FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(conversation?-2:-1,-2,own?Gravity.RIGHT:Gravity.LEFT);cp.leftMargin=dp(conversation?(own?56:6):72);cp.rightMargin=dp(conversation?(own?6:56):12);cp.topMargin=dp(conversation?2:12);cp.bottomMargin=dp(conversation?2:12);column.setLayoutParams(cp);column.setBackgroundResource(conversation?(own?R.drawable.classic_msg_out:R.drawable.classic_msg_in):0);column.setPadding(dp(conversation?12:0),dp(conversation?7:0),dp(conversation?12:0),dp(conversation?6:0));
            int width=Math.max(dp(120),getResources().getDisplayMetrics().widthPixels-dp(86));body.setMaxWidth(width);head.setMaxWidth(width);head.setPadding(0,0,conversation?0:dp(60),dp(conversation?2:6));head.setTextSize(conversation?14:17);head.setTextColor(conversation?ClassicUi.BLUE:0xff212121);head.setSingleLine(!conversation);head.setEllipsize(conversation?null:TextUtils.TruncateAt.END);body.setEllipsize(conversation?null:TextUtils.TruncateAt.END);body.setTextColor(conversation?Color.BLACK:0xff8f8f8f);foot.setTextColor(own?0xff70b15c:0xffa1aab3);foot.setCompoundDrawablesWithIntrinsicBounds(0,0,0,0);foot.setCompoundDrawablePadding(dp(3));
            preview.setLayoutParams(new LinearLayout.LayoutParams(Math.min(dp(230),width),dp(170)));FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(dp(66),dp(18),Gravity.RIGHT|Gravity.TOP);tp.rightMargin=dp(12);tp.topMargin=dp(14);time.setLayoutParams(tp);FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-2,dp(22),Gravity.RIGHT|Gravity.TOP);bp.rightMargin=dp(12);bp.topMargin=dp(40);badge.setLayoutParams(bp);badge.setMinWidth(dp(22));badge.setPadding(dp(5),0,dp(5),0);FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-1,1,Gravity.BOTTOM);sp.leftMargin=dp(72);separator.setLayoutParams(sp);
        }
    }

    private final class RowAdapter extends BaseAdapter {

        public int getCount(){return rows.size();}public Object getItem(int p){return rows.get(p);}public long getItemId(int p){return p;}

        public View getView(int p,View reused,android.view.ViewGroup parent){final JSONObject r=rows.get(p);Row v=reused instanceof Row?(Row)reused:new Row();v.media.setVisibility(View.GONE);v.preview.setVisibility(View.GONE);v.preview.setImageDrawable(null);v.head.setVisibility(View.VISIBLE);v.body.setVisibility(View.VISIBLE);v.foot.setVisibility(View.GONE);v.reactions.setVisibility(View.GONE);boolean op=r.has("operation_id");v.layout(chat.length()>0,op||r.optBoolean("outgoing"));

            if(chat.length()==0){v.avatar.setVisibility(View.VISIBLE);Bitmap icon=service==null||r.optInt("avatar_file")==0?null:picture(service.media.preview(r.optString("id"),"0",r.optInt("avatar_file"),"icon"));if(icon!=null)v.avatar.setImageBitmap(icon);else {String title=r.optString("title","?");Bitmap initial=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(initial);Paint paint=new Paint(3);paint.setColor(0xff548ead);c.drawCircle(32,32,31,paint);paint.setColor(Color.WHITE);paint.setTextSize(30);paint.setTextAlign(Paint.Align.CENTER);c.drawText(title.length()==0?"?":title.substring(0,title.offsetByCodePoints(0,1)).toUpperCase(java.util.Locale.US),32,43,paint);v.avatar.setImageBitmap(initial);}int unread=r.optInt("unread");v.badge.setVisibility(unread>0?View.VISIBLE:View.GONE);v.badge.setText(Integer.toString(unread));long last=r.optLong("last_date");v.time.setText(last<=0?"":android.text.format.DateFormat.format("MMM d",new Date(last*1000L)));v.head.setText(r.optString("title"));v.body.setPadding(0,0,unread>0?dp(36):0,0);v.body.setText(r.optString("last_text"));v.body.setAutoLinkMask(0);v.body.setMaxLines(1);v.body.setTextSize(16);}

            else{v.avatar.setVisibility(View.GONE);boolean own=op||r.optBoolean("outgoing");v.body.setPadding(0,0,0,0);v.head.setText(own?"":r.optString("sender"));v.head.setVisibility(own?View.GONE:View.VISIBLE);String body=r.optString("text");JSONObject attachment=r.optJSONObject("media");if(attachment!=null){int closing=body.indexOf(']');if(body.startsWith("[")&&closing>=0)body=body.substring(closing+1).trim();v.media.setVisibility(View.VISIBLE);String kind=attachment.optString("kind");String label=kind.equals("photo")?"Photo":kind.equals("video")||kind.equals("animation")?"Play video":kind.equals("sticker")?"Sticker":kind.equals("voice")||kind.equals("audio")?"Play audio":attachment.optString("name","Open file");v.media.setText(label);v.media.setOnClickListener(new View.OnClickListener(){public void onClick(View view){openMedia(r);}});if(attachment.optBoolean("preview")){v.preview.setVisibility(View.VISIBLE);v.preview.setImageBitmap(service==null?null:picture(service.media.preview(r.optString("chat_id"),r.optString("id"),attachment.optInt("file_id"),"preview")));v.preview.setOnClickListener(new View.OnClickListener(){public void onClick(View view){openMedia(r);}});}}

                v.body.setText(op&&body.length()==0?"Sent":body);v.body.setVisibility(body.length()>0?View.VISIBLE:View.GONE);v.body.setTextSize(17);v.body.setMaxLines(1024);v.body.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);String when=android.text.format.DateFormat.format("MMM d, h:mm a",new Date(op?r.optLong("created"):r.optLong("date")*1000L)).toString();String state=op?r.optString("state"):r.optString("send_state","SENT");String extra=own?("SENT".equals(state)?"":"  "+state.toLowerCase(java.util.Locale.US)):"";if(r.optBoolean("edited"))extra+=" · edited";if(!"0".equals(r.optString("reply_to_id","0")))extra+=" · reply";ReactionUi.bindCounts(v.reactions,ChatActivity.this,service,r);v.foot.setText(when+extra);if(own&&"SENT".equals(state))v.foot.setCompoundDrawablesWithIntrinsicBounds(0,0,R.drawable.classic_msg_check,0);v.foot.setVisibility(View.VISIBLE);}

            return v;

        }

    }

}

