package org.retrobridge.galaxyservices.s2;
import android.content.*;import android.database.Cursor;import android.net.Uri;import android.provider.ContactsContract;import android.util.Base64;
import java.io.*;import java.util.*;import org.json.*;import org.retrobridge.galaxyservices.*;

/** One-way modern-phone mirror. Unowned local contacts are never removed. */
final class ContactsMirror {
 static final String OWNER="galaxy-companion-v1";
 static final Uri RAW=ContactsContract.RawContacts.CONTENT_URI,DATA=ContactsContract.Data.CONTENT_URI;
 final Context app;final ContentResolver cr;
 ContactsMirror(Context c){app=c;cr=c.getContentResolver();}
 static String[] args(Object n){return new String[]{String.valueOf(n)};}
 static final class Raw {long id;String owner,source,digest,account;JSONObject contact;}
 List<Raw> local()throws Exception{
  List<Raw> result=new ArrayList<Raw>();
  Cursor c=cr.query(RAW,new String[]{"_id","sync1","sync2","sync3","account_type","display_name"},"deleted=0",null,"_id ASC");
  if(c==null)throw new IOException("Contact provider unavailable");try{while(c.moveToNext()){Raw r=new Raw();r.id=c.getLong(0);r.owner=c.getString(1);r.source=c.getString(2);r.digest=c.getString(3);r.account=c.getString(4);r.contact=new JSONObject().put("name",c.getString(5)==null?"":c.getString(5)).put("rows",new JSONArray());result.add(r);}}finally{c.close();}
  for(Raw r:result){String[] columns=new String[16];columns[0]="mimetype";for(int i=1;i<=15;i++)columns[i]="data"+i;c=cr.query(DATA,columns,"raw_contact_id=?",args(r.id),"_id ASC");if(c==null)throw new IOException("Contact data unavailable");try{while(c.moveToNext()){JSONObject row=new JSONObject().put("mime",c.getString(0));JSONArray v=new JSONArray();for(int i=1;i<=14;i++)v.put(c.isNull(i)?JSONObject.NULL:c.getString(i));row.put("v",v);if(!c.isNull(15))row.put("photo",Base64.encodeToString(c.getBlob(15),Base64.NO_WRAP));r.contact.getJSONArray("rows").put(row);}}finally{c.close();}}
  return result;
 }
 void backup(List<Raw> all)throws Exception{
  JSONArray rows=new JSONArray();for(Raw r:all)rows.put(new JSONObject().put("raw",r.id).put("owner",r.owner).put("source",r.source).put("account",r.account).put("contact",r.contact));
  File dir=new File(app.getFilesDir(),"contact-backups");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Contact backup directory");File f=new File(dir,"before-"+System.currentTimeMillis()+".json");
  try(FileOutputStream out=new FileOutputStream(f)){out.write(new JSONObject().put("contacts",rows).toString().getBytes("UTF-8"));out.getFD().sync();}
  File[] old=dir.listFiles();if(old!=null&&old.length>20){Arrays.sort(old,new Comparator<File>(){public int compare(File a,File b){return a.getName().compareTo(b.getName());}});for(int i=1;i<old.length-19;i++)old[i].delete();} // Retain the initial pre-sync backup.
 }
 void sync(JSONObject feed)throws Exception{
  if(!feed.optBoolean("enabled",false))return;ContactWire.validate(feed);String prefix=feed.getString("source")+":";JSONArray contacts=feed.getJSONArray("contacts");
  List<Raw> existing=local();Map<String,Raw> owned=new HashMap<String,Raw>();Map<String,List<Raw>> candidates=new HashMap<String,List<Raw>>();Map<String,Integer> incoming=new HashMap<String,Integer>();
  for(int i=0;i<contacts.length();i++){String fp=ContactWire.fingerprint(contacts.getJSONObject(i));incoming.put(fp,incoming.containsKey(fp)?incoming.get(fp)+1:1);}
  for(Raw r:existing){if(OWNER.equals(r.owner)){if(owned.put(r.source,r)!=null)throw new IOException("Duplicate contact ownership");}else if((r.owner==null||r.owner.length()==0)&&(r.account==null||r.account.length()==0||r.account.equals("com.android.localphone")||r.account.equals("vnd.sec.contact.phone"))){String fp=ContactWire.fingerprint(r.contact);if(fp.length()>0){List<Raw> list=candidates.get(fp);if(list==null){list=new ArrayList<Raw>();candidates.put(fp,list);}list.add(r);}}}
  boolean backed=false;int changed=0,adopted=0;Set<String> keys=new HashSet<String>();
  for(int i=0;i<contacts.length();i++){
   JSONObject c=contacts.getJSONObject(i);String key=prefix+c.getString("id"),digest=ContactWire.hash(c.toString());keys.add(key);Raw r=owned.get(key);
   if(r!=null&&digest.equals(r.digest))continue;
   if(r==null){String fp=ContactWire.fingerprint(c);List<Raw> list=candidates.get(fp);if(fp.length()>0&&incoming.get(fp)==1&&list!=null&&list.size()==1){r=list.get(0);candidates.remove(fp);adopted++;}}
   if(!backed){backup(existing);backed=true;}
   ArrayList<ContentProviderOperation> ops=new ArrayList<ContentProviderOperation>();ContentValues raw=new ContentValues();raw.put("sync1",OWNER);raw.put("sync2",key);raw.put("sync3",digest);raw.put("aggregation_mode",0);
   if(r==null){raw.putNull("account_name");raw.putNull("account_type");ops.add(ContentProviderOperation.newInsert(RAW).withValues(raw).build());}
   else{ops.add(ContentProviderOperation.newUpdate(ContentUris.withAppendedId(RAW,r.id)).withValues(raw).build());StringBuilder sel=new StringBuilder("raw_contact_id=? AND mimetype IN (");ArrayList<String> a=new ArrayList<String>();a.add(String.valueOf(r.id));for(String mime:ContactWire.MIMES){if(a.size()>1)sel.append(',');sel.append('?');a.add(mime);}sel.append(')');ops.add(ContentProviderOperation.newDelete(DATA).withSelection(sel.toString(),a.toArray(new String[a.size()])).build());}
   JSONArray rows=c.getJSONArray("rows");boolean name=false;
   for(int j=0;j<rows.length();j++){JSONObject row=rows.getJSONObject(j);ContentValues values=new ContentValues();String mime=row.getString("mime");if(mime.equals(ContactWire.NAME))name=true;values.put("mimetype",mime);JSONArray v=row.getJSONArray("v");for(int k=0;k<14;k++)if(!v.isNull(k))values.put("data"+(k+1),v.getString(k));if(row.has("photo"))values.put("data15",Base64.decode(row.getString("photo"),Base64.DEFAULT));ContentProviderOperation.Builder op=ContentProviderOperation.newInsert(DATA).withValues(values);if(r==null)op.withValueBackReference("raw_contact_id",0);else op.withValue("raw_contact_id",r.id);ops.add(op.build());}
   if(!name){ContentProviderOperation.Builder op=ContentProviderOperation.newInsert(DATA).withValue("mimetype",ContactWire.NAME).withValue("data1",c.getString("name"));if(r==null)op.withValueBackReference("raw_contact_id",0);else op.withValue("raw_contact_id",r.id);ops.add(op.build());}
   cr.applyBatch(ContactsContract.AUTHORITY,ops);changed++;
  }
  // Retirement only after a complete, validated snapshot and successful upserts.
  for(Raw r:owned.values())if(r.source!=null&&r.source.startsWith(prefix)&&!keys.contains(r.source)){if(!backed){backup(existing);backed=true;}cr.delete(ContentUris.withAppendedId(RAW,r.id),"sync1=? AND sync2=?",new String[]{OWNER,r.source});changed++;}
  Wire.writeFile(new File(app.getFilesDir(),"contacts-status.txt"),("Synced "+contacts.length()+" contacts\nChanged="+changed+" Adopted="+adopted+"\nUpdated="+System.currentTimeMillis()).getBytes("UTF-8"));
 }
}
