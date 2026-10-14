package org.retrobridge.galaxyservices.pixel;
import android.content.Context;
import org.retrobridge.galaxyservices.Wire;
import java.io.*;import java.security.MessageDigest;import java.util.*;import org.json.*;
final class RcsEndpoint {
 static final String HOST="rcs.galaxy-services.invalid";
 final Context app;RcsEndpoint(Context c){app=c;}
 void serve(InputStream in,OutputStream out)throws Exception{
  try{
   String[] lines=new String(Wire.header(in),"ISO-8859-1").split("\r\n");if(!lines[0].equals("POST /v1 HTTP/1.1"))throw new IOException("Request");
   Map<String,String> h=new HashMap<>();for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');if(colon>0&&h.put(lines[i].substring(0,colon).toLowerCase(Locale.US),lines[i].substring(colon+1).trim())!=null)throw new IOException("Repeated header");}
   byte[] expected=Wire.readFile(new File(app.getFilesDir(),"rcs-relay.key"));String offered=h.get("x-galaxy-rcs-key");
   if(offered==null||!offered.matches("[0-9a-f]{64}")||!MessageDigest.isEqual(new String(expected,"US-ASCII").trim().getBytes("US-ASCII"),offered.getBytes("US-ASCII"))){CameraBackupEndpoint.response(out,403,"{\"state\":\"denied\"}");return;}
   if(h.containsKey("transfer-encoding")||!"application/json".equals(h.get("content-type")))throw new IOException("Framing");
   int size=Integer.parseInt(h.get("content-length"));if(size<2||size>100000)throw new IOException("Size");byte[] data=new byte[size];new DataInputStream(in).readFully(data);
   JSONObject result=RcsRelay.get(app).request(new JSONObject(new String(data,"UTF-8")));CameraBackupEndpoint.response(out,200,result.toString());
  }catch(Exception e){CameraBackupEndpoint.response(out,503,"{\"state\":\"unavailable\"}");}
 }
}
