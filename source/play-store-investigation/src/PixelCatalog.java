package org.retrobridge.play2012;
import android.app.*;import android.content.*;import android.content.pm.*;import android.accounts.Account;import android.net.Uri;import android.os.*;import org.json.*;import java.io.*;import java.net.*;import javax.net.ssl.HttpsURLConnection;import java.lang.reflect.*;import java.util.*;

/** Original Store models. Browsing stays local; verified installers come from the Pixel. */
public final class PixelCatalog {
 static final String HOST="catalog.galaxy-services.invalid",PROTO="com.google.android.finsky.remoting.protos.";
 static JSONArray apps;
 public static Account account(){return new Account("My apps","org.retrobridge.play2012.catalog");}
 static Object app()throws Exception{return Class.forName("com.google.android.finsky.FinskyApp").getMethod("get").invoke(null);}
 static Context context()throws Exception{return (Context)app();}
 static Object make(String n)throws Exception{return Class.forName(PROTO+n).newInstance();}
 static Object invoke(Object o,String name,Object... args)throws Exception{for(Class<?> k=o.getClass();k!=null;k=k.getSuperclass())for(Method m:k.getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterTypes().length==args.length){m.setAccessible(true);try{return m.invoke(o,args);}catch(IllegalArgumentException ignored){}}throw new NoSuchMethodException(name);}
 static Object set(Object o,String name,Object v)throws Exception{invoke(o,name,v);return o;}
 interface Progress{void update(int bytes)throws IOException;}
 static byte[] read(InputStream in,int limit)throws Exception{try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Catalog too large");out.write(b,0,n);}return out.toByteArray();}finally{in.close();}}
 static byte[] get(String path,int limit)throws Exception{return get(path,limit,null);}
 static byte[] remote(String path,int limit)throws Exception{
  if(!path.equals("/v1/catalog")&&!path.matches("/icon/[a-zA-Z0-9_.]+\\.png"))throw new IOException("Invalid library route");
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://"+HOST+path).openConnection(new java.net.Proxy(java.net.Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",8766)));c.setConnectTimeout(4000);c.setReadTimeout(8000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
  try{if(c.getResponseCode()!=200)throw new IOException("Pixel library unavailable");return read(c.getInputStream(),limit);}finally{c.disconnect();}
 }
 static JSONArray validate(byte[] data)throws Exception{JSONArray list=new JSONObject(new String(data,"UTF-8")).getJSONArray("apps");if(list.length()>1000)throw new IOException("Library too large");for(int i=0;i<list.length();i++){JSONObject a=list.getJSONObject(i);if(!a.getString("package").matches("[a-zA-Z0-9_.]+")||!a.getString("sha256").matches("[a-f0-9]{64}")||!a.getString("signerSha256").matches("[a-f0-9]{64}")||a.getLong("size")<=0||a.getLong("size")>1000000000L)throw new IOException("Invalid library entry");}return list;}
 static synchronized void refreshCatalog()throws Exception{byte[] data=remote("/v1/catalog",1000000);JSONArray list=validate(data);File f=new File(context().getFilesDir(),"store-catalog.json"),part=new File(f.toString()+".part");FileOutputStream out=new FileOutputStream(part);try{out.write(data);out.getFD().sync();}finally{out.close();}if(!part.renameTo(f))throw new IOException("Cannot cache library");apps=list;}
 public static boolean menu(Activity a,android.view.MenuItem item){if("Refresh".contentEquals(item.getTitle())){a.recreate();return true;}return false;}
 static void download(String pkg,File target,long expected,Progress progress)throws Exception{
  if(!pkg.matches("[a-zA-Z0-9_.]+")||expected<=0||expected>1000000000L)throw new IOException("Invalid catalog download");
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://"+HOST+"/apk/"+pkg).openConnection(new java.net.Proxy(java.net.Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",8766)));
  c.setConnectTimeout(15000);c.setReadTimeout(90000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
  boolean complete=false;
  try{if(c.getResponseCode()!=200)throw new IOException("Pixel library unavailable");InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(target);
   try{byte[] buffer=new byte[32768];int n,count=0;while((n=in.read(buffer))!=-1){count+=n;if(count>expected)throw new IOException("Catalog size mismatch");out.write(buffer,0,n);if(progress!=null)progress.update(count);}if(count!=expected)throw new IOException("Incomplete download");out.getFD().sync();complete=true;}finally{out.close();in.close();}
  }finally{c.disconnect();if(!complete)target.delete();}
 }
 static byte[] get(String path,int limit,Progress progress)throws Exception{
  if(path.equals("/v1/catalog"))return read(context().getAssets().open("catalog/catalog.json"),limit);
  if(path.matches("/icon/[a-zA-Z0-9_.]+\\.png")){String pkg=path.substring(6,path.length()-4);JSONObject a=catalogItem(pkg);if(a!=null&&a.optBoolean("remoteIcon")){File dir=new File(context().getCacheDir(),"store-icons");dir.mkdirs();File f=new File(dir,pkg+"-"+a.getString("sha256")+".png");if(f.isFile())return read(new FileInputStream(f),limit);byte[] icon=remote(path,200000);FileOutputStream out=new FileOutputStream(f);try{out.write(icon);}finally{out.close();}return icon;}return read(context().getAssets().open("catalog/"+path.substring(6)),limit);}
  if(path.matches("/shot/[a-zA-Z0-9_.]+\\.png"))return read(context().getAssets().open("catalog/"+path.substring(6,path.length()-4)+"-shot.png"),limit);
  if(path.matches("/installed-icon/[a-zA-Z0-9_.]+\\.png"))return CatalogArt.installedIcon(context(),path.substring(16,path.length()-4));
  if(path.matches("/promo/[a-zA-Z0-9_.-]+\\.png"))return CatalogArt.banner(context(),path.substring(7,path.length()-4));
  if(!path.matches("/apk/[a-zA-Z0-9_.]+"))throw new IOException("Invalid catalog route");
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://"+HOST+path).openConnection(new java.net.Proxy(java.net.Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",8766)));c.setConnectTimeout(15000);c.setReadTimeout(90000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
  try{if(c.getResponseCode()!=200)throw new IOException("Pixel catalog unavailable");ByteArrayOutputStream out=new ByteArrayOutputStream();InputStream in=c.getInputStream();byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Catalog too large");out.write(b,0,n);if(progress!=null)progress.update(out.size());}return out.toByteArray();}finally{c.disconnect();}
 }
 static synchronized JSONArray catalog()throws Exception{if(apps==null){File f=new File(context().getFilesDir(),"store-catalog.json");if(f.isFile())try{apps=validate(read(new FileInputStream(f),1000000));}catch(Exception ignored){}if(apps==null)apps=validate(get("/v1/catalog",1000000));}return apps;}
 static JSONObject catalogItem(String pkg)throws Exception{JSONArray a=catalog();for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("package").equals(pkg))return a.getJSONObject(i);return null;}
 static JSONObject find(String pkg)throws Exception{JSONObject a=catalogItem(pkg);return a!=null?a:local(context().getPackageManager().getPackageInfo(pkg,0));}
 static boolean visible(PackageInfo p)throws Exception{return (p.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM)==0&&!p.packageName.equals("org.retrobridge.play2012")&&!p.packageName.equals("org.retrobridge.callprobe")&&!p.packageName.equals("org.retrobridge.galaxyservices.s2")&&context().getPackageManager().getLaunchIntentForPackage(p.packageName)!=null;}
 static JSONObject local(PackageInfo p)throws Exception{if(!visible(p))throw new IOException("App unavailable");JSONObject a=new JSONObject();a.put("package",p.packageName);a.put("name",p.applicationInfo.loadLabel(context().getPackageManager()).toString());a.put("version",p.versionName==null?"":p.versionName);a.put("versionCode",p.versionCode);a.put("developer","Installed on this phone");a.put("description","Installed on this phone.");a.put("localOnly",true);a.put("size",new File(p.applicationInfo.sourceDir).length());return a;}
 static JSONArray installedApps()throws Exception{List<JSONObject> found=new ArrayList<JSONObject>();for(PackageInfo p:context().getPackageManager().getInstalledPackages(0))if(visible(p)){JSONObject a=catalogItem(p.packageName);found.add(a==null?local(p):a);}Collections.sort(found,new Comparator<JSONObject>(){public int compare(JSONObject a,JSONObject b){return a.optString("name").compareToIgnoreCase(b.optString("name"));}});JSONArray result=new JSONArray();for(JSONObject a:found)result.put(a);return result;}
 static Object toc()throws Exception{Object t=make("Toc$TocResponse");set(t,"setHomeUrl","browse?c=3");Object c=make("Toc$CorpusMetadata");set(c,"setBackend",3);set(c,"setName","Apps");set(c,"setLandingUrl","browse?c=3");set(c,"setLibraryName","My apps");set(t,"addCorpus",c);return t;}
 public static void initialize(final Activity a,final boolean handle){try{Object f=app();invoke(f,"setCurrentAccount",account());Object t=toc();Object model=Class.forName("com.google.android.finsky.api.model.DfeToc").getConstructor(t.getClass()).newInstance(t);invoke(f,"setToc",model);invoke(f,"getAppStates");final Handler ui=new Handler();new Thread(new Runnable(){public void run(){try{catalog();refreshCatalog();}catch(Exception e){android.util.Log.i("PixelCatalog","Using cached library");}ui.post(new Runnable(){public void run(){if(a.isFinishing())return;try{invoke(a,"fireOnReadyRunnable",handle);}catch(Exception e){fail(a,e);}}});}},"Store library").start();}catch(Exception e){fail(a,e);}}
 static void fail(Context c,Exception e){android.util.Log.e("PixelCatalog",e.getClass().getSimpleName()+":"+e.getMessage());android.widget.Toast.makeText(c,"Unable to open the catalog",1).show();}
 static Object image(Object d,int type,String path,int w,int h)throws Exception{Object im=make("Doc$Image");set(im,"setImageType",type);set(im,"setImageUrl","https://"+HOST+path);set(im,"setSecureUrl","https://"+HOST+path);if(w>0){Object dim=make("Doc$Image$Dimension");set(dim,"setWidth",w);set(dim,"setHeight",h);set(im,"setDimension",dim);}set(d,"addImage",im);return d;}
 static Object doc(JSONObject a)throws Exception{
  String pkg=a.getString("package");boolean local=a.optBoolean("localOnly");Object d=make("DocumentV2$DocV2");set(d,"setAnnotations",make("DocumentV2$Annotations"));set(d,"setAggregateRating",make("Rating$AggregateRating"));set(d,"setDocid",pkg);set(d,"setBackendDocid",pkg);set(d,"setDocType",1);set(d,"setBackendId",3);set(d,"setTitle",a.getString("name"));set(d,"setCreator",a.getString("developer"));set(d,"setSubtitle",a.getString("developer"));set(d,"setDescriptionHtml",a.getString("description")+(local?"":"<br><br>"+a.getString("license")+". Version "+a.getString("version")+". "+a.optString("sourceLabel","Supplied by F-Droid.")));set(d,"setDetailsUrl","details?doc="+pkg);if(!local&&!a.optString("source").isEmpty())set(d,"setShareUrl",a.getString("source"));
  Object offer=make("Common$Offer");set(offer,"setOfferType",1);set(offer,"setMicros",0L);set(offer,"setCurrencyCode","USD");set(offer,"setFormattedAmount",a.optBoolean("personalLibrary")?"In library":"Free");set(offer,"setCheckoutFlowRequired",false);set(d,"addOffer",offer);Object avail=make("FilterRules$Availability");set(avail,"setRestriction",1);set(d,"setAvailability",avail);
  image(d,4,(local?"/installed-icon/":"/icon/")+pkg+".png",0,0);if(a.optBoolean("screenshot"))image(d,1,"/shot/"+pkg+".png",a.optInt("screenWidth",480),a.optInt("screenHeight",800));
  Object details=make("DocDetails$DocumentDetails"),info=make("DocDetails$AppDetails");set(info,"setPackageName",pkg);set(info,"setVersionCode",a.getInt("versionCode"));set(info,"setVersionString",a.getString("version"));set(info,"setTitle",a.getString("name"));set(info,"setDeveloperName",a.getString("developer"));if(!local)set(info,"setDeveloperWebsite",a.getString("source"));set(info,"setInstallationSize",a.getLong("size"));set(info,"setContentRating",1);set(details,"setAppDetails",info);set(d,"setDetails",details);return d;
 }
 static Object container(String title,String key)throws Exception{Object d=make("DocumentV2$DocV2");set(d,"setAnnotations",make("DocumentV2$Annotations"));set(d,"setAggregateRating",make("Rating$AggregateRating"));set(d,"setDocid","catalog:"+key);set(d,"setBackendId",3);set(d,"setTitle",title);Object meta=make("Containers$ContainerMetadata");set(meta,"setBrowseUrl","browse?group="+key);set(meta,"setOrdered",false);set(d,"setContainerMetadata",meta);return d;}
 static boolean matches(JSONObject a,String g){if(g.equals("library"))return a.optBoolean("personalLibrary");if(g.equals("games"))return a.optString("category").equals("Games");if(g.equals("apps"))return a.optString("category").equals("Apps");if(g.equals("picks"))return Arrays.asList("com.replica.replicaisland","com.glTron","org.jfedor.frozenbubble","com.google.android.stardroid","com.gpl.rpg.AndorsTrail","com.dozingcatsoftware.bouncy").contains(a.optString("package"));if(g.equals("classics"))return Arrays.asList("com.mobilepearls.sokoban","com.kmagic.solitaire","cz.romario.opensudoku","com.shurik.droidzebra","com.anoshenko.android.mahjongg","org.scoutant.blokish").contains(a.optString("package"));return true;}
 static Object bucket(String title,String group,String query)throws Exception{Object d=container(title,group);JSONArray a=group.equals("installed")?installedApps():catalog();for(int i=0;i<a.length();i++){JSONObject item=a.getJSONObject(i);if(matches(item,group)&&(query.isEmpty()||(item.optString("name")+" "+item.optString("description")).toLowerCase(Locale.US).contains(query.toLowerCase(Locale.US))))set(d,"addChild",doc(item));}return d;}
 static Object template(Object d,String name)throws Exception{Object annotations=make("DocumentV2$Annotations"),t=make("DocAnnotations$Template"),tile=make("DocAnnotations$TileTemplate");set(tile,"setColorTextArgb","#ffffffff");set(t,name,tile);set(annotations,"setTemplate",t);set(d,"setAnnotations",annotations);return d;}
 static Object promoTile(String title,String group,String asset)throws Exception{return image(template(container(title,group),"setTileGraphicUpperLeftTitle2X1"),2,"/promo/"+asset+".png",240,120);}
 static Object hero(String pkg,String asset)throws Exception{return image(template(doc(find(pkg)),"setTileGraphic4X2"),2,"/promo/"+asset+".png",480,240);}
 static Object featured()throws Exception{
  Object d=container("Featured","featured");
  // The public kit has no bundled APKs or third-party artwork.
  set(d,"addChild",promoTile("Your library","library","library"));
  set(d,"addChild",promoTile("Games","games","games"));
  set(d,"addChild",promoTile("Apps & tools","apps","apps"));
  JSONArray own=catalog();
  for(int i=0;i<own.length()&&i<12;i++)set(d,"addChild",doc(own.getJSONObject(i)));
  return d;
 }
 static String title(String g){if(g.equals("games"))return "Games";if(g.equals("apps"))return "Apps";if(g.equals("picks"))return "Staff picks";if(g.equals("classics"))return "Classics";if(g.equals("installed"))return "Installed";if(g.equals("library"))return "Your library";return "All apps";}
 static void category(Object response,String name,String group)throws Exception{Object c=make("Browse$BrowseLink");set(c,"setName",name);set(c,"setDataUrl","browse?group="+group);set(response,"addCategory",c);}
 public static Object network(Object request)throws Exception{
  String url=(String)invoke(request,"getUrl");Uri uri=Uri.parse(url);byte[] data;
  if(HOST.equals(uri.getHost()))data=get(uri.getPath(),1200000);
  else if("android.clients.google.com".equals(uri.getHost())&&uri.getPath().startsWith("/fdfe/")){
   String path=uri.getLastPathSegment(),field;Object response;String group=uri.getQueryParameter("group");if(group==null)group=uri.getQueryParameter("installed")!=null?"installed":"home";android.util.Log.i("PixelCatalog","request "+path+" group="+group);
   if(path.equals("toc")){response=toc();field="Toc";}
   else if(path.equals("browse")){response=make("Browse$BrowseResponse");set(response,"setContentsUrl","list?group="+group);if(group.equals("home")){set(response,"setPromoUrl","list?group=featured");category(response,"Games","games");category(response,"Apps & tools","apps");category(response,"Staff picks","picks");category(response,"Classic games","classics");category(response,"Your library","library");category(response,"My apps","myapps");}field="Browse";}
   else if(path.equals("list")){response=make("DocList$ListResponse");if(group.equals("featured"))set(response,"addDoc",featured());else if(group.equals("home")){set(response,"addDoc",bucket("All apps","all",""));set(response,"addDoc",bucket("Games","games",""));set(response,"addDoc",bucket("Apps","apps",""));}else if(group.equals("myapps")){set(response,"addDoc",container("Installed","installed"));set(response,"addDoc",container("All","library"));}else set(response,"addDoc",bucket(title(group),group,""));field="List";}
   else if(path.equals("search")){response=make("Search$SearchResponse");String q=uri.getQueryParameter("q");set(response,"addDoc",bucket("Search results","all",q==null?"":q));field="Search";}
   else if(path.equals("details")){response=make("Details$DetailsResponse");set(response,"setDocV2",doc(find(uri.getQueryParameter("doc"))));field="Details";}
   else if(path.equals("log")){response=make("Log$LogResponse");field="Log";}
   else if(path.equals("replicateLibrary")){response=make("LibraryReplication$LibraryReplicationResponse");field="LibraryReplication";}
   else if(path.equals("checkPromoOffer")){response=make("CheckPromoOffer$CheckPromoOfferResponse");field="CheckPromoOffer";}
   else throw new IOException("This Store feature is unavailable");
   Object payload=make("Response$Payload"),wrapper=make("Response$ResponseWrapper");set(payload,"set"+field+"Response",response);set(wrapper,"setPayload",payload);data=(byte[])invoke(wrapper,"toByteArray");
  }else throw new IOException("Legacy Store network route disabled");
  return Class.forName("com.android.volley.NetworkResponse").getConstructor(byte[].class).newInstance((Object)data);
 }
 public static void about(Activity a){new AlertDialog.Builder(a).setTitle("Google Play").setMessage("Archived apps and games, supplied by your Pixel. Browse locally and install over the paired connection. My apps also includes apps already on this phone.\n\nNo Google login or purchases are used. Downloads are verified against the selected archive versions.").setPositiveButton("OK",null).show();}
 public static void installed(Object nav,Object toc){try{invoke(nav,"goBrowse","browse?group=myapps","My apps",3,"",toc);}catch(Exception e){android.util.Log.e("PixelCatalog","Installed list unavailable",e);}}
 public static void uninstall(String pkg){try{Context c=context();find(pkg);c.startActivity(new Intent(Intent.ACTION_DELETE,Uri.parse("package:"+pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(Exception e){android.util.Log.e("PixelCatalog","Uninstall unavailable",e);}}
 public static void install(Object nav,String url,String pkg){try{Field f=nav.getClass().getDeclaredField("mActivity");f.setAccessible(true);Activity a=(Activity)f.get(nav);String id=pkg;if(id==null||id.isEmpty())id=Uri.parse("https://catalog.invalid/"+url).getQueryParameter("doc");if(find(id).optBoolean("localOnly")){Intent launch=a.getPackageManager().getLaunchIntentForPackage(id);if(launch!=null)a.startActivity(launch);return;}PixelInstallerService.start(a,id);}catch(Exception e){android.util.Log.e("PixelCatalog","Install unavailable",e);}}
}
