package org.retrobridge.galaxyservices.pixel;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.json.*;

/** Read-only adaptation of authorized YouTube metadata for the original UI. */
final class YouTubeVideoFeeds {
 static final String ORIGIN=YouTubeAccountFeeds.ORIGIN, USERS=YouTubeAccountFeeds.USERS;
 static final ExecutorService FETCH=Executors.newFixedThreadPool(6);
 static final Object LOCK=new Object(), REFRESH=new Object();
 static final LinkedHashMap<String,Cached> CACHE=new LinkedHashMap<String,Cached>();
 static final class Cached {final JSONObject data;final long time;Cached(JSONObject d){data=d;time=System.currentTimeMillis();}}
 static JSONObject api(Context c,String q)throws Exception{
  android.os.Bundle status=c.getContentResolver().call(Uri.parse("content://org.retrobridge.galaxymail.pixel"),"status",null,null);
  if(status==null||status.containsKey("error"))throw new IOException("Connect YouTube on Pixel");
  JSONObject state=new JSONObject(status.getString("json"));String selected=state.optString("youtubeCacheKey","");
  if(!state.optBoolean("youtubeConnected")||selected.isEmpty())throw new IOException("Connect YouTube on Pixel");
  String key=selected+"|"+generation+"|"+q;
  boolean mutable=q.startsWith("playlists?")||q.startsWith("playlistItems?")||q.startsWith("commentThreads?")||q.contains("myRating=")||q.startsWith("videos/getRating?");
  synchronized(LOCK){Cached old=CACHE.get(key);if(old!=null&&(!mutable||android.os.SystemClock.elapsedRealtime()>=freshUntil)&&System.currentTimeMillis()-old.time<(mutable?10000:300000))return old.data;}
  JSONObject value=YouTubeAccountFeeds.api(c,q);
  synchronized(LOCK){if(!mutable||android.os.SystemClock.elapsedRealtime()>=freshUntil){CACHE.put(key,new Cached(value));while(CACHE.size()>600)CACHE.remove(CACHE.keySet().iterator().next());}}return value;
 }
 static String owner="";static List<JSONObject> recent;static volatile long refreshed,generation;static long freshUntil;
 // Google list responses can briefly lag a successful write. Do not freeze the
 // first old response into the normal cache while the change propagates.
 static void invalidate(){synchronized(LOCK){generation++;CACHE.clear();freshUntil=android.os.SystemClock.elapsedRealtime()+30000;}refreshed=0;}
 static String esc(String s){return YouTubeAccountFeeds.esc(s);}
 static String channelId(String id)throws IOException{if(id==null||!id.matches("UC[A-Za-z0-9_-]{22}"))throw new IOException("Invalid channel");return id;}
 static JSONObject channel(Context c,String id)throws Exception{
  JSONArray items=api(c,"channels?part=snippet,contentDetails,statistics&id="+channelId(id)).getJSONArray("items");
  if(items.length()!=1)throw new IOException("Channel unavailable");return items.getJSONObject(0);
 }
 static String playlist(JSONObject channel)throws Exception{
  String id=channel.getJSONObject("contentDetails").getJSONObject("relatedPlaylists").getString("uploads");
  if(!id.matches("[A-Za-z0-9_-]{10,80}"))throw new IOException("Invalid uploads playlist");return id;
 }
 static String profileStats(JSONObject row){
  JSONObject stats=row.optJSONObject("statistics");if(stats==null)return "";
  return "<yt:statistics viewCount='"+esc(stats.optString("viewCount","0"))+"' totalUploadViews='"+esc(stats.optString("viewCount","0"))+"' subscriberCount='"+Math.min(Integer.MAX_VALUE,stats.optLong("subscriberCount",0))+"'/>";
 }
 static JSONObject items(Context c,String id,int count,String token)throws Exception{
  if(!token.matches("[A-Za-z0-9_-]{0,512}"))throw new IOException("Invalid continuation");
  return api(c,"playlistItems?part=snippet,contentDetails&playlistId="+id+"&maxResults="+count+(token.isEmpty()?"":"&pageToken="+token));
 }
 static List<JSONObject> videos(Context c,List<String> ids)throws Exception{
  HashMap<String,JSONObject> found=new HashMap<String,JSONObject>();
  for(int start=0;start<ids.size();start+=50){StringBuilder joined=new StringBuilder();for(String id:ids.subList(start,Math.min(start+50,ids.size()))){if(!id.matches("[A-Za-z0-9_-]{11}"))throw new IOException("Invalid video");if(joined.length()>0)joined.append(',');joined.append(id);}
   JSONArray rows=api(c,"videos?part=snippet,contentDetails,statistics&id="+joined).getJSONArray("items");for(int i=0;i<rows.length();i++)found.put(rows.getJSONObject(i).getString("id"),rows.getJSONObject(i));}
  List<JSONObject> result=new ArrayList<JSONObject>();for(String id:ids)if(found.containsKey(id))result.add(found.get(id));return result;
 }
 static List<JSONObject> recent(Context c)throws Exception{
  String selected=YouTubeAccountFeeds.account(c).getString("id");
  synchronized(REFRESH){
   long began=generation;
   if(selected.equals(owner)&&recent!=null&&System.currentTimeMillis()-refreshed<900000)return recent;
   if(!selected.equals(owner)){synchronized(LOCK){CACHE.clear();}recent=null;owner=selected;}
   List<String> channels=new ArrayList<String>();String token="";
   do{JSONObject page=YouTubeAccountFeeds.api(c,"subscriptions?part=snippet&mine=true&maxResults=50&order=alphabetical"+(token.isEmpty()?"":"&pageToken="+token));JSONArray rows=page.getJSONArray("items");for(int i=0;i<rows.length();i++)channels.add(channelId(rows.getJSONObject(i).getJSONObject("snippet").getJSONObject("resourceId").getString("channelId")));token=page.optString("nextPageToken","");if(!token.matches("[A-Za-z0-9_-]{0,512}")||channels.size()>1000)throw new IOException("Subscription list too large");}while(!token.isEmpty());
   List<String> playlists=new ArrayList<String>();
   for(int start=0;start<channels.size();start+=50){String joined=android.text.TextUtils.join(",",channels.subList(start,Math.min(start+50,channels.size())));JSONArray rows=api(c,"channels?part=snippet,contentDetails,statistics&id="+joined).getJSONArray("items");for(int i=0;i<rows.length();i++)playlists.add(playlist(rows.getJSONObject(i)));}
   List<Future<JSONArray>> jobs=new ArrayList<Future<JSONArray>>();final Context app=c;
   for(final String list:playlists)jobs.add(FETCH.submit(new Callable<JSONArray>(){public JSONArray call()throws Exception{try{return items(app,list,10,"").getJSONArray("items");}catch(IOException e){if(e.getMessage()!=null&&e.getMessage().startsWith("Google HTTP 404"))return new JSONArray();throw e;}}}));
   List<JSONObject> candidates=new ArrayList<JSONObject>();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(65);
   try{for(Future<JSONArray> job:jobs){JSONArray rows=job.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);for(int i=0;i<rows.length();i++)candidates.add(rows.getJSONObject(i));}}finally{for(Future<?> job:jobs)if(!job.isDone())job.cancel(true);}
   Collections.sort(candidates,new Comparator<JSONObject>(){public int compare(JSONObject a,JSONObject b){return date(b).compareTo(date(a));}});
   LinkedHashSet<String> unique=new LinkedHashSet<String>();for(JSONObject row:candidates){String id=row.getJSONObject("contentDetails").optString("videoId");if(id.matches("[A-Za-z0-9_-]{11}"))unique.add(id);if(unique.size()==200)break;}
   recent=videos(c,new ArrayList<String>(unique));refreshed=began==generation?System.currentTimeMillis():0;return recent;
  }
 }
 static String date(JSONObject row){JSONObject d=row.optJSONObject("contentDetails");return d==null?"":d.optString("videoPublishedAt","");}
 static int seconds(String duration){Matcher m=Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matcher(duration);if(!m.matches())return 0;int n=0;for(int i=1;i<=3;i++)if(m.group(i)!=null)n+=Integer.parseInt(m.group(i))*(i==1?3600:i==2?60:1);return n;}
 static String video(JSONObject v)throws Exception{
  String id=v.getString("id"),url=ORIGIN+"/feeds/api/videos/"+id;JSONObject s=v.getJSONObject("snippet"),stats=v.optJSONObject("statistics");String title=esc(s.getString("title")),name=esc(s.getString("channelTitle")),cid=channelId(s.getString("channelId")),date=esc(s.getString("publishedAt"));int duration=seconds(v.getJSONObject("contentDetails").getString("duration"));
  return "<entry><id>"+url+"</id>"+(v.has("_edit")?"<link rel='edit' href='"+esc(v.getString("_edit"))+"'/>":"")+"<published>"+date+"</published><updated>"+date+"</updated><title>"+title+"</title><author><name>"+name+"</name><uri>"+ORIGIN+USERS+cid+"</uri><yt:userId>"+cid+"</yt:userId></author><link rel='http://gdata.youtube.com/schemas/2007#video.related' href='"+url+"/related'/><media:group><media:title>"+title+"</media:title><media:category scheme='http://gdata.youtube.com/schemas/2007/categories.cat'>Film</media:category><media:content url='"+ORIGIN+"/sabr_hls_adapter/galaxy/"+id+"/index.m3u8' type='video/3gpp' medium='video' expression='full' duration='"+duration+"' yt:format='3'/><media:player url='http://www.youtube.com/watch?v="+id+"'/><media:thumbnail yt:name='hqdefault' url='http://i.ytimg.com/vi/"+id+"/hqdefault.jpg' height='240' width='320'/><media:thumbnail yt:name='default' url='http://i.ytimg.com/vi/"+id+"/default.jpg' height='90' width='120'/><yt:duration seconds='"+duration+"'/><yt:uploaded>"+date+"</yt:uploaded><yt:videoid>"+id+"</yt:videoid><media:credit role='uploader' yt:display='"+name+"'>"+cid+"</media:credit></media:group>"+(stats==null?"":"<yt:statistics viewCount='"+esc(stats.optString("viewCount","0"))+"'/>")+"</entry>";
 }
 static String event(JSONObject v)throws Exception{JSONObject s=v.getJSONObject("snippet");String id=v.getString("id"),cid=channelId(s.getString("channelId"));return "<entry><id>upload-"+id+"</id><updated>"+esc(s.getString("publishedAt"))+"</updated><author><name>"+esc(s.getString("channelTitle"))+"</name><uri>"+ORIGIN+USERS+cid+"</uri></author><category scheme='http://gdata.youtube.com/schemas/2007/userevents.cat' term='video_uploaded'/><yt:videoid>"+id+"</yt:videoid><link rel='http://gdata.youtube.com/schemas/2007#video'>"+video(v)+"</link></entry>";}
 static boolean serve(Context c,Socket socket,URI request,String method)throws Exception{
  String path=request.getPath();
  if(path.matches("/feeds/api/galaxy/channel-avatar/UC[A-Za-z0-9_-]{22}")){
   if(!method.equals("GET")&&!method.equals("HEAD"))throw new IOException("Read-only avatar");
   String cid=path.substring(path.lastIndexOf('/')+1),target=channel(c,cid).getJSONObject("snippet").getJSONObject("thumbnails").getJSONObject("default").getString("url");
   URL url=new URL(target);if(!url.getProtocol().equals("https")||!(url.getHost().equals("yt3.ggpht.com")||url.getHost().equals("yt3.googleusercontent.com"))||url.getUserInfo()!=null||url.getPort()!=-1)throw new IOException("Invalid avatar host");
   HttpURLConnection conn=(HttpURLConnection)url.openConnection(Proxy.NO_PROXY);conn.setConnectTimeout(10000);conn.setReadTimeout(15000);conn.setInstanceFollowRedirects(false);
   try{if(conn.getResponseCode()!=200)throw new IOException("Avatar unavailable");String mime=conn.getContentType();if(mime==null||!(mime.startsWith("image/jpeg")||mime.startsWith("image/png")||mime.startsWith("image/webp")))throw new IOException("Invalid avatar type");ByteArrayOutputStream data=new ByteArrayOutputStream();try(InputStream in=conn.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(data.size()+n>2097152)throw new IOException("Avatar too large");data.write(b,0,n);}}YouTubeAccountFeeds.respond(socket,method,mime,data.toByteArray());return true;}finally{conn.disconnect();}
  }
  boolean activity=path.equals("/feeds/api/events");
  boolean combined=path.equals(USERS+"default/subtivity")||path.equals(USERS+"default/newsubscriptionvideos"),uploads=path.matches(USERS+"UC[A-Za-z0-9_-]{22}/uploads"),profile=path.matches(USERS+"UC[A-Za-z0-9_-]{22}");
  if(!combined&&!uploads&&!profile&&!activity)return false;if(!method.equals("GET")&&!method.equals("HEAD"))throw new IOException("Read-only channels");
  String body;
  if(profile){JSONObject row=channel(c,path.substring(USERS.length()));JSONObject s=row.getJSONObject("snippet");String id=row.getString("id"),name=esc(s.getString("title")),url=ORIGIN+USERS+id;
   String thumb=ORIGIN+"/feeds/api/galaxy/channel-avatar/"+id;
   body="<?xml version='1.0' encoding='UTF-8'?><entry"+YouTubeAccountFeeds.NS+"><id>"+url+"</id><author><name>"+name+"</name><uri>"+url+"</uri></author><yt:username>"+id+"</yt:username><summary>"+esc(s.optString("description"))+"</summary><media:thumbnail url='"+thumb+"'/>"+profileStats(row)+"<gd:feedLink rel='http://gdata.youtube.com/schemas/2007#user.uploads' href='"+url+"/uploads' countHint='"+(row.optJSONObject("statistics")==null?0:row.getJSONObject("statistics").optInt("videoCount"))+"'/></entry>";
  }else{Uri uri=Uri.parse(request.toString());int start=YouTubeAccountFeeds.number(uri,"start-index",1,10000),count=YouTubeAccountFeeds.number(uri,"max-results",18,50),total;List<JSONObject> selected;
   if(combined){List<JSONObject> all=recent(c);total=all.size();selected=all.subList(Math.min(start-1,total),Math.min(start-1+count,total));}
   else{String cid=activity?channelId(uri.getQueryParameter("author")):path.substring(USERS.length(),path.length()-8),list=playlist(channel(c,cid)),token="";List<String> picked=new ArrayList<String>();int seen=0;total=0;do{JSONObject page=items(c,list,50,token);total=page.getJSONObject("pageInfo").getInt("totalResults");JSONArray rows=page.getJSONArray("items");for(int i=0;i<rows.length();i++){seen++;if(seen>=start&&picked.size()<count)picked.add(rows.getJSONObject(i).getJSONObject("contentDetails").getString("videoId"));}token=YouTubeLibraryFeeds.token(page);}while(!token.isEmpty()&&picked.size()<count);selected=videos(c,picked);}
   String url=ORIGIN+path;StringBuilder xml=new StringBuilder("<?xml version='1.0' encoding='UTF-8'?><feed"+YouTubeAccountFeeds.NS+"><id>"+url+"</id><title>"+(combined?"Channel Feed":"Uploads")+"</title><openSearch:totalResults>"+total+"</openSearch:totalResults><openSearch:startIndex>"+start+"</openSearch:startIndex><openSearch:itemsPerPage>"+count+"</openSearch:itemsPerPage>");
   if(start-1+count<total)xml.append("<link rel='next' href='").append(url).append("?start-index=").append(start+count).append("&amp;max-results=").append(count).append(activity?"&amp;author="+channelId(uri.getQueryParameter("author"))+"&amp;inline=true":"").append("'/>");for(JSONObject v:selected)xml.append(path.endsWith("/subtivity")||activity?event(v):video(v));body=xml.append("</feed>").toString();
  }
  YouTubeAccountFeeds.respond(socket,method,"application/atom+xml; charset=UTF-8",body.getBytes("UTF-8"));return true;
 }
}
