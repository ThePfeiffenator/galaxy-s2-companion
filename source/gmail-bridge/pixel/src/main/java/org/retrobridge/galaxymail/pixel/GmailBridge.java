package org.retrobridge.galaxymail.pixel;
import android.content.Context;import android.text.Html;import android.text.TextUtils;import android.text.util.Rfc822Tokenizer;import android.text.util.Rfc822Token;import android.util.Base64;import org.json.*;import java.util.*;import java.util.concurrent.*;import java.io.*;import java.net.*;

/** Bounded Gmail model for the original 2012 client. Never logs mailbox data. */
final class GmailBridge {
 static final String BASE="https://gmail.googleapis.com/gmail/v1/users/me/";
 static final ExecutorService READERS=Executors.newFixedThreadPool(4);
 static String enc(String s)throws Exception{return URLEncoder.encode(s,"UTF-8");}
 static String identifier(String s)throws IOException{if(!s.matches("[0-9a-f]{1,16}"))throw new IOException("Invalid mail identifier");return s;}
 static JSONObject read(Context c,String path)throws Exception{return GoogleApi.read(c,()->MailAccount.token(c),BASE+path);}
 static JSONObject run(Context c,JSONObject r)throws Exception {
  String op=r.getString("op"),email=MailAccount.email(c);if(email.isEmpty())throw new IOException("Connect Gmail on Pixel");
  if(op.equals("profile")){JSONObject profile=read(c,"profile");if(!profile.getString("emailAddress").equalsIgnoreCase(email))throw new IOException("Gmail account changed");return new JSONObject().put("email",email);}
  if(!email.equalsIgnoreCase(r.optString("account")))throw new IOException("Gmail account changed");
  if(op.equals("labels"))return read(c,"labels");
  if(op.equals("attachment"))return attachment(c,r);
  if(op.equals("upload"))return GmailUploads.chunk(c,r);
  if(op.equals("thread"))return normalized(read(c,"threads/"+identifier(r.getString("id"))+"?format=full"),true);
  if(op.equals("list")){
   String query=r.optString("query"),page=r.optString("page");if(query.length()>1024||query.indexOf('\0')>=0||page.length()>2048||page.matches(".*[\r\n].*"))throw new IOException("Invalid mail query");
   String path="threads?maxResults=20&includeSpamTrash=true&q="+enc(query);String label=r.optString("label");if(!label.isEmpty()){if(!label.matches("Label_[0-9]+"))throw new IOException("Invalid mail label");path+="&labelIds="+enc(label);}if(!page.isEmpty())path+="&pageToken="+enc(page);
   JSONObject list=read(c,path);JSONArray ids=list.optJSONArray("threads"),threads=new JSONArray();List<Future<JSONObject>> pending=new ArrayList<>();
   // Reuse a single token for one bounded page; the credential never leaves this process.
   final String token=MailAccount.token(c);
   if(ids!=null)for(int i=0;i<ids.length();i++){final String id=identifier(ids.getJSONObject(i).getString("id"));pending.add(READERS.submit(()->normalized(GoogleApi.read(token,BASE+"threads/"+id+"?format=metadata&metadataHeaders=From&metadataHeaders=To&metadataHeaders=Cc&metadataHeaders=Subject"),false)));}
   try{for(Future<JSONObject> f:pending)threads.put(f.get(50,TimeUnit.SECONDS));}finally{for(Future<?> f:pending)if(!f.isDone())f.cancel(true);}
   return new JSONObject().put("threads",threads).put("next",list.optString("nextPageToken"));
  }
  if(op.equals("modify")||op.equals("send")||op.equals("draft")||op.equals("discardDraft"))return GmailActions.run(c,r);
  throw new IOException("Mail operation unavailable");
 }
 static String header(JSONObject payload,String name){JSONArray h=payload.optJSONArray("headers");if(h!=null)for(int i=0;i<h.length();i++){JSONObject x=h.optJSONObject(i);if(x!=null&&name.equalsIgnoreCase(x.optString("name")))return x.optString("value");}return "";}
 static JSONArray addresses(String text){JSONArray a=new JSONArray();for(Rfc822Token t:Rfc822Tokenizer.tokenize(text))a.put(t.toString());return a;}
 static String textPart(JSONObject p,String mime,int depth)throws Exception {
  if(depth>15)return "";
  if(mime.equalsIgnoreCase(p.optString("mimeType"))&&p.optString("filename").isEmpty()){
   String data=p.optJSONObject("body")==null?"":p.getJSONObject("body").optString("data");if(!data.isEmpty())return new String(Base64.decode(data,Base64.URL_SAFE|Base64.NO_WRAP),"UTF-8");
  }
  JSONArray parts=p.optJSONArray("parts");if(parts!=null)for(int i=0;i<parts.length();i++){String text=textPart(parts.getJSONObject(i),mime,depth+1);if(!text.isEmpty())return text;}return "";
 }
 static boolean attachments(JSONObject p,int depth){if(p==null||depth>15)return false;if(!p.optString("filename").isEmpty())return true;JSONArray a=p.optJSONArray("parts");if(a!=null)for(int i=0;i<a.length();i++)if(attachments(a.optJSONObject(i),depth+1))return true;return false;}
 static void attachmentList(JSONObject p,JSONArray out,int depth)throws Exception {if(depth>15)return;JSONObject body=p.optJSONObject("body");if(!p.optString("filename").isEmpty()&&body!=null&&body.has("attachmentId"))out.put(new JSONObject().put("part",p.optString("partId")).put("id",body.getString("attachmentId")).put("name",p.optString("filename")).put("mime",p.optString("mimeType","application/octet-stream")).put("size",body.optInt("size")));JSONArray parts=p.optJSONArray("parts");if(parts!=null)for(int i=0;i<parts.length();i++)attachmentList(parts.getJSONObject(i),out,depth+1);}
 static synchronized JSONObject attachment(Context c,JSONObject r)throws Exception {
  String message=identifier(r.getString("message")),id=r.getString("attachment");int offset=r.getInt("offset");if(!id.matches("[A-Za-z0-9_-]{1,2048}")||offset<0||offset>25000000)throw new IOException("Invalid attachment request");
  String key=GmailActions.digest((MailAccount.email(c)+":"+message+":"+id).getBytes("UTF-8"));File dir=new File(c.getCacheDir(),"mail-attachments");dir.mkdirs();File file=new File(dir,key);
  if(!file.isFile()){
   JSONObject result=read(c,"messages/"+message+"/attachments/"+id);byte[] data=Base64.decode(result.getString("data"),Base64.URL_SAFE|Base64.NO_WRAP);if(data.length>6000000)throw new IOException("Attachment exceeds device limit");File temp=new File(dir,key+".part");try(FileOutputStream out=new FileOutputStream(temp)){out.write(data);out.getFD().sync();}if(!temp.renameTo(file))throw new IOException("Cannot save attachment");
  }
  if(offset>file.length())throw new IOException("Invalid attachment offset");int count=(int)Math.min(196608,file.length()-offset);byte[] part=new byte[count];try(RandomAccessFile in=new RandomAccessFile(file,"r")){in.seek(offset);in.readFully(part);}return new JSONObject().put("total",file.length()).put("offset",offset).put("data",Base64.encodeToString(part,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING));
 }
 static JSONObject normalized(JSONObject thread,boolean full)throws Exception {
  JSONArray input=thread.getJSONArray("messages"),messages=new JSONArray();boolean attached=false;int totalBudget=350000;
  for(int i=0;i<input.length();i++){
   if(i>=150)throw new IOException("Conversation exceeds device limit");
   JSONObject m=input.getJSONObject(i),p=m.getJSONObject("payload");attached|=attachments(p,0);
   JSONObject item=new JSONObject().put("id",m.getString("id")).put("date",m.optLong("internalDate")).put("labels",m.optJSONArray("labelIds")==null?new JSONArray():m.getJSONArray("labelIds")).put("from",header(p,"From")).put("to",addresses(header(p,"To"))).put("cc",addresses(header(p,"Cc"))).put("subject",header(p,"Subject")).put("snippet",Html.fromHtml(m.optString("snippet"),Html.FROM_HTML_MODE_LEGACY).toString());
   if(full){String text=textPart(p,"text/plain",0);if(text.isEmpty())text=Html.fromHtml(textPart(p,"text/html",0),Html.FROM_HTML_MODE_LEGACY).toString();int cap=Math.min(60000,totalBudget);if(text.length()>cap)text=text.substring(0,cap)+"\n\n[Message clipped for this phone]";totalBudget-=text.length();if(totalBudget<0)totalBudget=0;item.put("rfcMessageId",header(p,"Message-ID")).put("references",header(p,"References")).put("replyTo",addresses(header(p,"Reply-To")));item.put("html","<div style=\"white-space:pre-wrap;word-wrap:break-word\">"+TextUtils.htmlEncode(text)+"</div>");JSONArray files=new JSONArray();attachmentList(p,files,0);item.put("files",files);}
   messages.put(item);
  }
  return new JSONObject().put("id",thread.getString("id")).put("messages",messages).put("attachments",attached);
 }
}
