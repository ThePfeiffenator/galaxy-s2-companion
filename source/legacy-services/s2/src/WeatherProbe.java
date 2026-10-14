package org.retrobridge.galaxyservices.s2;
import android.content.Context;import java.io.*;import java.net.*;import java.util.*;import org.xml.sax.InputSource;import org.retrobridge.galaxyservices.Wire;
/** Executes the unmodified Samsung parser under our UID; never writes widget data. */
final class WeatherProbe {
 static String fetch(String path)throws Exception {HttpURLConnection c=(HttpURLConnection)new URL("http://samsungmobile.accu-weather.com/widget/samsungmobile/"+path).openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",Wire.LOCAL)));c.setConnectTimeout(20000);c.setReadTimeout(45000);try{if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());ByteArrayOutputStream b=new ByteArrayOutputStream();Wire.copy(c.getInputStream(),b);return b.toString("UTF-8");}finally{c.disconnect();}}
 static Object get(Object o,String name)throws Exception{return o.getClass().getMethod(name).invoke(o);}
 static void run(Context app){StringBuilder result=new StringBuilder();try{
  Object cm=app.getSystemService(Context.CONNECTIVITY_SERVICE);result.append("LegacyProxy=").append(android.net.Proxy.getHost(app)).append(':').append(android.net.Proxy.getPort(app)).append(" Effective=").append(cm.getClass().getMethod("getProxy").invoke(cm)).append('\n');
  Context stock=app.createPackageContext("com.sec.android.widgetapp.weatherclock",Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
  Class<?> parser=stock.getClassLoader().loadClass("com.sec.android.widgetapp.weatherclock.provider.accuweather.AccuWeatherResponseParser");Object p=parser.newInstance();
  String xml=fetch("city-find.asp?returnGeoPosition=1&location=London");List<?> cities=(List<?>)parser.getMethod("parseCityList",String.class).invoke(p,xml);if(cities==null||cities.isEmpty())throw new IOException("No cities parsed");result.append("Cities=").append(cities.size()).append('\n');
  Object city=cities.get(0);String id=(String)get(city,"getLocation");result.append("City=").append(get(city,"getCity")).append('\n');
  for(int metric=0;metric<=1;metric++){
   String forecast=fetch("weather-data.asp?metric="+metric+"&location="+URLEncoder.encode(id,"UTF-8"));
   Object info=parser.getMethod("parseDetailWeather",int.class,InputSource.class,String.class).invoke(p,metric,new InputSource(new StringReader(forecast)),String.valueOf(System.currentTimeMillis()));if(info==null)throw new IOException("Null forecast");
   Object today=get(info,"getTodayWeatherInfo");int count=(Integer)get(info,"getForcastSize");if(today==null||count!=6)throw new IOException("Incomplete parsed forecast");
   result.append("Metric=").append(metric).append(" Current=").append(get(today,"getCurrentTemp")).append(" High=").append(get(today,"getHighTemp")).append(" Low=").append(get(today,"getLowTemp")).append(" Icon=").append(get(today,"getIconNum")).append(" Days=").append(count).append('\n');
  }
  result.append("PASS\n");
 }catch(Throwable e){result.append("FAILED ").append(e).append(" cause=").append(e.getCause()).append('\n');}try{Wire.writeFile(new File(app.getFilesDir(),"weather-probe.txt"),result.toString().getBytes("UTF-8"));}catch(Exception ignored){}}
}
