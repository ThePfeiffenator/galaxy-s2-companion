import org.json.*;import org.retrobridge.galaxyservices.ContactWire;
public class ContactChecks {
 static void check(boolean v){if(!v)throw new AssertionError();}
 static JSONObject row(String mime,String text)throws Exception{JSONArray values=new JSONArray();values.put(text);for(int i=1;i<14;i++)values.put(JSONObject.NULL);return new JSONObject().put("mime",mime).put("v",values);}
 static void reject(JSONObject feed)throws Exception{try{ContactWire.validate(feed);throw new AssertionError("Accepted bad snapshot");}catch(java.io.IOException expected){}}
 public static void main(String[] args)throws Exception{
  JSONObject contact=new JSONObject().put("id","1").put("name","Example").put("rows",new JSONArray().put(row(ContactWire.PHONE,"+1 (555) 010-0200")));
  JSONObject feed=new JSONObject().put("version",1).put("complete",true).put("source","00000000000000000000000000000001").put("generated",System.currentTimeMillis()).put("contacts",new JSONArray().put(contact));ContactWire.validate(feed);
  JSONObject other=new JSONObject(contact.toString());other.getJSONArray("rows").getJSONObject(0).getJSONArray("v").put(0,"+15550100200");check(ContactWire.fingerprint(contact).equals(ContactWire.fingerprint(other)));
  reject(new JSONObject(feed.toString()).put("complete",false));reject(new JSONObject(feed.toString()).put("generated",0));reject(new JSONObject(feed.toString()).put("contacts",new JSONArray().put(contact).put(contact)));
  JSONObject bad=new JSONObject(feed.toString());bad.getJSONArray("contacts").getJSONObject(0).getJSONArray("rows").getJSONObject(0).put("mime","unknown");reject(bad);
  bad=new JSONObject(feed.toString());bad.getJSONArray("contacts").getJSONObject(0).getJSONArray("rows").getJSONObject(0).put("v",new JSONArray());reject(bad);
  check(ContactWire.fingerprint(new JSONObject().put("name","No numbers").put("rows",new JSONArray())).isEmpty());System.out.println("Contact validation and matching checks: 8 passed");
 }
}
