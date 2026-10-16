package org.retrobridge.galaxyservices.pixel;
import android.app.*;import android.content.*;import android.os.*;import android.service.notification.*;import org.json.*;import java.util.*;
import org.retrobridge.galaxyservices.ContactWire;

/** Uses only content Android supplies to the already-authorized listener. */
final class NotificationRelay {
 static volatile RcsNotificationListener listener;
 static boolean excluded(String pkg){
  return pkg.equals("com.google.android.apps.messaging")||pkg.equals("com.google.android.gm")||pkg.startsWith("org.telegram.")||pkg.startsWith("org.retrobridge.")||pkg.equals("android")||pkg.equals("com.android.systemui")||pkg.equals("com.google.android.dialer")||pkg.equals("com.termux")||pkg.equals("com.google.android.gms");
 }
 static String clean(CharSequence value,int max){String s=value==null?"":value.toString().replaceAll("[\\r\\n\\t]+"," ").trim();if(s.length()>max){int end=max-1;if(Character.isHighSurrogate(s.charAt(end-1)))end--;s=s.substring(0,end)+"\u2026";}return s;}
 static JSONObject snapshot(Context app)throws Exception{
  SharedPreferences prefs=app.getSharedPreferences("notification-forwarding",0);JSONObject out=new JSONObject().put("enabled",prefs.getBoolean("enabled",false));
  if(!out.getBoolean("enabled"))return out;
  RcsNotificationListener service=listener;if(service==null)throw new java.io.IOException("Notification access unavailable");
  StatusBarNotification[] active=service.getActiveNotifications();if(active==null)throw new java.io.IOException("Notifications unavailable");
  JSONArray rows=new JSONArray();NotificationListenerService.RankingMap rankings=service.getCurrentRanking();SharedPreferences.Editor known=app.getSharedPreferences("notification-apps",0).edit();
  for(StatusBarNotification sbn:active){String pkg=sbn.getPackageName();Notification n=sbn.getNotification();if(excluded(pkg))continue;
   String label=pkg;try{label=app.getPackageManager().getApplicationLabel(app.getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(Exception ignored){}
   known.putString(pkg,label);if(prefs.getBoolean("exclude:"+pkg,false))continue;
   if((n.flags&(Notification.FLAG_ONGOING_EVENT|Notification.FLAG_FOREGROUND_SERVICE|Notification.FLAG_GROUP_SUMMARY))!=0||Notification.CATEGORY_PROGRESS.equals(n.category)||Notification.CATEGORY_TRANSPORT.equals(n.category)||Notification.CATEGORY_CALL.equals(n.category))continue;
   Bundle extras=n.extras;if(extras==null)continue;String title=clean(extras.getCharSequence(Notification.EXTRA_TITLE),200),body=clean(extras.getCharSequence(Notification.EXTRA_BIG_TEXT),1600);if(body.length()==0)body=clean(extras.getCharSequence(Notification.EXTRA_TEXT),1600);
   Parcelable[] messages=extras.getParcelableArray(Notification.EXTRA_MESSAGES);if(messages!=null&&messages.length>0&&messages[messages.length-1] instanceof Bundle){Bundle last=(Bundle)messages[messages.length-1];String text=clean(last.getCharSequence("text"),1200),sender=clean(last.getCharSequence("sender"),120);if(text.length()>0)body=(sender.length()>0&&!sender.equals(title)?sender+": ":"")+text;}
   if(body.length()==0){CharSequence[] lines=extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);if(lines!=null&&lines.length>0)body=clean(lines[lines.length-1],1600);}
   if(title.length()==0&&body.length()==0)continue;
   NotificationListenerService.Ranking rank=new NotificationListenerService.Ranking();boolean ranked=rankings!=null&&rankings.getRanking(sbn.getKey(),rank);if(ranked&&rank.getImportance()==NotificationManager.IMPORTANCE_NONE)continue;
   rows.put(new JSONObject().put("key",ContactWire.hash(sbn.getKey())).put("revision",revision(sbn)).put("clearable",sbn.isClearable()).put("app",clean(label,120)).put("icon",ForwardedIcons.remember(app,pkg)).put("title",title).put("text",body).put("when",sbn.getPostTime()).put("alert",!ranked||rank.getImportance()>=NotificationManager.IMPORTANCE_DEFAULT));
   if(rows.length()>=200)throw new java.io.IOException("Notification snapshot limit");
  }
  known.apply();return out.put("version",1).put("complete",true).put("generated",System.currentTimeMillis()).put("items",rows);
 }

 static String revision(StatusBarNotification n)throws Exception{
  Bundle e=n.getNotification().extras;JSONArray values=new JSONArray().put(n.getKey()).put(n.getPostTime()).put(n.getNotification().flags);
  if(e!=null){values.put(clean(e.getCharSequence(Notification.EXTRA_TITLE),200)).put(clean(e.getCharSequence(Notification.EXTRA_TEXT),1600)).put(clean(e.getCharSequence(Notification.EXTRA_BIG_TEXT),1600));
   CharSequence[] lines=e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);if(lines!=null)for(CharSequence line:lines)values.put(clean(line,1600));
   Parcelable[] messages=e.getParcelableArray(Notification.EXTRA_MESSAGES);if(messages!=null)for(Parcelable value:messages)if(value instanceof Bundle){Bundle b=(Bundle)value;values.put(clean(b.getCharSequence("text"),1600)).put(clean(b.getCharSequence("sender"),120)).put(b.getLong("time"));}
  }
  return ContactWire.hash(values.toString());
 }
 static JSONObject result(String status)throws Exception{return new JSONObject().put("status",status);}
 /** Only a paired, explicit tap may dismiss a single currently forwarded alert. */
 static synchronized JSONObject dismiss(Context app,String key,String expected)throws Exception{
  if(!key.matches("[0-9a-f]{64}")||!expected.matches("[0-9a-f]{64}"))throw new java.io.IOException("Invalid notification");
  JSONObject feed=snapshot(app);if(!feed.optBoolean("enabled"))return result("disabled");
  JSONArray rows=feed.getJSONArray("items");JSONObject row=null;
  for(int i=0;i<rows.length();i++)if(key.equals(rows.getJSONObject(i).getString("key")))row=rows.getJSONObject(i);
  if(row==null){
   RcsNotificationListener current=listener;if(current==null)throw new java.io.IOException("Notification access unavailable");
   StatusBarNotification[] all=current.getActiveNotifications();if(all==null)throw new java.io.IOException("Notifications unavailable");
   for(StatusBarNotification n:all)if(key.equals(ContactWire.hash(n.getKey())))return result("not_forwarded");
   return result("already_gone");
  }
  if(!expected.equals(row.getString("revision")))return result("changed");
  if(!row.getBoolean("clearable"))return result("not_clearable");
  RcsNotificationListener service=listener;if(service==null)throw new java.io.IOException("Notification access unavailable");
  StatusBarNotification[] active=service.getActiveNotifications();if(active==null)throw new java.io.IOException("Notifications unavailable");
  StatusBarNotification target=null;
  for(StatusBarNotification n:active)if(key.equals(ContactWire.hash(n.getKey())))target=n;
  if(target==null)return result("already_gone");
  // Recheck immediately before the platform call; never clear a whole package or all alerts.
  if(!expected.equals(revision(target)))return result("changed");
  if(!target.isClearable())return result("not_clearable");
  service.cancelNotification(target.getKey());
  for(int attempt=0;attempt<15;attempt++){
   if(listener!=service)throw new java.io.IOException("Notification access disconnected");
   StatusBarNotification[] remaining=service.getActiveNotifications(new String[]{target.getKey()});
   if(remaining==null)throw new java.io.IOException("Notifications unavailable");
   if(remaining.length==0)return result("cleared");
   if(!expected.equals(revision(remaining[0])))return result("changed");
   Thread.sleep(100);
  }
  return result("not_cleared");
 }
}
