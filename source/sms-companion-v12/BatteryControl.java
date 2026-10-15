package org.retrobridge.callprobe;
import android.net.LocalSocket;import android.net.LocalSocketAddress;import java.io.*;import org.json.JSONObject;
final class BatteryControl {
 interface Callback {void completed(JSONObject value);}
 static JSONObject requestNow(String command){LocalSocket s=new LocalSocket();try{
  if(!command.equals("STATUS")&&!command.equals("ENABLE")&&!command.equals("DISABLE"))return null;
  s.connect(new LocalSocketAddress("galaxy-companion.charge.v1",LocalSocketAddress.Namespace.ABSTRACT));s.setSoTimeout(7000);
  if(s.getPeerCredentials().getUid()!=0)return null;
  s.getOutputStream().write((command+"\n").getBytes("US-ASCII"));ByteArrayOutputStream b=new ByteArrayOutputStream();InputStream in=s.getInputStream();
  for(int i=0;i<1024;i++){int c=in.read();if(c<0)return null;if(c=='\n'){JSONObject result=new JSONObject(b.toString("UTF-8"));return result.has("error")?null:result;}b.write(c);}return null;
 }catch(Exception e){return null;}finally{try{s.close();}catch(Exception ignored){}}}
 static void request(final String command,final Callback cb){Thread t=new Thread(new Runnable(){public void run(){cb.completed(requestNow(command));}},"Battery control");t.setDaemon(true);t.start();}
}
