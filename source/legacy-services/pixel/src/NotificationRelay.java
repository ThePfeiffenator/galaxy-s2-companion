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
   rows.put(new JSONObject().put("key",ContactWire.hash(sbn.getKey())).put("app",clean(label,120)).put("icon",ForwardedIcons.remember(app,pkg)).put("title",title).put("text",body).put("when",sbn.getPostTime()).put("alert",!ranked||rank.getImportance()>=NotificationManager.IMPORTANCE_DEFAULT));
   if(rows.length()>=200)throw new java.io.IOException("Notification snapshot limit");
  }
  known.apply();return out.put("version",1).put("complete",true).put("generated",System.currentTimeMillis()).put("items",rows);
 }
}
