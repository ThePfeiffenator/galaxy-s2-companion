package org.retrobridge.galaxyservices.s2;

import android.app.*;import android.content.*;import android.database.Cursor;import android.net.Uri;
import org.retrobridge.galaxyservices.Wire;import java.io.*;import java.net.*;import java.security.*;import org.json.*;import javax.net.ssl.HttpsURLConnection;

/** Installs a complete attachment before publishing it in Samsung's inbox. */
final class IncomingMms {
 static final int MAX=32*1024*1024;final LegacyService app;
 IncomingMms(LegacyService s){app=s;}
 HttpsURLConnection connection(String path,String method)throws Exception {
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://media.galaxy-services.invalid/incoming"+path).openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",Wire.LOCAL)));
  c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setRequestMethod(method);
  String secret=new String(Wire.readFile(new File(app.getFilesDir(),"media-relay.key")),"US-ASCII").trim();if(!secret.matches("[0-9a-f]{64}"))throw new IOException("pairing");c.setRequestProperty("X-Galaxy-Media-Key",secret);return c;
 }
 JSONObject json(String path,String method)throws Exception {
  HttpsURLConnection c=connection(path,method);try{if(method.equals("POST")){c.setDoOutput(true);c.setFixedLengthStreamingMode(0);c.getOutputStream().close();}if(c.getResponseCode()!=200)throw new IOException("Pixel unavailable");ByteArrayOutputStream out=new ByteArrayOutputStream();InputStream in=c.getInputStream();try{byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>200000)throw new IOException("reply size");out.write(b,0,n);}}finally{in.close();}return new JSONObject(out.toString("UTF-8"));}finally{c.disconnect();}
 }
 File download(JSONObject m)throws Exception {
  String id=m.getString("id");long expected=m.getLong("size");if(expected<12||expected>MAX)throw new IOException("attachment size");String digest=m.getString("sha256");if(!digest.matches("[0-9a-f]{64}"))throw new IOException("digest");
  File f=new File(app.getFilesDir(),"incoming-"+id+".tmp");HttpsURLConnection c=connection("/"+id,"GET");boolean ok=false;
  try{if(c.getResponseCode()!=200||c.getContentLength()!=expected)throw new IOException("attachment size");MessageDigest sha=MessageDigest.getInstance("SHA-256");long size=0;InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(f);
   try{byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){size+=n;if(size>expected)throw new IOException("attachment size");out.write(b,0,n);sha.update(b,0,n);}out.getFD().sync();}finally{in.close();out.close();}
   if(size!=expected||!digest.equals(Wire.hex(sha.digest())))throw new IOException("attachment changed");ok=true;return f;
  }finally{c.disconnect();if(!ok)f.delete();}
 }
 void sync()throws Exception {
  app.gateway();JSONArray a=json("","GET").getJSONArray("messages");if(a.length()>4)throw new IOException("batch size");
  for(int i=0;i<a.length();i++){JSONObject m=a.getJSONObject(i);String id=m.getString("id"),phone=m.getString("number"),mime=m.getString("mime"),caption=m.getString("caption");long stamp=m.getLong("time");
   if(!id.matches("[0-9a-f]{64}")||!phone.matches("\\+[1-9][0-9]{4,14}")||caption.length()>10000||stamp<1||stamp>System.currentTimeMillis()+60000)throw new IOException("message metadata");
   if(!mime.equals("image/jpeg")&&!mime.equals("video/mp4")&&!mime.equals("video/3gpp"))throw new IOException("attachment format");
   String marker="gs-in-"+id;long prior=0;boolean complete=false;Cursor c=app.getContentResolver().query(Uri.parse("content://mms"),new String[]{"_id","msg_box"},"tr_id=?",new String[]{marker},null);try{if(c==null)throw new IOException("inbox");if(c.getCount()>1)throw new IOException("duplicate marker");if(c.moveToFirst()){prior=c.getLong(0);complete=c.getInt(1)==1;}}finally{if(c!=null)c.close();}
   if(complete){json("/"+id+"/ack","POST");continue;}
   // The marker names only our unfinished insertion, never a user's draft.
   if(prior>0)app.getContentResolver().delete(Uri.parse("content://mms/"+prior),"tr_id=? AND msg_box=3",new String[]{marker});
   File f=download(m);try{long thread=insert(m,f,marker);json("/"+id+"/ack","POST");notifyMessage(thread);}finally{f.delete();}
  }
 }
 long thread(String phone)throws Exception {
  Uri u=Uri.parse("content://mms-sms/threadID").buildUpon().appendQueryParameter("recipient",phone).build();Cursor c=app.getContentResolver().query(u,new String[]{"_id"},null,null,null);try{if(c==null||!c.moveToFirst())throw new IOException("conversation");return c.getLong(0);}finally{if(c!=null)c.close();}
 }
 long insert(JSONObject m,File f,String marker)throws Exception {
  ContentResolver cr=app.getContentResolver();long tid=thread(m.getString("number"));String mime=m.getString("mime"),name=mime.equals("image/jpeg")?"picture.jpg":mime.equals("video/3gpp")?"video.3gp":"video.mp4",tag=mime.startsWith("image/")?"img":"video";
  ContentValues v=new ContentValues();v.put("thread_id",tid);v.put("date",m.getLong("time")/1000);v.put("msg_box",3);v.put("read",0);v.put("seen",0);v.put("m_type",132);v.put("v",18);v.put("ct_t","application/vnd.wap.multipart.related");v.put("tr_id",marker);v.put("m_id",marker);v.put("m_size",f.length());
  Uri pdu=cr.insert(Uri.parse("content://mms"),v);if(pdu==null)throw new IOException("message insertion");long mid=ContentUris.parseId(pdu);boolean done=false;
  try{
   v.clear();v.put("address",m.getString("number"));v.put("type",137);v.put("charset",106);if(cr.insert(Uri.parse("content://mms/"+mid+"/addr"),v)==null)throw new IOException("sender");
   String caption=m.getString("caption"),smil="<smil><head><layout><root-layout width=\"320\" height=\"480\"/><region id=\"Image\" left=\"0\" top=\"0\" width=\"320\" height=\"400\" fit=\"meet\"/><region id=\"Text\" left=\"0\" top=\"400\" width=\"320\" height=\"80\"/></layout></head><body><par dur=\"10000ms\"><"+tag+" src=\""+name+"\" region=\"Image\"/>"+(caption.length()>0?"<text src=\"caption.txt\" region=\"Text\"/>":"")+"</par></body></smil>";
   part(mid,-1,"application/smil","smil.xml",smil,null);part(mid,0,mime,name,null,f);if(caption.length()>0)part(mid,1,"text/plain","caption.txt",caption,null);
   v.clear();v.put("msg_box",1);if(cr.update(pdu,v,"tr_id=? AND msg_box=3",new String[]{marker})!=1)throw new IOException("inbox commit");done=true;
   cr.notifyChange(Uri.parse("content://mms"),null);cr.notifyChange(Uri.parse("content://mms-sms/conversations"),null);return tid;
  }finally{if(!done)cr.delete(pdu,"tr_id=? AND msg_box=3",new String[]{marker});}
 }
 void part(long mid,int seq,String mime,String name,String text,File file)throws Exception {
  ContentResolver cr=app.getContentResolver();ContentValues v=new ContentValues();v.put("seq",seq);v.put("ct",mime);v.put("name",name);v.put("cl",name);v.put("cid","<"+name+">");if(text!=null){v.put("chset",106);v.put("text",text);}
  Uri part=cr.insert(Uri.parse("content://mms/"+mid+"/part"),v);if(part==null)throw new IOException("part");if(file!=null){InputStream in=new FileInputStream(file);OutputStream out=cr.openOutputStream(part);if(out==null){in.close();throw new IOException("part data");}try{byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}finally{in.close();out.close();}}
 }
 void notifyMessage(long thread){Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse("content://mms-sms/conversations/"+thread)).setPackage("com.android.mms");Notification n=new Notification.Builder(app).setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("Messaging").setContentText("New picture or video").setContentIntent(PendingIntent.getActivity(app,62,intent,PendingIntent.FLAG_UPDATE_CURRENT)).setAutoCancel(true).getNotification();n.defaults=Notification.DEFAULT_SOUND|Notification.DEFAULT_LIGHTS;((NotificationManager)app.getSystemService(Context.NOTIFICATION_SERVICE)).notify(762,n);}
}
