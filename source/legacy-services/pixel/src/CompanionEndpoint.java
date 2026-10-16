package org.retrobridge.galaxyservices.pixel;
import android.content.Context;import java.io.*;import java.util.*;import org.json.*;import org.retrobridge.galaxyservices.Wire;
final class CompanionEndpoint {
 static final String HOST="companion.galaxy-services.invalid";
 final Context app;final ContactsFeed contacts;
 CompanionEndpoint(Context c){app=c;contacts=new ContactsFeed(c);}
 void serve(InputStream in,OutputStream out)throws Exception{
  try{
   String[] lines=new String(Wire.header(in),"ISO-8859-1").split("\r\n"),r=lines[0].split(" ");
   if(r.length!=3)throw new IOException("Request");
   Map<String,String> headers=new HashMap<>();
   for(int i=1;i<lines.length;i++){
    int colon=lines[i].indexOf(':');if(colon<=0)throw new IOException("Header");
    if(headers.put(lines[i].substring(0,colon).trim().toLowerCase(Locale.US),lines[i].substring(colon+1).trim())!=null)throw new IOException("Duplicate header");
   }
   String supplied=headers.get("x-companion-key");
   String secret=new String(Wire.readFile(new File(app.getFilesDir(),"link.key")),"US-ASCII").trim();
   if(supplied==null||!supplied.matches("[0-9a-f]{64}")||!java.security.MessageDigest.isEqual(supplied.getBytes("US-ASCII"),secret.getBytes("US-ASCII"))){CameraBackupEndpoint.response(out,403,"{\"error\":\"pairing_required\"}");return;}
   JSONObject data;
   if(r[0].equals("GET")){
    if(r[1].equals("/contacts"))data=contacts.snapshot();
    else if(r[1].equals("/notifications"))data=NotificationRelay.snapshot(app);
    else if(r[1].matches("/icons/[0-9a-f]{64}"))data=ForwardedIcons.get(app,r[1].substring(7));
    else throw new IOException("Route");
   }else if(r[0].equals("POST")&&r[1].matches("/notifications/[0-9a-f]{64}/dismiss")){
    int size=Integer.parseInt(headers.get("content-length"));
    if(size<1||size>1024||headers.containsKey("transfer-encoding")||!"application/json".equals(headers.get("content-type")))throw new IOException("Body");
    byte[] bytes=new byte[size];int offset=0;
    while(offset<size){int n=in.read(bytes,offset,size-offset);if(n<0)throw new EOFException();offset+=n;}
    JSONObject body=new JSONObject(new String(bytes,"UTF-8"));
    data=NotificationRelay.dismiss(app,r[1].substring(15,79),body.getString("revision"));
   }else{CameraBackupEndpoint.response(out,405,"{\"error\":\"method\"}");return;}
   CameraBackupEndpoint.response(out,200,data.toString());
  }catch(Exception e){CameraBackupEndpoint.response(out,503,"{\"error\":\"temporarily_unavailable\"}");}
 }
}
