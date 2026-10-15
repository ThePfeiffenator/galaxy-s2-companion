package org.retrobridge.galaxyservices.pixel;
import android.content.*;import android.database.Cursor;import android.provider.ContactsContract;import android.content.pm.PackageManager;import android.graphics.*;import android.util.Base64;
import java.io.*;import java.util.*;import org.json.*;import org.retrobridge.galaxyservices.*;

final class ContactsFeed {
 final Context app;JSONObject cached;long cachedAt;final java.util.concurrent.atomic.AtomicLong generation=new java.util.concurrent.atomic.AtomicLong();long cachedGeneration;final android.database.ContentObserver observer;
 ContactsFeed(Context c){app=c;observer=new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())){public void onChange(boolean self){generation.incrementAndGet();}};c.getContentResolver().registerContentObserver(ContactsContract.AUTHORITY_URI,true,observer);}
 void close(){app.getContentResolver().unregisterContentObserver(observer);}
 synchronized JSONObject snapshot()throws Exception{
  if(!app.getSharedPreferences("contact-sync",0).getBoolean("enabled",false))return new JSONObject().put("enabled",false);
  if(app.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED)throw new IOException("Contacts permission needed");
  long before=generation.get();if(cached!=null&&before==cachedGeneration&&android.os.SystemClock.elapsedRealtime()-cachedAt<30000)return new JSONObject(cached.toString()).put("generated",System.currentTimeMillis());
  SharedPreferences settings=app.getSharedPreferences("contact-sync",0);String source=settings.getString("source",null);if(source==null){source=UUID.randomUUID().toString().replace("-","");if(!settings.edit().putString("source",source).commit())throw new IOException("Contacts identity storage");}
  LinkedHashMap<Long,JSONObject> contacts=new LinkedHashMap<>();ContentResolver cr=app.getContentResolver();
  try(Cursor c=cr.query(ContactsContract.Contacts.CONTENT_URI,new String[]{"_id","display_name"},null,null,"_id ASC")){
   if(c==null)throw new IOException("Contacts unavailable");while(c.moveToNext()){if(contacts.size()>=10000)throw new IOException("Contact limit");contacts.put(c.getLong(0),new JSONObject().put("id",c.getString(0)).put("name",c.getString(1)==null?"":c.getString(1)).put("rows",new JSONArray()));}}
  String[] columns=new String[17];columns[0]="contact_id";columns[1]="mimetype";for(int i=1;i<=15;i++)columns[i+1]="data"+i;
  HashMap<Long,Set<String>> seen=new HashMap<>();
  try(Cursor c=cr.query(ContactsContract.Data.CONTENT_URI,columns,null,null,"contact_id ASC, _id ASC")){
   if(c==null)throw new IOException("Contact details unavailable");while(c.moveToNext()){JSONObject item=contacts.get(c.getLong(0));String mime=c.getString(1);if(item==null||!ContactWire.MIMES.contains(mime))continue;
    JSONObject row=new JSONObject().put("mime",mime);JSONArray v=new JSONArray();for(int i=0;i<14;i++)v.put(c.isNull(i+2)?JSONObject.NULL:c.getString(i+2));row.put("v",v);
    if(mime.equals(ContactWire.PHOTO)){v=new JSONArray();for(int k=0;k<14;k++)v.put(JSONObject.NULL);row.put("v",v);byte[] b=c.getBlob(16);if(b==null)continue;Bitmap bitmap=BitmapFactory.decodeByteArray(b,0,b.length);if(bitmap==null)continue;Bitmap small=Bitmap.createScaledBitmap(bitmap,Math.max(1,bitmap.getWidth()*128/Math.max(bitmap.getWidth(),bitmap.getHeight())),Math.max(1,bitmap.getHeight()*128/Math.max(bitmap.getWidth(),bitmap.getHeight())),true);ByteArrayOutputStream out=new ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,80,out);if(small!=bitmap)small.recycle();bitmap.recycle();row.put("photo",Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP));}
    Set<String> keys=seen.get(c.getLong(0));if(keys==null){keys=new HashSet<>();seen.put(c.getLong(0),keys);}if(keys.add(row.toString()))item.getJSONArray("rows").put(row);
   }}
  JSONArray rows=new JSONArray();for(JSONObject c:contacts.values())rows.put(c);
  JSONObject result=new JSONObject().put("version",1).put("enabled",true).put("source",source).put("complete",true).put("generated",System.currentTimeMillis()).put("contacts",rows);ContactWire.validate(result);
  if(before!=generation.get())throw new IOException("Contacts changed during read");if(result.toString().getBytes("UTF-8").length>6000000)throw new IOException("Contacts snapshot limit");cached=result;cachedAt=android.os.SystemClock.elapsedRealtime();cachedGeneration=before;
  Wire.writeFile(new File(app.getFilesDir(),"contacts-status.txt"),(rows.length()+" contacts available").getBytes("UTF-8"));return result;
 }
}
