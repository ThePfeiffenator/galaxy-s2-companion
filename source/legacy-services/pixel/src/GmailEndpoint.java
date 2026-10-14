package org.retrobridge.galaxyservices.pixel;
import android.content.Context;import android.net.Uri;import android.os.Bundle;import org.json.JSONObject;import java.io.*;import java.util.*;import org.retrobridge.galaxyservices.Wire;
/** Only reachable inside authenticated paired TLS. No mail data in logs or public endpoints. */
final class GmailEndpoint {
 static final String HOST="gmail.galaxy-services.invalid";
 static void serve(Context c,InputStream in,OutputStream out)throws Exception {
  byte[] data;int code=200;
  try{
   String[] lines=new String(Wire.header(in),"US-ASCII").split("\r\n");if(!lines[0].equals("POST /v1/mail HTTP/1.1"))throw new IOException("Invalid mail request");
   int length=-1;String host=null;
   for(int i=1;i<lines.length;i++){int at=lines[i].indexOf(':');if(at<1)continue;String key=lines[i].substring(0,at).trim().toLowerCase(Locale.US),value=lines[i].substring(at+1).trim();if(key.equals("content-length")){if(length!=-1)throw new IOException("Duplicate mail length");length=Integer.parseInt(value);}if(key.equals("host")){if(host!=null)throw new IOException("Duplicate mail host");host=value;}if(key.equals("transfer-encoding"))throw new IOException("Unsupported encoding");}
   if(!HOST.equals(host)||length<1||length>500000)throw new IOException("Invalid mail request");
   byte[] body=new byte[length];new DataInputStream(in).readFully(body);JSONObject request=new JSONObject(new String(body,"UTF-8"));Bundle b=new Bundle();b.putString("request",request.toString());
   Bundle result=c.getContentResolver().call(Uri.parse("content://org.retrobridge.galaxymail.pixel"),"gmail",null,b);if(result==null)throw new IOException("Google Bridge unavailable");
   if(result.containsKey("error"))data=new JSONObject().put("error",result.getString("error")).toString().getBytes("UTF-8");else data=result.getString("json").getBytes("UTF-8");
   if(data.length>900000)throw new IOException("Mail response too large");
  }catch(Exception e){code=503;data="{\"error\":\"Gmail connection unavailable\"}".getBytes("UTF-8");}
  out.write(("HTTP/1.1 "+code+(code==200?" OK":" Service Unavailable")+"\r\nContent-Type: application/json; charset=UTF-8\r\nContent-Length: "+data.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));out.write(data);out.flush();
 }
}
