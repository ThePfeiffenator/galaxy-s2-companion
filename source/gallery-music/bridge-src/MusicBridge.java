package org.retrobridge.playmusic2012.galaxy;
import android.app.*;import android.content.*;import android.database.*;import android.database.sqlite.*;import android.net.Uri;import android.os.*;import android.view.*;import android.widget.Toast;
import java.io.*;import java.net.*;import java.util.*;import javax.net.ssl.HttpsURLConnection;import org.json.*;
import org.retrobridge.playmusic2012.store.Store;import org.retrobridge.playmusic2012.store.MusicFile;

/** Imports a paired Pixel catalog into the original player's own store. */
public final class MusicBridge extends IntentService {
 static final int ACCOUNT=9001,MENU=0x4753;static final String ORIGIN="content://org.retrobridge.playmusic2012.MusicContent";
 public MusicBridge(){super("music-library");}
 public static void traceArtwork(Intent event){android.util.Log.i("GalaxyMusicArt",event.getAction()+" song="+event.getLongExtra("id",-1)+" album="+event.getLongExtra("albumId",-1)+" loaded="+event.getBooleanExtra("currentSongLoaded",false)+" queueLoading="+event.getBooleanExtra("queueLoading",false));}
 public static android.graphics.Bitmap fitArtwork(android.graphics.Bitmap source,int width,int height,boolean upscale){
  if(source==null||width<1||height<1||width>2048||height>2048)return source;
  int sw=source.getWidth(),sh=source.getHeight();if(sw==width&&sh==height)return source;
  float scale=Math.min((float)width/sw,(float)height/sh);if(!upscale)scale=Math.min(1f,scale);
  float dw=sw*scale,dh=sh*scale;
  android.graphics.Bitmap result=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);
  android.graphics.Canvas canvas=new android.graphics.Canvas(result);canvas.drawColor(0xff182437);
  canvas.drawBitmap(source,null,new android.graphics.RectF((width-dw)/2f,(height-dh)/2f,(width+dw)/2f,(height+dh)/2f),new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
  source.recycle();return result;
 }
 public static long duration(Context context,String source){Store store=Store.getInstance(context);SQLiteDatabase db=store.beginRead();try{MusicFile song=MusicFile.readMusicFile(db,String.valueOf(ACCOUNT),source,null);return song==null?0:Math.max(0,song.getDurationInMilliSec());}finally{store.endRead(db);}}
 PendingIntent retryIntent(int attempt){return PendingIntent.getService(this,0,new Intent(this,MusicBridge.class).putExtra("retry",attempt),PendingIntent.FLAG_UPDATE_CURRENT);}
 void cancelRetry(){((AlarmManager)getSystemService(ALARM_SERVICE)).cancel(retryIntent(0));}
 void retry(int attempt){if(attempt>=5)return;long delay=60000L*(1L<<attempt);((AlarmManager)getSystemService(ALARM_SERVICE)).set(AlarmManager.ELAPSED_REALTIME,SystemClock.elapsedRealtime()+delay,retryIntent(attempt+1));}
 void refreshLater(){PendingIntent next=PendingIntent.getService(this,0,new Intent(this,MusicBridge.class).putExtra("libraryRefresh",true),PendingIntent.FLAG_UPDATE_CURRENT);((AlarmManager)getSystemService(ALARM_SERVICE)).set(AlarmManager.ELAPSED_REALTIME,SystemClock.elapsedRealtime()+60000,next);}
 public static void start(Context c){c.startService(new Intent(c,MusicBridge.class));}
 public static void addMenu(Menu menu){menu.add(0,MENU,0,"Refresh library");}
 public static boolean menu(Context c,MenuItem item){if(item.getItemId()!=MENU)return false;c.getSharedPreferences("pixel-music",0).edit().remove("checked").commit();start(c);Toast.makeText(c,"Refreshing library",Toast.LENGTH_SHORT).show();return true;}
 public static String stream(Context context,String source){if(source==null||!source.matches("yt:[A-Za-z0-9_-]{11}"))return null;String id=source.substring(3),play=id,transport="galaxy";
  try{HttpsURLConnection connection=(HttpsURLConnection)new URL("https://music.galaxy-services.invalid/resolve/"+id).openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",8766)));JSONObject detail;
   try{connection.setConnectTimeout(10000);connection.setReadTimeout(50000);connection.setInstanceFollowRedirects(false);if(connection.getResponseCode()!=200)throw new IOException("Track unavailable");ByteArrayOutputStream bytes=new ByteArrayOutputStream();InputStream in=connection.getInputStream();try{byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>32768)throw new IOException("Metadata limit");bytes.write(buffer,0,n);}}finally{in.close();}detail=new JSONObject(bytes.toString("UTF-8"));}finally{connection.disconnect();}
   String resolved=detail.getString("play_id");if(!resolved.matches("[A-Za-z0-9_-]{11}")||!id.equals(detail.getString("source_id")))throw new IOException("Track response");play=resolved;
   if("upload".equals(detail.optString("transport","")))transport="music";
   String album=detail.optString("album","");if(album.length()>0&&album.length()<=1000){Store store=Store.getInstance(context);SQLiteDatabase db=store.beginWriteTxn();boolean ok=false;SQLiteStatement statement=null;try{MusicFile music=MusicFile.readMusicFile(db,String.valueOf(ACCOUNT),source,null);if(music!=null){String artist=detail.optString("artist","");if(artist.length()>0&&artist.length()<=1000){music.setTrackArtist(artist);music.setAlbumArtist(artist);}music.setAlbumName(album);statement=MusicFile.compileFullUpdateStatement(db);music.updateMusicFile(statement,db);}ok=true;}finally{if(statement!=null)statement.close();store.endWriteTxn(db,ok);}context.getContentResolver().notifyChange(Uri.parse(ORIGIN),null);}
  }catch(Exception error){android.util.Log.w("GalaxyMusic","Track lookup: "+error.getClass().getSimpleName());return "";}
  return "http://127.0.0.1:8768/sabr_hls_adapter/"+transport+"/"+play+"/index.m3u8";
 }
 static final Map<String,Long> artRetryAfter=Collections.synchronizedMap(new HashMap<String,Long>());
 public static boolean downloadArt(String address,File destination)throws IOException{
  if(address==null||!address.matches("https://i\\.ytimg\\.com/vi/[A-Za-z0-9_-]{11}/hqdefault\\.jpg"))return false;
  String id=address.substring("https://i.ytimg.com/vi/".length(),"https://i.ytimg.com/vi/".length()+11);
  Long retryAt=artRetryAfter.get(id);if(retryAt!=null&&SystemClock.elapsedRealtime()<retryAt.longValue())throw new IOException("Artwork retry delayed");
  HttpsURLConnection connection=(HttpsURLConnection)new URL("https://music.galaxy-services.invalid/art/"+id+".jpg").openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",8766)));
  File temporary=new File(destination.getPath()+".pixel-new");boolean saved=false;
  try{connection.setConnectTimeout(10000);connection.setReadTimeout(15000);connection.setInstanceFollowRedirects(false);if(connection.getResponseCode()!=200)throw new IOException("Artwork unavailable");
   InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(temporary);try{byte[] buffer=new byte[16384];int n,total=0;while((n=in.read(buffer))!=-1){total+=n;if(total>2*1024*1024)throw new IOException("Artwork limit");out.write(buffer,0,n);}out.getFD().sync();}finally{try{in.close();}finally{out.close();}}
   android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(temporary.getPath(),bounds);if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>2048||bounds.outHeight>2048)throw new IOException("Artwork image");
   if(!temporary.renameTo(destination))throw new IOException("Artwork save");saved=true;artRetryAfter.remove(id);return true;
  }finally{connection.disconnect();if(!saved){temporary.delete();if(artRetryAfter.size()>2048)artRetryAfter.clear();artRetryAfter.put(id,SystemClock.elapsedRealtime()+300000L);}}
 }
 public static boolean hasArtwork(Store store,long album,boolean configured){if(configured)return true;String location=store.getRemoteArtLocationForAlbum(album);return location!=null&&location.matches("https://i\\.ytimg\\.com/vi/[A-Za-z0-9_-]{11}/hqdefault\\.jpg");}
 void note(String value){try{FileOutputStream out=openFileOutput("pixel-music-status.txt",0);try{out.write(value.getBytes("UTF-8"));}finally{out.close();}}catch(Exception ignored){}}
 void recordCounts(){try{Store store=Store.getInstance(this);SQLiteDatabase db=store.beginRead();String value;try{Cursor c=db.rawQuery("SELECT COUNT(*),SUM(CASE WHEN Album IS NOT NULL AND Album <> '' THEN 1 ELSE 0 END),COUNT(DISTINCT CASE WHEN Album IS NOT NULL AND Album <> '' THEN AlbumId END) FROM MUSIC WHERE SourceAccount=9001",null);try{if(!c.moveToFirst())return;value="tracks="+c.getLong(0)+" tracks_with_album="+c.getLong(1)+" named_albums="+c.getLong(2);}finally{c.close();}}finally{store.endRead(db);}FileOutputStream out=openFileOutput("pixel-music-counts.txt",0);try{out.write(value.getBytes("UTF-8"));}finally{out.close();}}catch(Exception ignored){}}
 JSONObject fetch()throws Exception{
  HttpsURLConnection connection=(HttpsURLConnection)new URL("https://music.galaxy-services.invalid/catalog").openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",8766)));
  try{connection.setConnectTimeout(10000);connection.setReadTimeout(25000);connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);int code=connection.getResponseCode();if(code==503)return null;if(code!=200)throw new IOException("Music HTTP "+code);
   ByteArrayOutputStream body=new ByteArrayOutputStream();InputStream in=connection.getInputStream();try{byte[] data=new byte[32768];int n;while((n=in.read(data))!=-1){if(body.size()+n>16*1024*1024)throw new IOException("Catalog size");body.write(data,0,n);}}finally{in.close();}return new JSONObject(body.toString("UTF-8"));
  }finally{connection.disconnect();}
 }
 protected void onHandleIntent(Intent intent){SharedPreferences prefs=getSharedPreferences("pixel-music",0);if(prefs.getInt("bridge_metadata",0)>=2&&System.currentTimeMillis()-prefs.getLong("checked",0)<300000&&(intent==null||!intent.getBooleanExtra("libraryRefresh",false))){if(prefs.getBoolean("metadata_pending",false))refreshLater();else cancelRetry();return;}int attempt=intent==null?0:Math.max(0,Math.min(5,intent.getIntExtra("retry",0)));PowerManager.WakeLock wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PlayMusic:library");wake.acquire(180000);
  try{note("Loading library");JSONObject catalog=fetch();if(catalog==null){note("Pixel is preparing your library");retry(attempt);return;}long updated=catalog.getLong("updated");if(updated!=prefs.getLong("updated",0))importCatalog(catalog);boolean pending=catalog.optInt("metadata_pending",0)>0;prefs.edit().putLong("updated",updated).putLong("checked",System.currentTimeMillis()).putInt("bridge_metadata",2).putBoolean("metadata_pending",pending).commit();if(pending)refreshLater();else cancelRetry();note("Ready");}
  catch(Exception error){note("Library unavailable: "+error.getClass().getSimpleName());android.util.Log.w("GalaxyMusic","Catalog import failed: "+error.getClass().getSimpleName());retry(attempt);}
  finally{if(wake.isHeld())wake.release();}
 }
 void importCatalog(JSONObject catalog)throws Exception{
  if(catalog.getInt("schema")!=1)throw new IOException("Catalog schema");JSONArray songs=catalog.getJSONArray("songs"),lists=catalog.getJSONArray("playlists");if(songs.length()>15000||lists.length()>1000)throw new IOException("Catalog count");
  HashSet<String> valid=new HashSet<String>();for(int i=0;i<songs.length();i++){JSONObject item=songs.getJSONObject(i);String id=item.getString("id");if(!id.matches("[A-Za-z0-9_-]{11}")||!valid.add(id)||item.getString("title").length()>1000)throw new IOException("Catalog song");}
  HashSet<String> listIds=new HashSet<String>();for(int i=0;i<lists.length();i++){JSONObject item=lists.getJSONObject(i);String id=item.getString("id");if(!id.matches("[A-Za-z0-9_-]{2,160}")||!listIds.add(id)||item.getString("name").length()>1000)throw new IOException("Catalog playlist");JSONArray ids=item.getJSONArray("tracks");if(ids.length()>15000)throw new IOException("Playlist size");for(int j=0;j<ids.length();j++)if(!valid.contains(ids.getString(j)))throw new IOException("Unknown song");}
  Store store=Store.getInstance(this);SQLiteDatabase db=store.beginWriteTxn();boolean success=false;SQLiteStatement insert=null,update=null;
  try{insert=MusicFile.compileMusicInsertStatement(db);update=MusicFile.compileFullUpdateStatement(db);
   for(int i=0;i<songs.length();i++){JSONObject song=songs.getJSONObject(i);String id="yt:"+song.getString("id");MusicFile music=MusicFile.readMusicFile(db,String.valueOf(ACCOUNT),id,null);boolean exists=music!=null;if(!exists)music=new MusicFile();
    music.setSourceAccount(ACCOUNT);music.setSourceId(id);music.setSourceVersion("pixel");music.setNeedsSync(false);music.setTitle(song.getString("title"));music.setTrackArtist(song.optString("artist",""));music.setAlbumArtist(song.optString("artist",""));if(!exists||song.optString("album","").length()>0)music.setAlbumName(song.optString("album",""));music.setDurationInMilliSec(song.optLong("duration_ms",0));music.setLocalCopyType(0);music.setMimeType("audio/mp4");music.setRating(song.optBoolean("liked",false)?5:0);
    String art=song.optString("art","");if(art.matches("https://i\\.ytimg\\.com/vi/[A-Za-z0-9_-]{11}/hqdefault\\.jpg"))music.setAlbumArtLocation(art);
    if(exists)music.updateMusicFile(update,db);else music.insertMusicFile(insert);
   }
   // Stable song IDs preserve playback queues. Remove only stale imported rows.
   ArrayList<Long> obsolete=new ArrayList<Long>();Cursor cursor=db.query("MUSIC",new String[]{"Id","SourceId"},"SourceAccount=?",new String[]{String.valueOf(ACCOUNT)},null,null,null);try{while(cursor.moveToNext()){String id=cursor.getString(1);if(!id.startsWith("yt:")||!valid.contains(id.substring(3)))obsolete.add(cursor.getLong(0));}}finally{cursor.close();}for(Long id:obsolete)db.delete("MUSIC","Id=?",new String[]{id.toString()});
   HashSet<String> keepLists=new HashSet<String>();for(int i=0;i<lists.length();i++){JSONObject list=lists.getJSONObject(i);String source="yt:"+list.getString("id");keepLists.add(source);long local=-1;cursor=db.query("LISTS",new String[]{"Id"},"SourceAccount=? AND SourceId=?",new String[]{String.valueOf(ACCOUNT),source},null,null,null);try{if(cursor.moveToFirst())local=cursor.getLong(0);}finally{cursor.close();}
    ContentValues values=new ContentValues();values.put("Name",list.getString("name"));values.put("SourceAccount",ACCOUNT);values.put("SourceId",source);values.put("_sync_dirty",0);values.put("ListType",0);if(local<0)local=db.insertOrThrow("LISTS",null,values);else db.update("LISTS",values,"Id=?",new String[]{String.valueOf(local)});
    db.delete("LISTITEMS","ListId=?",new String[]{String.valueOf(local)});JSONArray ids=list.getJSONArray("tracks");for(int j=0;j<ids.length();j++){values.clear();values.put("ListId",local);values.put("MusicSourceAccount",ACCOUNT);values.put("MusicSourceId","yt:"+ids.getString(j));values.put("ClientPosition",j);values.put("ServerPosition",j);values.put("ServerOrder",String.format(Locale.US,"%08d",j));values.put("_sync_dirty",0);db.insertOrThrow("LISTITEMS",null,values);}
   }
   obsolete.clear();cursor=db.query("LISTS",new String[]{"Id","SourceId"},"SourceAccount=?",new String[]{String.valueOf(ACCOUNT)},null,null,null);try{while(cursor.moveToNext())if(!keepLists.contains(cursor.getString(1)))obsolete.add(cursor.getLong(0));}finally{cursor.close();}for(Long id:obsolete){db.delete("LISTITEMS","ListId=?",new String[]{id.toString()});db.delete("LISTS","Id=?",new String[]{id.toString()});}success=true;
  }finally{if(insert!=null)insert.close();if(update!=null)update.close();store.endWriteTxn(db,success);}
  recordCounts();getContentResolver().notifyChange(Uri.parse(ORIGIN),null);getContentResolver().notifyChange(Uri.parse(ORIGIN+"/audio"),null);getContentResolver().notifyChange(Uri.parse(ORIGIN+"/playlists"),null);
 }
}
