package org.retrobridge.galaxyservices.s2;
import android.content.Context;import java.io.*;import java.net.*;import javax.net.ssl.HttpsURLConnection;import org.json.JSONObject;import org.retrobridge.galaxyservices.Wire;
/** Paired TLS link shared by background sync and explicit notification actions. */
final class CompanionRequest {
 static JSONObject send(Context app,String path,JSONObject body,int limit)throws Exception{
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://companion.galaxy-services.invalid/"+path).openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",Wire.LOCAL)));
  c.setConnectTimeout(10000);c.setReadTimeout(body==null?30000:15000);c.setUseCaches(false);c.setInstanceFollowRedirects(false);
  c.setRequestProperty("X-Companion-Key",new String(Wire.readFile(new File(app.getFilesDir(),"link.key")),"US-ASCII").trim());
  try{
   if(body!=null){
    byte[] bytes=body.toString().getBytes("UTF-8");c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(bytes.length);
    try(OutputStream out=c.getOutputStream()){out.write(bytes);}
   }
   if(c.getResponseCode()!=200)throw new IOException("Companion unavailable");
   ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1){if(b.size()+n>limit)throw new IOException("Response limit");b.write(bytes,0,n);}}
   return new JSONObject(b.toString("UTF-8"));
  }finally{c.disconnect();}
 }
}
