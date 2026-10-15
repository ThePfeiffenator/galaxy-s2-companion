package org.retrobridge.galaxyservices.pixel;

import android.content.Context;
import android.graphics.*;
import android.util.Xml;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import org.xmlpull.v1.XmlPullParser;
import org.json.*;
import org.retrobridge.galaxyservices.Wire;

/** Stock AP Mobile protocol, with clearly attributed NASA science/space feeds. */
final class NewsEndpoint {
    static final String HOST="samsungrss.vrvm.com";
    static final String BASE="http://"+HOST;
    final Context app;
    final File cache;
    NewsEndpoint(Context c){app=c;cache=new File(c.getFilesDir(),"science-news.json");}
    static String text(String html){return android.text.Html.fromHtml(html,0).toString().replaceAll("\\s+"," ").trim();}
    static String shortText(String s,int max){return s.length()>max?s.substring(0,max):s;}
    static boolean nasa(URL u){return u.getProtocol().equals("https")&&(u.getPort()==-1||u.getPort()==443)&&u.getUserInfo()==null&&(u.getHost().equals("nasa.gov")||u.getHost().endsWith(".nasa.gov"));}
    static byte[] fetch(String url,int max)throws Exception{
        for(int redirect=0;redirect<4;redirect++){
            URL u=new URL(url);if(!nasa(u))throw new IOException("News source");
            HttpURLConnection c=(HttpURLConnection)u.openConnection(Proxy.NO_PROXY);
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","GalaxyCompanion/1.0 personal RSS reader");
            try{int status=c.getResponseCode();if(status>=300&&status<400){url=new URL(u,c.getHeaderField("Location")).toString();continue;}if(status!=200)throw new IOException("News HTTP "+status);
                ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(b.size()+n>max)throw new IOException("News size");b.write(buf,0,n);}}return b.toByteArray();
            }finally{c.disconnect();}
        }throw new IOException("News redirect");
    }
    static JSONArray parse(byte[] bytes)throws Exception{
        XmlPullParser x=Xml.newPullParser();x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);x.setInput(new ByteArrayInputStream(bytes),"UTF-8");
        JSONArray rows=new JSONArray();JSONObject item=null;String tag="";StringBuilder value=new StringBuilder();
        for(int event=x.getEventType();event!=XmlPullParser.END_DOCUMENT;event=x.next()){
            if(event==XmlPullParser.START_TAG){String n=x.getName();if(n.equals("item"))item=new JSONObject();
                if(item!=null&&(n.equals("title")||n.equals("link")||n.equals("description")||n.equals("encoded")||n.equals("pubDate"))){tag=n;value.setLength(0);}
                if(item!=null&&(n.equals("content")||n.equals("thumbnail"))){String u=x.getAttributeValue(null,"url");if(u!=null&&nasa(new URL(u)))item.put("image",u);}
            }else if(event==XmlPullParser.TEXT&&item!=null&&tag.length()>0){if(value.length()<200000)value.append(x.getText());}
            else if(event==XmlPullParser.END_TAG&&item!=null){String n=x.getName();
                if(n.equals(tag)){String v=value.toString();if(n.equals("encoded")||n.equals("description")){
                        if(!item.has("image")){java.util.regex.Matcher m=java.util.regex.Pattern.compile("<img[^>]+src=[\"']([^\"']+)").matcher(v);if(m.find()){String u=m.group(1).replace("&amp;","&");try{if(nasa(new URL(u)))item.put("image",u);}catch(Exception ignored){}}}
                        if(n.equals("encoded")||!item.has("body"))item.put("body",shortText(text(v),16000));
                    }else item.put(n,shortText(text(v),n.equals("title")?512:2048));tag="";}
                if(n.equals("item")){if(item.has("title")&&item.has("link")&&nasa(new URL(item.getString("link")))){
                        item.put("id",Wire.hex(MessageDigest.getInstance("SHA-256").digest(item.getString("link").getBytes("UTF-8"))));rows.put(item);
                    }item=null;if(rows.length()>=20)break;}
            }
        }
        if(rows.length()==0)throw new IOException("Empty news feed");return rows;
    }
    synchronized JSONArray items()throws Exception{
        JSONObject old=null;try{if(cache.length()<1500000)old=new JSONObject(new String(Wire.readFile(cache),"UTF-8"));}catch(Exception ignored){}
        if(old!=null&&System.currentTimeMillis()-old.optLong("fetched")<1800000)return old.getJSONArray("items");
        try{
            JSONArray result=new JSONArray();Set<String> ids=new HashSet<>();
            for(String feed:new String[]{"https://www.nasa.gov/centers-and-facilities/jpl/feed/","https://www.nasa.gov/news-release/feed/"}){
                try{JSONArray rows=parse(fetch(feed,2000000));for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if(ids.add(r.getString("id")))result.put(r);}}catch(Exception e){if(result.length()==0)continue;}
            }
            if(result.length()==0)throw new IOException("News unavailable");
            JSONObject saved=new JSONObject().put("fetched",System.currentTimeMillis()).put("items",result);
            File tmp=new File(cache+".new");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(saved.toString().getBytes("UTF-8"));out.getFD().sync();}if(!tmp.renameTo(cache))throw new IOException("News cache");
            Wire.writeFile(new File(app.getFilesDir(),"news-status.txt"),(result.length()+" science & space headlines").getBytes("UTF-8"));return result;
        }catch(Exception e){if(old!=null)return old.getJSONArray("items");throw e;}
    }
    static void send(OutputStream out,String mime,byte[] bytes)throws Exception{out.write(("HTTP/1.1 200 OK\r\nContent-Type: "+mime+"\r\nContent-Length: "+bytes.length+"\r\nCache-Control: max-age=900\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));out.write(bytes);out.flush();}
    void serve(URI uri,OutputStream out)throws Exception{
        String path=uri.getPath();
        if(path.equals("/capi/ap/hierarchy")){String xml="<?xml version=\"1.0\"?><opml version=\"1.0\"><body><outline name=\"Science &amp; Space\" id=\"1\" adCategoryId=\"0\" partnerModuleId=\"0\" headline=\"true\" xmlUrl=\""+BASE+"/science.xml\"/></body></opml>";send(out,"text/xml; charset=UTF-8",xml.getBytes("UTF-8"));return;}
        JSONArray rows=items();
        if(path.matches("/image/[0-9a-f]{64}\\.jpg")){
            String id=path.substring(7,path.length()-4);File f=new File(app.getCacheDir(),"news-"+id+".jpg");
            if(!f.isFile()){JSONObject row=null;for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("id").equals(id))row=rows.getJSONObject(i);if(row==null||!row.has("image"))throw new IOException("News image unavailable");
                byte[] b=fetch(row.getString("image"),6000000);BitmapFactory.Options opt=new BitmapFactory.Options();opt.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(b,0,b.length,opt);if(opt.outWidth<1||opt.outHeight<1)throw new IOException("News image");opt.inSampleSize=1;while(Math.max(opt.outWidth,opt.outHeight)/opt.inSampleSize>640)opt.inSampleSize*=2;opt.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeByteArray(b,0,b.length,opt);if(bitmap==null)throw new IOException("News image");try(FileOutputStream dst=new FileOutputStream(f)){bitmap.compress(Bitmap.CompressFormat.JPEG,82,dst);}finally{bitmap.recycle();}
                File[] files=app.getCacheDir().listFiles((d,n)->n.startsWith("news-")&&n.endsWith(".jpg"));if(files!=null&&files.length>80){Arrays.sort(files,Comparator.comparingLong(File::lastModified));for(int i=0;i<files.length-60;i++)files[i].delete();}
            }send(out,"image/jpeg",Wire.readFile(f));return;
        }
        if(!path.equals("/science.xml"))throw new IOException("Unknown news route");
        StringBuilder xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\" xmlns:content=\"http://purl.org/rss/1.0/modules/content/\" xmlns:media=\"http://search.yahoo.com/mrss/\"><channel><title>Science &amp; Space — NASA</title>");
        for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);xml.append("<item>").append(Weather.tag("title",r.getString("title"))).append(Weather.tag("link",r.getString("link"))).append(Weather.tag("pubDate",r.optString("pubDate"))).append(Weather.tag("content:encoded","NASA\n\n"+r.optString("body")));
            // Samsung appends &width=... without adding a question mark.
            if(r.has("image"))xml.append("<media:content url=\"").append(BASE).append("/image/").append(r.getString("id")).append(".jpg?source=nasa\"/>");xml.append("</item>");}
        xml.append("</channel></rss>");send(out,"text/xml; charset=UTF-8",xml.toString().getBytes("UTF-8"));
    }
}
