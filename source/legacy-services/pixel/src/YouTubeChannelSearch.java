package org.retrobridge.galaxyservices.pixel;
import android.content.Context;import android.net.Uri;import java.io.*;import java.net.*;import java.util.*;import org.json.*;
final class YouTubeChannelSearch {
 static boolean serve(Context c,Socket socket,URI request,String method)throws Exception {
  String path=request.getPath();boolean suggestions=path.equals("/feeds/api/users/default/suggestion");
  if(!path.equals("/feeds/api/channels")&&!suggestions)return false;
  if(!method.equals("GET")&&!method.equals("HEAD"))throw new IOException("Channel search is read-only");
  Uri uri=Uri.parse(request.toString());int start=YouTubeAccountFeeds.number(uri,"start-index",1,500),count=YouTubeAccountFeeds.number(uri,"max-results",18,50),seen=0,total=0;
  List<JSONObject> picked=new ArrayList<JSONObject>();String query=uri.getQueryParameter("q"),next="";
  if(suggestions){
   JSONArray videos=YouTubeVideoFeeds.api(c,"videos?part=snippet,contentDetails,statistics&chart=mostPopular&regionCode=US&maxResults=50").getJSONArray("items");LinkedHashSet<String> ids=new LinkedHashSet<String>();
   for(int i=0;i<videos.length();i++)ids.add(videos.getJSONObject(i).getJSONObject("snippet").getString("channelId"));total=ids.size();
   for(String id:ids){seen++;if(seen>=start&&picked.size()<count)picked.add(new JSONObject().put("id",id));}
  }else{
   if(query==null||query.trim().isEmpty()||query.length()>300)throw new IOException("Enter a channel search");
   String safe=uri.getQueryParameter("safeSearch");if(safe==null)safe="moderate";if(!safe.matches("none|moderate|strict"))throw new IOException("Invalid search filter");
   do{JSONObject page=YouTubeVideoFeeds.api(c,"search?part=snippet&type=channel&maxResults=50&safeSearch="+safe+"&q="+URLEncoder.encode(query,"UTF-8")+(next.isEmpty()?"":"&pageToken="+next));JSONArray rows=page.getJSONArray("items");
    for(int i=0;i<rows.length();i++){seen++;if(seen>=start&&picked.size()<count){JSONObject row=rows.getJSONObject(i);picked.add(new JSONObject().put("id",row.getJSONObject("id").getString("channelId")).put("snippet",row.getJSONObject("snippet")));}}
    next=YouTubeLibraryFeeds.token(page);total=next.isEmpty()?seen:Math.min(500,Math.max(seen+1,page.optJSONObject("pageInfo")==null?seen+1:page.getJSONObject("pageInfo").optInt("totalResults")));
   }while(!next.isEmpty()&&picked.size()<count&&seen<500);
  }
  // Fetch actual counts and profile metadata in one batch. Search results do
  // not include video counts, and a fabricated zero misleads the old UI.
  if(!picked.isEmpty()){
   List<String> ids=new ArrayList<String>();for(JSONObject row:picked)ids.add(YouTubeVideoFeeds.channelId(row.getString("id")));
   JSONArray profiles=YouTubeVideoFeeds.api(c,"channels?part=snippet,contentDetails,statistics&id="+android.text.TextUtils.join(",",ids)).getJSONArray("items");
   Map<String,JSONObject> byId=new HashMap<String,JSONObject>();for(int i=0;i<profiles.length();i++){JSONObject row=profiles.getJSONObject(i);byId.put(row.getString("id"),row);}
   List<JSONObject> available=new ArrayList<JSONObject>();for(String id:ids)if(byId.containsKey(id))available.add(byId.get(id));picked=available;
  }
  String origin=YouTubeAccountFeeds.ORIGIN,url=origin+path;
  StringBuilder xml=new StringBuilder("<feed"+YouTubeAccountFeeds.NS+"><id>"+url+"</id><title>"+(suggestions?"Popular channels":"Channels")+"</title><openSearch:totalResults>"+total+"</openSearch:totalResults><openSearch:startIndex>"+start+"</openSearch:startIndex><openSearch:itemsPerPage>"+count+"</openSearch:itemsPerPage>");
  if(start-1+count<total)xml.append("<link rel='next' href='").append(url).append("?start-index=").append(start+count).append("&amp;max-results=").append(count).append(suggestions?"&amp;type=channel":"&amp;q="+YouTubeAccountFeeds.esc(URLEncoder.encode(query,"UTF-8"))+"&amp;safeSearch="+YouTubeAccountFeeds.esc(uri.getQueryParameter("safeSearch")==null?"moderate":uri.getQueryParameter("safeSearch"))).append("'/>");
  for(JSONObject row:picked){String id=YouTubeVideoFeeds.channelId(row.getString("id")),profile=origin+"/feeds/api/users/"+id;JSONObject s=row.getJSONObject("snippet");String name=YouTubeAccountFeeds.esc(s.getString("title"));
   // The 2012 suggestions screen consumes inline UserProfile entries; the
   // search tab consumes Channel entries. They use different native parsers.
   if(suggestions){xml.append("<entry><content type='application/atom+xml'><entry><id>").append(profile).append("</id><title>").append(name).append("</title><author><name>").append(name).append("</name><uri>").append(profile).append("</uri></author><yt:username display='").append(name).append("'>").append(id).append("</yt:username><media:thumbnail url='").append(origin).append("/feeds/api/galaxy/channel-avatar/").append(id).append("'/><gd:feedLink rel='http://gdata.youtube.com/schemas/2007#user.uploads' href='").append(profile).append("/uploads'/></entry></content></entry>");continue;}
   xml.append("<entry><id>").append(profile).append("</id><title>").append(name).append("</title><summary>").append(YouTubeAccountFeeds.esc(s.optString("description"))).append("</summary><updated>").append(YouTubeAccountFeeds.esc(s.optString("publishedAt","1970-01-01T00:00:00Z"))).append("</updated><author><name>").append(name).append("</name><uri>").append(profile).append("</uri></author><gd:feedLink href='").append(profile).append("/uploads' countHint='").append(row.optJSONObject("statistics")==null?0:row.getJSONObject("statistics").optInt("videoCount")).append("'/></entry>");
  }
  YouTubeAccountFeeds.respond(socket,method,"application/atom+xml; charset=UTF-8",xml.append("</feed>").toString().getBytes("UTF-8"));return true;
 }
}
