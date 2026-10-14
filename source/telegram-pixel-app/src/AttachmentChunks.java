package org.retrobridge.galaxychat.pixel;
import java.io.*;
import java.security.MessageDigest;

/** File boundaries and idempotent chunks; also exercised without Android. */
final class AttachmentChunks {
 static final long MAX=100L*1024*1024;
 static final int CHUNK=16384;
 static void validate(long size,String sha)throws IOException {
  if(size<1||size>MAX||sha==null||!sha.matches("[a-f0-9]{64}"))throw new IOException("Invalid attachment size or hash");
 }
 static long append(File file,long size,long offset,byte[] data)throws IOException {
  if(size<1||size>MAX||data==null||data.length<1||data.length>CHUNK||offset<0||offset>size-data.length)throw new IOException("Invalid chunk");
  try(RandomAccessFile f=new RandomAccessFile(file,"rw")) {
   long end=f.length();if(end>size||offset>end)throw new IOException("Upload gap");
   f.seek(offset);
   if(offset<end) {
    if(data.length>end-offset)throw new IOException("Overlapping chunk");
    byte[] old=new byte[data.length];f.readFully(old);
    if(!MessageDigest.isEqual(old,data))throw new IOException("Conflicting chunk");
   } else {f.write(data);f.getFD().sync();}
   return f.length();
  }
 }
 static int range(long size,long offset,int limit)throws IOException {
  if(size<1||size>MAX||offset<0||offset>=size||limit<1||limit>CHUNK)throw new IOException("Invalid download range");
  return (int)Math.min(limit,size-offset);
 }
}
