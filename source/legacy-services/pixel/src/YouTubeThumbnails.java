package org.retrobridge.galaxyservices.pixel;
import java.io.*;import java.net.*;import java.util.*;

/** Public JPEG thumbnails over the existing authenticated phone link. */
final class YouTubeThumbnails {
 static final String PREFIX="/feeds/api/galaxy/video-thumbnail/";
 static final int LIMIT=2*1024*1024, CACHE_LIMIT=8*1024*1024;
 static final LinkedHashMap<String,Image> CACHE=new LinkedHashMap<String,Image>(64,0.75f,true);
 static int cachedBytes;
 static final class Image {final byte[] bytes;final long at=System.currentTimeMillis();Image(byte[] b){bytes=b;}}
 static byte[] fetch(String suffix)throws Exception{
  HttpURLConnection c=(HttpURLConnection)new URL("https://i.ytimg.com/vi/"+suffix).openConnection(Proxy.NO_PROXY);
  c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","image/jpeg");
  try{
   if(c.getResponseCode()!=200)throw new IOException("Thumbnail HTTP "+c.getResponseCode());
   String type=c.getContentType();if(type==null||!type.toLowerCase(Locale.US).startsWith("image/jpeg"))throw new IOException("Thumbnail format");
   ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>LIMIT)throw new IOException("Thumbnail too large");out.write(b,0,n);}}
   byte[] data=out.toByteArray();if(data.length<4||(data[0]&255)!=255||(data[1]&255)!=216)throw new IOException("Thumbnail image");return data;
  }finally{c.disconnect();}
 }
 static boolean serve(Socket socket,URI uri,String method)throws Exception{
  String path=uri.getRawPath();if(!path.startsWith(PREFIX))return false;
  String suffix=path.substring(PREFIX.length());
  if(!suffix.matches("[A-Za-z0-9_-]{11}/(default|mqdefault|hqdefault|sddefault|maxresdefault|[0-3])[.]jpg")||uri.getRawQuery()!=null||(!method.equals("GET")&&!method.equals("HEAD")))throw new IOException("Thumbnail request");
  byte[] data=null;
  synchronized(CACHE){Image old=CACHE.get(suffix);if(old!=null&&System.currentTimeMillis()-old.at<6*3600000)data=old.bytes;}
  if(data==null){data=fetch(suffix);synchronized(CACHE){Image old=CACHE.put(suffix,new Image(data));if(old!=null)cachedBytes-=old.bytes.length;cachedBytes+=data.length;while(cachedBytes>CACHE_LIMIT||CACHE.size()>128){String first=CACHE.keySet().iterator().next();cachedBytes-=CACHE.remove(first).bytes.length;}}}
  YouTubeAccountFeeds.respond(socket,method,"image/jpeg",data);return true;
 }
}
