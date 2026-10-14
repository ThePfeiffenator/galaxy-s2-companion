package org.retrobridge.galaxychat.s2;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
/** Read-only URI grants to a single completed private attachment. */
public final class MediaProvider extends ContentProvider {
 public boolean onCreate(){return true;}
 private File file(Uri u)throws FileNotFoundException{String n=u.getLastPathSegment();if(n==null||!n.matches("[a-f0-9]{64}\\.data"))throw new FileNotFoundException();File f=new File(new File(getContext().getCacheDir(),"media"),n);if(!f.isFile())throw new FileNotFoundException();return f;}
 public ParcelFileDescriptor openFile(Uri u,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(file(u),ParcelFileDescriptor.MODE_READ_ONLY);}
 public String getType(Uri u){String type=u.getQueryParameter("mime");return type!=null&&type.matches("[a-zA-Z0-9][a-zA-Z0-9.+-]*/[a-zA-Z0-9][a-zA-Z0-9.+-]*")?type:"application/octet-stream";}
 public Cursor query(Uri u,String[] projection,String selection,String[] args,String sort){try{File f=file(u);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor c=new MatrixCursor(columns);Object[] row=new Object[columns.length];for(int i=0;i<columns.length;i++)row[i]=OpenableColumns.SIZE.equals(columns[i])?Long.valueOf(f.length()):OpenableColumns.DISPLAY_NAME.equals(columns[i])?u.getQueryParameter("name"):null;c.addRow(row);return c;}catch(Exception e){return null;}}
 public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
