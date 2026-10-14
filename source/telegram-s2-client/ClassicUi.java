package org.retrobridge.galaxychat.s2;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Presentation helpers; artwork is from the user's supplied Telegram 3.9.0 APK. */
final class ClassicUi {
    static final int BLUE=0xff527da3;
    static int dp(Context c,int value){return (int)(value*c.getResources().getDisplayMetrics().density+0.5f);}
    static StateListDrawable feedback(int color){
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},new ColorDrawable(color));
        states.addState(new int[]{android.R.attr.state_focused},new ColorDrawable(color));
        states.addState(new int[0],new ColorDrawable(Color.TRANSPARENT));return states;
    }
    static ImageButton icon(Context c,int drawable,String description){
        ImageButton b=new ImageButton(c);b.setImageResource(drawable);b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(c,10),dp(c,10),dp(c,10),dp(c,10));b.setBackgroundDrawable(feedback(0x16000000));
        b.setContentDescription(description);return b;
    }
    static final class Avatar extends ImageView {
        private final Path circle=new Path();
        Avatar(Context c){super(c);setScaleType(ScaleType.CENTER_CROP);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        protected void onDraw(Canvas canvas){int save=canvas.save();circle.reset();circle.addCircle(getWidth()/2f,getHeight()/2f,Math.min(getWidth(),getHeight())/2f,Path.Direction.CW);canvas.clipPath(circle);super.onDraw(canvas);canvas.restoreToCount(save);}
    }
    static Drawable wallpaper(Context c){
        BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.RGB_565;
        final Bitmap bitmap=BitmapFactory.decodeResource(c.getResources(),R.drawable.classic_wallpaper,options);
        return new Drawable(){final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);final Rect source=new Rect();
            public void draw(Canvas canvas){Rect target=getBounds();float ratio=Math.max(target.width()/(float)bitmap.getWidth(),target.height()/(float)bitmap.getHeight());int w=(int)(target.width()/ratio),h=(int)(target.height()/ratio);int x=(bitmap.getWidth()-w)/2,y=(bitmap.getHeight()-h)/2;source.set(x,y,x+w,y+h);canvas.drawBitmap(bitmap,source,target,paint);}
            public void setAlpha(int a){paint.setAlpha(a);}public void setColorFilter(ColorFilter f){paint.setColorFilter(f);}public int getOpacity(){return android.graphics.PixelFormat.OPAQUE;}
        };
    }
}
