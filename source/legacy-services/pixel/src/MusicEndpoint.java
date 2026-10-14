package org.retrobridge.galaxyservices.pixel;
import android.content.*;import java.io.*;import java.net.*;import org.retrobridge.galaxyservices.Wire;

/** The paired TLS endpoint never exposes the Google credentials to the S2. */
final class MusicEndpoint {
 static final String HOST="music.galaxy-services.invalid";final Context app;
 MusicEndpoint(Context c){app=c;}
 void serve(InputStream in,OutputStream out)throws Exception{
  String[] lines=new String(Wire.header(in),"ISO-8859-1").split("\r\n");String[] request=lines[0].split(" ");
  if(request.length!=3||!request[0].equals("GET")||!(request[1].equals("/catalog")||request[1].matches("/resolve/[A-Za-z0-9_-]{11}")||request[1].matches("/art/[A-Za-z0-9_-]{11}\\.jpg"))){CameraBackupEndpoint.response(out,404,"{\"error\":\"not_found\"}");return;}
  for(int i=1;i<lines.length;i++)if(lines[i].toLowerCase(java.util.Locale.US).startsWith("transfer-encoding:")||lines[i].toLowerCase(java.util.Locale.US).startsWith("content-length:"))throw new IOException("Unexpected music request body");
  File pairing=new File(app.getFilesDir(),"music-relay.key");if(!pairing.exists()){CameraBackupEndpoint.response(out,503,"{\"error\":\"not_configured\"}");return;}
  String secret=new String(Wire.readFile(pairing),"US-ASCII").trim();if(!secret.matches("[0-9a-f]{64}"))throw new IOException("Music pairing");
  HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:8769"+request[1]).openConnection(Proxy.NO_PROXY);
  try{c.setConnectTimeout(2500);c.setReadTimeout(45000);c.setInstanceFollowRedirects(false);c.setRequestProperty("X-Galaxy-Music-Key",secret);int code=c.getResponseCode();if(code!=200&&code!=503)throw new IOException("Music backend");
   InputStream source=code==200?c.getInputStream():c.getErrorStream();if(source==null)throw new IOException("Music response");ByteArrayOutputStream data=new ByteArrayOutputStream();try(InputStream input=source){byte[] buffer=new byte[32768];int n;while((n=input.read(buffer))!=-1){if(data.size()+n>16*1024*1024)throw new IOException("Catalog limit");data.write(buffer,0,n);}}
   if(code==200&&request[1].startsWith("/art/")){byte[] bytes=data.toByteArray();out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));out.write(bytes);out.flush();}else CameraBackupEndpoint.response(out,code,data.toString("UTF-8"));
  }catch(IOException e){CameraBackupEndpoint.response(out,503,"{\"error\":\"music_starting\"}");}finally{c.disconnect();}
 }
}
