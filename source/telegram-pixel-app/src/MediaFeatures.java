package org.retrobridge.galaxychat.pixel;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import org.retrobridge.galaxychat.protocol.Wire;
import org.json.*;
import java.io.*;
import java.util.*;

/** Chat-scoped media methods layered on the same authenticated session. */
final class MediaFeatures {
 final TelegramEngine e;final MediaFiles files;final LinkedHashMap<Integer,JSONObject> stickers=new LinkedHashMap<>();
 MediaFeatures(TelegramEngine engine){e=engine;files=new MediaFiles(e.context);}
 static boolean ephemeral(JSONObject m){return m.optJSONObject("self_destruct_type")!=null||m.optDouble("auto_delete_in",0)>0||m.optJSONObject("ephemeral_content")!=null||m.optJSONObject("content")!=null&&m.optJSONObject("content").optBoolean("is_secret");}
 static String category(JSONObject m){String t=m.optJSONObject("content")==null?"":m.optJSONObject("content").optString("@type");return t.equals("messagePhoto")?"photo":t.equals("messageVideo")||t.equals("messageVideoNote")?"video":t.equals("messageAnimation")?"animation":t.equals("messageVoiceNote")?"voice":t.equals("messageAudio")?"audio":t.equals("messageDocument")?"document":t.equals("messageSticker")?"sticker":"";}
 static JSONObject object(JSONObject m){JSONObject c=m.optJSONObject("content");String type=c.optString("@type");return c.optJSONObject(type.equals("messageVideoNote")?"video_note":type.equals("messageVoiceNote")?"voice_note":category(m));}
 static JSONObject selected(JSONObject m,boolean preview)throws Exception{
  String kind=category(m);JSONObject o=object(m);if(o==null)return null;
  if(kind.equals("photo")){JSONArray sizes=o.optJSONArray("sizes");JSONObject best=null;long delta=Long.MAX_VALUE;for(int i=0;sizes!=null&&i<sizes.length();i++){JSONObject s=sizes.getJSONObject(i);long edge=Math.max(s.optInt("width"),s.optInt("height")),d=preview?Math.abs(edge-420):100000-edge;if(d<delta){delta=d;best=s.optJSONObject("photo");}}return best;}
  if(preview){JSONObject t=o.optJSONObject("thumbnail");if(t==null)t=o.optJSONObject("album_cover_thumbnail");if(t!=null)return t.optJSONObject("file");if(kind.equals("sticker")&&o.optJSONObject("format")!=null&&o.optJSONObject("format").optString("@type").equals("stickerFormatWebp"))return o.optJSONObject("sticker");return null;}
  String field=kind.equals("voice")?"voice":m.optJSONObject("content").optString("@type").equals("messageVideoNote")?"video":kind;return o.optJSONObject(field);
 }
 static JSONObject describe(JSONObject m)throws Exception{
  String kind=category(m);if(kind.isEmpty()||ephemeral(m))return null;JSONObject o=object(m),f=selected(m,false);if(f==null)return null;
  String name=o.optString("file_name",kind.equals("photo")?"photo.jpg":kind.equals("voice")?"voice.ogg":kind.equals("sticker")?"sticker.webp":kind.equals("video")?"video.mp4":"attachment");
  String mime=o.optString("mime_type",kind.equals("photo")?"image/jpeg":kind.equals("sticker")?"image/webp":kind.equals("video")||kind.equals("animation")?"video/mp4":kind.equals("voice")?"audio/ogg":"application/octet-stream");
  return Wire.json("kind",kind,"file_id",f.optInt("id"),"size",f.optLong("size"),"name",MediaFiles.name(name),"mime",MediaFiles.safeMime(mime),"width",o.optInt("width"),"height",o.optInt("height"),"duration",o.optInt("duration"),"preview",selected(m,true)!=null,"emoji",o.optString("emoji",""));
 }
 JSONObject request(String method,JSONObject p)throws Exception{
  if(method.equals("UPLOAD_BEGIN"))return files.begin(p);
  if(method.equals("UPLOAD_CHUNK"))return files.chunk(p);
  if(method.equals("READ_MEDIA"))return files.read(p);
  if(method.equals("MEDIA_INFO"))return info(p);
  if(method.equals("STICKER_SETS")){JSONArray raw=e.call(Wire.json("@type","getInstalledStickerSets","sticker_type",Wire.json("@type","stickerTypeRegular"))).getJSONArray("sets"),out=new JSONArray();for(int i=0;i<raw.length()&&i<200;i++){JSONObject s=raw.getJSONObject(i);out.put(Wire.json("id",s.optString("id"),"title",s.optString("title"),"count",s.optInt("size")));}return Wire.json("sets",out);}
  if(method.equals("STICKERS"))return stickerList(p);
  if(method.equals("STICKER_PREVIEW")){JSONObject s=stickers.get(p.getInt("file_id"));if(s==null)throw new IOException("Reload stickers");JSONObject t=s.optJSONObject("thumbnail"),f=t==null?s.optJSONObject("sticker"):t.optJSONObject("file");return download(f,"preview","image/webp","sticker.webp");}
  if(method.equals("SEND_MEDIA"))return send(p,false);
  if(method.equals("SEND_STICKER"))return send(p,true);
  return null;
 }
 private JSONObject info(JSONObject p)throws Exception{
  long chat=TelegramEngine.id(p,"chat_id",false);e.allowed(chat,false);String variant=p.optString("variant","full");JSONObject f;String mode,mime,name;
  if(variant.equals("icon")){JSONObject c=e.call(Wire.json("@type","getChat","chat_id",chat)),photo=c.optJSONObject("photo");if(photo==null)throw new TelegramEngine.Problem("NOT_FOUND","No chat photo");f=photo.optJSONObject("small");mode="icon";mime="image/jpeg";name="avatar.jpg";}
  else{if(!variant.equals("full")&&!variant.equals("preview"))throw new IOException("Invalid media variant");JSONObject m=e.call(Wire.json("@type","getMessage","chat_id",chat,"message_id",TelegramEngine.id(p,"message_id",false)));if(ephemeral(m))throw new TelegramEngine.Problem("UNSUPPORTED","Disappearing media stays on Pixel");JSONObject d=describe(m);if(d==null)throw new TelegramEngine.Problem("NOT_FOUND","No attachment");boolean staticSticker=d.optString("kind").equals("sticker")&&!object(m).optJSONObject("format").optString("@type").equals("stickerFormatWebp");f=selected(m,variant.equals("preview")||staticSticker);mime=d.optString("mime");name=d.optString("name");mode=variant.equals("preview")||d.optString("kind").equals("sticker")?"preview":d.optString("kind").equals("voice")?"voice":"full";}
  return download(f,mode,mime,name);
 }
 private JSONObject download(JSONObject f,String mode,String mime,String name)throws Exception{
  if(f==null)throw new TelegramEngine.Problem("NOT_FOUND","No preview available");if(Math.max(f.optLong("size"),f.optLong("expected_size"))>MediaFiles.MAX)throw new TelegramEngine.Problem("UNSUPPORTED","Attachments are limited to 100 MB on this S2");
  f=e.call(Wire.json("@type","getFile","file_id",f.getInt("id")));JSONObject local=f.getJSONObject("local");if(!local.optBoolean("is_downloading_completed")){if(!local.optBoolean("is_downloading_active"))e.call(Wire.json("@type","downloadFile","file_id",f.getInt("id"),"priority",16,"offset",0,"limit",0,"synchronous",false));return Wire.json("ready",false);}
  return files.prepare(new File(local.getString("path")),f.getInt("id"),mode,mime,name);
 }
 private JSONObject stickerList(JSONObject p)throws Exception{
  String set=p.optString("set_id","recent");JSONArray raw;
  if(set.equals("recent"))raw=e.call(Wire.json("@type","getRecentStickers","is_attached",false)).getJSONArray("stickers");
  else if(set.equals("favorites"))raw=e.call(Wire.json("@type","getFavoriteStickers")).getJSONArray("stickers");
  else raw=e.call(Wire.json("@type","getStickerSet","set_id",TelegramEngine.id(Wire.json("set",set),"set",false))).getJSONArray("stickers");
  int offset=p.optInt("offset",0);if(offset<0||offset>10000)throw new IOException("Invalid sticker page");JSONArray out=new JSONArray();int at=offset;
  for(;at<raw.length()&&out.length()<24;at++){JSONObject s=raw.getJSONObject(at);int id=s.getJSONObject("sticker").getInt("id");stickers.put(id,s);out.put(Wire.json("file_id",id,"emoji",s.optString("emoji"),"width",s.optInt("width"),"height",s.optInt("height")));}while(stickers.size()>2000)stickers.remove(stickers.keySet().iterator().next());return Wire.json("stickers",out,"next",at,"more",at<raw.length());
 }
 private JSONObject send(JSONObject p,boolean sticker)throws Exception{
  String op=TelegramEngine.op(p),caption=p.optString("caption","");long chat=TelegramEngine.id(p,"chat_id",false),reply=TelegramEngine.id(p,"reply_to_id",true);if(caption.codePointCount(0,caption.length())>1024||Wire.utf8(caption).length>4096)throw new IOException("Caption too long");
  String fingerprint=TelegramEngine.digest((sticker?"sticker":"media")+"\n"+chat+"\n"+reply+"\n"+p.optString("upload_id")+"\n"+p.optInt("file_id")+"\n"+p.optString("media_kind")+"\n"+caption);JSONObject prior=e.journal.get(op);if(prior!=null){if(!fingerprint.equals(prior.optString("digest")))throw new TelegramEngine.Problem("CONFLICT","Send identifier reused");return SendJournal.publicState(prior);}
  JSONObject input;String kind=sticker?"sticker":p.getString("media_kind");JSONObject cap=Wire.json("@type","formattedText","text",caption,"entities",new JSONArray());
  if(sticker){JSONObject s=stickers.get(p.getInt("file_id"));if(s==null)throw new IOException("Reload stickers");input=Wire.json("@type","inputMessageSticker","sticker",Wire.json("@type","inputSticker","sticker",Wire.json("@type","inputFileId","id",p.getInt("file_id")),"width",s.optInt("width"),"height",s.optInt("height")),"emoji",s.optString("emoji"));}
  else{File f=files.upload(p);JSONObject file=Wire.json("@type","inputFileLocal","path",f.getAbsolutePath());int duration=0,width=0,height=0;
   if(kind.equals("photo")){if(f.length()>10L*1024*1024)throw new IOException("Photo exceeds 10 MB");BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getPath(),o);width=o.outWidth;height=o.outHeight;if(width<1||height<1||width+height>10000)throw new IOException("Invalid photo dimensions");input=Wire.json("@type","inputMessagePhoto","photo",Wire.json("@type","inputPhoto","photo",file,"width",width,"height",height,"added_sticker_file_ids",new JSONArray()),"caption",cap);}
   else if(kind.equals("video")||kind.equals("voice")||kind.equals("audio")){MediaMetadataRetriever r=new MediaMetadataRetriever();try{r.setDataSource(f.getPath());duration=(int)((number(r.extractMetadata(9))+999)/1000);width=(int)number(r.extractMetadata(18));height=(int)number(r.extractMetadata(19));}finally{r.release();}
    if(kind.equals("video"))input=Wire.json("@type","inputMessageVideo","video",Wire.json("@type","inputVideo","video",file,"duration",duration,"width",width,"height",height,"supports_streaming",true,"added_sticker_file_ids",new JSONArray()),"caption",cap);
    else if(kind.equals("voice"))input=Wire.json("@type","inputMessageVoiceNote","voice_note",Wire.json("@type","inputVoiceNote","voice_note",file,"duration",duration,"waveform",""),"caption",cap);
    else input=Wire.json("@type","inputMessageAudio","audio",Wire.json("@type","inputAudio","audio",file,"duration",duration,"title",files.uploadMeta(p).optString("name"),"performer",""),"caption",cap);
   }else if(kind.equals("document"))input=Wire.json("@type","inputMessageDocument","document",Wire.json("@type","inputDocument","document",file,"disable_content_type_detection",true),"caption",cap);
   else throw new IOException("Unsupported attachment type");
  }
  return e.submit(op,fingerprint,chat,reply,input);
 }
 private static long number(String n){try{return Long.parseLong(n);}catch(Exception e){return 0;}}
}
