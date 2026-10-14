package org.retrobridge.galaxyservices.pixel;
import android.content.Context;import android.net.Uri;import java.io.*;import java.net.*;import java.util.*;import org.json.*;
final class YouTubeComments {
 static String pageToken(JSONObject page)throws IOException {
  String token=page.optString("nextPageToken","");
  // Comment continuations are opaque and can contain base64 padding. Encode
  // the complete value rather than interpreting it as part of the query.
  if(token.length()>4096)throw new IOException("Comment page token too large");
  for(int i=0;i<token.length();i++)if(token.charAt(i)<32||token.charAt(i)>126)throw new IOException("Invalid comment page token");
  return URLEncoder.encode(token,"UTF-8");
 }
 static String comment(String video,JSONObject row)throws Exception {
  JSONObject s=row.getJSONObject("snippet");String url=YouTubeAccountFeeds.ORIGIN+"/feeds/api/videos/"+video+"/comments/"+row.getString("id");
  return "<entry><id>"+YouTubeAccountFeeds.esc(url)+"</id><published>"+YouTubeAccountFeeds.esc(s.optString("publishedAt"))+"</published><updated>"+YouTubeAccountFeeds.esc(s.optString("updatedAt",s.optString("publishedAt")))+"</updated><content>"+YouTubeAccountFeeds.esc(s.optString("textOriginal",s.optString("textDisplay")))+"</content><author><name>"+YouTubeAccountFeeds.esc(s.optString("authorDisplayName"))+"</name></author></entry>";
 }
 static boolean serve(Context c,Socket socket,URI request,String method)throws Exception {
  String path=request.getPath();if(!path.matches("/feeds/api/videos/[A-Za-z0-9_-]{11}/comments"))return false;
  android.os.Bundle state=c.getContentResolver().call(Uri.parse("content://org.retrobridge.galaxymail.pixel"),"status",null,null);
  // Google's comment-list endpoint also requires the expanded scope. Keep public browsing until granted.
  if(state==null||state.containsKey("error")||!new JSONObject(state.getString("json")).optBoolean("youtubeControls"))return false;
  if(!method.equals("GET")&&!method.equals("HEAD"))throw new IOException("Use encrypted comment action");
  String video=path.split("/")[4];Uri uri=Uri.parse(request.toString());int start=YouTubeAccountFeeds.number(uri,"start-index",1,1000),count=YouTubeAccountFeeds.number(uri,"max-results",20,50),seen=0,total=0;
  String next="";List<JSONObject> selected=new ArrayList<JSONObject>();
  do{JSONObject page=YouTubeVideoFeeds.api(c,"commentThreads?part=snippet&videoId="+video+"&maxResults=50&order=time&textFormat=plainText"+(next.isEmpty()?"":"&pageToken="+next));JSONArray rows=page.getJSONArray("items");
   for(int i=0;i<rows.length();i++){seen++;if(seen>=start&&selected.size()<count)selected.add(rows.getJSONObject(i).getJSONObject("snippet").getJSONObject("topLevelComment"));}
   next=pageToken(page);total=next.isEmpty()?seen:Math.max(seen+1,page.getJSONObject("pageInfo").optInt("totalResults"));
  }while(!next.isEmpty()&&selected.size()<count&&seen<1000);
  String url=YouTubeAccountFeeds.ORIGIN+path;StringBuilder xml=new StringBuilder("<feed"+YouTubeAccountFeeds.NS+"><id>"+url+"</id><title>Comments</title><openSearch:totalResults>"+total+"</openSearch:totalResults><openSearch:startIndex>"+start+"</openSearch:startIndex><openSearch:itemsPerPage>"+count+"</openSearch:itemsPerPage>");
  if(start-1+count<total)xml.append("<link rel='next' href='").append(url).append("?start-index=").append(start+count).append("&amp;max-results=").append(count).append("'/>");
  for(JSONObject row:selected)xml.append(comment(video,row));YouTubeAccountFeeds.respond(socket,method,"application/atom+xml; charset=UTF-8",xml.append("</feed>").toString().getBytes("UTF-8"));return true;
 }
}
