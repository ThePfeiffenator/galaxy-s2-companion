package org.retrobridge.galaxyservices.pixel;

import android.content.Context;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

/** Translates current Open-Meteo data into the stock Samsung widget's XML. */
final class Weather {
 static final String HOST="samsungmobile.accu-weather.com";
 final Context app;
 final LinkedHashMap<String,Cache> cache=new LinkedHashMap<String,Cache>();
 static final class Cache {long time;String xml;Cache(String x){time=System.currentTimeMillis();xml=x;}}
 Weather(Context c){app=c;}
 static String esc(Object value){return String.valueOf(value).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
 static String tag(String k,Object v){return "<"+k+">"+esc(v)+"</"+k+">";}
 static JSONObject json(String url)throws Exception {
  HttpsURLConnection c=(HttpsURLConnection)new URL(url).openConnection(java.net.Proxy.NO_PROXY);
  c.setConnectTimeout(15000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","GalaxyServices/0.1 personal weather companion");
  try{if(c.getResponseCode()!=200)throw new IOException("Weather provider HTTP "+c.getResponseCode());ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(b.size()+n>1048576)throw new IOException("Weather response too large");b.write(buf,0,n);}}return new JSONObject(b.toString("UTF-8"));}finally{c.disconnect();}
 }
 static double coordinate(String s,double limit){double n=Double.parseDouble(s);if(Double.isNaN(n)||Double.isInfinite(n)||Math.abs(n)>limit)throw new IllegalArgumentException("Coordinate");return n;}
 static String key(JSONObject city)throws Exception {return city.has("id")?"OM"+city.getLong("id"):"GEO_"+city.getDouble("latitude")+"_"+city.getDouble("longitude");}
 JSONObject city(String id)throws Exception {
  if(id==null)throw new IOException("Select a city");
  if(id.matches("OM[0-9]{1,12}"))return json("https://geocoding-api.open-meteo.com/v1/get?id="+id.substring(2));
  if(id.startsWith("GEO_")){String[] a=id.split("_");if(a.length!=3)throw new IOException("Location");return new JSONObject().put("name","Current location").put("admin1","Local").put("country","Local").put("latitude",coordinate(a[1],90)).put("longitude",coordinate(a[2],180));}
  throw new IOException("Reselect city to use current weather");
 }
 static String state(JSONObject c){String country=c.optString("country","Unknown"),region=c.optString("admin1",country);return "US".equals(c.optString("country_code"))?region:country+" ("+region+")";}
 String search(Uri u)throws Exception {
  JSONArray result;
  if(u.getQueryParameter("latitude")!=null){JSONObject c=new JSONObject().put("name","Current location").put("admin1","Local").put("country","Local").put("latitude",coordinate(u.getQueryParameter("latitude"),90)).put("longitude",coordinate(u.getQueryParameter("longitude"),180));result=new JSONArray().put(c);}
  else {String query=u.getQueryParameter("location");if(query==null||query.length()<2||query.length()>160)throw new IOException("City search");result=json("https://geocoding-api.open-meteo.com/v1/search?name="+URLEncoder.encode(query,"UTF-8")+"&count=20&language=en&format=json").optJSONArray("results");if(result==null)result=new JSONArray();}
  StringBuilder x=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><citylist intl=\"1\">");
  for(int i=0;i<result.length();i++){JSONObject c=result.getJSONObject(i);x.append("<location cnt=\"1\" city=\"").append(esc(c.getString("name"))).append("\" state=\"").append(esc(state(c))).append("\" location=\"").append(esc(key(c))).append("\" latitude=\"").append(c.getDouble("latitude")).append("\" longitude=\"").append(c.getDouble("longitude")).append("\"/>");}return x.append("</citylist>").toString();
 }
 static int icon(int code,boolean day){switch(code){case 0:return day?1:33;case 1:return day?2:34;case 2:return day?3:35;case 3:return 7;case 45:case 48:return 11;case 51:case 53:case 55:case 80:case 81:case 82:return 12;case 56:case 57:case 66:case 67:return 26;case 61:case 63:case 65:return 18;case 71:case 73:case 75:case 77:case 85:case 86:return 22;case 95:case 96:case 99:return 15;default:throw new IllegalArgumentException("Unsupported weather code "+code);}}
 static String description(int code){if(code==0)return "Clear";if(code==1)return "Mostly clear";if(code==2)return "Partly cloudy";if(code==3)return "Overcast";if(code==45||code==48)return "Fog";if(code>=51&&code<=57)return "Drizzle";if(code>=61&&code<=67)return "Rain";if(code>=71&&code<=77)return "Snow";if(code>=80&&code<=82)return "Rain showers";if(code==85||code==86)return "Snow showers";if(code>=95)return "Thunderstorms";throw new IllegalArgumentException("Unknown weather");}
 String forecast(Uri u)throws Exception {
  JSONObject c=city(u.getQueryParameter("location"));boolean metric=!"0".equals(u.getQueryParameter("metric"));
  double lat=coordinate(c.getString("latitude"),90),lon=coordinate(c.getString("longitude"),180);
  JSONObject w=json("https://api.open-meteo.com/v1/forecast?latitude="+lat+"&longitude="+lon+"&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,weather_code&timezone=auto&forecast_days=7&temperature_unit="+(metric?"celsius":"fahrenheit"));
  JSONObject current=w.getJSONObject("current"),daily=w.getJSONObject("daily");int offset=w.getInt("utc_offset_seconds")/60;String tz=String.format(Locale.US,"%s%02d:%02d",offset<0?"-":"",Math.abs(offset)/60,Math.abs(offset)%60);
  String time=current.getString("time").substring(11,16),url="https://open-meteo.com/";
  StringBuilder x=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><weather>");
  x.append("<local>").append(tag("city",c.getString("name"))).append(tag("state",state(c))).append(tag("lat",lat)).append(tag("lon",lon)).append(tag("currentGmtOffset",tz)).append(tag("time",time)).append("</local><currentconditions>");
  x.append(tag("temperature",current.getDouble("temperature_2m"))).append(tag("weathertext",description(current.getInt("weather_code")))).append(tag("weathericon",icon(current.getInt("weather_code"),current.getInt("is_day")==1))).append(tag("url",url)).append("</currentconditions><forecast>");
  JSONArray hi=daily.getJSONArray("temperature_2m_max"),lo=daily.getJSONArray("temperature_2m_min"),codes=daily.getJSONArray("weather_code");
  if(hi.length()<7||lo.length()<7||codes.length()<7)throw new IOException("Incomplete forecast");
  for(int i=0;i<7;i++)x.append("<day><daytime>").append(tag("hightemperature",hi.getDouble(i))).append(tag("lowtemperature",lo.getDouble(i))).append(tag("weathericon",icon(codes.getInt(i),true))).append(tag("url",url)).append("</daytime></day>");
  return x.append("</forecast></weather>").toString();
 }
 synchronized String xml(URI uri)throws Exception {
  String id=uri.toASCIIString();Cache old=cache.get(id);if(old!=null&&System.currentTimeMillis()-old.time<600000)return old.xml;
  Uri u=Uri.parse(id);String x;if("/widget/samsungmobile/city-find.asp".equals(uri.getPath()))x=search(u);else if("/widget/samsungmobile/weather-data.asp".equals(uri.getPath()))x=forecast(u);else throw new IOException("Unsupported legacy weather request");
  if(cache.size()>=32)cache.remove(cache.keySet().iterator().next());cache.put(id,new Cache(x));return x;
 }
 void serve(URI uri,OutputStream out)throws Exception {String x=xml(uri);byte[] data=x.getBytes("UTF-8");out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=UTF-8\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));out.write(data);out.flush();}
}
