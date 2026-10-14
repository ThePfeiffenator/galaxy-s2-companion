package org.retrobridge.galaxymail.pixel;
import android.content.*;import android.util.Base64;import org.json.*;import java.io.*;import java.net.*;import javax.net.ssl.HttpsURLConnection;import java.security.MessageDigest;

/** Owner-driven actions, account-bound with durable receipts for sends. No permanent-delete API. */
final class GmailActions {
 static String digest(byte[] data)throws Exception{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))s.append(String.format(java.util.Locale.US,"%02x",b&255));return s.toString();}
 static JSONObject write(Context c,String method,String path,JSONObject body)throws Exception {
  if(!path.matches("(?:messages/[0-9a-f]{1,16}/modify|threads/[0-9a-f]{1,16}/modify|messages/send|drafts|drafts/[A-Za-z0-9_-]{1,160}|drafts/send)"))throw new IOException("Invalid mail action");
  HttpsURLConnection conn=(HttpsURLConnection)new URL(GmailBridge.BASE+path).openConnection();conn.setRequestMethod(method);conn.setConnectTimeout(15000);conn.setReadTimeout(60000);conn.setUseCaches(false);conn.setInstanceFollowRedirects(false);conn.setRequestProperty("Authorization","Bearer "+MailAccount.token(c));conn.setRequestProperty("Accept","application/json");
  try{if(!method.equals("DELETE")){byte[] data=body.toString().getBytes("UTF-8");conn.setDoOutput(true);conn.setFixedLengthStreamingMode(data.length);conn.setRequestProperty("Content-Type","application/json; charset=UTF-8");try(OutputStream out=conn.getOutputStream()){out.write(data);}}
   int status=conn.getResponseCode();if(status!=200&&status!=201&&status!=204)throw GoogleApi.failure(conn,status);if(status==204)return new JSONObject();ByteArrayOutputStream data=new ByteArrayOutputStream();try(InputStream in=conn.getInputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(data.size()+n>500000)throw new IOException("Mail receipt too large");data.write(b,0,n);}}return data.size()==0?new JSONObject():new JSONObject(data.toString("UTF-8"));
  }finally{conn.disconnect();}
 }
 static String label(String s)throws IOException{if(!s.matches("INBOX|UNREAD|STARRED|TRASH|SPAM|IMPORTANT|Label_[0-9]+"))throw new IOException("Unsupported mail label");return s;}
 static synchronized JSONObject run(Context c,JSONObject r)throws Exception {
  String op=r.getString("op");
  if(op.equals("modify")){String target=r.getString("target");if(!target.equals("messages")&&!target.equals("threads"))throw new IOException("Invalid mail target");JSONObject body=new JSONObject().put(r.getBoolean("add")?"addLabelIds":"removeLabelIds",new JSONArray().put(label(r.getString("label"))));return write(c,"POST",target+"/"+GmailBridge.identifier(r.getString("id"))+"/modify",body);}
  if(!op.equals("send")&&!op.equals("draft")&&!op.equals("discardDraft"))throw new IOException("Mail action unavailable");
  String key=r.getString("requestId");if(!key.matches("[0-9a-f-]{36}"))throw new IOException("Invalid mail action identity");
  SharedPreferences receipts=c.getSharedPreferences("gmail-receipts",0);String account=MailAccount.email(c),prefix=digest(account.getBytes("UTF-8"))+":"+key;
  String fingerprint=digest(r.toString().getBytes("UTF-8")),saved=receipts.getString(prefix+":hash","");if(!saved.isEmpty()&&!saved.equals(fingerprint))throw new IOException("Mail action identity reused");
  String done=receipts.getString(prefix+":result","");if(!done.isEmpty())return new JSONObject(done);
  if(receipts.getBoolean(prefix+":pending",false)){
   if(op.equals("send")){JSONObject found=GmailBridge.read(c,"messages?maxResults=1&q="+GmailBridge.enc("in:sent rfc822msgid:"+key+"@galaxy-s2.local"));JSONArray items=found.optJSONArray("messages");if(items!=null&&items.length()==1){JSONObject result=items.getJSONObject(0);receipts.edit().putString(prefix+":result",result.toString()).remove(prefix+":pending").commit();return result;}}
   throw new IOException("Delivery uncertain. Check Sent on Pixel before retrying");
  }
  String draftKey=r.optString("draftKey");if(!draftKey.matches("[0-9a-f-]{36}"))throw new IOException("Invalid draft identity");String draftPref=digest(account.getBytes("UTF-8"))+":draft:"+draftKey,draft=receipts.getString(draftPref,"");
  if(op.equals("discardDraft")){if(!draft.isEmpty())write(c,"DELETE","drafts/"+draft,null);receipts.edit().remove(draftPref).commit();return new JSONObject();}
  String raw;byte[] mime;if(r.has("upload")){mime=GmailUploads.load(c,r.getString("upload"));raw=Base64.encodeToString(mime,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}else{raw=r.getString("raw");if(raw.length()>350000)throw new IOException("Message exceeds transfer limit");mime=Base64.decode(raw,Base64.URL_SAFE|Base64.NO_WRAP);}String head=new String(mime,0,Math.min(mime.length,3000),"UTF-8");
  if(!head.startsWith("From: "+account+"\r\n")||!head.contains("\r\nMessage-ID: <"+key+"@galaxy-s2.local>\r\n"))throw new IOException("Mail sender identity mismatch");
  JSONObject message=new JSONObject().put("raw",raw);String thread=r.optString("thread");if(!thread.isEmpty())message.put("threadId",GmailBridge.identifier(thread));
  if(!receipts.edit().putString(prefix+":hash",fingerprint).putBoolean(prefix+":pending",true).commit())throw new IOException("Cannot save mail receipt");
  try{
   JSONObject result;
   if(op.equals("draft")){JSONObject payload=new JSONObject().put("message",message);if(!draft.isEmpty())payload.put("id",draft);result=write(c,draft.isEmpty()?"POST":"PUT",draft.isEmpty()?"drafts":"drafts/"+draft,payload);receipts.edit().putString(draftPref,result.getString("id")).commit();}
   else if(!draft.isEmpty()){write(c,"PUT","drafts/"+draft,new JSONObject().put("id",draft).put("message",message));result=write(c,"POST","drafts/send",new JSONObject().put("id",draft));receipts.edit().remove(draftPref).commit();}
   else result=write(c,"POST","messages/send",message);
   if(!receipts.edit().putString(prefix+":result",result.toString()).remove(prefix+":pending").commit())throw new IOException("Cannot save mail receipt");return result;
  }catch(GoogleApi.HttpFailure e){if(e.status>=400&&e.status<500)receipts.edit().remove(prefix+":pending").commit();throw e;}
 }
}
