package org.retrobridge.galaxyservices.pixel;

import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Private, owner-managed installers. No installation on the Pixel and no public upload. */
final class CatalogLibrary {
 static File directory(Context c){File f=new File(c.getFilesDir(),"store-library");f.mkdirs();return f;}
 static JSONArray personal(Context c)throws Exception{
  AtomicFile a=new AtomicFile(new File(directory(c),"library.json"));
  if(!a.getBaseFile().exists())return new JSONArray();
  return new JSONObject(new String(CatalogEndpoint.bytes(a.openRead(),1000000),"UTF-8")).getJSONArray("apps");
 }
 static synchronized JSONObject catalog(Context c)throws Exception{
  JSONObject base=new JSONObject(new String(CatalogEndpoint.bytes(c.getAssets().open("catalog/catalog.json"),1000000),"UTF-8"));
  LinkedHashMap<String,JSONObject> all=new LinkedHashMap<>();JSONArray built=base.getJSONArray("apps");
  for(int i=0;i<built.length();i++)all.put(built.getJSONObject(i).getString("package"),built.getJSONObject(i));
  JSONArray own=personal(c);for(int i=0;i<own.length();i++)all.put(own.getJSONObject(i).getString("package"),own.getJSONObject(i));
  JSONArray merged=new JSONArray();for(JSONObject app:all.values())merged.put(app);base.put("apps",merged);return base;
 }
 static File iconFile(Context c,String pkg){return new File(directory(c),pkg+".png");}
 static JSONObject existing(Context c,String pkg)throws Exception{JSONArray a=catalog(c).getJSONArray("apps");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("package").equals(pkg))return a.getJSONObject(i);return null;}
 static File stage(Context c,Uri uri)throws Exception{
  File f=File.createTempFile("store-import-",".apk",c.getCacheDir());boolean complete=false;
  try(InputStream in=c.getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(f)){
   if(in==null)throw new IOException("Cannot open this file");byte[] b=new byte[32768];long count=0;int n;
   while((n=in.read(b))!=-1){count+=n;if(count>CatalogEndpoint.MAX_APK)throw new IOException("APK is larger than 1 GB");out.write(b,0,n);}if(count==0)throw new IOException("The file is empty");out.getFD().sync();complete=true;
  }finally{if(!complete)f.delete();}return f;
 }
 static JSONObject inspect(Context c,File f)throws Exception{
  PackageManager pm=c.getPackageManager();PackageInfo pi=pm.getPackageArchiveInfo(f.toString(),PackageManager.GET_SIGNATURES|PackageManager.GET_ACTIVITIES);
  if(pi==null||pi.applicationInfo==null||pi.signatures==null||pi.signatures.length!=1)throw new IOException("Cannot read this APK and its signing certificate");
  if(!pi.packageName.matches("[a-zA-Z0-9_.]+"))throw new IOException("Invalid package name");
  if(pi.applicationInfo.minSdkVersion>15)throw new IOException("This app requires Android newer than 4.0.4");
  Set<String> abis=new TreeSet<>();boolean split=false,signatureFile=false,signatureBlock=false;
  try(ZipFile zip=new ZipFile(f)){Enumeration<? extends ZipEntry> entries=zip.entries();while(entries.hasMoreElements()){String name=entries.nextElement().getName();String[] pieces=name.split("/");if(pieces.length>=3&&pieces[0].equals("lib")&&name.endsWith(".so"))abis.add(pieces[1]);String upper=name.toUpperCase(Locale.ROOT);if(upper.startsWith("META-INF/")){if(upper.endsWith(".SF"))signatureFile=true;if(upper.endsWith(".RSA")||upper.endsWith(".DSA")||upper.endsWith(".EC"))signatureBlock=true;}}split=pi.splitNames!=null&&pi.splitNames.length>0;}
  if(!signatureFile||!signatureBlock)throw new IOException("This APK lacks the older signature format required by the S2");
  if(split)throw new IOException("Choose a complete APK, not a split package");
  if(!abis.isEmpty()&&!abis.contains("armeabi")&&!abis.contains("armeabi-v7a"))throw new IOException("This APK does not contain S2-compatible ARM code");
  ApplicationInfo ai=pi.applicationInfo;ai.sourceDir=f.toString();ai.publicSourceDir=f.toString();String name=ai.loadLabel(pm).toString();
  JSONObject a=new JSONObject();a.put("package",pi.packageName);a.put("name",name);a.put("version",pi.versionName==null?String.valueOf(pi.versionCode):pi.versionName);a.put("versionCode",pi.versionCode);a.put("minSdk",ai.minSdkVersion);a.put("size",f.length());a.put("sha256",CatalogEndpoint.fileHash(f));a.put("signerSha256",CatalogEndpoint.hash(pi.signatures[0].toByteArray()));a.put("abis",new JSONArray(abis));
  a.put("developer","Your library");a.put("description","Added to your Pixel library.");a.put("license","Owner-provided APK");a.put("sourceLabel","Stored on your Pixel.");a.put("category",(ai.flags&ApplicationInfo.FLAG_IS_GAME)!=0?"Games":"Apps");a.put("cachedOnly",true);a.put("personalLibrary",true);a.put("remoteIcon",true);a.put("screenshot",false);a.put("source","");a.put("url","");
  return a;
 }
 static synchronized void commit(Context c,File staged,JSONObject a)throws Exception{
  // Inspect again before committing: the UI preview alone is not the trust boundary.
  JSONObject actual=inspect(c,staged);if(!actual.getString("sha256").equals(a.getString("sha256")))throw new IOException("APK changed during import");
  File cache=new File(c.getFilesDir(),"catalog-apks");cache.mkdirs();File target=new File(cache,a.getString("sha256")+".apk");
  if(target.isFile()&&!CatalogEndpoint.fileHash(target).equals(a.getString("sha256")))throw new IOException("Existing library file is damaged");
  PackageInfo pi=c.getPackageManager().getPackageArchiveInfo(staged.toString(),0);pi.applicationInfo.sourceDir=staged.toString();pi.applicationInfo.publicSourceDir=staged.toString();Drawable drawable=pi.applicationInfo.loadIcon(c.getPackageManager());
  Bitmap bitmap=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);drawable.setBounds(0,0,96,96);drawable.draw(new Canvas(bitmap));
  AtomicFile icon=new AtomicFile(iconFile(c,a.getString("package")));FileOutputStream image=null;
  try{image=icon.startWrite();bitmap.compress(Bitmap.CompressFormat.PNG,100,image);icon.finishWrite(image);}catch(Exception e){if(image!=null)icon.failWrite(image);throw e;}finally{bitmap.recycle();}
  if(!target.exists()&&!staged.renameTo(target))throw new IOException("Cannot store installer");
  JSONArray next=new JSONArray(),old=personal(c);for(int i=0;i<old.length();i++)if(!old.getJSONObject(i).getString("package").equals(a.getString("package")))next.put(old.getJSONObject(i));next.put(a);
  AtomicFile manifest=new AtomicFile(new File(directory(c),"library.json"));FileOutputStream output=null;
  try{output=manifest.startWrite();output.write(new JSONObject().put("apps",next).toString(2).getBytes("UTF-8"));manifest.finishWrite(output);}catch(Exception e){if(output!=null)manifest.failWrite(output);throw e;}
  staged.delete();
 }
}
