package org.retrobridge.galaxyservices.pixel;

import android.content.Context;import android.os.Bundle;import android.net.Uri;
import java.io.*;import java.net.*;import java.util.*;import org.json.*;
import org.retrobridge.galaxyservices.Wire;

/** Available only inside paired, authenticated private TLS. */
final class YouTubeControls {
 static String esc(String s){return YouTubeAccountFeeds.esc(s);}
 static void response(OutputStream out,int status,String text)throws IOException {
  byte[] body=text.getBytes("UTF-8");out.write(("HTTP/1.1 "+status+" "+(status<300?"OK":"Request failed")+"\r\nContent-Type: application/atom+xml; charset=UTF-8\r\nContent-Length: "+body.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));out.write(body);out.flush();
 }
 static String entry(String inside){return "<?xml version='1.0' encoding='UTF-8'?><entry"+YouTubeAccountFeeds.NS+">"+inside+"</entry>";}
 static String subscription(JSONObject item)throws Exception {
  JSONObject s=item.getJSONObject("snippet");String channel=s.getJSONObject("resourceId").getString("channelId"),id=item.getString("id"),origin=YouTubeAccountFeeds.ORIGIN;
  String url=origin+"/feeds/api/users/default/subscriptions/"+id,profile=origin+"/feeds/api/users/"+channel;
  return entry("<id>"+esc(url)+"</id><category scheme='http://gdata.youtube.com/schemas/2007/subscriptiontypes.cat' term='channel'/><yt:username display='"+esc(s.optString("title"))+"'>"+esc(channel)+"</yt:username><content type='application/atom+xml;type=feed' src='"+esc(profile)+"/uploads'/><link rel='related' href='"+esc(profile)+"'/><link rel='edit' href='"+esc(url)+"'/>");
 }
 static String result(Context c,YouTubeControlXml action,JSONObject data)throws Exception {
  String op=action.operation;
  if(op.equals("subscribe"))return subscription(data);
  if(op.equals("playlistCreate")||op.equals("playlistUpdate")){
   if(!data.has("contentDetails"))data.put("contentDetails",new JSONObject().put("itemCount",0));
   return "<?xml version='1.0' encoding='UTF-8'?>"+YouTubeLibraryFeeds.playlist(data).replaceFirst("<entry>","<entry"+YouTubeAccountFeeds.NS+">");
  }
  if(op.equals("comment")){
   JSONObject row=data.getJSONObject("snippet").getJSONObject("topLevelComment"),s=row.getJSONObject("snippet");
   String url=YouTubeAccountFeeds.ORIGIN+"/feeds/api/videos/"+action.params.getString("video")+"/comments/"+row.getString("id");
   return entry("<id>"+esc(url)+"</id><published>"+esc(s.optString("publishedAt"))+"</published><updated>"+esc(s.optString("updatedAt",s.optString("publishedAt")))+"</updated><content>"+esc(s.optString("textOriginal"))+"</content><author><name>"+esc(s.optString("authorDisplayName"))+"</name><uri>"+YouTubeAccountFeeds.ORIGIN+"/feeds/api/users/"+esc(s.optJSONObject("authorChannelId")==null?"default":s.getJSONObject("authorChannelId").optString("value","default"))+"</uri></author>");
  }
  if(op.equals("playlistAdd"))return "<?xml version='1.0' encoding='UTF-8'?>"+YouTubeVideoFeeds.video(data.getJSONObject("_video")).replaceFirst("<entry>","<entry"+YouTubeAccountFeeds.NS+">");
  // Rating and playlist insertion converters consume only success; retain a valid Atom body.
  return entry("<id>"+esc(data.optString("id",action.params.optString("video","action")))+"</id>");
 }
 static void serve(Context c,InputStream in,OutputStream out)throws Exception {
  try {
   String header=new String(Wire.header(in),"ISO-8859-1");String[] lines=header.split("\r\n"),request=lines[0].split(" ");if(request.length!=3)throw new IOException("Invalid action request");
   URI uri=new URI(request[1]);if(uri.isAbsolute()||uri.getFragment()!=null||uri.getRawPath().contains("%"))throw new IOException("Invalid action target");
   // Original GData versions/key selectors do not alter the operation.
   if(uri.getRawQuery()!=null)for(String pair:uri.getRawQuery().split("&"))if(!pair.matches("(v=2|alt=atom|key=[A-Za-z0-9_-]{1,200}|prettyprint=(true|false))"))throw new IOException("Unsupported action option");
   Map<String,String> h=new HashMap<String,String>();for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');if(colon<1)throw new IOException("Invalid header");String key=lines[i].substring(0,colon).toLowerCase(Locale.US);if(h.put(key,lines[i].substring(colon+1).trim())!=null)throw new IOException("Duplicate header");}
   if(h.containsKey("transfer-encoding")||h.containsKey("x-http-method-override"))throw new IOException("Unsupported encoding");
   int length=Integer.parseInt(h.containsKey("content-length")?h.get("content-length"):"0");if(length<0||length>16384)throw new IOException("Action too large");
   byte[] body=new byte[length];new DataInputStream(in).readFully(body);
   YouTubeControlXml action=YouTubeControlXml.parse(request[0],uri.getPath(),body);
   JSONObject addedVideo=null;
   if(action.operation.equals("playlistAdd")){
    List<JSONObject> videos=YouTubeVideoFeeds.videos(c,Collections.singletonList(action.params.getString("video")));
    if(videos.size()!=1)throw new IOException("Video unavailable");addedVideo=videos.get(0);
   }
   Bundle params=new Bundle();params.putString("params",action.params.toString());params.putString("requestId",h.get("x-galaxy-action-id"));params.putString("channel",h.get("x-galaxy-channel"));
   Bundle reply=c.getContentResolver().call(Uri.parse("content://org.retrobridge.galaxymail.pixel"),"youtubeWrite",action.operation,params);
   if(reply==null)throw new IOException("Google Bridge unavailable");if(reply.containsKey("error"))throw new IOException(reply.getString("error"));
   JSONObject data=new JSONObject(reply.getString("json"));
   if(addedVideo!=null)data.put("_video",addedVideo);
   YouTubeVideoFeeds.invalidate();
   response(out,request[0].equals("POST")?201:200,result(c,action,data));
  }catch(Exception e){
   // Error text contains only our fixed diagnostics/status codes, never upstream message data.
   String message=e instanceof IOException?e.getMessage():"YouTube action could not be completed";
   if(message==null)message="YouTube action could not be completed";
   response(out,403,"<errors xmlns='http://schemas.google.com/g/2005'><error><domain>yt:service</domain><code>requestFailed</code><internalReason>"+esc(message)+"</internalReason></error></errors>");
  }
 }
}
