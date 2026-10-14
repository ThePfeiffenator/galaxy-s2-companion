package org.retrobridge.callprobe;
import android.content.Context;
import java.io.*;import java.net.*;import org.json.*;
/** Authenticated private endpoint through the existing S2/Pixel TLS compatibility link. */
final class RcsClient {
 final Context app;volatile HttpURLConnection current;
 RcsClient(Context c){app=c;}
 boolean configured(){return new File(app.getFilesDir(),"rcs-relay.key").isFile();}
 private String key()throws Exception{
  File f=new File(app.getFilesDir(),"rcs-relay.key");if(f.length()!=64&&f.length()!=65)throw new IOException("RCS pairing missing");
  byte[] raw=new byte[(int)f.length()];DataInputStream in=new DataInputStream(new FileInputStream(f));try{in.readFully(raw);}finally{in.close();}
  String key=new String(raw,"US-ASCII").trim();if(!key.matches("[0-9a-f]{64}"))throw new IOException("Invalid RCS pairing");return key;
 }
 JSONObject request(JSONObject body)throws Exception{
  byte[] data=body.toString().getBytes("UTF-8");if(data.length>100000)throw new IOException("RCS request too large");
  HttpURLConnection c=(HttpURLConnection)new URL("https://rcs.galaxy-services.invalid/v1").openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",8766)));
  current=c;
  try{
   c.setConnectTimeout(6000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setRequestMethod("POST");c.setDoOutput(true);c.setFixedLengthStreamingMode(data.length);
   c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("X-Galaxy-Rcs-Key",key());
   OutputStream out=c.getOutputStream();out.write(data);out.close();if(c.getResponseCode()!=200)throw new IOException("RCS endpoint unavailable");
   InputStream in=c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
   try{while((n=in.read(buffer))>=0){if(bytes.size()+n>768*1024)throw new IOException("RCS response too large");bytes.write(buffer,0,n);}}finally{in.close();}
   return new JSONObject(bytes.toString("UTF-8"));
  }finally{if(current==c)current=null;c.disconnect();}
 }
 JSONObject route(String number)throws Exception{return request(new JSONObject().put("op","route").put("number",number));}
 JSONArray pending()throws Exception{JSONObject r=request(new JSONObject().put("op","list"));if(!"ok".equals(r.optString("state")))throw new IOException("RCS relay not ready");return r.getJSONArray("messages");}
 void ack(String id)throws Exception{JSONObject r=request(new JSONObject().put("op","ack").put("id",id));if(!"ok".equals(r.optString("state")))throw new IOException("RCS receipt unavailable");}
 String send(String id,String number,String body,String route)throws Exception{return request(new JSONObject().put("op","send").put("id",id).put("number",number).put("body",body).put("route",route)).getString("state");}
 void cancel(){HttpURLConnection c=current;if(c!=null)c.disconnect();}
}
