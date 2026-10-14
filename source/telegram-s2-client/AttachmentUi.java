package org.retrobridge.galaxychat.s2;
import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Camera/gallery/file selection with an explicit preview before sending. */
final class AttachmentUi implements AttachmentJob.Observer {
 interface Sender {void send(File file,String name,String mime,String kind,String caption);}
 final Activity activity;final Sender sender;final AttachmentJob job;final boolean retained;
 private boolean resumed;private Dialog dialog;private EditText caption;private File shownFile;private String shownError;private boolean showingProgress;
 AttachmentUi(Activity a,Sender s){activity=a;sender=s;Object previous=a.getLastNonConfigurationInstance();retained=previous instanceof AttachmentJob;job=retained?(AttachmentJob)previous:new AttachmentJob();}
 Object retain(){return job;}
 void resume(){resumed=true;job.observer=this;changed();}
 void pause(){resumed=false;if(job.observer==this)job.observer=null;hide();}
 void destroy(){pause();if(!activity.isChangingConfigurations())job.clear(true);}
 private void hide(){
  if(caption!=null)job.caption=caption.getText().toString();caption=null;
  Dialog old=dialog;dialog=null;shownFile=null;shownError=null;showingProgress=false;
  if(old!=null){old.setOnDismissListener(null);old.setOnCancelListener(null);try{old.dismiss();}catch(IllegalArgumentException alreadyDetached){/* Old Android may already have removed the window. */}}
 }
 public void changed(){
  if(!resumed||activity.isFinishing())return;
  if(job.busy){if(showingProgress)return;hide();dialog=ProgressDialog.show(activity,"","Preparing…",true,false);showingProgress=true;return;}
  if(job.file!=null){if(shownFile==job.file)return;hide();preview();return;}
  if(job.error!=null){if(job.error.equals(shownError))return;hide();shownError=job.error;
   dialog=new AlertDialog.Builder(activity).setMessage(job.error).setPositiveButton("OK",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){job.error=null;hide();}}).create();
   dialog.setOnCancelListener(new DialogInterface.OnCancelListener(){public void onCancel(DialogInterface d){job.error=null;hide();}});dialog.show();return;}
  hide();
 }
 void choose(){new AlertDialog.Builder(activity).setTitle("Attach").setItems(new String[]{"Photo or picture","Take photo","Video","Record video","File"},new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){try{if(which==4){job.kind="document";browse(Environment.getExternalStorageDirectory());return;}if(which==1||which==3){job.kind=which==1?"photo":"video";File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),"GalaxyChat");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Camera storage unavailable");job.camera=new File(dir,"GC_"+System.currentTimeMillis()+(which==1?".jpg":".mp4"));Intent i=new Intent(which==1?MediaStore.ACTION_IMAGE_CAPTURE:MediaStore.ACTION_VIDEO_CAPTURE);i.putExtra(MediaStore.EXTRA_OUTPUT,Uri.fromFile(job.camera));if(which==3){i.putExtra(MediaStore.EXTRA_VIDEO_QUALITY,0);i.putExtra(MediaStore.EXTRA_SIZE_LIMIT,MediaClient.MAX);}activity.startActivityForResult(i,82);}else{job.kind=which==0?"photo":which==2?"video":"document";Intent i=new Intent(Intent.ACTION_GET_CONTENT);i.setType(which==0?"image/*":which==2?"video/*":"*/*");i.addCategory(Intent.CATEGORY_OPENABLE);activity.startActivityForResult(Intent.createChooser(i,"Choose attachment"),81);}}catch(Exception e){error("No app can open that attachment source.");}}}).show();}
 void browse(final File directory){File[] raw=directory.listFiles();if(raw==null){error("Folder unavailable");return;}final ArrayList<File> files=new ArrayList<File>();for(File f:raw)if(!f.getName().startsWith(".")&&f.canRead())files.add(f);Collections.sort(files,new Comparator<File>(){public int compare(File a,File b){if(a.isDirectory()!=b.isDirectory())return a.isDirectory()?-1:1;return a.getName().compareToIgnoreCase(b.getName());}});ArrayList<String> labels=new ArrayList<String>();for(File f:files)labels.add((f.isDirectory()?"▸ ":"")+f.getName());AlertDialog.Builder d=new AlertDialog.Builder(activity).setTitle(directory.getName()).setItems(labels.toArray(new String[0]),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int index){File f=files.get(index);if(f.isDirectory())browse(f);else{String n=f.getName().toLowerCase(Locale.US);job.kind=n.endsWith(".jpg")||n.endsWith(".jpeg")||n.endsWith(".png")||n.endsWith(".webp")?"photo":n.endsWith(".mp4")||n.endsWith(".3gp")?"video":"document";prepare(Uri.fromFile(f));}}}).setNegativeButton("Cancel",null);if(!directory.equals(Environment.getExternalStorageDirectory()))d.setNeutralButton("Up",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){browse(directory.getParentFile());}});d.show();}
 void restore(Bundle b){if(!retained)job.restore(activity.getApplicationContext(),b);}
 void save(Bundle b){if(caption!=null)job.caption=caption.getText().toString();job.save(b);}
 void result(int request,int result,Intent data){
  if(request!=81&&request!=82)return;
  if(result!=Activity.RESULT_OK){if(job.camera!=null&&job.camera.length()==0)job.camera.delete();return;}
  Uri selected=request==82&&job.camera!=null&&job.camera.length()>0?Uri.fromFile(job.camera):data==null?null:data.getData();
  if(selected==null){error("No attachment returned");return;}
  if(request==82)activity.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,selected));prepare(selected);
 }
 private void prepare(Uri uri){job.start(activity.getApplicationContext(),uri,job.kind);}
 private void preview(){
  final File file=job.file;shownFile=file;
  LinearLayout box=new LinearLayout(activity);box.setOrientation(1);box.setPadding(16,8,16,8);
  if(job.kind.equals("photo")){
   ImageView image=new ImageView(activity);BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=4;
   image.setImageBitmap(BitmapFactory.decodeFile(file.getPath(),o));image.setAdjustViewBounds(true);box.addView(image,new LinearLayout.LayoutParams(-1,220));
  }else{TextView label=new TextView(activity);label.setText(job.name+" · "+((file.length()+1023)/1024)+" KB");box.addView(label);}
  caption=new EditText(activity);caption.setHint("Caption");caption.setMaxLines(4);caption.setText(job.caption);box.addView(caption);
  final AlertDialog preview=new AlertDialog.Builder(activity).setTitle(job.kind.equals("photo")?"Photo":job.kind.equals("video")?"Video":"File").setView(box)
   .setNegativeButton("Cancel",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){hide();job.clear(true);}})
   .setPositiveButton("Send",null).create();dialog=preview;
  preview.setOnCancelListener(new DialogInterface.OnCancelListener(){public void onCancel(DialogInterface d){hide();job.clear(true);}});
  preview.show();
  preview.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new android.view.View.OnClickListener(){public void onClick(android.view.View view){
   try{sender.send(file,job.name,job.mime,job.kind,caption.getText().toString());hide();job.clear(false);}
   catch(Exception e){Toast.makeText(activity,e.getMessage()==null?"Could not send attachment":e.getMessage(),Toast.LENGTH_LONG).show();}
  }});
 }
 private void error(String message){job.error=message;changed();}
 static void photo(File source,File output)throws Exception{BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(source.getPath(),o);if(o.outWidth<1||o.outHeight<1)throw new IOException("Unsupported picture");o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>2048)o.inSampleSize*=2;o.inJustDecodeBounds=false;o.inPreferredConfig=Bitmap.Config.RGB_565;Bitmap b=BitmapFactory.decodeFile(source.getPath(),o);if(b==null)throw new IOException("Could not decode picture");try{Matrix m=new Matrix();int orientation=1;try{orientation=new ExifInterface(source.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(Exception ignored){}if(orientation==6)m.postRotate(90);else if(orientation==3)m.postRotate(180);else if(orientation==8)m.postRotate(270);float scale=Math.min(1f,1600f/Math.max(b.getWidth(),b.getHeight()));m.postScale(scale,scale);Bitmap c=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);try{FileOutputStream out=new FileOutputStream(output);try{if(!c.compress(Bitmap.CompressFormat.JPEG,90,out))throw new IOException("Could not prepare photo");}finally{out.close();}}finally{if(c!=b)c.recycle();}}finally{b.recycle();}}
}
