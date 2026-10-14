package org.retrobridge.galaxychat.s2;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.util.Base64;
import org.retrobridge.galaxychat.protocol.Wire;
import org.json.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Files stay private and transfers never hold a whole attachment in memory. */
final class MediaClient {
 static final long MAX=100L*1024*1024;
 interface Result {void done(File file,String mime,String name,String error);}
 final ChatService service;final File root;final Handler main=new Handler();
 final ExecutorService downloads=Executors.newSingleThreadExecutor(),uploads=Executors.newSingleThreadExecutor();
 final Set<String> active=Collections.synchronizedSet(new HashSet<String>());
 final Map<String,Long> failures=Collections.synchronizedMap(new HashMap<String,Long>());
 volatile String progress="";volatile boolean uploading;volatile boolean stopped;
 MediaClient(ChatService s){service=s;root=new File(s.getCacheDir(),"media");root.mkdirs();File[] old=root.listFiles();if(old!=null)for(File f:old)if(f.getName().endsWith(".part"))f.delete();trim(0);}
 void close(){stopped=true;downloads.shutdownNow();uploads.shutdownNow();}
 static String hash(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");InputStream in=new FileInputStream(f);try{byte[] b=new byte[16384];for(int n;(n=in.read(b))>=0;)d.update(b,0,n);}finally{in.close();}return hex(d.digest());}
 static String hex(byte[] a){StringBuilder b=new StringBuilder();for(byte v:a)b.append(String.format(Locale.US,"%02x",v&255));return b.toString();}
 static String key(String s){try{return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")));}catch(Exception e){throw new IllegalStateException(e);}}
 File cached(String key){File f=new File(root,key+".data");return f.isFile()&&new File(root,key+".json").isFile()?f:null;}
 File preview(final String chat,final String message,final int file,final String variant){final String k=key("image-v2:"+chat+":"+message+":"+file+":"+variant);File f=cached(k);if(f!=null)return f;if(!service.connected()||stopped)return null;Long failed=failures.get(k);if(failed!=null&&SystemClock.elapsedRealtime()-failed<60000)return null;if(active.size()<48&&active.add(k))downloads.execute(new Runnable(){public void run(){try{download(k,"MEDIA_INFO",Wire.json("chat_id",chat,"message_id",message,"variant",variant),false);service.mediaChanged();}catch(Exception e){recordFailure(k);}finally{active.remove(k);}}});return null;}
 File stickerPreview(final int id){final String k=key("sticker-v2:"+id);File f=cached(k);if(f!=null)return f;Long failed=failures.get(k);if(failed!=null&&SystemClock.elapsedRealtime()-failed<60000)return null;if(!stopped&&active.size()<48&&active.add(k))downloads.execute(new Runnable(){public void run(){try{download(k,"STICKER_PREVIEW",Wire.json("file_id",id),false);service.mediaChanged();}catch(Exception e){recordFailure(k);}finally{active.remove(k);}}});return null;}
 void open(final JSONObject message,final Result callback){final JSONObject m=message.optJSONObject("media");if(m==null)return;final String k=key(message.optString("chat_id")+":"+message.optString("id")+":"+m.optInt("file_id")+":full");if(!active.add(k))return;
  downloads.execute(new Runnable(){public void run(){File f=null;String mime="",name="",error=null;try{f=download(k,"MEDIA_INFO",Wire.json("chat_id",message.optString("chat_id"),"message_id",message.optString("id"),"variant","full"),true);JSONObject meta=metadata(k);mime=meta.optString("mime");name=meta.optString("name");}catch(Exception e){error=friendly(e);}finally{active.remove(k);setProgress("");}final File file=f;final String type=mime,label=name,err=error;main.post(new Runnable(){public void run(){callback.done(file,type,label,err);}});}});
 }
 private JSONObject metadata(String k)throws Exception{File f=new File(root,k+".json");if(f.length()>4096)throw new IOException("Invalid file metadata");FileInputStream in=new FileInputStream(f);try{byte[] b=new byte[(int)f.length()];new DataInputStream(in).readFully(b);return new JSONObject(new String(b,"UTF-8"));}finally{in.close();}}
 private File download(String k,String method,JSONObject args,boolean report)throws Exception{
  File existing=cached(k);if(existing!=null){existing.setLastModified(System.currentTimeMillis());return existing;}if(report)setProgress("Downloading…");JSONObject info=null;long until=SystemClock.elapsedRealtime()+120000;
  while(!stopped&&SystemClock.elapsedRealtime()<until){info=service.rpc(method,args);if(info.optBoolean("ready"))break;Thread.sleep(700);}if(info==null||!info.optBoolean("ready"))throw new IOException("Download is not ready. Try again.");
  long size=info.getLong("size");if(size<1||size>MAX||root.getUsableSpace()<size+8L*1024*1024)throw new IOException("Attachment is too large or storage is full");String token=info.getString("token");File partial=new File(root,k+".part"),done=new File(root,k+".data");trim(size);
  try{FileOutputStream out=new FileOutputStream(partial);try{long at=0;while(at<size){if(stopped)throw new InterruptedIOException();JSONObject r=service.rpc("READ_MEDIA",Wire.json("token",token,"offset",at,"limit",16384));String raw=r.getString("data");if(raw.length()>22000||r.getLong("offset")!=at)throw new IOException("Invalid file chunk");byte[] b=Base64.decode(raw,Base64.NO_WRAP);if(b.length<1||b.length>16384||at+b.length>size)throw new IOException("Invalid file chunk");out.write(b);at+=b.length;if(report)setProgress("Downloading "+(100*at/size)+"%");}out.getFD().sync();}finally{out.close();}if(!partial.renameTo(done))throw new IOException("Could not save attachment");FileOutputStream meta=new FileOutputStream(new File(root,k+".json"));try{meta.write(Wire.utf8(Wire.json("mime",info.optString("mime"),"name",info.optString("name")).toString()));meta.getFD().sync();}finally{meta.close();}return done;}catch(Exception e){partial.delete();done.delete();throw e;}
 }
 void send(final String chat,final File source,final String name,final String mime,final String kind,final String caption,final String reply){if(uploading)throw new IllegalStateException("An attachment is already sending");uploading=true;setProgress("Preparing attachment…");
  uploads.execute(new Runnable(){public void run(){String operation=null;boolean submitted=false;try{if(source.length()<1||source.length()>MAX)throw new IOException("Attachments are limited to 100 MB");String sha=hash(source),upload=ClientRules.uuid(Wire.random(16));operation=service.prepareMedia(chat,caption.length()>0?caption:"["+kind+"]",reply);JSONObject begin=service.rpc("UPLOAD_BEGIN",Wire.json("upload_id",upload,"size",source.length(),"sha256",sha,"name",name,"mime",mime));long offset=begin.getLong("offset");if(offset<0||offset>source.length())throw new IOException("Invalid upload offset");RandomAccessFile in=new RandomAccessFile(source,"r");try{in.seek(offset);byte[] buffer=new byte[16384];for(int n;(n=in.read(buffer))!=-1;){if(stopped)throw new InterruptedIOException();byte[] chunk=n==buffer.length?buffer:Arrays.copyOf(buffer,n);JSONObject r=service.rpc("UPLOAD_CHUNK",Wire.json("upload_id",upload,"offset",offset,"data",Base64.encodeToString(chunk,Base64.NO_WRAP)));offset+=n;if(r.getLong("offset")!=offset)throw new IOException("Upload offset changed");setProgress("Sending "+(100*offset/source.length())+"%");}}finally{in.close();}
    submitted=true;JSONObject state=service.rpc("SEND_MEDIA",Wire.json("operation_id",operation,"upload_id",upload,"chat_id",chat,"reply_to_id",reply,"caption",caption,"media_kind",kind));service.mediaState(state);setProgress("");
   }catch(Exception e){if(operation!=null)service.mediaFailure(operation,submitted,friendly(e));setProgress(friendly(e));}finally{uploading=false;source.delete();service.mediaChanged();}}});
 }
 void stickers(final String set,final int offset,final ChatService.DataResult callback){uploads.execute(new Runnable(){public void run(){JSONObject data=null;String error=null;try{data=service.rpc("STICKERS",Wire.json("set_id",set,"offset",offset));}catch(Exception e){error=friendly(e);}final JSONObject d=data;final String err=error;main.post(new Runnable(){public void run(){callback.done(d,err);}});}});}
 void stickerSets(final ChatService.DataResult callback){uploads.execute(new Runnable(){public void run(){JSONObject data=null;String error=null;try{data=service.rpc("STICKER_SETS",Wire.json());}catch(Exception e){error=friendly(e);}final JSONObject d=data;final String err=error;main.post(new Runnable(){public void run(){callback.done(d,err);}});}});}
 void sendSticker(final String chat,final int id,final String reply){uploads.execute(new Runnable(){public void run(){String op=null;boolean sent=false;try{op=service.prepareMedia(chat,"[Sticker]",reply);sent=true;service.mediaState(service.rpc("SEND_STICKER",Wire.json("operation_id",op,"chat_id",chat,"reply_to_id",reply,"file_id",id)));}catch(Exception e){if(op!=null)service.mediaFailure(op,sent,friendly(e));}service.mediaChanged();}});}
 private void setProgress(String s){if(!s.equals(progress)){progress=s;service.mediaChanged();}}
 private static String friendly(Exception e){return e instanceof IOException&&e.getMessage()!=null?e.getMessage():"Attachment failed. Check the connection.";}
 private void recordFailure(String k){if(failures.size()>256)failures.clear();failures.put(k,SystemClock.elapsedRealtime());}
 private void trim(long reserve){File[] all=root.listFiles();if(all==null)return;Arrays.sort(all,new Comparator<File>(){public int compare(File a,File b){return Long.valueOf(a.lastModified()).compareTo(b.lastModified());}});long total=0;for(File f:all)total+=f.length();for(File f:all)if(total+reserve>192L*1024*1024&&f.getName().endsWith(".data")){String k=f.getName().substring(0,f.getName().length()-5);if(active.contains(k))continue;long length=f.length();if(f.delete()){new File(root,k+".json").delete();total-=length;}}}
}
