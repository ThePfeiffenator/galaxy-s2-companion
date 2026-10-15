package org.retrobridge.galaxychat.s2;
import org.json.*;
public class NoticeChecks {
 static void check(boolean yes){if(!yes)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  JSONObject m=new JSONObject().put("sender","Alice").put("text","Hello\nworld");
  String[] n=MessageNotice.format(new JSONObject().put("title","Alice"),m);check(n[0].equals("Alice")&&n[1].equals("Hello world"));
  n=MessageNotice.format(new JSONObject().put("title","Science group"),m);check(n[0].equals("Science group")&&n[1].equals("Alice: Hello world"));
  m.put("text","").put("media",new JSONObject().put("kind","photo"));n=MessageNotice.format(null,m);check(n[0].equals("Alice")&&n[1].equals("Photo"));
  m.put("media",new JSONObject().put("kind","document").put("name","report.pdf"));check(MessageNotice.format(null,m)[1].equals("report.pdf"));
  String pair="\uD83D\uDE80";String longText=new String(new char[218]).replace('\0','x')+pair+pair;String clipped=MessageNotice.clean(longText,220);check(clipped.length()<=220&&!Character.isHighSurrogate(clipped.charAt(clipped.length()-2)));
  check(MessageNotice.format(null,new JSONObject())[0].equals("Telegram"));System.out.println("Telegram notification checks: 6 passed");
 }
}
