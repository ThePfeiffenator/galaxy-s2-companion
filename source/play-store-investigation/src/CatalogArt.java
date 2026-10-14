package org.retrobridge.play2012;
import android.content.Context;import android.graphics.*;import android.graphics.drawable.Drawable;import java.io.*;
/** Original Canvas art; no developer-owned APK collection or third-party icons. */
public final class CatalogArt {
 static byte[] png(Bitmap b){ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,out);b.recycle();return out.toByteArray();}
 public static byte[] installedIcon(Context ctx,String pkg)throws Exception{Drawable d=ctx.getPackageManager().getApplicationIcon(pkg);Bitmap b=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);d.setBounds(0,0,96,96);d.draw(c);return png(b);}
 public static byte[] banner(Context ctx,String key)throws Exception{
  if(!key.equals("library")&&!key.equals("games")&&!key.equals("apps"))throw new IOException("Unknown promo");
  Bitmap b=Bitmap.createBitmap(240,120,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);Paint p=new Paint(3);
  p.setShader(new LinearGradient(0,120,240,0,0xff121811,0xff799d22,Shader.TileMode.CLAMP));c.drawRect(0,0,240,120,p);p.setShader(null);p.setColor(0xffa5ca44);
  if(key.equals("games")){c.drawRoundRect(new RectF(142,45,222,94),12,12,p);p.setColor(0xff243322);c.drawRect(155,65,180,73,p);c.drawRect(164,56,172,82,p);c.drawCircle(202,62,5,p);c.drawCircle(213,76,5,p);}
  else {for(int y=0;y<2;y++)for(int x=0;x<3;x++)c.drawRoundRect(new RectF(143+x*25,46+y*25,161+x*25,64+y*25),3,3,p);}
  return png(b);
 }
}
