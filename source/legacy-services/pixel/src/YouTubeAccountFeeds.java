package org.retrobridge.galaxyservices.pixel;

import android.content.Context;import android.net.Uri;import android.os.Bundle;
import java.io.*;import java.net.*;import java.util.*;import org.json.*;

/** Account feeds come from Google's authorized on-device helper, never from public trending. */
final class YouTubeAccountFeeds {
 static final String ORIGIN="http://127.0.0.1:8768", USERS="/feeds/api/users/";
 static final String NS=" xmlns='http://www.w3.org/2005/Atom' xmlns:yt='http://gdata.youtube.com/schemas/2007' xmlns:gd='http://schemas.google.com/g/2005' xmlns:media='http://search.yahoo.com/mrss/' xmlns:openSearch='http://a9.com/-/spec/opensearch/1.1/'";
 static JSONObject api(Context app,String suffix)throws Exception {
  Bundle b=app.getContentResolver().call(Uri.parse("content://org.retrobridge.galaxymail.pixel"),"youtube",suffix,null);
  if(b==null)throw new IOException("Connect YouTube in Google Bridge on Pixel");if(b.containsKey("error"))throw new IOException(b.getString("error"));return new JSONObject(b.getString("json"));
 }
 static JSONObject account(Context app)throws Exception {
  JSONArray a=api(app,"channels?part=snippet&mine=true").getJSONArray("items");if(a.length()!=1)throw new IOException("Select YouTube channel on Pixel");return a.getJSONObject(0);
 }
 static String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
 static int number(Uri uri,String name,int fallback,int max)throws IOException {String s=uri.getQueryParameter(name);if(s==null)return fallback;try{int n=Integer.parseInt(s);if(n<1||n>max)throw new Exception();return n;}catch(Exception e){throw new IOException("Invalid feed page");}}
 static boolean serve(Context app,Socket client,URI request,String method)throws Exception {
  String path=request.getPath();
  if(path.equals("/feeds/api/galaxy/avatar")){
   if(!method.equals("GET")&&!method.equals("HEAD"))throw new IOException("Read-only avatar");
   String target=account(app).getJSONObject("snippet").getJSONObject("thumbnails").getJSONObject("default").getString("url");
   URL url=new URL(target);if(!url.getProtocol().equals("https")||!(url.getHost().equals("yt3.ggpht.com")||url.getHost().equals("yt3.googleusercontent.com"))||url.getUserInfo()!=null||url.getPort()!=-1)throw new IOException("Invalid avatar host");
   HttpURLConnection conn=(HttpURLConnection)url.openConnection(Proxy.NO_PROXY);conn.setConnectTimeout(10000);conn.setReadTimeout(15000);conn.setInstanceFollowRedirects(false);
   try {if(conn.getResponseCode()!=200)throw new IOException("Avatar unavailable");String mime=conn.getContentType();if(mime==null||!(mime.startsWith("image/jpeg")||mime.startsWith("image/png")||mime.startsWith("image/webp")))throw new IOException("Invalid avatar type");ByteArrayOutputStream data=new ByteArrayOutputStream();try(InputStream in=conn.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(data.size()+n>2097152)throw new IOException("Avatar too large");data.write(b,0,n);}}respond(client,method,mime,data.toByteArray());return true;}finally{conn.disconnect();}
  }
  boolean identity=path.equals("/feeds/api/galaxy/account"),profile=path.equals(USERS+"default"),subscriptions=path.equals(USERS+"default/subscriptions")||path.startsWith(USERS+"default/subscriptions/");
  if(!identity&&!profile&&!subscriptions)return false;
  if(!method.equals("GET")&&!method.equals("HEAD"))throw new IOException("Read-only YouTube account");
  String body,type="application/atom+xml; charset=UTF-8";
  if(identity||profile){JSONObject channel=account(app);String id=channel.getString("id"),name=channel.getJSONObject("snippet").getString("title");
   if(!id.matches("UC[A-Za-z0-9_-]{22}"))throw new IOException("Invalid channel identity");
   if(identity){{Bundle state=app.getContentResolver().call(Uri.parse("content://org.retrobridge.galaxymail.pixel"),"status",null,null);body=new JSONObject().put("id",id).put("name",name).put("controls",state!=null&&!state.containsKey("error")&&new JSONObject(state.getString("json")).optBoolean("youtubeControls")).toString();}type="application/json; charset=UTF-8";}
   else body="<?xml version='1.0' encoding='UTF-8'?><entry"+NS+"><id>"+ORIGIN+USERS+id+"</id><title>"+esc(name)+"</title><author><name>"+esc(name)+"</name><uri>"+ORIGIN+USERS+id+"</uri></author><yt:username display='"+esc(name)+"'>"+id+"</yt:username><yt:channelId>"+id+"</yt:channelId><media:thumbnail url='"+ORIGIN+"/feeds/api/galaxy/avatar'/><gd:feedLink rel='http://gdata.youtube.com/schemas/2007#user.subscriptions' href='"+ORIGIN+USERS+"default/subscriptions'/><gd:feedLink rel='http://gdata.youtube.com/schemas/2007#user.uploads' href='"+ORIGIN+USERS+id+"/uploads'/></entry>";
  }else{
   Uri uri=Uri.parse(request.toString());int start=number(uri,"start-index",1,10000),count=number(uri,"max-results",20,50),seen=0,total=0;String next="";JSONArray picked=new JSONArray();int pages=0;
   String fields=uri.getQueryParameter("fields"),filter="";
   if(fields!=null){java.util.regex.Matcher match=java.util.regex.Pattern.compile("entry\\[yt:username='(UC[A-Za-z0-9_-]{22})'\\]").matcher(fields);if(!match.matches())throw new IOException("Unsupported subscription filter");filter=match.group(1);}
   do {String suffix="subscriptions?part=snippet&mine=true&maxResults=50"+(filter.isEmpty()?"&order=alphabetical"+(next.isEmpty()?"":"&pageToken="+next):"&forChannelId="+filter);JSONObject data=api(app,suffix);total=data.getJSONObject("pageInfo").getInt("totalResults");JSONArray items=data.getJSONArray("items");
    for(int i=0;i<items.length();i++){seen++;if(seen>=start&&picked.length()<count)picked.put(items.getJSONObject(i));}
    next=data.optString("nextPageToken","");if(!next.isEmpty()&&!next.matches("[A-Za-z0-9_-]{1,512}"))throw new IOException("Invalid page token");
   }while(!next.isEmpty()&&picked.length()<count&&++pages<200);
   String url=ORIGIN+USERS+"default/subscriptions";StringBuilder xml=new StringBuilder("<?xml version='1.0' encoding='UTF-8'?><feed"+NS+"><id>"+url+"</id><title>Subscriptions</title><openSearch:totalResults>"+total+"</openSearch:totalResults><openSearch:startIndex>"+start+"</openSearch:startIndex><openSearch:itemsPerPage>"+count+"</openSearch:itemsPerPage>");
   if(start+picked.length()<=total)xml.append("<link rel='next' href='").append(url).append("?start-index=").append(start+count).append("&amp;max-results=").append(count).append("'/>");
   for(int i=0;i<picked.length();i++){JSONObject item=picked.getJSONObject(i),s=item.getJSONObject("snippet");String id=s.getJSONObject("resourceId").getString("channelId"),name=s.getString("title");if(!id.matches("UC[A-Za-z0-9_-]{22}"))continue;String channelUrl=ORIGIN+USERS+id;
    xml.append("<entry><id>").append(esc(url+"/"+item.getString("id"))).append("</id><category scheme='http://gdata.youtube.com/schemas/2007/subscriptiontypes.cat' term='channel'/><yt:username display='").append(esc(name)).append("'>").append(id).append("</yt:username><content type='application/atom+xml;type=feed' src='").append(channelUrl).append("/uploads'/><link rel='related' href='").append(channelUrl).append("'/><link rel='edit' href='").append(esc(url+"/"+item.getString("id"))).append("'/></entry>");
   }body=xml.append("</feed>").toString();
  }
  respond(client,method,type,body.getBytes("UTF-8"));return true;
 }
 static void respond(Socket client,String method,String type,byte[] bytes)throws IOException {OutputStream out=client.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\nContent-Length: "+bytes.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));if(!method.equals("HEAD"))out.write(bytes);out.flush();
 }
}
