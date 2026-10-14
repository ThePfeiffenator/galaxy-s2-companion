package org.retrobridge.galaxyservices.pixel;

import android.content.*;import android.os.*;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;
import org.json.*;import org.retrobridge.galaxyservices.Wire;

/** Converts a normal public page rendered on the Pixel into RIF's original JSON shape. */
final class RedditWebEndpoint {
 static final String HOST=RedditEndpoint.HOST;
 static final long CACHE_MS=15*60*1000;
 final Context app;final Handler main=new Handler(Looper.getMainLooper());
 volatile long blockedUntil;long nextRequest;
 final java.util.concurrent.locks.ReentrantLock gate=new java.util.concurrent.locks.ReentrantLock(true);
 RedditWebEndpoint(Context c){app=c;blockedUntil=c.getSharedPreferences("reddit_web_reader",0).getLong("blocked_until",0);}
 static final class Target {String url,after;boolean comments,about,communities,search;int limit;}
 static Target target(URI u)throws Exception{
  if(u.isAbsolute()||u.getRawAuthority()!=null||u.getFragment()!=null)throw new RedditEndpoint.Failure(400,"Invalid page target");
  String p=u.getPath();if(p==null||p.length()>1000||p.contains("..")||p.contains("%"))throw new RedditEndpoint.Failure(400,"Invalid page path");
  p=p.replaceFirst("\\.json$","");if(p.isEmpty())p="/";
  Target t=new Target();t.comments=p.matches("(?:/r/[A-Za-z0-9_+]{1,120})?/comments/[a-z0-9]+(?:/[A-Za-z0-9_/-]*)?");t.about=p.matches("/r/[A-Za-z0-9_]{1,40}/about/?");t.communities=p.matches("/(?:subreddits|reddits)(?:/(?:default|popular|new))?/?");boolean search=p.matches("(?:/r/[A-Za-z0-9_+]{1,120})?/search/?");
  t.search=search;
  if(!(t.comments||t.about||t.communities||search||p.matches("(?:/r/[A-Za-z0-9_+]{1,120})?(?:/(?:hot|new|top|controversial|rising))?/?")))throw new RedditEndpoint.Failure(404,"Not available in this reader");
  t.limit=25;try{t.limit=Integer.parseInt(SearchEndpoint.parameter(u,"limit"));}catch(Exception ignored){}t.limit=Math.max(1,Math.min(t.comments?150:100,t.limit));
  t.after=SearchEndpoint.parameter(u,"after");if(!t.after.isEmpty()&&!t.after.matches("t3_[a-z0-9]+"))throw new RedditEndpoint.Failure(400,"Invalid next page");
  if(t.about)p=p.replaceFirst("/about/?$","");if(t.communities)p="/r/popular/";if(p.matches("/(?:hot/?)?"))p="/r/popular/";else if(p.matches("/(?:new|top|controversial|rising)/?"))p="/r/popular"+p;
  p=p.replaceFirst("/hot/?$","");if(!p.endsWith("/"))p+="/";
  StringBuilder q=new StringBuilder();if(!t.after.isEmpty())q.append("after=").append(t.after);
  if(search){String text=SearchEndpoint.parameter(u,"q");if(text.isEmpty()||text.length()>200)throw new RedditEndpoint.Failure(400,"Invalid search");add(q,"q",text);String sr=SearchEndpoint.parameter(u,"restrict_sr");if(sr.equals("on")||sr.equals("true"))add(q,"restrict_sr","on");}
  for(String k:new String[]{"sort","t"}){String v=SearchEndpoint.parameter(u,k);if(!v.isEmpty()){if(!v.matches("[a-z]{1,20}"))throw new RedditEndpoint.Failure(400,"Invalid sort");add(q,k,v);}}
  t.url="https://www.reddit.com"+p+(q.length()>0?"?"+q:"");return t;
 }
 static void add(StringBuilder q,String k,String v)throws Exception{if(q.length()>0)q.append('&');q.append(k).append('=').append(URLEncoder.encode(v,"UTF-8"));}
 File cacheFile(String url)throws Exception {File d=new File(app.getCacheDir(),"reddit-pages");d.mkdirs();return new File(d,Wire.hex(java.security.MessageDigest.getInstance("SHA-256").digest(url.getBytes("UTF-8")))+".json");}
 JSONObject cached(File f){if(f.isFile()&&f.length()<1000000&&System.currentTimeMillis()-f.lastModified()<CACHE_MS)try{return new JSONObject(new String(Wire.readFile(f),"UTF-8"));}catch(Exception ignored){}return null;}
 synchronized void timing(String mode,int status,long started){try{File f=new File(app.getFilesDir(),"reddit-timing.jsonl");if(f.length()>32768)f.delete();try(FileOutputStream out=new FileOutputStream(f,true)){out.write((new JSONObject().put("at",System.currentTimeMillis()).put("mode",mode).put("status",status).put("ms",SystemClock.elapsedRealtime()-started).toString()+"\n").getBytes("UTF-8"));}}catch(Exception ignored){}}
 JSONObject page(String url)throws Exception{
  long started=SystemClock.elapsedRealtime();File f=cacheFile(url);JSONObject hit=cached(f);if(hit!=null){timing("cache",200,started);return hit;}
  // Cache hits never wait behind a slow unrelated page. Uncached work has a bounded queue.
  if(System.currentTimeMillis()<blockedUntil)throw new RedditEndpoint.Failure(429,"Reddit asked to wait. Try later.");
  if(!gate.tryLock(RedditLoadPolicy.QUEUE_TIMEOUT_MS,TimeUnit.MILLISECONDS))throw new RedditEndpoint.Failure(503,"Another Reddit page is loading. Try again.");
  try{hit=cached(f);if(hit!=null){timing("shared-cache",200,started);return hit;}JSONObject data=loadPage(url,f);timing("page",200,started);return data;}
  catch(RedditEndpoint.Failure e){timing("page",e.status,started);throw e;}
  finally{gate.unlock();}
 }
 JSONObject loadPage(String url,File f)throws Exception{
  long now=System.currentTimeMillis();
  if(now<blockedUntil)throw new RedditEndpoint.Failure(429,"Reddit asked to wait. Try later.");
  if(now<nextRequest)Thread.sleep(nextRequest-now);
  nextRequest=System.currentTimeMillis()+RedditLoadPolicy.START_SPACING_MS;
  final String id=UUID.randomUUID().toString();final CountDownLatch done=new CountDownLatch(1);final int[] status={504},retry={600};final String[] error={"Reddit page timed out"};
  final File result=new File(new File(app.getCacheDir(),"reddit-browser-results"),id+".json");
  Messenger reply=new Messenger(new Handler(Looper.getMainLooper(),m->{status[0]=m.arg1;error[0]=m.getData().getString("error","");retry[0]=Math.max(600,m.getData().getInt("retry_seconds",0));done.countDown();return true;}));
  ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder binder){try{Message m=Message.obtain();Bundle b=new Bundle();b.putString("id",id);b.putString("url",url);m.setData(b);m.replyTo=reply;new Messenger(binder).send(m);}catch(Exception e){status[0]=502;done.countDown();}}public void onServiceDisconnected(ComponentName n){status[0]=502;done.countDown();}public void onNullBinding(ComponentName n){status[0]=502;done.countDown();}};
  PowerManager.WakeLock wake=((PowerManager)app.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GalaxyServices:reddit");wake.acquire(40000);boolean bound=false;
  try{
   bound=app.bindService(new Intent(app,RedditBrowserService.class),connection,Context.BIND_AUTO_CREATE);if(!bound)throw new IOException("Browser bind failed");
   if(!done.await(RedditLoadPolicy.PAGE_TIMEOUT_MS+3000,TimeUnit.MILLISECONDS))throw new RedditEndpoint.Failure(504,"Reddit page timed out");
   if(status[0]==403||status[0]==429){blockedUntil=System.currentTimeMillis()+retry[0]*1000L;app.getSharedPreferences("reddit_web_reader",0).edit().putLong("blocked_until",blockedUntil).commit();}
   if(status[0]!=200)throw new RedditEndpoint.Failure(status[0],error[0]);
   if(!result.isFile()||result.length()>1000000)throw new IOException("Page result missing");byte[] bytes=Wire.readFile(result);JSONObject data=new JSONObject(new String(bytes,"UTF-8"));
   File tmp=new File(f.getPath()+".tmp");Wire.writeFile(tmp,bytes);if(!tmp.renameTo(f))tmp.delete();
   File[] files=f.getParentFile().listFiles((d,n)->n.endsWith(".json"));if(files!=null&&files.length>24){Arrays.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));for(int k=0;k<files.length-24;k++)files[k].delete();}
   return data;
  }finally{if(bound)app.unbindService(connection);result.delete();if(wake.isHeld())wake.release();}
 }
 static String mediaUrl(String url)throws Exception{
  URI u=new URI(url);String p=u.getPath();
  if("https".equals(u.getScheme())&&"v.redd.it".equals(u.getHost())&&u.getPort()==-1&&u.getUserInfo()==null&&u.getFragment()==null&&p!=null&&p.matches("/[a-z0-9]{6,32}/?"))
   return "http://127.0.0.1:8768/sabrhls-video-fragment/reddit/"+p.replace("/","")+".html";
  return url;
 }
 static JSONObject thing(JSONObject e,boolean post,String linkId)throws Exception{
  String id=e.optString("id");if(!id.matches(post?"t3_[a-z0-9]+":"t1_[a-z0-9]+"))throw new IOException("Invalid item id");
  String permalink=e.optString("permalink");if(!permalink.startsWith("/")||permalink.startsWith("//"))throw new IOException("Invalid permalink");String html=e.optString("html");
  boolean hidden=e.optBoolean("score_hidden",true);int score=hidden?0:e.optInt("score",0);
  JSONObject d=new JSONObject().put("id",id.substring(3)).put("name",id).put("author",e.optString("author")).put("permalink",permalink).put("created_utc",e.optLong("created")).put("created",e.optLong("created")).put("score",score).put("ups",Math.max(0,score)).put("downs",Math.max(0,-score)).put("score_hidden",hidden).put("likes",JSONObject.NULL).put("saved",false).put("distinguished",JSONObject.NULL);
  if(post){String url=RedditEndpoint.publicUrl(e.optString("url"));if(url.isEmpty())url="https://www.reddit.com"+permalink;String sub=e.optString("subreddit");boolean self=e.optString("type").equals("text")||e.optString("type").equals("discussion");d.put("title",e.optString("title")).put("url",mediaUrl(url)).put("subreddit",sub).put("domain",self?"self."+sub:new URI(url).getHost()).put("is_self",self).put("selftext",RedditEndpoint.plain(html)).put("selftext_html",html.isEmpty()?JSONObject.NULL:SearchEndpoint.esc(html)).put("thumbnail",RedditEndpoint.publicUrl(e.optString("thumbnail"))).put("num_comments",e.optInt("num_comments",0)).put("hide_score",hidden).put("over_18",e.optBoolean("over_18"));}
  else d.put("body",RedditEndpoint.plain(html)).put("body_html",SearchEndpoint.esc(html)).put("parent_id",e.optString("parent",linkId)).put("link_id",linkId).put("replies","");
  return new JSONObject().put("kind",post?"t3":"t1").put("data",d);
 }
 static Object convert(JSONObject page,Target t)throws Exception{
  JSONArray raw=page.getJSONArray("posts"),posts=new JSONArray();Set<String> seen=new HashSet<String>();
  for(int i=0;i<raw.length()&&posts.length()<(t.comments?1:t.limit);i++){JSONObject e=raw.getJSONObject(i);if(seen.add(e.optString("id")))posts.put(thing(e,true,""));}
  if(t.about){String sub=new URI(t.url).getPath().split("/")[2];return RedditEndpoint.subreddit(sub,page.optString("title"),"");}
  if(t.communities){JSONArray rows=new JSONArray();seen.clear();for(int i=0;i<raw.length();i++){String sub=raw.getJSONObject(i).optString("subreddit");if(!sub.isEmpty()&&seen.add(sub))rows.put(RedditEndpoint.subreddit(sub,sub,""));}return RedditEndpoint.listing(rows,null);}
  if(!t.comments)return RedditEndpoint.listing(posts,!t.search&&posts.length()>0?posts.getJSONObject(posts.length()-1).getJSONObject("data").getString("name"):null);
  if(posts.length()==0)throw new IOException("Missing post");String linkId=posts.getJSONObject(0).getJSONObject("data").getString("name");JSONArray top=new JSONArray();LinkedHashMap<String,JSONObject> nodes=new LinkedHashMap<>();JSONArray comments=page.getJSONArray("comments");
  for(int i=0;i<comments.length()&&nodes.size()<t.limit;i++){JSONObject e=comments.getJSONObject(i);if(!nodes.containsKey(e.optString("id")))nodes.put(e.getString("id"),thing(e,false,linkId));}
  for(JSONObject node:nodes.values()){JSONObject d=node.getJSONObject("data"),parent=nodes.get(d.getString("parent_id"));if(parent==null||parent==node)top.put(node);else{JSONObject pd=parent.getJSONObject("data");if(!(pd.opt("replies") instanceof JSONObject))pd.put("replies",RedditEndpoint.listing(new JSONArray(),null));pd.getJSONObject("replies").getJSONObject("data").getJSONArray("children").put(node);}}
  return new JSONArray().put(RedditEndpoint.listing(posts,null)).put(RedditEndpoint.listing(top,null));
 }
 Object response(URI u)throws Exception{Target t=target(u);return convert(page(t.url),t);}
 void serve(InputStream in,OutputStream out)throws Exception{
  try{String h=new String(Wire.header(in),"ISO-8859-1");String[] r=h.substring(0,h.indexOf("\r\n")).split(" ");if(r.length!=3||!r[0].equals("GET"))throw new RedditEndpoint.Failure(405,"Browsing only");Object result=response(new URI(r[1]));SearchEndpoint.reply(out,200,"application/json; charset=utf-8",result.toString());android.util.Log.i("GalaxyReddit","Public page delivered");}
  catch(RedditEndpoint.Failure e){SearchEndpoint.reply(out,e.status,"application/json",new JSONObject().put("error",e.status).put("message",e.getMessage()).toString());android.util.Log.i("GalaxyReddit","Public page status "+e.status);}
  catch(Exception e){SearchEndpoint.reply(out,502,"application/json",new JSONObject().put("error",502).put("message","Reddit page unavailable").toString());android.util.Log.w("GalaxyReddit","Public page error: "+e.getClass().getSimpleName());}
 }
}
