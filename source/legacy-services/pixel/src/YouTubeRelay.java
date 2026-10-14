package org.retrobridge.galaxyservices.pixel;
import java.io.*;import java.net.*;import java.util.Locale;import org.retrobridge.galaxyservices.Wire;
/** Fixed private backend, reachable only after the paired S2 has authenticated. */
final class YouTubeRelay {
 static final String HOST="youtube.galaxy-services.invalid";
 static void serve(android.content.Context app,Socket client,URI uri,String method,String header)throws Exception {
  if(YouTubeThumbnails.serve(client,uri,method))return;
  if(YouTubeChannelSearch.serve(app,client,uri,method))return;
  if(YouTubeComments.serve(app,client,uri,method))return;
  if(YouTubeAccountFeeds.serve(app,client,uri,method))return;
  if(YouTubeLibraryFeeds.serve(app,client,uri,method))return;
  if(YouTubeVideoFeeds.serve(app,client,uri,method))return;
  String p=uri.getRawPath();if(p==null||p.length()>4096||p.contains("..")||p.contains("%"))throw new IOException("YouTube path");
  android.util.Log.i("GalaxyYouTube",method+" "+p);
  boolean register=p.equals("/youtube/accounts/registerDevice");
  boolean catalog=p.equals("/schemas/2007/categories.cat");
  boolean upload=p.matches("/sabr_hls_adapter/music/[A-Za-z0-9_-]{11}/index\\.m3u8")||p.matches("/sabrhls-audio-fragment/music/[A-Za-z0-9_-]{11}/[0-9]{1,5}\\.ts");
  if(upload&&(!method.equals("GET")||uri.getRawQuery()!=null))throw new IOException("Upload request");
  boolean read=p.startsWith("/feeds/api/")||p.startsWith("/sabr_hls_adapter/")||p.startsWith("/shls-video-manifest/")||p.startsWith("/shls-audio-manifest/")||p.startsWith("/sabrhls-video-fragment/")||p.startsWith("/sabrhls-audio-fragment/");
  if(catalog)read=true;
  if(p.matches("/assets/[a-zA-Z0-9_=-]{1,600}\\.(png|jpg)")||p.equals("/assets/site-assets/default.png"))read=true;
  if(p.equals("/avatar_wait")){String av=null;String query=uri.getRawQuery();if(query==null)throw new IOException("Missing avatar");for(String pair:query.split("&")){String[] kv=pair.split("=",2);if(kv.length!=2)throw new IOException("Avatar query");if(kv[0].equals("av")){if(av!=null)throw new IOException("Duplicate avatar");av=URLDecoder.decode(kv[1],"UTF-8");}else if(!kv[0].equals("defaultadapt")||!kv[1].equals("1"))throw new IOException("Avatar option");}if(av==null||!av.matches("/assets/[a-zA-Z0-9_=-]{1,600}\\.(png|jpg)"))throw new IOException("Invalid avatar");read=true;}
  // Progressive public-video endpoints only; constrain the ID before upstream shell-based media tooling.
  boolean mp4=p.equals("/get_480")||p.equals("/exp_hd")||p.equals("/channel_fh264_getvideo");
  if(mp4){String query=uri.getRawQuery();String id=null;if(query!=null)for(String pair:query.split("&")){String[] kv=pair.split("=",2);if(kv.length==2&&(kv[0].equals("video_id")||kv[0].equals("v")))id=URLDecoder.decode(kv[1],"UTF-8");}if(id==null||!id.matches("[a-zA-Z0-9_-]{11}(/lower)?"))throw new IOException("Invalid public video ID");read=true;uri=new URI("http://"+HOST+"/get_480?video_id="+id.substring(0,11));p="/get_480";}
  if(p.matches("/assets/[a-zA-Z0-9_-]{11}(-[0-9]{3,4}p)?\\.mp4"))read=true;
  if(!(register&&(method.equals("POST")||method.equals("GET")))&&!(read&&(method.equals("GET")||method.equals("HEAD"))))throw new IOException("YouTube endpoint unavailable: "+method+" "+p);
  if(uri.getRawQuery()!=null)p+="?"+uri.getRawQuery();
  int port=upload?8769:8768;
  StringBuilder request=new StringBuilder(method+" "+p+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nConnection: close\r\n");
  if(upload){String key=new String(Wire.readFile(new File(app.getFilesDir(),"music-relay.key")),"US-ASCII").trim();if(!key.matches("[0-9a-f]{64}"))throw new IOException("Music pairing");request.append("X-Galaxy-Music-Key: ").append(key).append("\r\n");}
  for(String line:header.substring(header.indexOf("\r\n")+2).split("\r\n")){String lower=line.toLowerCase(Locale.US);if(line.isEmpty()||lower.startsWith("host:")||lower.startsWith("connection:")||lower.startsWith("proxy-")||lower.startsWith("authorization:")||lower.startsWith("cookie:")||lower.startsWith("x-galaxy-music-key:"))continue;if(lower.startsWith("transfer-encoding:"))throw new IOException("Unsupported request encoding");if(lower.startsWith("content-length:")&&Long.parseLong(line.substring(line.indexOf(':')+1).trim())>16384)throw new IOException("Request too large");request.append(line).append("\r\n");}
  request.append("\r\n");Socket remote=new Socket(java.net.Proxy.NO_PROXY);try{remote.connect(new InetSocketAddress("127.0.0.1",port),3000);remote.setSoTimeout(90000);client.setSoTimeout(90000);remote.getOutputStream().write(request.toString().getBytes("ISO-8859-1"));remote.getOutputStream().flush();Wire.bridge(client,remote);}finally{Wire.close(remote);}
 }
}
