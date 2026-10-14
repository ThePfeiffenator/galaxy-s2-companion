package org.retrobridge.galaxymail.pixel;
import android.content.Context;import android.util.Base64;import org.json.*;import java.io.*;import java.util.*;

/** Resumable private MIME staging. The request never carries a filesystem path. */
final class GmailUploads {
 static final int MAX=8000000,CHUNK=196608;
 static File directory(Context c)throws Exception{File d=new File(c.getFilesDir(),"mail-outgoing");if(!d.isDirectory()&&!d.mkdirs())throw new IOException("Cannot stage attachment");return d;}
 static File file(Context c,String digest)throws Exception{if(!digest.matches("[a-f0-9]{64}"))throw new IOException("Invalid upload identity");return new File(directory(c),GmailActions.digest(MailAccount.email(c).getBytes("UTF-8"))+"-"+digest);}
 static synchronized JSONObject chunk(Context c,JSONObject r)throws Exception{
  String hash=r.getString("sha256"),encoded=r.getString("data");int offset=r.getInt("offset"),total=r.getInt("total");
  if(total<1||total>MAX||offset<0||offset>=total||encoded.length()>CHUNK*4/3+4)throw new IOException("Invalid upload size");
  byte[] data=Base64.decode(encoded,Base64.URL_SAFE|Base64.NO_WRAP);if(data.length<1||data.length>CHUNK||offset+data.length>total)throw new IOException("Invalid upload chunk");
  File f=file(c,hash);long now=System.currentTimeMillis();long bytes=0;
  for(File old:directory(c).listFiles()){if(!old.equals(f)&&now-old.lastModified()>7L*86400000)old.delete();else bytes+=old.length();}
  if(bytes+data.length>64000000)throw new IOException("Outgoing attachment cache full");
  try(RandomAccessFile out=new RandomAccessFile(f,"rw")){
   if(out.length()>total||offset>out.length())throw new IOException("Upload offset mismatch");
   if(offset<out.length()){if(offset+data.length>out.length())throw new IOException("Upload overlap mismatch");byte[] saved=new byte[data.length];out.seek(offset);out.readFully(saved);if(!Arrays.equals(saved,data))throw new IOException("Upload content mismatch");}
   else{out.seek(offset);out.write(data);out.getFD().sync();}
  }
  f.setLastModified(now);if(f.length()==total&&!GmailActions.digest(read(f)).equals(hash)){f.delete();throw new IOException("Upload checksum mismatch");}
  return new JSONObject().put("next",offset+data.length).put("complete",f.length()==total);
 }
 static byte[] read(File f)throws Exception{if(!f.isFile()||f.length()<1||f.length()>MAX)throw new IOException("Outgoing message unavailable");byte[] b=new byte[(int)f.length()];try(DataInputStream in=new DataInputStream(new FileInputStream(f))){in.readFully(b);}return b;}
 static byte[] load(Context c,String hash)throws Exception{byte[] b=read(file(c,hash));if(!GmailActions.digest(b).equals(hash))throw new IOException("Upload checksum mismatch");return b;}
}
