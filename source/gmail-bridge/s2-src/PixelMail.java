package org.retrobridge.gmail2012.provider;
import android.accounts.*;import android.content.*;import android.database.sqlite.*;import android.os.*;import android.net.Uri;import android.text.*;import org.json.*;import java.net.*;import javax.net.ssl.HttpsURLConnection;import java.io.*;import java.util.*;import java.lang.reflect.*;

/** Adapter into the original Gmail store. No Google password or token is held here. */
public final class PixelMail {
 static final String HOST="gmail.galaxy-services.invalid";
 static final String[] BUILTINS={"^f","^i","^r","^u","^k","^s","^t","^b","^g","^all","^io_im","^o"};
 static final String[] API={"SENT","INBOX","DRAFT","UNREAD","TRASH","SPAM","STARRED","CHAT","MUTED","ALL","IMPORTANT","OPENED"};
 static final String[] NAMES={"Sent","Inbox","Drafts","Unread","Trash","Spam","Starred","Chats","Muted","All mail","Important","Opened"};
 static final Map<Object,String> pages=Collections.synchronizedMap(new WeakHashMap<Object,String>());
 static boolean isFixture(Context c){return c.getSharedPreferences("pixel",0).getBoolean("fixture",false);}
 static Object field(Object o,String name)throws Exception {Field f=findField(o.getClass(),name);return f.get(o);}
 static Field findField(Class<?> c,String n)throws Exception {for(Class<?> k=c;k!=null;k=k.getSuperclass())try{Field f=k.getDeclaredField(n);f.setAccessible(true);return f;}catch(NoSuchFieldException e){}throw new NoSuchFieldException(n);}
 static void set(Object o,String n,Object v)throws Exception {findField(o.getClass(),n).set(o,v);}
 static long id(String s)throws Exception {if(!s.matches("[0-9a-f]{1,16}"))throw new IOException("Invalid mail identifier");long v=new java.math.BigInteger(s,16).longValue();if(v<=0)throw new IOException("Unsupported mail identifier");return v;}
 static String safeError(Exception e){for(StackTraceElement f:e.getStackTrace())android.util.Log.w("PixelGmail",f.getClassName()+"."+f.getMethodName()+":"+f.getLineNumber());if(e instanceof IOException&&e.getMessage()!=null&&e.getMessage().matches("[A-Za-z0-9 .:-]{1,120}"))return e.getMessage();return "Mail connection unavailable ("+e.getClass().getSimpleName()+")";}
 static JSONObject call(JSONObject request)throws Exception {
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://"+HOST+"/v1/mail").openConnection(new java.net.Proxy(java.net.Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",8766)));
  c.setConnectTimeout(15000);c.setReadTimeout(90000);c.setUseCaches(false);c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setDoOutput(true);byte[] data=request.toString().getBytes("UTF-8");c.setFixedLengthStreamingMode(data.length);c.setRequestProperty("Content-Type","application/json");
  try{OutputStream out=c.getOutputStream();out.write(data);out.close();int code=c.getResponseCode();if(code!=200)throw new IOException("Pixel mail HTTP "+code);ByteArrayOutputStream b=new ByteArrayOutputStream();InputStream in=c.getInputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(b.size()+n>900000)throw new IOException("Mail response too large");b.write(buf,0,n);}JSONObject result=new JSONObject(b.toString("UTF-8"));if(result.has("error"))throw new IOException(result.getString("error"));return result;}finally{c.disconnect();}
 }
 static JSONObject request(MailEngine e,String op)throws Exception {return new JSONObject().put("op",op).put("account",e.getAccountName());}
 static String connect(Context c,boolean fixture)throws Exception {
  String email=fixture?"sample@galaxy.invalid":call(new JSONObject().put("op","profile")).getString("email");
  if(email.length()>320||email.indexOf('@')<1)throw new IOException("Gmail account unavailable");
  c.getSharedPreferences("pixel",0).edit().putBoolean("fixture",fixture).commit();
  Account a=new Account(email,PixelAuthenticator.TYPE);AccountManager.get(c).addAccountExplicitly(a,null,null);ContentResolver.setIsSyncable(a,PixelAuthenticator.AUTHORITY,1);ContentResolver.setSyncAutomatically(a,PixelAuthenticator.AUTHORITY,!fixture);
  if(!fixture)ContentResolver.addPeriodicSync(a,PixelAuthenticator.AUTHORITY,new Bundle(),300);
  MailEngine e=MailEngine.getOrMakeMailEngineSync(c,email);e.blockUntilBackgroundTasksComplete();
  if(fixture){labels(e,new JSONArray());sample(e);}else{labels(e,call(request(e,"labels")).getJSONArray("labels"));page(e,"label:^i","",null);}
  c.getSharedPreferences("pixel",0).edit().putString("account",email).commit();
  c.getSharedPreferences("Gmail",0).edit().putString("account",email).commit();return email;
 }
 static synchronized void labels(MailEngine e,JSONArray input)throws Exception {
  MailStore store=e.mMailStore;Map<Long,MailStore.LabelInfo> labels=new HashMap<Long,MailStore.LabelInfo>();
  for(int i=0;i<BUILTINS.length;i++){long key=i+1;store.getOrAddLabel(key);labels.put(key,new MailStore.LabelInfo(BUILTINS[i],NAMES[i],0,0,Integer.MAX_VALUE,"SHOW"));}
  android.content.SharedPreferences map=e.getContext().getSharedPreferences("pixel-labels",0);android.content.SharedPreferences.Editor edit=map.edit();long next=map.getLong("next",100);
  for(int i=0;i<input.length();i++){JSONObject l=input.getJSONObject(i);String source=l.getString("id");if(!source.startsWith("Label_"))continue;long key=map.getLong(source,0);if(key==0){key=next++;edit.putLong(source,key);}store.getOrAddLabel(key);String canonical="pixel:"+source;labels.put(key,new MailStore.LabelInfo(canonical,l.getString("name"),0,0,Integer.MAX_VALUE,"SHOW"));}
  edit.putLong("next",next).commit();
  // The original app creates some server-style priority labels itself. Preserve their IDs
  // so its notification requests cannot refer to a label removed by a later refresh.
  SQLiteDatabase db=(SQLiteDatabase)field(e,"mDb");android.database.Cursor existing=db.query("labels",new String[]{"_id","canonicalName","name","numConversations","numUnreadConversations","color","visibility"},"_id>0 AND canonicalName<>''",null,null,null,null);
  try{while(existing.moveToNext()){long key=existing.getLong(0);if(!labels.containsKey(key))labels.put(key,new MailStore.LabelInfo(existing.getString(1),existing.getString(2),existing.getInt(3),existing.getInt(4),existing.getInt(5),existing.getString(6)));}}finally{existing.close();}
  store.setLabels(labels);
  for(int i=0;i<30&&!e.getLabelMap().labelsSynced();i++)Thread.sleep(100);
  if(!e.getLabelMap().labelsSynced())throw new IOException("Mail labels not ready");
  Map<String,String> settings=new HashMap<String,String>();settings.put("clientId","1");settings.put("serverVersion","25");settings.put("labelsIncluded","^i ^f ^r ^t");settings.put("labelsPartial","");store.setSettings(settings,false);
 }
 static long labelId(MailEngine e,String name){for(int i=0;i<API.length;i++)if(API[i].equals(name))return i+1;return e.getContext().getSharedPreferences("pixel-labels",0).getLong(name,0);}
 static Set<Long> labelIds(MailEngine e,JSONArray arr)throws Exception {Set<Long> r=new HashSet<Long>();boolean all=true;for(int i=0;i<arr.length();i++){String name=arr.getString(i);if(name.equals("TRASH")||name.equals("SPAM"))all=false;long key=labelId(e,name);if(key!=0)r.add(key);}if(all)r.add(10L);return r;}
 static List<String> list(JSONArray a)throws Exception {List<String> r=new ArrayList<String>();for(int i=0;i<a.length();i++)r.add(a.getString(i));return r;}
 static void changed(MailEngine e){e.getContext().getContentResolver().notifyChange(Uri.parse("content://galaxy-gmail-ls/"+Uri.encode(e.getAccountName())),null,false);}
 static void putThread(MailEngine e,JSONObject j,long query,Object cursor,boolean bodies)throws Exception {
  JSONArray messages=j.getJSONArray("messages");if(messages.length()==0)return;long thread=id(j.getString("id"));Set<Long> union=new HashSet<Long>();long newest=0,date=0;JSONObject first=messages.getJSONObject(0),last=first;
  CompactSenderInstructions senders=new CompactSenderInstructions();
  for(int i=0;i<messages.length();i++){JSONObject data=messages.getJSONObject(i);long mid=id(data.getString("id"));newest=Math.max(newest,mid);date=Math.max(date,data.getLong("date"));last=data;Set<Long> flags=labelIds(e,data.getJSONArray("labels"));union.addAll(flags);senders.addMessage(data.optString("from"),flags.contains(3L),flags.contains(4L),flags.contains(1L),false,false);
   if(bodies){MailSync.Message m=new MailSync.Message();m.messageId=mid;m.conversationId=thread;m.fromAddress=data.optString("from");m.toAddresses=list(data.optJSONArray("to")==null?new JSONArray():data.getJSONArray("to"));m.ccAddresses=list(data.optJSONArray("cc")==null?new JSONArray():data.getJSONArray("cc"));m.bccAddresses=Collections.emptyList();m.replyToAddresses=data.has("replyTo")?list(data.getJSONArray("replyTo")):Collections.<String>emptyList();e.getContext().getSharedPreferences("pixel-rfc",0).edit().putString(data.getString("id"),data.optString("rfcMessageId")).putString(data.getString("id")+":refs",data.optString("references")).commit();m.dateSentMs=m.dateReceivedMs=data.getLong("date");m.subject=data.optString("subject");m.snippet=data.optString("snippet");m.body=data.optString("html","<p>Empty message</p>");m.personalLevel=Gmail.PersonalLevel.TO_ME_AND_OTHERS;m.listInfo="";m.labelIds=flags;m.attachments=PixelAttachments.model(e.getContext(),data.getString("id"),data.optJSONArray("files"));e.mMailStore.addOrUpdateMessage(m,query);}
  }
  MailSync.Conversation c=new MailSync.Conversation();c.conversationId=thread;c.date=date;c.maxMessageId=c.sortMessageId=newest;c.subject=first.optString("subject","(no subject)");c.snippet=last.optString("snippet");c.fromAddress="";c.numMessages=messages.length();c.personalLevel=Gmail.PersonalLevel.TO_ME_AND_OTHERS;c.labelIds=union;c.hasAttachments=j.optBoolean("attachments");
  c.fromAddress=senders.toInstructionString(5);
  if(cursor!=null){Method m=cursor.getClass().getMethod("handleConversation",MailSync.Conversation.class);m.setAccessible(true);m.invoke(cursor,c);}else e.mMailStore.handleConversation(c);
 }
 static String apiQuery(String q){if(q.startsWith("label:")){String n=q.substring(6);for(int i=0;i<BUILTINS.length;i++)if(n.equals(BUILTINS[i])){if(i==9)return "-in:spam -in:trash";if(i==11)return "";return new String[]{"in:sent","in:inbox","in:drafts","is:unread","in:trash","in:spam","is:starred","in:chats","is:muted","","is:important"}[i];}return q;}return q;}
 static String page(MailEngine e,String q,String token,Object cursor)throws Exception {
  JSONObject req=request(e,"list").put("query",apiQuery(q)).put("page",token);if(q.startsWith("label:pixel:Label_"))req.put("label",q.substring(12)).put("query","");JSONObject reply=call(req);JSONArray threads=reply.getJSONArray("threads");
  long query=cursor==null?0:((Long)field(cursor,"mQueryId")).longValue();
  for(int i=0;i<threads.length();i++)putThread(e,threads.getJSONObject(i),query,cursor,false);
  changed(e);return reply.optString("next","");
 }
 public static synchronized void fetch(MailEngine e,Object cursor)throws IOException {
  try{if(isFixture(e.getContext())){if(cursor.getClass().getName().contains("Conversation")){set(cursor,"mNextHighestMessageId",0L);set(cursor,"mReceivedServerResults",true);}return;}
   if(cursor.getClass().getName().contains("Conversation")){String q=(String)field(cursor,"mQuery");String token=pages.get(cursor);String next=page(e,q,token==null?"":token,cursor);pages.put(cursor,next);set(cursor,"mNextHighestMessageId",next.isEmpty()?0L:Long.MAX_VALUE);set(cursor,"mReceivedServerResults",true);}
   else{long id=(Long)field(cursor,"mConversationId");JSONObject thread=call(request(e,"thread").put("id",Long.toHexString(id)));putThread(e,thread,0,null,true);changed(e);}
  }catch(Exception ex){throw new IOException(safeError(ex));}
 }
 public static synchronized void sync(MailEngine e,android.content.SyncResult result){if(isFixture(e.getContext()))return;try{PixelOutbox.flush(e);labels(e,call(request(e,"labels")).getJSONArray("labels"));page(e,"label:^i","",null);e.sendNotificationIntents(false);}catch(Exception ex){if(result!=null)result.stats.numIoExceptions++;android.util.Log.w("PixelGmail",safeError(ex));}}
 static void sample(MailEngine e)throws Exception {
  JSONObject m=new JSONObject().put("id","123456789a").put("date",System.currentTimeMillis()).put("from","Galaxy Test <sample@galaxy.invalid>").put("to",new JSONArray().put("sample@galaxy.invalid")).put("subject","Welcome to Gmail").put("snippet","The original inbox is connected to the Pixel bridge.").put("html","<p>This is a local sample message.</p><p>No email has been sent.</p>").put("labels",new JSONArray().put("INBOX").put("UNREAD"));
  putThread(e,new JSONObject().put("id","123456789a").put("messages",new JSONArray().put(m)),0,null,true);changed(e);
 }
}

