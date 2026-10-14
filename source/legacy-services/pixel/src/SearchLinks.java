package org.retrobridge.galaxyservices.pixel;
import java.net.*;
final class SearchLinks {
 static String direct(String url)throws Exception{
  for(int i=0;i<3;i++){URI u=new URI(url);String host=u.getHost();if(host==null||(!"http".equals(u.getScheme())&&!"https".equals(u.getScheme()))||u.getUserInfo()!=null)return "";
   if((host.equals("google.com")||host.endsWith(".google.com"))&&u.getPath().equals("/url")){
    String next="",q="";if(u.getRawQuery()!=null)for(String p:u.getRawQuery().split("&")){String[] kv=p.split("=",2);if(kv.length<2)continue;if(kv[0].equals("url"))next=URLDecoder.decode(kv[1],"UTF-8");if(kv[0].equals("q"))q=URLDecoder.decode(kv[1],"UTF-8");}
    if(next.isEmpty())next=q;if(!next.isEmpty()){url=next;continue;}
   }return url;
  }return "";
 }
}
