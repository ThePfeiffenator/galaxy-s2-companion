package org.retrobridge.galaxyservices.s2;
import java.io.*;import java.net.*;import java.security.*;import org.json.*;import android.os.SystemClock;import javax.net.ssl.HttpsURLConnection;import org.retrobridge.galaxyservices.Wire;
final class VideoBackup {
 static final int CHUNK=262144;final CameraBackup owner;
 VideoBackup(CameraBackup c){owner=c;}
 JSONObject request(String method,String hash,File file,long taken,long offset,byte[] chunk,boolean finish)throws Exception{
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://camera.galaxy-services.invalid/videos/"+hash+(finish?"/complete":"")).openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",Wire.LOCAL)));
  c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setRequestMethod(method);c.setUseCaches(false);c.setInstanceFollowRedirects(false);c.setRequestProperty("X-Video-Size",String.valueOf(file.length()));c.setRequestProperty("X-Video-Taken",String.valueOf(taken));c.setRequestProperty("X-Video-Type",file.getName().toLowerCase(java.util.Locale.US).endsWith(".3gp")?"video/3gpp":"video/mp4");c.setRequestProperty("X-Video-Offset",String.valueOf(offset));
  try{if(!method.equals("GET")){c.setDoOutput(true);c.setFixedLengthStreamingMode(chunk==null?0:chunk.length);if(chunk!=null)c.setRequestProperty("X-Chunk-SHA256",Wire.hex(MessageDigest.getInstance("SHA-256").digest(chunk)));try(OutputStream out=c.getOutputStream()){if(chunk!=null)out.write(chunk);}}
   if(c.getResponseCode()!=200)throw new IOException("Video transfer waiting");ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] buf=new byte[1024];int n;while((n=in.read(buf))!=-1){if(b.size()+n>4096)throw new IOException("Video receipt");b.write(buf,0,n);}}JSONObject reply=new JSONObject(b.toString("UTF-8"));if(!hash.equals(reply.getString("sha256")))throw new IOException("Video receipt identity");return reply;
  }finally{c.disconnect();}
 }
 boolean upload(File file,String hash,long size,long modified,long deadline)throws Exception{
  JSONObject state=request("GET",hash,file,modified,0,null,false);if(state.getBoolean("stored"))return true;long offset=state.getLong("offset");if(offset<0||offset>size)throw new IOException("Video offset");
  try(RandomAccessFile in=new RandomAccessFile(file,"r")){in.seek(offset);while(offset<size){if(owner.stopped||!owner.settings.getBoolean("enabled",false)||SystemClock.elapsedRealtime()>deadline)return false;if(size!=file.length()||modified!=file.lastModified())throw new IOException("Camera video changed");int len=(int)Math.min(CHUNK,size-offset);byte[] chunk=new byte[len];in.readFully(chunk);state=request("PUT",hash,file,modified,offset,chunk,false);if(state.getBoolean("stored"))return true;if(state.getLong("offset")!=offset+len)throw new IOException("Video acknowledgement");offset+=len;Thread.sleep(100);}}
  if(size!=file.length()||modified!=file.lastModified())throw new IOException("Camera video changed");return request("POST",hash,file,modified,offset,null,true).getBoolean("stored");
 }
}
