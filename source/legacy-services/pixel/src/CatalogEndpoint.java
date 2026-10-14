package org.retrobridge.galaxyservices.pixel;
import android.content.Context;import android.content.pm.*;import org.retrobridge.galaxyservices.Wire;import java.io.*;import java.net.*;import javax.net.ssl.HttpsURLConnection;import java.security.*;import java.util.*;import org.json.*;
/** Pinned installers served over the paired TLS link with bounded memory. */
final class CatalogEndpoint {
 static final String HOST="catalog.galaxy-services.invalid";
 static final long MAX_APK=1000000000L;
 static byte[] bytes(InputStream in,int limit)throws Exception{try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Catalog file too large");out.write(b,0,n);}return out.toByteArray();}finally{in.close();}}
 static String hex(byte[] data){StringBuilder s=new StringBuilder();for(byte b:data)s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
 static String hash(byte[] data)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(data));}
 static String fileHash(File file)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return hex(d.digest());}
 static JSONObject find(Context c,String pkg)throws Exception{JSONObject a=CatalogLibrary.existing(c,pkg);if(a==null)throw new IOException("App unavailable");return a;}
 static synchronized File apk(Context c,JSONObject app)throws Exception{
  long size=app.getLong("size");String expected=app.getString("sha256");if(size<=0||size>MAX_APK||!expected.matches("[a-f0-9]{64}"))throw new IOException("Invalid catalog entry");
  File dir=new File(c.getFilesDir(),"catalog-apks");dir.mkdirs();File f=new File(dir,expected+".apk");
  if(f.isFile()){if(f.length()==size&&fileHash(f).equals(expected))return f;if(!f.delete())throw new IOException("Cannot replace damaged installer");}
  // Owner-selected archive copies are preloaded, never fetched from arbitrary URLs.
  if(app.optBoolean("cachedOnly"))throw new IOException("Installer missing from Pixel library");
  URL u=new URL(app.getString("url"));if(!u.getProtocol().equals("https")||!u.getHost().equals("f-droid.org")||!u.getPath().startsWith("/archive/")||u.getUserInfo()!=null||u.getPort()!=-1)throw new IOException("Unapproved catalog source");
  HttpsURLConnection conn=(HttpsURLConnection)u.openConnection();conn.setConnectTimeout(15000);conn.setReadTimeout(60000);conn.setInstanceFollowRedirects(false);
  File tmp=new File(dir,f.getName()+".part");
  try{
   if(conn.getResponseCode()!=200)throw new IOException("Catalog source unavailable");
   try(InputStream in=conn.getInputStream();FileOutputStream out=new FileOutputStream(tmp)){byte[] b=new byte[32768];int n;long count=0;while((n=in.read(b))!=-1){count+=n;if(count>size)throw new IOException("Catalog file too large");out.write(b,0,n);}if(count!=size)throw new IOException("Incomplete catalog file");out.getFD().sync();}
   if(!fileHash(tmp).equals(expected))throw new IOException("Catalog checksum mismatch");
   PackageInfo pi=c.getPackageManager().getPackageArchiveInfo(tmp.toString(),PackageManager.GET_SIGNATURES);
   if(pi==null||!app.getString("package").equals(pi.packageName)||pi.versionCode!=app.getInt("versionCode")||pi.signatures==null||pi.signatures.length!=1||!hash(pi.signatures[0].toByteArray()).equals(app.getString("signerSha256")))throw new IOException("Catalog signature mismatch");
   if(!tmp.renameTo(f))throw new IOException("Cannot cache catalog app");return f;
  }finally{conn.disconnect();tmp.delete();}
 }
 static void serve(Context c,InputStream in,OutputStream out)throws Exception{
  byte[] data=null;File file=null;String type="application/json";int code=200;
  try{String[] lines=new String(Wire.header(in),"US-ASCII").split("\r\n"),first=lines[0].split(" ");if(first.length!=3||!first[0].equals("GET"))throw new IOException("Invalid catalog request");String host="";for(String h:lines)if(h.toLowerCase(Locale.US).startsWith("host:"))host=h.substring(5).trim();if(!HOST.equals(host))throw new IOException("Invalid catalog host");String path=first[1];
   if(path.equals("/v1/catalog"))data=CatalogLibrary.catalog(c).toString().getBytes("UTF-8");
   else if(path.startsWith("/icon/")&&path.substring(6).matches("[a-zA-Z0-9_.]+\\.png")){String name=path.substring(6),pkg=name.substring(0,name.length()-4);find(c,pkg);File icon=CatalogLibrary.iconFile(c,pkg);data=bytes(icon.isFile()?new FileInputStream(icon):c.getAssets().open("catalog/"+name),200000);type="image/png";}
   else if(path.startsWith("/shot/")&&path.substring(6).matches("[a-zA-Z0-9_.]+\\.png")){String name=path.substring(6);find(c,name.substring(0,name.length()-4));data=bytes(c.getAssets().open("catalog/"+name.substring(0,name.length()-4)+"-shot.png"),1200000);type="image/png";}
   else if(path.startsWith("/apk/")&&path.substring(5).matches("[a-zA-Z0-9_.]+")){file=apk(c,find(c,path.substring(5)));type="application/vnd.android.package-archive";}
   else throw new IOException("Catalog item unavailable");
  }catch(Exception e){code=503;file=null;data="{\"error\":\"Catalog unavailable\"}".getBytes("UTF-8");}
  long length=file!=null?file.length():data.length;
  out.write(("HTTP/1.1 "+code+(code==200?" OK":" Service Unavailable")+"\r\nContent-Type: "+type+"\r\nContent-Length: "+length+"\r\nCache-Control: private, max-age=300\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
  if(file!=null){try(InputStream source=new FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=source.read(b))!=-1)out.write(b,0,n);}}else out.write(data);out.flush();
 }
}
