package org.retrobridge.galaxyservices.pixel;
import android.content.Context;import java.io.*;import org.json.*;import org.retrobridge.galaxyservices.Wire;
final class CompanionEndpoint {
 static final String HOST="companion.galaxy-services.invalid";
 final Context app;final ContactsFeed contacts;
 CompanionEndpoint(Context c){app=c;contacts=new ContactsFeed(c);}
 void serve(InputStream in,OutputStream out)throws Exception{
  try{String[] lines=new String(Wire.header(in),"ISO-8859-1").split("\r\n");String[] r=lines[0].split(" ");if(r.length!=3||!r[0].equals("GET"))throw new IOException("Method");String supplied=null;for(int i=1;i<lines.length;i++)if(lines[i].toLowerCase(java.util.Locale.US).startsWith("x-companion-key:")){if(supplied!=null)throw new IOException("Duplicate key");supplied=lines[i].substring(lines[i].indexOf(':')+1).trim();}String secret=new String(Wire.readFile(new File(app.getFilesDir(),"link.key")),"US-ASCII").trim();if(supplied==null||!supplied.matches("[0-9a-f]{64}")||!java.security.MessageDigest.isEqual(supplied.getBytes("US-ASCII"),secret.getBytes("US-ASCII")))throw new IOException("Pairing required");JSONObject data;
   if(r[1].equals("/contacts"))data=contacts.snapshot();else if(r[1].equals("/notifications"))data=NotificationRelay.snapshot(app);else if(r[1].matches("/icons/[0-9a-f]{64}"))data=ForwardedIcons.get(app,r[1].substring(7));else throw new IOException("Route");CameraBackupEndpoint.response(out,200,data.toString());
  }catch(Exception e){CameraBackupEndpoint.response(out,503,"{\"error\":\"temporarily_unavailable\"}");}
 }
}
