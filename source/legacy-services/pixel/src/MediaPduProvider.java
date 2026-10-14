package org.retrobridge.galaxyservices.pixel;
import android.content.*;import android.database.Cursor;import android.net.Uri;import android.os.ParcelFileDescriptor;import java.io.*;
/** MMS framework receives a read-only URI grant; no exported file access. */
public final class MediaPduProvider extends ContentProvider {
 public boolean onCreate(){return true;}
 public String getType(Uri u){return "application/vnd.wap.mms-message";}
 public ParcelFileDescriptor openFile(Uri u,String mode)throws FileNotFoundException{
  if(!"r".equals(mode)||!"content".equals(u.getScheme())||!"org.retrobridge.galaxyservices.pixel.media".equals(u.getAuthority())||u.getQuery()!=null||u.getFragment()!=null||u.getPath()==null||!u.getPath().matches("/[0-9a-f]{32}"))throw new FileNotFoundException("request");
  return ParcelFileDescriptor.open(new File(new File(getContext().getFilesDir(),"message-media"),u.getLastPathSegment()+".pdu"),ParcelFileDescriptor.MODE_READ_ONLY);
 }
 public Cursor query(Uri u,String[]p,String s,String[]a,String o){return null;}
 public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
 public int delete(Uri u,String s,String[]a){throw new UnsupportedOperationException();}
 public int update(Uri u,ContentValues v,String s,String[]a){throw new UnsupportedOperationException();}
}
