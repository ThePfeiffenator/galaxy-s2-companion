package org.retrobridge.galaxyservices.pixel;
import android.content.*;import android.content.pm.*;import android.graphics.*;import android.graphics.drawable.Drawable;import android.util.Base64;import java.io.*;import java.security.MessageDigest;import java.util.*;import org.json.*;import org.retrobridge.galaxyservices.Wire;
/** Runtime icons from installed apps, not bundled third-party artwork. */
final class ForwardedIcons {
 static final HashMap<String,String> keys=new HashMap<>();
 static synchronized String remember(Context app,String pkg){try{
  long updated=app.getPackageManager().getPackageInfo(pkg,0).lastUpdateTime;String key=pkg+":"+updated;String prior=keys.get(key);if(prior!=null&&new File(app.getCacheDir(),"notice-icon-"+prior+".png").isFile())return prior;
  Drawable icon=app.getPackageManager().getApplicationIcon(pkg);Bitmap bitmap=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);icon.setBounds(0,0,96,96);icon.draw(canvas);ByteArrayOutputStream bytes=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes);bitmap.recycle();byte[] b=bytes.toByteArray();String hash=Wire.hex(MessageDigest.getInstance("SHA-256").digest(b));File f=new File(app.getCacheDir(),"notice-icon-"+hash+".png");if(!f.isFile())Wire.writeFile(f,b);keys.put(key,hash);return hash;
 }catch(Exception e){return "";}}
 static JSONObject get(Context app,String hash)throws Exception{if(!hash.matches("[0-9a-f]{64}"))throw new IOException("Icon key");File f=new File(app.getCacheDir(),"notice-icon-"+hash+".png");if(!f.isFile()||f.length()>65536)throw new IOException("Icon unavailable");return new JSONObject().put("sha256",hash).put("png",Base64.encodeToString(Wire.readFile(f),Base64.NO_WRAP));}
}
