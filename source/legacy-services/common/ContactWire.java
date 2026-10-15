package org.retrobridge.galaxyservices;
import java.util.*;import java.security.MessageDigest;import java.io.IOException;import org.json.*;
/** The same bounded contact schema is validated before any provider writes. */
public final class ContactWire {
 public static final String NAME="vnd.android.cursor.item/name",PHONE="vnd.android.cursor.item/phone_v2",EMAIL="vnd.android.cursor.item/email_v2",PHOTO="vnd.android.cursor.item/photo";
 public static final List<String> MIMES=Arrays.asList(NAME,PHONE,EMAIL,PHOTO,"vnd.android.cursor.item/postal-address_v2","vnd.android.cursor.item/organization","vnd.android.cursor.item/website","vnd.android.cursor.item/contact_event","vnd.android.cursor.item/note","vnd.android.cursor.item/nickname");
 public static String hash(String value)throws Exception{return Wire.hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8")));}
 public static void validate(JSONObject feed)throws Exception{
  if(feed.getInt("version")!=1||!feed.getBoolean("complete")||!feed.getString("source").matches("[0-9a-f]{32}")||Math.abs(System.currentTimeMillis()-feed.getLong("generated"))>300000)throw new IOException("Incomplete contacts");
  JSONArray contacts=feed.getJSONArray("contacts");if(contacts.length()>10000)throw new IOException("Contact limit");Set<String> ids=new HashSet<String>();
  for(int i=0;i<contacts.length();i++){JSONObject c=contacts.getJSONObject(i);if(!c.getString("id").matches("[1-9][0-9]{0,18}")||!ids.add(c.getString("id"))||c.getString("name").length()>1024)throw new IOException("Contact identity");JSONArray rows=c.getJSONArray("rows");if(rows.length()>200)throw new IOException("Contact row limit");for(int j=0;j<rows.length();j++){JSONObject row=rows.getJSONObject(j);if(!MIMES.contains(row.getString("mime")))throw new IOException("Contact type");JSONArray v=row.getJSONArray("v");if(v.length()!=14)throw new IOException("Contact fields");for(int k=0;k<14;k++)if(!v.isNull(k)&&(!(v.get(k) instanceof String)||v.getString(k).length()>32768))throw new IOException("Contact value");if(row.has("photo")&&(row.getString("photo").length()>65536||!row.getString("mime").equals(PHOTO)))throw new IOException("Contact image");}}
 }
 public static String fingerprint(JSONObject c)throws Exception{
  TreeSet<String> phones=new TreeSet<String>(),emails=new TreeSet<String>();JSONArray rows=c.getJSONArray("rows");for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);String v=r.getJSONArray("v").optString(0,"");if(r.getString("mime").equals(PHONE))phones.add(v.replaceAll("[ ()-]",""));if(r.getString("mime").equals(EMAIL))emails.add(v.trim().toLowerCase(Locale.US));}
  if(phones.isEmpty()&&emails.isEmpty())return "";return hash(c.getString("name").trim()+"\n"+phones.toString()+"\n"+emails.toString());
 }
}
