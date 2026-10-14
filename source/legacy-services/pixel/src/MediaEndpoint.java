package org.retrobridge.galaxyservices.pixel;
import android.content.Context;import org.retrobridge.galaxyservices.*;import java.io.*;import java.security.MessageDigest;import java.util.*;import org.json.*;
final class MediaEndpoint {
 static final String HOST="media.galaxy-services.invalid";final Context app;MediaEndpoint(Context c){app=c;}
 synchronized void serve(InputStream in,OutputStream out)throws Exception{
  try{
   String[] lines=new String(Wire.header(in),"ISO-8859-1").split("\r\n"),req=lines[0].split(" ");
   if(req.length!=3||!req[2].equals("HTTP/1.1")||!req[1].matches("(/outgoing/[0-9a-f]{32}(/send)?|/incoming(/[0-9a-f]{64}(/ack)?)?)"))throw new IOException("request");
   Map<String,String> h=new HashMap<>();for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');if(colon<1||h.put(lines[i].substring(0,colon).toLowerCase(Locale.US),lines[i].substring(colon+1).trim())!=null)throw new IOException("header");}
   String expected=new String(Wire.readFile(new File(app.getFilesDir(),"media-relay.key")),"US-ASCII").trim(),offered=h.get("x-galaxy-media-key");
   if(!expected.matches("[0-9a-f]{64}")||offered==null||!offered.matches("[0-9a-f]{64}")||!MessageDigest.isEqual(expected.getBytes("US-ASCII"),offered.getBytes("US-ASCII"))){CameraBackupEndpoint.response(out,403,"{\"state\":\"denied\"}");return;}
   if(h.containsKey("transfer-encoding"))throw new IOException("framing");
   if(req[1].startsWith("/incoming")){
    if(!MediaRelay.get(app).enabled())throw new IOException("disabled");
    if(!"0".equals(h.containsKey("content-length")?h.get("content-length"):"0"))throw new IOException("body");
    IncomingMedia inbox=IncomingMedia.get(app);
    if(req[0].equals("GET")&&req[1].equals("/incoming"))CameraBackupEndpoint.response(out,200,inbox.list().toString());
    else if(req[0].equals("GET")&&req[1].length()==74){File file=inbox.attachment(req[1].substring(10));out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: "+file.length()+"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));try(InputStream body=new FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=body.read(b))!=-1)out.write(b,0,n);}}
    else if(req[0].equals("POST")&&req[1].endsWith("/ack"))CameraBackupEndpoint.response(out,200,inbox.ack(req[1].substring(10,74)).toString());
    else throw new IOException("method");return;
   }
   String id=req[1].substring(10,42);MediaRelay relay=MediaRelay.get(app);JSONObject response;
   int size=Integer.parseInt(h.containsKey("content-length")?h.get("content-length"):"0");
   if(req[0].equals("PUT")&&req[1].length()==42){if(size<20||size>MediaPayload.MAX_WIRE||!"application/octet-stream".equals(h.get("content-type")))throw new IOException("size");String digest=h.get("x-media-sha256");if(digest==null||!digest.matches("[0-9a-f]{64}"))throw new IOException("digest");byte[] body=new byte[size];new DataInputStream(in).readFully(body);response=relay.stage(id,digest,body);}
   else if(req[0].equals("POST")&&req[1].endsWith("/send")&&size==0)response=relay.send(id);
   else if(req[0].equals("GET")&&req[1].length()==42&&size==0)response=relay.status(id);
   else throw new IOException("method");
   CameraBackupEndpoint.response(out,200,response.toString());
  }catch(Exception e){CameraBackupEndpoint.response(out,503,"{\"state\":\"unavailable\"}");}
 }
}
