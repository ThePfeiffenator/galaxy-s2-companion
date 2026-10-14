package org.retrobridge.galaxychat.pixel;
import org.json.*;import java.util.*;import org.retrobridge.galaxychat.protocol.Wire;

/** Ordinary emoji reactions only. Paid reactions are never submitted. */
final class Reactions {
 final TelegramEngine engine;Reactions(TelegramEngine e){engine=e;}
 static JSONArray summary(JSONObject m)throws Exception {
  JSONArray out=new JSONArray();JSONObject info=m.optJSONObject("interaction_info"),wrapper=info==null?null:info.optJSONObject("reactions");JSONArray rows=wrapper==null?null:wrapper.optJSONArray("reactions");
  for(int i=0;rows!=null&&i<rows.length()&&out.length()<100;i++){JSONObject r=rows.getJSONObject(i),type=r.optJSONObject("type");if(type==null)continue;String emoji=type.optString("emoji","");if(type.optString("@type").equals("reactionTypeEmoji")&&!emoji.isEmpty())out.put(Wire.json("emoji",emoji,"count",r.optInt("total_count"),"chosen",r.optBoolean("is_chosen")));}
  return out;
 }
 JSONObject request(String method,JSONObject p)throws Exception {
  if(!method.equals("GET_REACTIONS")&&!method.equals("SET_REACTION"))return null;
  long chat=TelegramEngine.id(p,"chat_id",false),mid=TelegramEngine.id(p,"message_id",false);engine.allowed(chat,false);
  JSONObject message=engine.call(Wire.json("@type","getMessage","chat_id",chat,"message_id",mid));if(MediaFeatures.ephemeral(message))throw new TelegramEngine.Problem("UNSUPPORTED","Disappearing messages stay on Pixel");
  JSONArray current=summary(message);LinkedHashMap<String,Boolean> choices=new LinkedHashMap<>();
  for(int i=0;i<current.length();i++){JSONObject r=current.getJSONObject(i);if(r.optBoolean("chosen"))choices.put(r.getString("emoji"),true);}
  boolean removing=method.equals("SET_REACTION")&&!p.getBoolean("chosen");
  JSONObject available=removing?new JSONObject():engine.call(Wire.json("@type","getMessageAvailableReactions","chat_id",chat,"message_id",mid,"row_size",6));
  for(String field:new String[]{"top_reactions","recent_reactions","popular_reactions"}){JSONArray rows=available.optJSONArray(field);for(int i=0;rows!=null&&i<rows.length()&&choices.size()<100;i++){JSONObject r=rows.getJSONObject(i),type=r.getJSONObject("type");String emoji=type.optString("emoji","");if(type.optString("@type").equals("reactionTypeEmoji")&&!r.optBoolean("needs_premium")&&!emoji.isEmpty()&&!choices.containsKey(emoji))choices.put(emoji,false);}}
  if(method.equals("GET_REACTIONS")){JSONArray list=new JSONArray();for(Map.Entry<String,Boolean> r:choices.entrySet())list.put(Wire.json("emoji",r.getKey(),"chosen",r.getValue()));return Wire.json("choices",list,"reactions",current);}
  String emoji=p.getString("emoji");boolean chosen=p.getBoolean("chosen");if(emoji.isEmpty()||emoji.length()>32)throw new TelegramEngine.Problem("BAD_REQUEST","Invalid reaction");
  boolean wasChosen=false;for(int i=0;i<current.length();i++)if(emoji.equals(current.getJSONObject(i).getString("emoji")))wasChosen=current.getJSONObject(i).getBoolean("chosen");
  if(chosen&&!choices.containsKey(emoji))throw new TelegramEngine.Problem("FORBIDDEN","This reaction is unavailable here");
  // State-setting semantics make an explicit repeated tap safe; no automatic retry.
  if(chosen!=wasChosen)engine.call(Wire.json("@type",chosen?"addMessageReaction":"removeMessageReaction","chat_id",chat,"message_id",mid,"reaction_type",Wire.json("@type","reactionTypeEmoji","emoji",emoji),"is_big",false,"update_recent_reactions",true));
  return Wire.json("reactions",summary(engine.call(Wire.json("@type","getMessage","chat_id",chat,"message_id",mid))));
 }
}
