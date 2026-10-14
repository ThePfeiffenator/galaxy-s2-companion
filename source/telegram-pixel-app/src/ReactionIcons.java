package org.retrobridge.galaxychat.pixel;

import android.graphics.*;
import android.util.Base64;
import org.json.*;
import java.io.ByteArrayOutputStream;
import org.retrobridge.galaxychat.protocol.Wire;

/** Render with Pixel's current emoji font; ICS cannot draw supplementary emoji. */
final class ReactionIcons {
    static JSONObject request(JSONObject p) throws Exception {
        JSONArray values=p.getJSONArray("emoji");
        if(values.length()<1||values.length()>4)throw new TelegramEngine.Problem("BAD_REQUEST","Invalid icon batch");
        JSONArray icons=new JSONArray();
        for(int i=0;i<values.length();i++) {
            String emoji=values.getString(i);
            if(emoji.length()<1||emoji.length()>32)throw new TelegramEngine.Problem("BAD_REQUEST","Invalid emoji");
            for(int j=0;j<emoji.length();j++)if(Character.isISOControl(emoji.charAt(j)))throw new TelegramEngine.Problem("BAD_REQUEST","Invalid emoji");
            Bitmap bitmap=render(emoji);
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG,100,out);bitmap.recycle();
            byte[] png=out.toByteArray();
            if(png.length>7000)throw new TelegramEngine.Problem("UNSUPPORTED","Reaction icon too large");
            icons.put(Wire.json("emoji",emoji,"png",Base64.encodeToString(png,Base64.NO_WRAP)));
        }
        return Wire.json("icons",icons);
    }
    static Bitmap render(String emoji) {
        Bitmap bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        paint.setTypeface(Typeface.DEFAULT);paint.setColor(Color.BLACK);paint.setTextSize(52);
        String text=paint.hasGlyph(emoji)?emoji:"?";
        Rect bounds=new Rect();paint.getTextBounds(text,0,text.length(),bounds);
        float size=Math.max(bounds.width(),bounds.height());
        if(size>54){paint.setTextSize(52*54/size);paint.getTextBounds(text,0,text.length(),bounds);}
        canvas.drawText(text,32-(bounds.left+bounds.right)/2f,32-(bounds.top+bounds.bottom)/2f,paint);
        return bitmap;
    }
}
