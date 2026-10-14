package org.retrobridge.galaxyservices.pixel;

import android.text.Html;
import android.util.Xml;
import android.content.Context;
import org.retrobridge.galaxyservices.Wire;
import org.json.*;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.net.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.*;
import javax.net.ssl.HttpsURLConnection;

/** Read-only translation of Reddit's public Atom feeds for the original RIF UI.
 * No account cookies, API credentials, private endpoints, or fallback on access denial.
 * Reddit has announced retirement of these feeds on 2026-11-13.
 */
public final class RedditEndpoint {
 static final String HOST="reddit.galaxy-services.invalid";
 static final String UA="GalaxyLegacyReader/0.1 (personal Android compatibility test)";
 static final String ATOM="http://www.w3.org/2005/Atom";
 static final int MAX_BYTES=3*1024*1024;
 static final long CACHE_MS=15*60*1000;
 final Context app;
 final LinkedHashMap<String,Cached> cache=new LinkedHashMap<String,Cached>();
 long nextRequest,blockedUntil;
 RedditEndpoint(){app=null;}
 RedditEndpoint(Context c){app=c;blockedUntil=c.getSharedPreferences("reddit_reader",0).getLong("blocked_until",0);}
 static final class Cached {final long at;final Feed feed;Cached(Feed f){this(f,System.currentTimeMillis());}Cached(Feed f,long t){feed=f;at=t;}}
 static final class Entry {String id="",title="",author="",category="",link="",content="",date="",thumbnail="";}
 static final class Feed {String title="",subtitle="",category="";final List<Entry> entries=new ArrayList<Entry>();}
 static final class Failure extends IOException {final int status;Failure(int s,String m){super(m);status=s;}}
 File diskFile(String target)throws Exception {File dir=new File(app.getCacheDir(),"reddit-feeds");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cache directory");return new File(dir,Wire.hex(java.security.MessageDigest.getInstance("SHA-256").digest(target.getBytes("UTF-8")))+".xml");}
 Cached disk(String target){if(app==null)return null;try{File f=diskFile(target);if(!f.isFile()||f.length()>MAX_BYTES||System.currentTimeMillis()-f.lastModified()>CACHE_MS)return null;return new Cached(parse(Wire.readFile(f)),f.lastModified());}catch(Exception ignored){return null;}}
 void save(String target,byte[] bytes){if(app==null)return;try{File f=diskFile(target),tmp=new File(f.getPath()+".tmp");Wire.writeFile(tmp,bytes);if(!tmp.renameTo(f)){tmp.delete();return;}File[] files=f.getParentFile().listFiles((d,n)->n.endsWith(".xml"));if(files!=null&&files.length>24){Arrays.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));for(int i=0;i<files.length-24;i++)files[i].delete();}}catch(Exception ignored){}}

 static Feed parse(byte[] bytes)throws Exception {
  if(bytes.length>MAX_BYTES)throw new IOException("Feed too large");
  XmlPullParser p=Xml.newPullParser();p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);
  p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false);
  p.setInput(new ByteArrayInputStream(bytes),"UTF-8");Feed f=new Feed();Entry e=null;boolean author=false,sawFeed=false;
  for(int event=p.getEventType();event!=XmlPullParser.END_DOCUMENT;event=p.nextToken()){
   if(event==XmlPullParser.DOCDECL)throw new IOException("Feed document type rejected");
   if(event==XmlPullParser.START_TAG){
    String n=p.getName(),ns=p.getNamespace();
    if(ATOM.equals(ns)){
     if(n.equals("feed")){sawFeed=true;continue;}
     if(n.equals("entry")){if(e!=null||f.entries.size()>=150)throw new IOException("Feed structure");e=new Entry();continue;}
     if(n.equals("author")){author=true;continue;}
     if(n.equals("category")){String v=p.getAttributeValue(null,"term");if(v!=null){if(e==null)f.category=v;else e.category=v;}continue;}
     if(n.equals("link")&&e!=null){String rel=p.getAttributeValue(null,"rel");if(rel==null||rel.equals("alternate")){String v=p.getAttributeValue(null,"href");if(v!=null)e.link=v;}continue;}
     if(n.equals("title")||n.equals("subtitle")||n.equals("id")||n.equals("content")||n.equals("published")||n.equals("updated")||(n.equals("name")&&author)){
      String text=p.nextText();
      if(e==null){if(n.equals("title"))f.title=text;if(n.equals("subtitle"))f.subtitle=text;}
      else if(n.equals("title"))e.title=text;else if(n.equals("id"))e.id=text;else if(n.equals("content"))e.content=text;else if(n.equals("name"))e.author=text.replaceFirst("^/u/","");else if(n.equals("published")||n.equals("updated")&&e.date.isEmpty())e.date=text;
     }
    }else if(e!=null&&"http://search.yahoo.com/mrss/".equals(ns)&&n.equals("thumbnail")){String v=p.getAttributeValue(null,"url");if(v!=null)e.thumbnail=v;}
   }else if(event==XmlPullParser.END_TAG&&ATOM.equals(p.getNamespace())){
    if(p.getName().equals("author"))author=false;
    if(p.getName().equals("entry")){if(e!=null&&e.id.matches("t[13]_[a-z0-9]+")&&!e.link.isEmpty())f.entries.add(e);e=null;}
   }
  }
  if(!sawFeed)throw new IOException("Reddit returned no public feed");return f;
 }
 synchronized Feed fetch(String path,String query)throws Exception {
  String target="https://www.reddit.com"+path+(query.isEmpty()?"":"?"+query);
  Cached old=cache.get(target);if(old==null)old=disk(target);long now=System.currentTimeMillis();if(old!=null&&now-old.at<CACHE_MS){cache.put(target,old);return old.feed;}
  if(now<blockedUntil)throw new Failure(429,"Reddit asked to wait. Try again later.");
  if(now<nextRequest)Thread.sleep(nextRequest-now);
  HttpsURLConnection c=(HttpsURLConnection)new URL(target).openConnection(java.net.Proxy.NO_PROXY);
  c.setConnectTimeout(12000);c.setReadTimeout(18000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent",UA);c.setRequestProperty("Accept","application/atom+xml");
  try{
   int status=c.getResponseCode();nextRequest=System.currentTimeMillis()+10000;
   if(status==429){long seconds=600;try{seconds=Math.max(60,Math.min(86400,Long.parseLong(c.getHeaderField("Retry-After"))));}catch(Exception ignored){}blockedUntil=System.currentTimeMillis()+seconds*1000;if(app!=null)app.getSharedPreferences("reddit_reader",0).edit().putLong("blocked_until",blockedUntil).commit();throw new Failure(429,"Reddit asked to wait. Try again later.");}
   if(status!=200)throw new Failure(status==403?403:502,"Reddit public feed unavailable ("+status+")");
   String type=c.getContentType();if(type==null||!(type.contains("xml")||type.contains("atom")))throw new Failure(502,"Reddit returned no public feed");
   ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(b.size()+n>MAX_BYTES)throw new IOException("Feed too large");b.write(buf,0,n);}}
   byte[] bytes=b.toByteArray();Feed f=parse(bytes);if(cache.size()>=24)cache.remove(cache.keySet().iterator().next());cache.put(target,new Cached(f));save(target,bytes);return f;
  }finally{c.disconnect();}
 }
 static String parameter(URI u,String name)throws Exception{return SearchEndpoint.parameter(u,name);}
 static String enc(String s)throws Exception{return URLEncoder.encode(s,"UTF-8");}
 static String publicUrl(String s){try{URI u=new URI(s);if(("https".equals(u.getScheme())||"http".equals(u.getScheme()))&&u.getHost()!=null&&u.getUserInfo()==null)return s;}catch(Exception ignored){}return "";}
 static String path(String s){try{return new URI(s).getPath();}catch(Exception e){return "";}}
 static String esc(String s){return SearchEndpoint.esc(s);}
 static String plain(String s){return Html.fromHtml(s,Html.FROM_HTML_MODE_LEGACY).toString().trim();}
 static String content(String s){int start=s.indexOf("<!-- SC_OFF -->"),end=s.indexOf("<!-- SC_ON -->");if(start<0||end<start)return "";String h=s.substring(start+15,end);h=h.replaceAll("(?is)<(script|style|iframe|object|embed)\\b.*?</\\1\\s*>","");h=h.replaceAll("(?is)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*')","");return h;}
 static JSONObject thing(Entry e,String postId)throws Exception {
  String kind=e.id.substring(0,2),id=e.id.substring(3),permalink=path(e.link),html=content(e.content);long created=0;
  try{created=OffsetDateTime.parse(e.date).toEpochSecond();}catch(Exception ignored){}
  JSONObject d=new JSONObject().put("id",id).put("name",e.id).put("author",e.author).put("subreddit",e.category).put("permalink",permalink).put("created_utc",created).put("created",created).put("likes",JSONObject.NULL).put("saved",false).put("distinguished",JSONObject.NULL).put("score_hidden",true);
  if(kind.equals("t3")){
   String url=e.link;Matcher m=Pattern.compile("<a\\s+href=\"([^\"]+)\"[^>]*>\\[link\\]</a>",Pattern.CASE_INSENSITIVE).matcher(e.content);if(m.find()){String candidate=publicUrl(plain(m.group(1)));if(!candidate.isEmpty())url=candidate;}
   boolean self=url.equals(e.link);String domain;try{domain=self?"self."+e.category:new URI(url).getHost();}catch(Exception bad){domain="";}
   d.put("title",e.title).put("url",url).put("domain",domain).put("is_self",self).put("selftext",plain(html)).put("selftext_html",html.isEmpty()?JSONObject.NULL:esc(html)).put("thumbnail",publicUrl(e.thumbnail)).put("num_comments",-1).put("score",0).put("ups",0).put("downs",0).put("hide_score",true);
  }else{
   d.put("body",plain(html)).put("body_html",esc(html)).put("link_id",postId).put("parent_id",postId).put("replies","").put("ups",0).put("downs",0);
  }
  return new JSONObject().put("kind",kind).put("data",d);
 }
 static JSONObject listing(JSONArray children,String after)throws Exception{return new JSONObject().put("kind","Listing").put("data",new JSONObject().put("children",children).put("after",after==null?JSONObject.NULL:after).put("before",JSONObject.NULL).put("modhash",""));}
 static Object convert(Feed f,boolean comments,int limit)throws Exception {
  JSONArray posts=new JSONArray(),replies=new JSONArray();String postId="",last=null;
  for(Entry e:f.entries){if(e.id.startsWith("t3_")){if(!comments&&posts.length()>=limit)break;postId=e.id;posts.put(thing(e,""));last=e.id;}else if(!postId.isEmpty()&&replies.length()<limit)replies.put(thing(e,postId));}
  if(comments){if(posts.length()==0)throw new IOException("Post absent from public feed");return new JSONArray().put(listing(posts,null)).put(listing(replies,null));}
  return listing(posts,posts.length()>=limit?last:null);
 }
 Object response(URI u)throws Exception {
  if(u.isAbsolute()||u.getRawAuthority()!=null||u.getFragment()!=null)throw new Failure(400,"Invalid feed target");
  String p=u.getPath();if(p==null||p.length()>1000||p.contains("..")||p.contains("%"))throw new Failure(400,"Invalid feed path");
  p=p.replaceFirst("\\.json$","");if(p.isEmpty())p="/";
  boolean comments=p.matches("(?:/r/[A-Za-z0-9_+]{1,120})?/comments/[a-z0-9]+(?:/[A-Za-z0-9_/-]*)?");
  boolean about=p.matches("/r/[A-Za-z0-9_]{1,40}/about/?");
  boolean communities=p.matches("/(?:subreddits|reddits)(?:/(?:default|popular|new))?/?");
  boolean search=p.matches("(?:/r/[A-Za-z0-9_+]{1,120})?/search/?");
  if(!(comments||about||communities||search||p.matches("(?:/r/[A-Za-z0-9_+]{1,120})?(?:/(?:hot|new|top|controversial|rising))?/?")))throw new Failure(404,"Not available in public feeds");
  int limit=25;try{limit=Integer.parseInt(parameter(u,"limit"));}catch(Exception ignored){}limit=Math.max(1,Math.min(comments?100:50,limit));
  String after=parameter(u,"after");if(!after.isEmpty()&&!after.matches("t3_[a-z0-9]+"))throw new Failure(400,"Invalid next page");
  String q=parameter(u,"q");if(q.length()>200)throw new Failure(400,"Search too long");
  String query="limit="+(comments?100:50);if(!after.isEmpty())query+="&after="+enc(after);
  if(search){if(q.isEmpty())throw new Failure(400,"Enter a search");query+="&q="+enc(q);if("on".equals(parameter(u,"restrict_sr"))||"true".equals(parameter(u,"restrict_sr")))query+="&restrict_sr=on";}
  for(String key:new String[]{"sort","t"}){String v=parameter(u,key);if(!v.isEmpty()){if(!v.matches("[a-z]{1,20}"))throw new Failure(400,"Invalid sort");query+="&"+key+"="+v;}}
  String base=communities?"/":about?p.replaceFirst("/about/?$",""):p;
  if(!comments&&!search)base=base.replaceFirst("/hot/?$","");
  String rss=search?base.replaceFirst("/$","")+".rss":base.replaceFirst("/$","")+"/.rss";
  Feed f=fetch(rss,query);
  if(about)return subreddit(f.category.isEmpty()?p.split("/")[2]:f.category,f.title,f.subtitle);
  if(communities){JSONArray a=new JSONArray();Set<String> seen=new HashSet<String>();for(Entry e:f.entries)if(!e.category.isEmpty()&&seen.add(e.category))a.put(subreddit(e.category,e.category,""));return listing(a,null);}
  return convert(f,comments,limit);
 }
 static JSONObject subreddit(String name,String title,String description)throws Exception{return new JSONObject().put("kind","t5").put("data",new JSONObject().put("display_name",name).put("title",title).put("url","/r/"+name+"/").put("description",description).put("public_description",description).put("subreddit_type","public").put("user_is_subscriber",false));}
 void serve(InputStream in,OutputStream out)throws Exception {
  try{
   String header=new String(Wire.header(in),"ISO-8859-1");String[] request=header.substring(0,header.indexOf("\r\n")).split(" ");
   if(request.length!=3||!request[0].equals("GET"))throw new Failure(405,"Browsing only");
   URI u=new URI(request[1]);if(u.isAbsolute()||u.getRawAuthority()!=null)throw new Failure(400,"Invalid request target");
   String result=response(u).toString();SearchEndpoint.reply(out,200,"application/json; charset=utf-8",result);android.util.Log.i("GalaxyReddit","Public feed delivered");
  }catch(Failure e){SearchEndpoint.reply(out,e.status,"application/json",new JSONObject().put("error",e.status).put("message",e.getMessage()).toString());android.util.Log.i("GalaxyReddit","Public feed status "+e.status);}
  catch(Exception e){SearchEndpoint.reply(out,502,"application/json",new JSONObject().put("error",502).put("message","Public feed unavailable").toString());android.util.Log.w("GalaxyReddit","Public feed error: "+e.getClass().getSimpleName());}
 }
}
