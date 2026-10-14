package org.retrobridge.galaxyservices.s2;

import java.io.*;import java.net.*;import java.security.SecureRandom;import javax.net.ssl.*;
import org.retrobridge.galaxyservices.Wire;

/** Direct paired CONNECT avoids nesting work inside the same limited socket pool. */
final class YouTubeSecure {
 static final String HOST="youtube.galaxy-services.invalid";
 static void forward(LegacyService service,Socket client,String method,String path,String header)throws Exception {
  if(!method.matches("POST|PUT|DELETE"))throw new IOException("Invalid action method");
  int size=0;boolean hasLength=false;String action=null,channel=null;
  for(String line:header.split("\r\n")){int colon=line.indexOf(':');if(colon<0)continue;String key=line.substring(0,colon).trim().toLowerCase(java.util.Locale.US),value=line.substring(colon+1).trim();
   if(key.equals("content-length")){if(hasLength)throw new IOException("Duplicate length");hasLength=true;size=Integer.parseInt(value);}
   if(key.equals("transfer-encoding")||key.equals("x-http-method-override"))throw new IOException("Unsupported action encoding");
   if(key.equals("x-galaxy-action-id")){if(action!=null)throw new IOException("Duplicate action");action=value;}
   if(key.equals("x-galaxy-channel")){if(channel!=null)throw new IOException("Duplicate channel");channel=value;}
  }
  if(size<0||size>16384||action==null||!action.matches("[0-9a-f-]{36}")||channel==null||!channel.matches("UC[A-Za-z0-9_-]{22}"))throw new IOException("Invalid action request");
  byte[] body=new byte[size];new DataInputStream(client.getInputStream()).readFully(body);
  Socket base=new Socket(Proxy.NO_PROXY);SSLSocket tls=null;
  try {
   base.connect(new InetSocketAddress(service.gateway(),Wire.PORT),15000);base.setSoTimeout(65000);
   byte[] h=("CONNECT "+HOST+":443 HTTP/1.1\r\nHost: "+HOST+":443\r\n\r\n").getBytes("US-ASCII"),nonce=new byte[16];new SecureRandom().nextBytes(nonce);
   String prefix="GS1 "+System.currentTimeMillis()+" "+Wire.hex(nonce)+"\n";
   base.getOutputStream().write((prefix.trim()+" "+Wire.hex(Wire.mac(service.key,prefix,h))+"\r\n").getBytes("US-ASCII"));base.getOutputStream().write(h);base.getOutputStream().flush();
   if(!new String(Wire.header(base.getInputStream()),"US-ASCII").startsWith("HTTP/1.1 200 "))throw new IOException("Pixel connection failed");
   tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(base,HOST,443,true);tls.setSoTimeout(65000);tls.startHandshake();
   if(!HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST,tls.getSession()))throw new SSLPeerUnverifiedException("Pixel service identity");
   String out=method+" "+path+" HTTP/1.1\r\nHost: "+HOST+"\r\nX-Galaxy-Action-Id: "+action+"\r\nX-Galaxy-Channel: "+channel+"\r\nContent-Type: application/atom+xml; charset=UTF-8\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n";
   tls.getOutputStream().write(out.getBytes("US-ASCII"));tls.getOutputStream().write(body);tls.getOutputStream().flush();
   Wire.copy(tls.getInputStream(),client.getOutputStream());
  }finally{Wire.close(tls);Wire.close(base);}
 }
}
