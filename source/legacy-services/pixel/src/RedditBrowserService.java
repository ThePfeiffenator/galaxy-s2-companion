package org.retrobridge.galaxyservices.pixel;

import android.app.Service;
import android.content.*;
import android.os.*;
import android.webkit.*;
import java.io.*;
import java.net.URI;
import org.json.*;
import org.retrobridge.galaxyservices.Wire;

/** Private anonymous browser process: no account UI, JS bridge or RSS/API fallback. */
public final class RedditBrowserService extends Service {
 final Handler main=new Handler(Looper.getMainLooper());
 final Messenger receiver=new Messenger(new Handler(Looper.getMainLooper(),m->{start(m);return true;}));
 static boolean configured;
 WebView view;Messenger reply;String id,script;boolean finished=true,comments;int checks,stable,last=-1,retrySeconds;long started;String cause="";
 public void onCreate(){super.onCreate();if(!configured){if(Build.VERSION.SDK_INT>=28)WebView.setDataDirectorySuffix("reddit_public");configured=true;}}
 public IBinder onBind(Intent i){return receiver.getBinder();}
 static boolean host(String h){return h!=null&&(h.equals("reddit.com")||h.endsWith(".reddit.com")||h.equals("redd.it")||h.endsWith(".redd.it")||h.equals("redditstatic.com")||h.endsWith(".redditstatic.com")||h.equals("redditmedia.com")||h.endsWith(".redditmedia.com"));}
 void start(Message m){
  if(!finished){try{Message busy=Message.obtain();busy.arg1=503;Bundle b=new Bundle();b.putString("error","Another Reddit page is loading");busy.setData(b);if(m.replyTo!=null)m.replyTo.send(busy);}catch(Exception ignored){}return;}
  reply=m.replyTo;id=m.getData().getString("id","");String url=m.getData().getString("url","");
  if(!id.matches("[a-f0-9-]{36}")||reply==null)return;
  finished=false;checks=stable=retrySeconds=0;last=-1;started=SystemClock.elapsedRealtime();cause="timeout";
  try{
   URI u=new URI(url);if(!"https".equals(u.getScheme())||!"www.reddit.com".equals(u.getHost())||u.getUserInfo()!=null||u.getPort()!=-1||u.getFragment()!=null)throw new IOException("Invalid page");
   comments=u.getPath().contains("/comments/");
   try(InputStream in=getAssets().open("reddit-reader.js")){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] a=new byte[4096];int n;while((n=in.read(a))!=-1)b.write(a,0,n);script=b.toString("UTF-8");}
   view=new WebView(this);view.layout(0,0,1080,1920);
   WebSettings s=view.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setGeolocationEnabled(false);s.setLoadsImagesAutomatically(false);s.setMediaPlaybackRequiresUserGesture(true);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setUserAgentString(s.getUserAgentString()+" GalaxyLegacyReader/0.2");
   CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(view,false);
   view.setWebViewClient(new WebViewClient(){
    public WebResourceResponse shouldInterceptRequest(WebView w,WebResourceRequest r){return "https".equals(r.getUrl().getScheme())&&host(r.getUrl().getHost())?null:new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
    public boolean shouldOverrideUrlLoading(WebView w,WebResourceRequest r){String h=r.getUrl().getHost();return !"https".equals(r.getUrl().getScheme())||!("www.reddit.com".equals(h)||"reddit.com".equals(h));}
    public void onReceivedSslError(WebView w,SslErrorHandler h,android.net.http.SslError e){h.cancel();finish(502,"Reddit TLS verification failed",null);}
    public void onReceivedHttpError(WebView w,WebResourceRequest r,WebResourceResponse res){if(r.isForMainFrame()){int status=res.getStatusCode();if(status>=400){cause="http-"+status;try{for(java.util.Map.Entry<String,String> e:res.getResponseHeaders().entrySet())if(e.getKey().equalsIgnoreCase("Retry-After"))retrySeconds=Math.max(0,Math.min(86400,Integer.parseInt(e.getValue())));}catch(Exception ignored){}finish(status==403||status==429||status==404?status:502,"Reddit page access unavailable",null);}}}
    public void onReceivedError(WebView w,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame()){cause="network";finish(502,"Reddit connection failed",null);}}
    public boolean onRenderProcessGone(WebView w,RenderProcessGoneDetail d){finish(502,"Reddit browser stopped",null);return true;}
   });
   view.loadUrl(url);main.postDelayed(()->inspect(),500);main.postDelayed(()->finish(504,"Reddit page timed out",null),RedditLoadPolicy.PAGE_TIMEOUT_MS);
  }catch(Exception e){finish(502,"Reddit browser unavailable",null);}
 }
 void inspect(){if(finished||view==null)return;checks++;
  view.evaluateJavascript(script,value->{if(finished)return;try{
   String raw=(String)new JSONTokener(value).nextValue();if(raw.length()>800000)throw new IOException("Page size");JSONObject data=new JSONObject(raw);
   if(data.optBoolean("blocked")){cause="page-challenge";finish(403,"Reddit requires a browser check",null);return;}
   if(data.optBoolean("unavailable")){cause="page-unavailable";finish(404,"This Reddit page is not publicly available",null);return;}
   int count=data.getJSONArray("posts").length(),n=comments?data.getJSONArray("comments").length():count;
   stable=n==last?stable+1:0;last=n;
   if(RedditLoadPolicy.ready(comments,count,n,stable,data.optBoolean("ready"),data.optBoolean("empty"),SystemClock.elapsedRealtime()-started)){cause="content-ready";finish(200,"",raw);return;}
   main.postDelayed(()->inspect(),300);
  }catch(Exception e){cause="parse";finish(502,"Reddit page could not be read",null);}});
 }
 void finish(int status,String error,String raw){if(finished)return;finished=true;main.removeCallbacksAndMessages(null);
  if(view!=null){view.stopLoading();view.destroy();view=null;}
  try{File f=new File(getFilesDir(),"reddit-browser-timing.jsonl");if(f.length()>32768)f.delete();try(FileOutputStream out=new FileOutputStream(f,true)){out.write((new JSONObject().put("at",System.currentTimeMillis()).put("status",status).put("cause",cause).put("ms",SystemClock.elapsedRealtime()-started).put("checks",checks).put("rows",last).toString()+"\n").getBytes("UTF-8"));}}catch(Exception ignored){}
  if(raw!=null)try{File dir=new File(getCacheDir(),"reddit-browser-results");dir.mkdirs();Wire.writeFile(new File(dir,id+".json"),raw.getBytes("UTF-8"));}catch(Exception e){status=502;error="Reddit page storage failed";}
  try{Message m=Message.obtain();m.arg1=status;Bundle b=new Bundle();b.putString("error",error);b.putInt("retry_seconds",retrySeconds);m.setData(b);reply.send(m);}catch(Exception ignored){}
 }
 public void onDestroy(){if(!finished)finish(503,"Reddit reader closed",null);super.onDestroy();}
}
