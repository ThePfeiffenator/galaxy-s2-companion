package org.retrobridge.galaxyservices.pixel;
import android.content.Context;import org.retrobridge.galaxyservices.Wire;
import java.io.*;import java.net.*;import java.security.*;import java.util.*;import java.util.concurrent.*;import javax.net.ssl.*;

final class ProxyServer implements Runnable {
 static volatile long authenticatedAt;
 final Context app;final byte[] key;final Certificates certs;final Weather weather;final NewsEndpoint news;final CompanionEndpoint companion;final SpeechEndpoint speech;final SearchEndpoint search;final RedditWebEndpoint reddit;final CameraBackupEndpoint camera;final MusicEndpoint music;final RcsEndpoint rcs;final MediaEndpoint media;volatile ServerSocket server;volatile boolean stop;
 final org.retrobridge.galaxyservices.SocketWorkers pool=new org.retrobridge.galaxyservices.SocketWorkers();
 final LinkedHashMap<String,Long> nonces=new LinkedHashMap<String,Long>();
 volatile int accepted,failed,tls;volatile String last="Starting";
 ProxyServer(Context app)throws Exception{this.app=app;key=Wire.unhex(new String(Wire.readFile(new File(app.getFilesDir(),"link.key")),"US-ASCII").trim());if(key.length!=32)throw new IOException("Pairing missing");certs=new Certificates(app);weather=new Weather(app);news=new NewsEndpoint(app);companion=new CompanionEndpoint(app);speech=new SpeechEndpoint(app);search=new SearchEndpoint(app);reddit=new RedditWebEndpoint(app);camera=new CameraBackupEndpoint(app);music=new MusicEndpoint(app);rcs=new RcsEndpoint(app);media=new MediaEndpoint(app);}
 void status(String s){last=s;try{Wire.writeFile(new File(app.getFilesDir(),"status.txt"),(s+"\nAccepted="+accepted+" TLS="+tls+" Failed="+failed+"\n").getBytes("UTF-8"));}catch(Exception ignored){}}
 public void run(){try{server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(Wire.PORT));status("Ready");while(!stop){final Socket c=server.accept();try{pool.execute(c,new Runnable(){public void run(){handle(c);}});}catch(RejectedExecutionException e){Wire.close(c);}}}catch(Exception e){if(!stop)status("Listener: "+e.getClass().getSimpleName());}}
 void close(){stop=true;companion.contacts.close();try{if(server!=null)server.close();}catch(Exception ignored){}pool.shutdownNow();}
 synchronized boolean authenticate(String s,byte[] h)throws Exception{
  String[] a=s.split(" ");if(a.length!=4||!a[0].equals("GS1")||a[2].length()!=32||a[3].length()!=64)return false;
  long time=Long.parseLong(a[1]),now=System.currentTimeMillis();if(Math.abs(time-now)>120000)return false;
  String prefix=a[0]+" "+a[1]+" "+a[2]+"\n";
  if(!MessageDigest.isEqual(Wire.mac(key,prefix,h),Wire.unhex(a[3])))return false;
  Iterator<Map.Entry<String,Long>> it=nonces.entrySet().iterator();while(it.hasNext())if(now-it.next().getValue()>240000)it.remove();
  if(nonces.containsKey(a[2])||nonces.size()>=4096)return false;nonces.put(a[2],now);authenticatedAt=android.os.SystemClock.elapsedRealtime();return true;
 }
 static InetAddress publicAddress(String host)throws Exception {
  if(host.length()>253||!host.matches("[a-zA-Z0-9.-]+")||host.contains(".."))throw new IOException("Invalid host");
  InetAddress[] addresses=InetAddress.getAllByName(host);
  for(InetAddress a:addresses){byte[] b=a.getAddress();if(a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress()||(b.length==16&&(b[0]&254)==252)||(b.length==4&&(b[0]&255)==100&&(b[1]&255)>=64&&(b[1]&255)<=127))throw new IOException("Non-public destination");}
  // IPv4 is preferred because the older hotspot may lack IPv6. Resolution is pinned to this address.
  for(InetAddress a:addresses)if(a instanceof Inet4Address)return a;return addresses[0];
 }
 static SSLSocket upstream(String host,int port)throws Exception {
  Socket base=new Socket();try{
   base.connect(new InetSocketAddress(publicAddress(host),port),15000);base.setSoTimeout(60000);
   SSLSocket ssl=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(base,host,port,true);
   SSLParameters p=ssl.getSSLParameters();p.setEndpointIdentificationAlgorithm("HTTPS");ssl.setSSLParameters(p);ssl.setEnabledProtocols(new String[]{"TLSv1.2","TLSv1.3"});ssl.startHandshake();return ssl;
  }catch(Exception e){Wire.close(base);throw e;}
 }
 void handle(Socket client){Socket remote=null;Socket browser=client;String stage="Authentication";try{
  client.setSoTimeout(15000);InputStream in=client.getInputStream();String auth=Wire.line(in);byte[] header=Wire.header(in);
  if(!authenticate(auth,header))throw new IOException("Authentication rejected");accepted++;
  String h=new String(header,"ISO-8859-1"),first=h.substring(0,h.indexOf("\r\n"));String[] req=first.split(" ");if(req.length!=3)throw new IOException("Request line");
  if(req[0].equals("CONNECT")){
   int cut=req[1].lastIndexOf(':');if(cut<1)throw new IOException("CONNECT target");String host=req[1].substring(0,cut);int port=Integer.parseInt(req[1].substring(cut+1));if(port!=443)throw new IOException("Unsupported port");
   if(host.equals(CompanionEndpoint.HOST)||host.equals(CatalogEndpoint.HOST)||host.equals(GmailEndpoint.HOST)||host.equals(YouTubeRelay.HOST)||host.equals(MediaEndpoint.HOST)||host.equals(RcsEndpoint.HOST)||host.equals(MusicEndpoint.HOST)||host.equals(CameraBackupEndpoint.HOST)||host.equals(RedditEndpoint.HOST)||host.equals(SearchEndpoint.HOST)||host.equals("galaxy-services.invalid")||host.equals("location.galaxy-services.invalid")||host.equals("speech.galaxy-services.invalid")){
    client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes("US-ASCII"));client.getOutputStream().flush();
    stage="Private service TLS";client.setSoTimeout(60000);org.bouncycastle.tls.TlsServerProtocol legacy=LegacyTls.accept(client,certs,host);tls++;
    if(host.equals(CompanionEndpoint.HOST))companion.serve(legacy.getInputStream(),legacy.getOutputStream());else if(host.equals(CatalogEndpoint.HOST))CatalogEndpoint.serve(app,legacy.getInputStream(),legacy.getOutputStream());else if(host.equals(GmailEndpoint.HOST))GmailEndpoint.serve(app,legacy.getInputStream(),legacy.getOutputStream());else if(host.equals(YouTubeRelay.HOST))YouTubeControls.serve(app,legacy.getInputStream(),legacy.getOutputStream());else if(host.equals(MediaEndpoint.HOST))media.serve(legacy.getInputStream(),legacy.getOutputStream());else if(host.equals(RcsEndpoint.HOST))rcs.serve(legacy.getInputStream(),legacy.getOutputStream());else if(host.equals(MusicEndpoint.HOST))music.serve(legacy.getInputStream(),legacy.getOutputStream());else if(host.equals(CameraBackupEndpoint.HOST))camera.serve(legacy.getInputStream(),legacy.getOutputStream());else if(host.equals(RedditEndpoint.HOST))reddit.serve(legacy.getInputStream(),legacy.getOutputStream());else if(host.equals(SearchEndpoint.HOST))search.serve(legacy.getInputStream(),legacy.getOutputStream());else if(host.equals("speech.galaxy-services.invalid"))speech.serve(legacy.getInputStream(),legacy.getOutputStream());else if(host.equals("location.galaxy-services.invalid"))((LegacyService)app).locations.serve(legacy.getInputStream(),legacy.getOutputStream());else CalendarFeed.serve(app,legacy.getInputStream(),legacy.getOutputStream());legacy.close();status("Connected");return;
   }
   stage="Upstream TLS";remote=upstream(host,port);
   client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes("US-ASCII"));client.getOutputStream().flush();
   stage="Browser TLS";client.setSoTimeout(60000);org.bouncycastle.tls.TlsServerProtocol legacy=LegacyTls.accept(client,certs,host);tls++;status("Connected");LegacyTls.bridge(legacy,client,remote);
  }else{
   stage="HTTP";URI u=new URI(req[1]);if(!"http".equalsIgnoreCase(u.getScheme())||u.getUserInfo()!=null||u.getFragment()!=null)throw new IOException("HTTP URL");String host=u.getHost();if(host==null||(u.getPort()!=-1&&u.getPort()!=80))throw new IOException("HTTP target");
   if(YouTubeRelay.HOST.equals(host)){stage="YouTube";YouTubeRelay.serve(app,client,u,req[0],h);status("YouTube served");return;}
   if(NewsEndpoint.HOST.equalsIgnoreCase(host)){stage="News";if(!req[0].equals("GET"))throw new IOException("News method");news.serve(u,client.getOutputStream());status("News updated");return;}
   if(Weather.HOST.equalsIgnoreCase(host)){stage="Weather";if(!req[0].equals("GET"))throw new IOException("Weather method");weather.serve(u,client.getOutputStream());status("Weather updated");return;}
   remote=new Socket(java.net.Proxy.NO_PROXY);remote.connect(new InetSocketAddress(publicAddress(host),80),15000);remote.setSoTimeout(60000);client.setSoTimeout(60000);
   String path=u.getRawPath();if(path==null||path.isEmpty())path="/";if(u.getRawQuery()!=null)path+="?"+u.getRawQuery();StringBuilder out=new StringBuilder(req[0]+" "+path+" "+req[2]+"\r\n");
   for(String line:h.substring(first.length()+2).split("\r\n")){String lower=line.toLowerCase(Locale.US);if(line.isEmpty()||lower.startsWith("proxy-")||lower.startsWith("connection:")||lower.startsWith("host:"))continue;out.append(line).append("\r\n");}out.append("Host: ").append(host).append("\r\nConnection: close\r\n\r\n");remote.getOutputStream().write(out.toString().getBytes("ISO-8859-1"));remote.getOutputStream().flush();status("Connected");Wire.bridge(client,remote);
  }
 }catch(Exception e){failed++;android.util.Log.w("GalaxyProxy",stage+": "+e.getClass().getSimpleName()+" "+e.getMessage());status(stage+": "+e.getClass().getSimpleName()+" "+e.getMessage());if(stage.equals("Upstream TLS"))try{client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".getBytes("US-ASCII"));}catch(Exception ignored){}}finally{Wire.close(browser);Wire.close(client);Wire.close(remote);}}
}
