package org.retrobridge.youtube2012.core.async;

import android.app.Activity;import java.io.*;import java.net.*;import org.json.JSONObject;
import org.retrobridge.youtube2012.core.model.UserAuth;

/** Google grants are checked on Pixel. This UI marker is not a Google token. */
public final class PixelSignIn {
 public static void releasePlayer(final android.media.MediaPlayer player){
  // Samsung ICS can block in native release after a network interruption.
  // The caller has already detached this player from its AtomicReference.
  Thread cleanup=new Thread(new Runnable(){public void run(){try{player.release();}catch(RuntimeException ignored){}}},"youtube-player-release");
  cleanup.setDaemon(true);cleanup.start();
 }
 public static String thumbnailUrl(String value){
  try{
   URI uri=new URI(value);String host=uri.getHost(),path=uri.getRawPath();
   if(!("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()))||uri.getUserInfo()!=null||uri.getPort()!=-1||host==null||!host.matches("i[1-4]?[.]ytimg[.]com"))return value;
   java.util.regex.Matcher m=java.util.regex.Pattern.compile("/vi(?:_webp)?/([A-Za-z0-9_-]{11})/(default|mqdefault|hqdefault|sddefault|maxresdefault|[0-3])[.](?:jpg|webp)").matcher(path);
   if(m.matches())return "http://127.0.0.1:8768/feeds/api/galaxy/video-thumbnail/"+m.group(1)+"/"+m.group(2)+".jpg";
  }catch(Exception ignored){}return value;
 }
 public static void actionHeaders(org.apache.http.client.methods.HttpUriRequest http,GDataRequest request){
  if(!"127.0.0.1:8768".equals(http.getURI().getRawAuthority()))return;
  String method=http.getMethod();if(!method.equals("POST")&&!method.equals("PUT")&&!method.equals("DELETE"))return;
  http.setHeader("X-Galaxy-Action-Id",request.galaxyActionId);
  if(request.d!=null&&request.d.b!=null)http.setHeader("X-Galaxy-Channel",request.d.b);
 }
 public static boolean unavailableLibrary(android.view.View view){
  int id=view.getId();String title;
  if(id==0x7f0800ab)title="History";else if(id==0x7f0800ad)title="Watch later";else return false;
  new android.app.AlertDialog.Builder(view.getContext()).setTitle(title).setMessage("Google no longer shares this list with companion apps. Use YouTube on your Pixel.").setPositiveButton("OK",null).show();return true;
 }
 public static void connect(final Activity activity,final UserAuthorizer authorizer){
  new Thread(new Runnable(){public void run(){try{
   HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:8768/feeds/api/galaxy/account").openConnection(Proxy.NO_PROXY);c.setConnectTimeout(10000);c.setReadTimeout(45000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
   final String id;
   try{if(c.getResponseCode()!=200)throw new IOException("Connect YouTube in Google Bridge on Pixel");ByteArrayOutputStream bytes=new ByteArrayOutputStream();InputStream in=c.getInputStream();try{byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>16384)throw new IOException("Account response too large");bytes.write(b,0,n);}}finally{in.close();}JSONObject account=new JSONObject(bytes.toString("UTF-8"));id=account.getString("id");if(!id.matches("UC[A-Za-z0-9_-]{22}"))throw new IOException("Invalid channel identity");}finally{c.disconnect();}
   activity.runOnUiThread(new Runnable(){public void run(){if(!activity.isFinishing())authorizer.a(activity,new UserAuth("Pixel",id,UserAuth.AuthMethod.CLIENTLOGIN,"paired-pixel"));}});
  }catch(final Exception e){activity.runOnUiThread(new Runnable(){public void run(){authorizer.a(new IOException("YouTube connection unavailable. Check Google Bridge on Pixel."));}});}}},"pixel-youtube-signin").start();
 }
}
