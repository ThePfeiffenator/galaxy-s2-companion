package org.retrobridge.galaxymail.pixel;

import java.io.IOException;
import java.util.*;
import org.json.*;

/** The complete write allowlist. Neither companion can supply a Google URL. */
final class YouTubeActionSpec {
    final String method,path; final JSONObject body;
    YouTubeActionSpec(String m,String p,JSONObject b){method=m;path=p;body=b;}
    static String value(JSONObject p,String key,String regex)throws Exception {
        Object v=p.opt(key);if(!(v instanceof String)||!((String)v).matches(regex))throw new IOException("Invalid "+key);return (String)v;
    }
    static String text(JSONObject p,String key,int max)throws Exception {
        Object v=p.opt(key);if(!(v instanceof String))throw new IOException("Missing "+key);
        String s=(String)v;if(s.length()>max||s.indexOf('\u0000')>=0)throw new IOException("Invalid "+key);return s;
    }
    static void keys(JSONObject p,String allowed)throws Exception {
        Set<String> set=new HashSet<String>(Arrays.asList(allowed.split(",")));
        Iterator<String> it=p.keys();while(it.hasNext())if(!set.contains(it.next()))throw new IOException("Unknown action field");
    }
    static YouTubeActionSpec parse(String op,JSONObject p)throws Exception {
        String id,method="POST",path;JSONObject body=null;
        if(op.equals("rate")){
            keys(p,"video,rating");id=value(p,"video","[A-Za-z0-9_-]{11}");
            path="videos/rate?id="+id+"&rating="+value(p,"rating","like|dislike|none");
        }else if(op.equals("subscribe")){
            keys(p,"channel");id=value(p,"channel","UC[A-Za-z0-9_-]{22}");path="subscriptions?part=snippet";
            body=new JSONObject().put("snippet",new JSONObject().put("resourceId",new JSONObject().put("kind","youtube#channel").put("channelId",id)));
        }else if(op.equals("unsubscribe")||op.equals("playlistDelete")||op.equals("playlistRemove")){
            keys(p,"id");id=value(p,"id","[A-Za-z0-9_-]{10,512}");method="DELETE";
            path=(op.equals("unsubscribe")?"subscriptions":op.equals("playlistDelete")?"playlists":"playlistItems")+"?id="+id;
        }else if(op.equals("playlistCreate")||op.equals("playlistUpdate")){
            keys(p,op.equals("playlistCreate")?"title,description,privacy":"id,title,description,privacy");
            String title=text(p,"title",150);if(title.trim().isEmpty())throw new IOException("Playlist title required");
            body=new JSONObject().put("snippet",new JSONObject().put("title",title).put("description",text(p,"description",5000)));
            // Update receives all current snippet fields from the owner UI. Never silently change privacy.
            path="playlists?part=snippet";
            if(p.has("privacy")){body.put("status",new JSONObject().put("privacyStatus",value(p,"privacy","private|public|unlisted")));path+=",status";}
            if(op.equals("playlistCreate")&&!p.has("privacy"))throw new IOException("Choose playlist privacy");
            if(op.equals("playlistUpdate")){method="PUT";body.put("id",value(p,"id","[A-Za-z0-9_-]{10,80}"));}
        }else if(op.equals("playlistAdd")){
            keys(p,"playlist,video");path="playlistItems?part=snippet,contentDetails";
            body=new JSONObject().put("snippet",new JSONObject().put("playlistId",value(p,"playlist","[A-Za-z0-9_-]{10,80}")).put("resourceId",new JSONObject().put("kind","youtube#video").put("videoId",value(p,"video","[A-Za-z0-9_-]{11}"))));
        }else if(op.equals("comment")){
            keys(p,"video,text");String message=text(p,"text",500);if(message.trim().isEmpty())throw new IOException("Comment text required");
            path="commentThreads?part=snippet";
            body=new JSONObject().put("snippet",new JSONObject().put("videoId",value(p,"video","[A-Za-z0-9_-]{11}")).put("topLevelComment",new JSONObject().put("snippet",new JSONObject().put("textOriginal",message))));
        }else throw new IOException("Unsupported YouTube action");
        return new YouTubeActionSpec(method,path,body);
    }
}
