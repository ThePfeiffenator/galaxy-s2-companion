package org.retrobridge.galaxyservices.pixel;
import android.content.*;import android.os.*;import android.webkit.*;import android.net.Uri;import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;import javax.net.ssl.HttpsURLConnection;import org.json.*;import org.retrobridge.galaxyservices.Wire;

/** Public search only, behind the existing paired-phone TLS endpoint. No account cookies or JS bridge. */
final class SearchEndpoint {
 static final String HOST="search.galaxy-services.invalid";
 final Context app;final Handler main=new Handler(Looper.getMainLooper());final Semaphore gate=new Semaphore(1);
 SearchEndpoint(Context c){app=c;}
 void note(String s){try{Wire.writeFile(new File(app.getFilesDir(),"search-status.txt"),s.getBytes("UTF-8"));}catch(Exception ignored){}}
 static String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
 static String enc(String s)throws Exception{return URLEncoder.encode(s,"UTF-8");}
 static String parameter(URI u,String wanted)throws Exception{String q=u.getRawQuery();if(q==null)return "";String value="";for(String item:q.split("&")){String[] p=item.split("=",2);if(URLDecoder.decode(p[0],"UTF-8").equals(wanted))value=p.length==2?URLDecoder.decode(p[1],"UTF-8"):"";}return value;}
 void serve(InputStream in,OutputStream out)throws Exception{
  String h=new String(Wire.header(in),"ISO-8859-1");String[] r=h.substring(0,h.indexOf("\r\n")).split(" ");
  if(r.length!=3||!r[0].equals("GET")){reply(out,405,"text/plain","GET required");return;}
  URI u=new URI(r[1]);String q=parameter(u,"q");if(q.length()>512){reply(out,400,"text/plain","Search too long");return;}
  if(u.getPath().equals("/complete/search")){suggest(out,q);return;}
  if(!u.getPath().equals("/search")){reply(out,404,"text/plain","Not found");return;}
  int start=0;try{start=Integer.parseInt(parameter(u,"start"));}catch(Exception ignored){}start=Math.max(0,Math.min(90,start));
  StringBuilder body=new StringBuilder("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>Google</title><style>body{font:16px Arial,sans-serif;margin:14px;color:#222}header{font-size:28px;color:#4285f4;margin-bottom:12px}input{font-size:17px;padding:7px;width:65%;box-sizing:border-box}button{padding:9px}article{margin:22px 0}a{color:#12c;font-size:20px;text-decoration:none}small{display:block;color:#176a28;overflow:hidden;margin:4px 0}p{margin:5px 0;line-height:1.4}nav{margin:25px 0}</style></head><body><header>Google</header><form action='/search'><input name=q value='").append(esc(q)).append("'><button>Search</button></form>");
  if(!q.trim().isEmpty()){
   if(!gate.tryAcquire()){reply(out,503,"text/plain","Search is busy. Try again.");return;}
   PowerManager.WakeLock wake=((PowerManager)app.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GalaxyServices:search");wake.acquire(40000);
   Job job=new Job(q,start);
   try{main.post(job);if(!job.done.await(32,TimeUnit.SECONDS))main.post(()->job.finish("Search timed out"));job.done.await(2,TimeUnit.SECONDS);
    if(job.error.length()>0)body.append("<p>").append(esc(job.error)).append("</p>");
    else{int count=0;for(int k=0;k<job.rows.length();k++){JSONObject row=job.rows.getJSONObject(k);String url=row.optString("url"),title=row.optString("title"),snippet=row.optString("snippet");URI link;try{url=SearchLinks.direct(url);link=new URI(url);}catch(Exception bad){continue;}if((!"https".equals(link.getScheme())&&!"http".equals(link.getScheme()))||link.getHost()==null||link.getUserInfo()!=null||title.isEmpty())continue;body.append("<article><a href='").append(esc(url)).append("'>").append(esc(title)).append("</a><small>").append(esc(link.getHost())).append("</small><p>").append(esc(snippet)).append("</p></article>");count++;}
     if(count==0)body.append("<p>No web results were available for this search.</p>");
     if(count>0&&start<90)body.append("<nav><a href='/search?q=").append(enc(q)).append("&amp;start=").append(start+10).append("'>Next results</a></nav>");note("Results delivered; count="+count);
    }
   }finally{gate.release();if(wake.isHeld())wake.release();}
  }
  body.append("</body></html>");reply(out,200,"text/html; charset=utf-8",body.toString());
 }
 void suggest(OutputStream out,String q)throws Exception{
  JSONArray rows=new JSONArray();if(!q.isEmpty()){
   HttpsURLConnection c=(HttpsURLConnection)new URL("https://suggestqueries.google.com/complete/search?client=firefox&hl=en&q="+enc(q)).openConnection(java.net.Proxy.NO_PROXY);
   c.setConnectTimeout(6000);c.setReadTimeout(6000);c.setInstanceFollowRedirects(false);
   try{if(c.getResponseCode()==200){ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>65536)throw new IOException("Suggestion size");bytes.write(b,0,n);}}JSONArray a=new JSONArray(bytes.toString("UTF-8")).getJSONArray(1);for(int k=0;k<Math.min(10,a.length());k++)rows.put(new JSONArray().put(esc(a.getString(k))).put("").put(0));}}finally{c.disconnect();}
  }
  reply(out,200,"application/json; charset=utf-8",new JSONArray().put(q).put(rows).toString());
 }
 static void reply(OutputStream o,int status,String type,String s)throws Exception{byte[] b=s.getBytes("UTF-8");o.write(("HTTP/1.1 "+status+" Result\r\nContent-Type: "+type+"\r\nContent-Length: "+b.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));o.write(b);o.flush();}
 static boolean googleHost(String host){return host!=null&&(host.equals("google.com")||host.endsWith(".google.com")||host.equals("gstatic.com")||host.endsWith(".gstatic.com")||host.endsWith(".googleusercontent.com"));}
 final class Job implements Runnable {
  final String query;final int start;final CountDownLatch done=new CountDownLatch(1);volatile JSONArray rows=new JSONArray();volatile String error="";WebView view;boolean finished;int checks;
  Job(String q,int s){query=q;start=s;}
  public void run(){try{view=new WebView(app);view.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE,null);WebSettings s=view.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(false);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setGeolocationEnabled(false);s.setLoadsImagesAutomatically(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);android.webkit.CookieManager.getInstance().setAcceptCookie(false);android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(view,false);view.setWebViewClient(new WebViewClient(){
   public WebResourceResponse shouldInterceptRequest(WebView w,WebResourceRequest r){Uri u=r.getUrl();return "https".equals(u.getScheme())&&googleHost(u.getHost())?null:new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
   public boolean shouldOverrideUrlLoading(WebView w,WebResourceRequest r){return !"www.google.com".equals(r.getUrl().getHost());}
   public void onReceivedSslError(WebView w,SslErrorHandler h,android.net.http.SslError e){h.cancel();finish("Google connection could not be verified");}
   public void onPageFinished(WebView w,String url){if(!finished)main.postDelayed(()->inspect(),500);}
  });view.loadUrl("https://www.google.com/search?hl=en&udm=14&q="+enc(query)+"&start="+start);main.postDelayed(()->finish("Search timed out"),30000);
  }catch(Exception e){finish("Search unavailable on Pixel");}}
  void inspect(){if(finished||view==null)return;checks++;view.evaluateJavascript("(function(){var rows=[],seen={};document.querySelectorAll('a').forEach(function(a){var h=a.querySelector('h3,[role=heading]');if(!h)return;var u=a.href,t=h.innerText||h.textContent||'';if(u.indexOf('/url?')>=0){try{u=new URL(u).searchParams.get('q')||new URL(u).searchParams.get('url')||u;}catch(e){}}if(!/^https?:/.test(u)||!t||seen[u])return;seen[u]=1;var p=a,txt='';for(var i=0;i<5&&p;i++,p=p.parentElement){var v=p.innerText||'';if(v.length>t.length+30&&v.length<1500){txt=v;break;}}rows.push({url:u,title:t.slice(0,250),snippet:txt.slice(0,900)});});var text=(document.body.innerText||'').slice(0,20000).toLowerCase();return JSON.stringify({rows:rows.slice(0,30),blocked:text.indexOf('unusual traffic')>=0||text.indexOf('not a robot')>=0,consent:location.hostname==='consent.google.com'});})()",value->{if(finished)return;try{String raw=(String)new JSONTokener(value).nextValue();JSONObject result=new JSONObject(raw);rows=result.getJSONArray("rows");if(result.optBoolean("blocked")){finish("Google requires a verification check on the Pixel. Try again later.");return;}if(result.optBoolean("consent")){finish("Google requires consent in its browser before search is available.");return;}if(rows.length()>0){finish("");return;}if(checks<18)main.postDelayed(()->inspect(),1000);else finish("No readable Google results were returned. Try another search.");}catch(Exception e){finish("Search results could not be read");}});}
  void finish(String e){if(finished)return;finished=true;error=e;if(view!=null){view.stopLoading();view.destroy();view=null;}if(!e.isEmpty())note(e);done.countDown();}
 }
}
