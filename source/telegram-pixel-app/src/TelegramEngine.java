package org.retrobridge.galaxychat.pixel;

import android.content.Context;
import org.drinkless.tdlib.JsonClient;
import org.json.*;
import org.retrobridge.galaxychat.protocol.Wire;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** One new TDLib user session. Only an allowlisted application protocol leaves this app. */
final class TelegramEngine {
    interface Listener {void event(JSONObject event);}
    interface Guard {long remaining()throws Problem;}
    static final class Problem extends Exception {final String code;final int retry;Problem(String code,String message){this(code,message,0);}Problem(String c,String m,int r){super(m);code=c;retry=r;}}
    private static TelegramEngine instance;
    static synchronized TelegramEngine get(Context c){if(instance==null)instance=new TelegramEngine(c.getApplicationContext());return instance;}
    final Context context;final Secrets secrets;final SendJournal journal;final MediaFeatures media;
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String,CompletableFuture<JSONObject>> pending=new ConcurrentHashMap<>();
    private final AtomicLong ids=new AtomicLong();
    private final LinkedHashMap<Long,JSONObject> chats=new LinkedHashMap<>();
    private final HashMap<Long,JSONObject> users=new HashMap<>();
    private final ArrayDeque<JSONObject> events=new ArrayDeque<>();
    private final ArrayList<Listener> listeners=new ArrayList<>();
    final String epoch=UUID.randomUUID().toString();private long eventSeq;
    private long selfUser;private int client;private volatile boolean running,nativeReady;private volatile String auth="SETUP_REQUIRED",connection="CONNECTING",notice="Enter your own Telegram API credentials on this Pixel.";
    private volatile JSONObject authorization=new JSONObject();private boolean parametersSent;
    private Guard requestGuard;
    private TelegramEngine(Context c){context=c;secrets=new Secrets(c);journal=new SendJournal(c);media=new MediaFeatures(this);
        try{
            JSONObject empty=new JSONObject(JsonClient.execute(Wire.json("@type","setLogStream","log_stream",Wire.json("@type","logStreamEmpty")).toString()));
            JSONObject quiet=new JSONObject(JsonClient.execute(Wire.json("@type","setLogVerbosityLevel","new_verbosity_level",0).toString()));
            JSONObject check=new JSONObject(JsonClient.execute(Wire.json("@type","getTextEntities","text","offline check").toString()));
            if(!"ok".equals(empty.optString("@type"))||!"ok".equals(quiet.optString("@type"))||!"textEntities".equals(check.optString("@type")))throw new IOException("Native readiness failed");
            nativeReady=true;
            client=JsonClient.createClientId();running=true;
            Thread receiver=new Thread(()->receive(),"Galaxy Chat TDLib");receiver.setDaemon(true);receiver.start();
            worker.execute(()->{try{JSONObject a=call(Wire.json("@type","getAuthorizationState"));auth(a);}catch(Exception e){notice="Telegram engine is not ready.";}});
        }catch(Throwable unavailable){auth="ERROR";notice="The Telegram native engine could not be loaded.";}
    }
    JSONObject status(){return Wire.json("auth",auth,"connection",connection,"send_enabled","READY".equals(auth),"scope","cloud_media","notice",notice);}
    JSONObject localAuthorization(){return authorization;}
    boolean nativeReady(){return nativeReady;}
    private void receive(){while(running){try{String raw=JsonClient.receive(1.0);if(raw==null)continue;JSONObject value=new JSONObject(raw);
        if(value.optInt("@client_id",client)!=client)continue;String extra=value.optString("@extra","");CompletableFuture<JSONObject> future=pending.remove(extra);
        if(future!=null){future.complete(value);continue;}
        worker.execute(()->update(value));
    }catch(Throwable e){running=false;auth="ERROR";notice="Telegram engine stopped; reopen the app to recover.";for(CompletableFuture<JSONObject> f:pending.values())f.completeExceptionally(new IOException("Engine stopped"));pending.clear();}}}
    JSONObject call(JSONObject request)throws Exception{
        if(!running)throw new Problem("UNAVAILABLE","Telegram engine is unavailable.");
        String id=Long.toString(ids.incrementAndGet());CompletableFuture<JSONObject> answer=new CompletableFuture<>();pending.put(id,answer);
        try{long timeout=requestGuard==null?25000:Math.min(25000,requestGuard.remaining());request.put("@extra",id);JsonClient.send(client,request.toString());JSONObject result=answer.get(timeout,TimeUnit.MILLISECONDS);
            if("error".equals(result.optString("@type")))throw tdError(result);return result;
        }finally{pending.remove(id);}
    }
    private Problem tdError(JSONObject e){int code=e.optInt("code");String message=e.optString("message","");
        if(code==429){int retry=0;java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:FLOOD_WAIT_|retry after )([0-9]{1,7})").matcher(message);if(m.find())try{retry=Integer.parseInt(m.group(1));}catch(Exception ignored){}return new Problem("RATE_LIMIT","Telegram asked this client to wait.",retry);}
        if(code==401)return new Problem("AUTH_REQUIRED","Complete login on the Pixel.");
        if(code==403)return new Problem("FORBIDDEN","Telegram does not allow this action in this chat.");
        if(code==404)return new Problem("NOT_FOUND","The requested chat or message is unavailable.");
        return new Problem("UNAVAILABLE","Telegram could not complete this request.");
    }
    private void auth(JSONObject a)throws Exception{
        authorization=a;String state=a.optString("@type");
        if(state.equals("authorizationStateWaitTdlibParameters")){auth="SETUP_REQUIRED";parameters();}
        else if(state.equals("authorizationStateWaitPhoneNumber")){auth="WAIT_PHONE";notice="Enter your phone number here to create a new Telegram session.";}
        else if(state.equals("authorizationStateWaitCode")){auth="WAIT_CODE";notice="Enter the login code Telegram sent you.";}
        else if(state.equals("authorizationStateWaitPassword")){auth="WAIT_PASSWORD";notice="Enter your Telegram two-step verification password.";}
        else if(state.equals("authorizationStateWaitEmailAddress")){auth="WAIT_EMAIL";notice="Telegram requires a login email address.";}
        else if(state.equals("authorizationStateWaitEmailCode")){auth="WAIT_EMAIL_CODE";notice="Enter the code sent to your login email.";}
        else if(state.equals("authorizationStateReady")){auth="READY";notice="Connected";selfUser=call(Wire.json("@type","getMe")).getLong("id");reconcile();}
        else if(state.equals("authorizationStateClosed")){auth="CLOSED";notice="Telegram session closed. Reopen the app to start again.";running=false;}
        else{auth="WAIT_OTHER";notice=state.equals("authorizationStateWaitOtherDeviceConfirmation")?"Approve this new session using the link shown on this Pixel.":"Telegram requires a login step this prototype cannot complete. Use the official Telegram app to review your account.";}
        emit("STATUS",status());
    }
    private void parameters()throws Exception{
        if(parametersSent)return;JSONObject saved=secrets.read();if(saved.optInt("api_id",0)<=0||!saved.optString("api_hash","").matches("[0-9a-fA-F]{32}"))return;
        parametersSent=true;
        try{call(Wire.json("@type","setTdlibParameters","use_test_dc",false,"database_directory",new File(context.getFilesDir(),"tdlib").getAbsolutePath(),
            "files_directory",new File(context.getFilesDir(),"td-files").getAbsolutePath(),"database_encryption_key",Wire.token(secrets.databaseKey()),
            "use_file_database",true,"use_chat_info_database",true,"use_message_database",true,"use_secret_chats",false,
            "api_id",saved.getInt("api_id"),"api_hash",saved.getString("api_hash"),"system_language_code","en","device_model","Galaxy Chat Pixel companion",
            "system_version",android.os.Build.VERSION.RELEASE,"application_version","0.2"));}
        catch(Exception e){parametersSent=false;throw e;}
    }
    void configure(int api,String hash,java.util.function.Consumer<String> done){worker.execute(()->{try{if(api<=0||!hash.matches("[0-9a-fA-F]{32}"))throw new Problem("BAD_REQUEST","Use the API ID and hash from my.telegram.org.");if(!"SETUP_REQUIRED".equals(auth))throw new Problem("CONFLICT","API settings are already in use by this session.");secrets.credentials(api,hash);parameters();done.accept("Saved locally.");}catch(Exception e){done.accept(friendly(e));}});}
    void authenticate(String action,String value,java.util.function.Consumer<String> done){worker.execute(()->{try{
        JSONObject q;if(action.equals("WAIT_PHONE")&&auth.equals(action)){if(!value.matches("\\+?[0-9]{5,20}"))throw new Problem("BAD_REQUEST","Enter the full phone number including country code.");q=Wire.json("@type","setAuthenticationPhoneNumber","phone_number",value,"settings",Wire.json("@type","phoneNumberAuthenticationSettings","allow_flash_call",false,"allow_missed_call",false,"allow_sms_retriever_api",false));}
        else if(action.equals("WAIT_CODE")&&auth.equals(action))q=Wire.json("@type","checkAuthenticationCode","code",value);
        else if(action.equals("WAIT_PASSWORD")&&auth.equals(action))q=Wire.json("@type","checkAuthenticationPassword","password",value);
        else if(action.equals("WAIT_EMAIL")&&auth.equals(action))q=Wire.json("@type","setAuthenticationEmailAddress","email_address",value);
        else if(action.equals("WAIT_EMAIL_CODE")&&auth.equals(action))q=Wire.json("@type","checkAuthenticationEmailCode","code",Wire.json("@type","emailAddressAuthenticationCode","code",value));
        else throw new Problem("CONFLICT","The login step changed. Refresh the screen.");call(q);done.accept("Submitted. Waiting for Telegram.");
    }catch(Exception e){done.accept(friendly(e));}});}
    static String friendly(Exception e){return e instanceof Problem?e.getMessage():"The operation did not complete. Check the connection and try again.";}
    synchronized void addListener(Listener l){listeners.add(l);}synchronized void removeListener(Listener l){listeners.remove(l);}
    private synchronized void emit(String name,JSONObject data){JSONObject event=Wire.json("kind","event","epoch",epoch,"seq",Long.toString(++eventSeq),"event",name,"data",data);events.add(event);while(events.size()>128)events.remove();for(Listener l:new ArrayList<>(listeners))l.event(event);}
    synchronized JSONObject resync(){return Wire.json("epoch",epoch,"next_seq",Long.toString(eventSeq+1),"status",status());}
    synchronized List<JSONObject> replay(String requested,long after)throws Problem{
        if(!requested.equals(epoch)||after<0||after>eventSeq||(!events.isEmpty()&&after<Long.parseLong(events.peek().optString("seq"))-1))throw new Problem("CONFLICT","Refresh chats and pending sends after reconnecting.");
        ArrayList<JSONObject> out=new ArrayList<>();for(JSONObject e:events)if(Long.parseLong(e.optString("seq"))>after)out.add(e);return out;
    }
    private void update(JSONObject u){try{String type=u.optString("@type");
        if(type.equals("updateAuthorizationState")){auth(u.getJSONObject("authorization_state"));return;}
        if(type.equals("updateConnectionState")){String s=u.getJSONObject("state").optString("@type");connection=s.equals("connectionStateReady")?"READY":s.equals("connectionStateWaitingForNetwork")?"WAITING_NETWORK":s.equals("connectionStateUpdating")?"UPDATING":"CONNECTING";emit("STATUS",status());return;}
        if(type.equals("updateUser")){JSONObject user=u.getJSONObject("user");users.put(user.getLong("id"),user);if(users.size()>4000)users.clear();return;}
        if(type.equals("updateNewChat")){JSONObject c=u.getJSONObject("chat");cache(c);emit("CHAT_CHANGED",chatView(c));return;}
        if(type.equals("updateMessageSendSucceeded")||type.equals("updateMessageSendFailed")){
            JSONObject m=u.getJSONObject("message");String op=journal.operation(Long.toString(m.getLong("chat_id")),Long.toString(u.getLong("old_message_id")));
            if(op!=null){journal.state(op,type.equals("updateMessageSendSucceeded")?"SENT":"FAILED",Long.toString(m.getLong("id")),type.equals("updateMessageSendSucceeded")?"":"Telegram reported this send failed.");emit("SEND_CHANGED",SendJournal.publicState(journal.get(op)));}
            if(type.equals("updateMessageSendSucceeded")&&u.getLong("old_message_id")!=m.getLong("id"))emit("MESSAGES_DELETED",Wire.json("chat_id",Long.toString(m.getLong("chat_id")),"message_ids",new JSONArray().put(Long.toString(u.getLong("old_message_id")))));
            emitMessage("MESSAGE_CHANGED",m);return;
        }
        if(type.equals("updateNewMessage")){emitMessage("MESSAGE_NEW",u.getJSONObject("message"));return;}
        if(type.equals("updateDeleteMessages")){if(u.optBoolean("from_cache",false))return;JSONArray all=stringIds(u.getJSONArray("message_ids"));for(int at=0;at<all.length();){JSONArray chunk=new JSONArray();for(int n=0;n<200&&at<all.length();n++,at++)chunk.put(all.getString(at));emit("MESSAGES_DELETED",Wire.json("chat_id",Long.toString(u.getLong("chat_id")),"message_ids",chunk));}return;}
        if(type.equals("updateMessageContent")||type.equals("updateMessageEdited")||type.equals("updateMessageInteractionInfo")){JSONObject m=call(Wire.json("@type","getMessage","chat_id",u.getLong("chat_id"),"message_id",u.getLong("message_id")));emitMessage("MESSAGE_CHANGED",m);return;}
        if(type.startsWith("updateChat")){long id=u.optLong("chat_id",0);if(id!=0){JSONObject c=call(Wire.json("@type","getChat","chat_id",id));cache(c);emit("CHAT_CHANGED",chatView(c));}}
    }catch(Exception e){emit("RESYNC_REQUIRED",Wire.json("reason","Telegram data changed; refresh the current view."));}}
    private void emitMessage(String kind,JSONObject message)throws Exception{JSONObject c=chats.get(message.getLong("chat_id"));if(c==null)return;if(!blocked(c).isEmpty()){emit("CHAT_CHANGED",chatView(c));return;}emit(kind,messageView(message));}
    private void cache(JSONObject c)throws JSONException{chats.put(c.getLong("id"),c);if(chats.size()>4000){Iterator<Long> i=chats.keySet().iterator();i.next();i.remove();}}
    private JSONObject chat(long id)throws Exception{JSONObject c=call(Wire.json("@type","getChat","chat_id",id));cache(c);return c;}
    private String blocked(JSONObject c)throws Exception{
        JSONObject t=c.getJSONObject("type");String type=t.optString("@type");
        if(type.equals("chatTypeSecret"))return "Secret Chats stay on their original devices.";
        if(c.optInt("message_auto_delete_time",0)>0)return "Disappearing-message chats are not supported yet.";
        if(type.equals("chatTypeSupergroup")){if(t.optBoolean("is_channel"))return "Channel reading needs sponsored-message support.";JSONObject group=call(Wire.json("@type","getSupergroup","supergroup_id",t.getLong("supergroup_id")));
            if(group.optBoolean("is_forum")||group.optBoolean("is_direct_messages_group")||group.optBoolean("is_broadcast_group"))return "Topics and channel discussions are not supported yet.";}
        if(!type.equals("chatTypePrivate")&&!type.equals("chatTypeBasicGroup")&&!type.equals("chatTypeSupergroup"))return "This chat type is not supported yet.";return "";
    }
    private boolean canSend(JSONObject c)throws Exception{return canSend(c,"can_send_basic_messages");}
    private boolean canSend(JSONObject c,String permission)throws Exception{JSONObject type=c.getJSONObject("type"),status=null;String kind=type.optString("@type");
        if(kind.equals("chatTypeBasicGroup"))status=call(Wire.json("@type","getBasicGroup","basic_group_id",type.getLong("basic_group_id"))).getJSONObject("status");
        else if(kind.equals("chatTypeSupergroup"))status=call(Wire.json("@type","getSupergroup","supergroup_id",type.getLong("supergroup_id"))).getJSONObject("status");
        return EngineRules.canSend(c.optJSONObject("permissions"),status,permission);}
    JSONObject allowed(long id,boolean sending)throws Exception{JSONObject c=chat(id);String reason=blocked(c);if(!reason.isEmpty())throw new Problem("UNSUPPORTED",reason);
        if(sending&&!canSend(c))throw new Problem("FORBIDDEN","Text messages are not permitted in this chat.");return c;}
    private JSONObject chatView(JSONObject c)throws Exception{String reason=blocked(c);JSONObject last=c.optJSONObject("last_message");String preview="";if(last!=null&&reason.isEmpty())preview=messageView(last).optString("text");long order=0;boolean archived=false;JSONArray positions=c.optJSONArray("positions");
        if(positions!=null)for(int i=0;i<positions.length();i++){JSONObject p=positions.getJSONObject(i);String type=p.getJSONObject("list").optString("@type");if(type.equals("chatListMain")||type.equals("chatListArchive")){order=p.optLong("order");archived=type.equals("chatListArchive");break;}}
        return Wire.json("avatar_file",c.optJSONObject("photo")==null?0:c.optJSONObject("photo").getJSONObject("small").optInt("id"),"id",Long.toString(c.getLong("id")),"title",c.getLong("id")==selfUser?"Saved Messages":clip(c.optString("title"),256),"unread",c.optInt("unread_count"),"last_text",clip(preview,240),"last_date",last==null?0:last.optInt("date"),"order",Long.toString(order),"archived",archived,"can_send",reason.isEmpty()&&canSend(c),"blocked_reason",reason);}
    private JSONObject messageView(JSONObject m)throws Exception{JSONObject content=m.getJSONObject("content");String type=content.optString("@type"),text="";
        if(m.optJSONObject("self_destruct_type")!=null||m.optDouble("auto_delete_in",0)>0||m.optJSONObject("ephemeral_content")!=null){type="ephemeral";text="[Disappearing message — view on Pixel]";}
        else if(type.equals("messageText"))text=content.getJSONObject("text").optString("text");
        else{JSONObject caption=content.optJSONObject("caption");text="["+type.replace("message","")+"]"+(caption==null?"":" "+caption.optString("text"));}
        if(Wire.utf8(text).length>16384)text="[Long message — view on Pixel]";
        String sender="";JSONObject from=m.optJSONObject("sender_id");if(from!=null){long userId=from.optLong("user_id");if(userId!=0){JSONObject u=users.get(userId);if(u==null){u=call(Wire.json("@type","getUser","user_id",userId));users.put(userId,u);}sender=(u.optString("first_name")+" "+u.optString("last_name")).trim();}else sender="Chat "+from.optLong("chat_id");}
        JSONObject reply=m.optJSONObject("reply_to"),sending=m.optJSONObject("sending_state");String state=sending==null?"SENT":sending.optString("@type").equals("messageSendingStateFailed")?"FAILED":"PENDING";
        return Wire.json("reactions",Reactions.summary(m),"media",MediaFeatures.describe(m),"id",Long.toString(m.getLong("id")),"chat_id",Long.toString(m.getLong("chat_id")),"date",m.optInt("date"),"text",text,"outgoing",m.optBoolean("is_outgoing"),"sender",clip(sender,128),"reply_to_id",Long.toString(reply==null?0:reply.optLong("message_id")),"edited",m.optInt("edit_date")>0,"content_type",type.equals("messageText")?"text":type,"send_state",state);
    }
    private static JSONArray stringIds(JSONArray a)throws JSONException{JSONArray b=new JSONArray();for(int i=0;i<a.length();i++)b.put(Long.toString(a.getLong(i)));return b;}
    private static String clip(String s,int n){if(s.length()<=n)return s;int end=n;if(Character.isHighSurrogate(s.charAt(end-1)))end--;return s.substring(0,end);}
    static long id(JSONObject p,String field,boolean zero)throws Exception{String value=Wire.required(p,field);if(!value.matches("-?(0|[1-9][0-9]{0,18})"))throw new Problem("BAD_REQUEST","Invalid identifier.");long v=Long.parseLong(value);if(!zero&&v==0)throw new Problem("BAD_REQUEST","Invalid identifier.");return v;}
    static String op(JSONObject p)throws Exception{String s=Wire.required(p,"operation_id");if(!s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new Problem("BAD_REQUEST","Invalid operation identifier.");return s;}
    static String digest(String body)throws Exception{byte[] b=MessageDigest.getInstance("SHA-256").digest(Wire.utf8(body));StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(java.util.Locale.US,"%02x",x&255));return s.toString();}
    JSONObject guardedRequest(String method,JSONObject p,Guard guard)throws Exception{requestGuard=guard;try{guard.remaining();return request(method,p);}finally{requestGuard=null;}}
    private JSONObject request(String method,JSONObject p)throws Exception{
        if(method.equals("STATUS"))return status();
        if(method.equals("QUERY_SEND")){JSONObject s=journal.get(op(p));if(s==null)throw new Problem("NOT_FOUND","No send record exists. Do not automatically resend an uncertain operation.");return SendJournal.publicState(s);}
        if(method.equals("RESYNC"))return resync();
        if(!auth.equals("READY"))throw new Problem("AUTH_REQUIRED","Complete Telegram login on the Pixel.");
        if(method.equals("SAVED_MESSAGES")){JSONObject me=call(Wire.json("@type","getMe"));JSONObject self=call(Wire.json("@type","createPrivateChat","user_id",me.getLong("id"),"force",true));cache(self);return Wire.json("chat",chatView(self));}
        if(method.equals("REACTION_ICONS"))return ReactionIcons.request(p);
        JSONObject reactionResult=new Reactions(this).request(method,p);if(reactionResult!=null)return reactionResult;
        JSONObject mediaResult=media.request(method,p);if(mediaResult!=null)return mediaResult;
        if(method.equals("LIST_CHATS"))return list(p);
        if(method.equals("GET_HISTORY"))return history(p);
        if(method.equals("SEND_TEXT"))return send(p);
        long chat=id(p,"chat_id",false);allowed(chat,false);
        if(method.equals("OPEN_CHAT")){boolean open=p.optBoolean("open",true);call(Wire.json("@type",open?"openChat":"closeChat","chat_id",chat));return open?Wire.json("chat",chatView(chat(chat))):new JSONObject();}
        if(method.equals("TYPING")){call(Wire.json("@type","sendChatAction","chat_id",chat,"business_connection_id","","action",Wire.json("@type",p.optBoolean("active",false)?"chatActionTyping":"chatActionCancel")));return new JSONObject();}
        if(method.equals("VIEW_MESSAGES")){JSONArray values=p.getJSONArray("message_ids");if(values.length()<1||values.length()>50)throw new Problem("BAD_REQUEST","Invalid visible message count.");JSONArray ids=new JSONArray();for(int i=0;i<values.length();i++)ids.put(id(Wire.json("id",values.getString(i)),"id",false));call(Wire.json("@type","viewMessages","chat_id",chat,"message_ids",ids,"source",Wire.json("@type","messageSourceChatHistory"),"force_read",true));return new JSONObject();}
        throw new Problem("BAD_REQUEST","Unknown companion operation.");
    }
    private JSONObject list(JSONObject p)throws Exception{boolean archive=p.optBoolean("archived",false);int limit=p.optInt("limit",20);if(limit<1||limit>30)throw new Problem("BAD_REQUEST","Invalid page size.");String cursor=p.optString("cursor","");long anchor=0;
        if(!cursor.isEmpty()){if(cursor.length()>256)throw new Problem("BAD_REQUEST","Invalid cursor.");JSONObject c=Wire.object(android.util.Base64.decode(cursor,android.util.Base64.NO_WRAP));if(!epoch.equals(c.optString("epoch"))||archive!=c.optBoolean("archived"))throw new Problem("CONFLICT","Refresh this chat list.");anchor=id(c,"anchor",false);}
        JSONObject chatList=Wire.json("@type",archive?"chatListArchive":"chatListMain");boolean end=false;
        try{call(Wire.json("@type","loadChats","chat_list",chatList,"limit",100));}catch(Problem e){if(e.code.equals("NOT_FOUND"))end=true;else throw e;}
        JSONArray all=call(Wire.json("@type","getChats","chat_list",chatList,"limit",10000)).getJSONArray("chat_ids");int at=0;
        if(anchor!=0){boolean found=false;for(;at<all.length();at++)if(all.getLong(at)==anchor){at++;found=true;break;}if(!found)throw new Problem("CONFLICT","The chat order changed. Refresh this list.");}
        JSONArray result=new JSONArray();long last=anchor;
        for(;at<all.length()&&result.length()<limit;at++){JSONObject c=chatView(chat(all.getLong(at)));result.put(c);if(Wire.utf8(result.toString()).length>39000){result.remove(result.length()-1);break;}last=all.getLong(at);}
        boolean more=at<all.length()||!end;String next=more&&last!=0?Wire.token(Wire.utf8(Wire.json("epoch",epoch,"archived",archive,"anchor",Long.toString(last)).toString())):"";
        return Wire.json("chats",result,"next_cursor",next,"has_more",more&&!next.isEmpty());
    }
    private JSONObject history(JSONObject p)throws Exception{long chat=id(p,"chat_id",false),before=id(p,"before_id",true);allowed(chat,false);int limit=p.optInt("limit",20);if(limit<1||limit>30)throw new Problem("BAD_REQUEST","Invalid history page size.");
        JSONArray result=new JSONArray();long last=0,anchor=before;boolean full=false;
        for(int page=0;page<4&&!full&&result.length()<limit;page++){
            JSONArray raw=call(Wire.json("@type","getChatHistory","chat_id",chat,"from_message_id",anchor,"offset",0,"limit",limit+1,"only_local",false)).getJSONArray("messages");long prior=last;
            for(int i=0;i<raw.length();i++){JSONObject m=raw.getJSONObject(i);long message=m.getLong("id");if(!EngineRules.older(message,anchor)||(last!=0&&message>=last))continue;if(result.length()>=limit){full=true;break;}JSONObject view=messageView(m);result.put(view);if(Wire.utf8(result.toString()).length>39000){result.remove(result.length()-1);full=true;break;}last=message;}
            if(last==prior)break;anchor=last;
        }
        // TDLib may return a short page even while older history exists. Stop only on no progress.
        return Wire.json("messages",result,"next_before_id",Long.toString(last),"has_more",EngineRules.moreHistory(last,before));
    }
    private JSONObject send(JSONObject p)throws Exception{String op=op(p),text=Wire.required(p,"text");long chat=id(p,"chat_id",false),reply=id(p,"reply_to_id",true);
        if(text.isEmpty()||text.codePointCount(0,text.length())>4096||Wire.utf8(text).length>16384||text.indexOf(0)>=0)throw new Problem("BAD_REQUEST","Message is empty or too long.");String fingerprint=digest(chat+"\n"+reply+"\n"+text);JSONObject prior=journal.get(op);
        if(prior!=null){if(!fingerprint.equals(prior.optString("digest")))throw new Problem("CONFLICT","This send identifier was already used for different content.");return SendJournal.publicState(prior);}
        return submit(op,fingerprint,chat,reply,Wire.json("@type","inputMessageText","text",Wire.json("@type","formattedText","text",text,"entities",new JSONArray()),"link_preview_options",Wire.json("@type","linkPreviewOptions","is_disabled",false),"clear_draft",false));
    }
    JSONObject submit(String op,String fingerprint,long chat,long reply,JSONObject input)throws Exception{
        JSONObject permitted=allowed(chat,false);String type=input.optString("@type"),permission=type.equals("inputMessagePhoto")?"can_send_photos":type.equals("inputMessageVideo")?"can_send_videos":type.equals("inputMessageSticker")?"can_send_other_messages":type.equals("inputMessageDocument")?"can_send_documents":type.equals("inputMessageAudio")?"can_send_audios":type.equals("inputMessageVoiceNote")?"can_send_voice_notes":"can_send_basic_messages";if(!canSend(permitted,permission))throw new Problem("FORBIDDEN","This attachment or message is not permitted in this chat.");if(reply!=0){JSONObject original=call(Wire.json("@type","getMessage","chat_id",chat,"message_id",reply));JSONObject properties=call(Wire.json("@type","getMessageProperties","chat_id",chat,"message_id",reply));
            if(!EngineRules.supportedReply(original,properties))throw new Problem("UNSUPPORTED","This message cannot be replied to by this companion.");}
        journal.begin(op,fingerprint,Long.toString(chat));
        try{
            JSONObject options=Wire.json("@type","messageSendOptions","disable_notification",false,"from_background",false,"protect_content",false,"allow_paid_broadcast",false,"paid_message_star_count",0,"sending_id",0,"only_preview",false);
            JSONObject q=Wire.json("@type","sendMessage","chat_id",chat,"options",options,"input_message_content",input);
            if(reply!=0)q.put("reply_to",Wire.json("@type","inputMessageReplyToMessage","message_id",reply,"checklist_task_id",0,"poll_option_id",""));
            JSONObject m=call(q),sending=m.optJSONObject("sending_state");String state=sending==null?"SENT":sending.optString("@type").equals("messageSendingStateFailed")?"FAILED":"PENDING";journal.state(op,state,Long.toString(m.getLong("id")),state.equals("FAILED")?"Telegram reported this send failed.":"");
        }catch(Problem e){journal.state(op,"FAILED","0",e.getMessage());}
        catch(Exception uncertain){journal.state(op,"UNCERTAIN","0","Submission may have reached Telegram. Inspect this chat before retrying.");}
        JSONObject state=SendJournal.publicState(journal.get(op));emit("SEND_CHANGED",state);return state;
    }
    private void reconcile(){for(JSONObject row:journal.pending())try{JSONObject m=call(Wire.json("@type","getMessage","chat_id",Long.parseLong(row.optString("chat_id")),"message_id",Long.parseLong(row.optString("message_id"))));JSONObject state=m.optJSONObject("sending_state");String name=state==null?"SENT":state.optString("@type").equals("messageSendingStateFailed")?"FAILED":"PENDING";journal.state(row.optString("operation_id"),name,Long.toString(m.getLong("id")),name.equals("FAILED")?"Telegram reported this send failed.":"");emit("SEND_CHANGED",SendJournal.publicState(journal.get(row.optString("operation_id"))));}catch(Exception unresolved){journal.state(row.optString("operation_id"),"UNCERTAIN",row.optString("message_id"),"The earlier pending message could not be reconciled. Inspect this chat.");}}
    void closeChat(long id){if(id==0)return;worker.execute(()->{try{call(Wire.json("@type","closeChat","chat_id",id));}catch(Exception ignored){}});}
}
