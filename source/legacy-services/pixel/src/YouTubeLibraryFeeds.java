package org.retrobridge.galaxyservices.pixel;

import android.content.Context;
import android.net.Uri;
import java.net.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Translate the original library's read requests to the selected Google account. */
final class YouTubeLibraryFeeds {
 static final String ORIGIN=YouTubeAccountFeeds.ORIGIN, USERS=YouTubeAccountFeeds.USERS;
 static String esc(String text){return YouTubeAccountFeeds.esc(text);}
 static String token(JSONObject page)throws IOException {
  String value=page.optString("nextPageToken","");
  if(!value.matches("[A-Za-z0-9_-]{0,512}"))throw new IOException("Invalid page token");
  return value;
 }
 static String playlist(JSONObject row)throws Exception {
  String id=row.getString("id");if(!id.matches("[A-Za-z0-9_-]{10,80}"))throw new IOException("Invalid playlist");
  JSONObject s=row.getJSONObject("snippet");String url=ORIGIN+"/feeds/api/playlists/"+id;
  StringBuilder xml=new StringBuilder("<entry><id>"+url+"</id><yt:playlistId>"+id+"</yt:playlistId><title>"+esc(s.getString("title"))+"</title><updated>"+esc(s.getString("publishedAt"))+"</updated><author><name>"+esc(s.optString("channelTitle"))+"</name></author><summary>"+esc(s.optString("description"))+"</summary>"+(row.optJSONObject("status")!=null&&"private".equals(row.getJSONObject("status").optString("privacyStatus"))?"<yt:private/>":"")+"<yt:countHint>"+row.getJSONObject("contentDetails").optInt("itemCount")+"</yt:countHint><content src='"+url+"'/><link rel='edit' href='"+url+"'/>");
  JSONObject thumbs=s.optJSONObject("thumbnails");
  if(thumbs!=null){JSONObject def=thumbs.optJSONObject("default");if(def!=null){String thumb=def.optString("url");if(thumb.matches("https://i[.]ytimg[.]com/.*"))xml.append("<media:group><media:thumbnail yt:name='default' url='").append(esc(thumb.replace("https://","http://"))).append("' width='120' height='90'/></media:group>");}}
  return xml.append("</entry>").toString();
 }
 static boolean serve(Context c,Socket socket,URI request,String method)throws Exception {
  String path=request.getPath();
  if(path.equals(USERS+"default/uploads"))return YouTubeVideoFeeds.serve(c,socket,new URI(request.toString().replace(USERS+"default/uploads",USERS+YouTubeAccountFeeds.account(c).getString("id")+"/uploads")),method);
  boolean channelLists=path.matches(USERS+"UC[A-Za-z0-9_-]{22}/playlists");
  boolean lists=path.equals(USERS+"default/playlists")||channelLists,likes=path.equals(USERS+"default/favorites"),contents=path.matches("/feeds/api/playlists/[A-Za-z0-9_-]{10,80}");
  if(path.matches(USERS+"UC[A-Za-z0-9_-]{22}/favorites")){
   if(!method.equals("GET")&&!method.equals("HEAD"))throw new IOException("Read-only channel list");
   String cid=path.substring(USERS.length(),path.lastIndexOf('/'));
   if(cid.equals(YouTubeAccountFeeds.account(c).getString("id")))return serve(c,socket,new URI(request.toString().replace(USERS+cid+"/favorites",USERS+"default/favorites")),method);
   // Modern YouTube does not expose another channel's liked-video list.
   YouTubeAccountFeeds.respond(socket,method,"application/atom+xml; charset=UTF-8",("<feed"+YouTubeAccountFeeds.NS+"><id>"+ORIGIN+path+"</id><title>Liked videos unavailable</title><openSearch:totalResults>0</openSearch:totalResults><openSearch:startIndex>1</openSearch:startIndex><openSearch:itemsPerPage>18</openSearch:itemsPerPage></feed>").getBytes("UTF-8"));return true;
  }
  if(!lists&&!likes&&!contents)return false;
  if(!method.equals("GET")&&!method.equals("HEAD"))throw new IOException("YouTube account is read-only");
  Uri uri=Uri.parse(request.toString());int start=YouTubeAccountFeeds.number(uri,"start-index",1,10000),count=YouTubeAccountFeeds.number(uri,"max-results",18,50),total=0,seen=0,pages=0;
  String next="",prefix=lists?"playlists?part=snippet,contentDetails,status&"+(channelLists?"channelId="+path.substring(USERS.length(),path.lastIndexOf('/')):"mine=true")+"&maxResults=50":likes?"videos?part=snippet,contentDetails,statistics&myRating=like&maxResults=50":"playlistItems?part=snippet,contentDetails&playlistId="+path.substring(path.lastIndexOf('/')+1)+"&maxResults=50";
  List<JSONObject> rows=new ArrayList<JSONObject>();
  do {
   JSONObject page=YouTubeVideoFeeds.api(c,prefix+(next.isEmpty()?"":"&pageToken="+next));
   total=page.getJSONObject("pageInfo").getInt("totalResults");if(likes)total=Math.min(total,1000);
   JSONArray items=page.getJSONArray("items");
   for(int i=0;i<items.length();i++){seen++;if(seen>=start&&rows.size()<count)rows.add(items.getJSONObject(i));}
   next=token(page);
  }while(!next.isEmpty()&&rows.size()<count&&++pages<200);
  if(contents){
   List<JSONObject> items=rows;List<String> ids=new ArrayList<String>();for(JSONObject row:items){String id=row.getJSONObject("contentDetails").optString("videoId");if(id.matches("[A-Za-z0-9_-]{11}"))ids.add(id);}
   Map<String,JSONObject> found=new HashMap<String,JSONObject>();for(JSONObject row:YouTubeVideoFeeds.videos(c,ids))found.put(row.getString("id"),row);
   rows=new ArrayList<JSONObject>();for(JSONObject item:items){JSONObject video=found.get(item.getJSONObject("contentDetails").optString("videoId"));if(video!=null){video=new JSONObject(video.toString());String itemId=item.getString("id");if(!itemId.matches("[A-Za-z0-9_-]{10,512}"))throw new IOException("Invalid playlist entry");video.put("_edit",ORIGIN+path+"/"+itemId);rows.add(video);}}
  }
  if(likes){List<JSONObject> copies=new ArrayList<JSONObject>();for(JSONObject row:rows)copies.add(new JSONObject(row.toString()).put("_edit",ORIGIN+USERS+"default/favorites/"+row.getString("id")));rows=copies;}
  String url=ORIGIN+path;
  StringBuilder xml=new StringBuilder("<?xml version='1.0' encoding='UTF-8'?><feed"+YouTubeAccountFeeds.NS+"><id>"+url+"</id><title>"+(lists?"Playlists":likes?"Liked videos":"Playlist")+"</title><openSearch:totalResults>"+total+"</openSearch:totalResults><openSearch:startIndex>"+start+"</openSearch:startIndex><openSearch:itemsPerPage>"+count+"</openSearch:itemsPerPage>");
  if(start-1+count<total)xml.append("<link rel='next' href='").append(url).append("?start-index=").append(start+count).append("&amp;max-results=").append(count).append("'/>");
  for(JSONObject row:rows){String entry=lists?playlist(row):YouTubeVideoFeeds.video(row);if(channelLists)entry=entry.replaceAll("<link rel='edit' href='[^']*'/>","");xml.append(entry);}
  YouTubeAccountFeeds.respond(socket,method,"application/atom+xml; charset=UTF-8",xml.append("</feed>").toString().getBytes("UTF-8"));return true;
 }
}
