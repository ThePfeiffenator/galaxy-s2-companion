package org.retrobridge.galaxyservices.s2;
import java.net.*;import java.io.*;import javax.net.ssl.*;import android.content.Context;import org.retrobridge.galaxyservices.Wire;
final class Probe {
 static void run(Context c){try{Thread.sleep(1200);}catch(Exception ignored){}StringBuilder result=new StringBuilder("Started="+System.currentTimeMillis()+"\n");String[] hosts={"example.com","en.wikipedia.org","expired.badssl.com","wrong.host.badssl.com","self-signed.badssl.com"};
  for(String host:hosts){Socket base=null;try{base=new Socket(java.net.Proxy.NO_PROXY);base.connect(new InetSocketAddress("127.0.0.1",Wire.LOCAL),5000);base.setSoTimeout(30000);base.getOutputStream().write(("CONNECT "+host+":443 HTTP/1.1\r\nHost: "+host+":443\r\n\r\n").getBytes("US-ASCII"));String header=new String(Wire.header(base.getInputStream()),"US-ASCII");if(!header.startsWith("HTTP/1.1 200")){result.append(host).append(" REJECTED ").append(header.split("\r\n")[0]).append('\n');continue;}
   SSLSocket ssl=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(base,host,443,true);ssl.setSoTimeout(30000);ssl.startHandshake();if(!HttpsURLConnection.getDefaultHostnameVerifier().verify(host,ssl.getSession()))throw new SSLHandshakeException("Hostname mismatch");ssl.getOutputStream().write(("GET / HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));String response=Wire.line(ssl.getInputStream());result.append(host).append(' ').append(ssl.getSession().getProtocol()).append(' ').append(response).append('\n');ssl.close();
  }catch(Exception e){result.append(host).append(" ERROR ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');}finally{Wire.close(base);}try{Wire.writeFile(new File(c.getFilesDir(),"probe.txt"),result.toString().getBytes("UTF-8"));}catch(Exception ignored){}}
  try{Wire.writeFile(new File(c.getFilesDir(),"probe.txt"),result.toString().getBytes("UTF-8"));}catch(Exception ignored){}
 }
}
