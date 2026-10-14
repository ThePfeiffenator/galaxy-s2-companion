package org.retrobridge.galaxyservices;

import java.io.*;

/** Private wire format: one recipient, one attachment, optional plain caption. */
public final class MediaPayload {
 public static final int MAX_MEDIA=2*1024*1024, MAX_WIRE=MAX_MEDIA+65536;
 public final String number,mime,caption; public final byte[] media;
 /** Samsung can resize a PNG into JPEG without updating the provider MIME. */
 public static String imageMime(byte[] bytes)throws IOException{
  if(bytes==null||bytes.length<12)throw new IOException("image");
  if((bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255)return "image/jpeg";
  if(bytes[0]==(byte)137&&bytes[1]==80&&bytes[2]==78&&bytes[3]==71&&bytes[4]==13&&bytes[5]==10&&bytes[6]==26&&bytes[7]==10)return "image/png";
  if(new String(bytes,0,6,"US-ASCII").matches("GIF8[79]a"))return "image/gif";
  throw new IOException("image format");
 }
 public MediaPayload(String number,String mime,String caption,byte[] media)throws IOException{
  if(number==null||!number.matches("\\+?[0-9]{5,15}"))throw new IOException("recipient");
  if(caption==null||caption.length()>10000)throw new IOException("caption");
  if(media==null||media.length<12||media.length>MAX_MEDIA)throw new IOException("size");
  boolean valid=false;
  if("image/jpeg".equals(mime))valid=(media[0]&255)==255&&(media[1]&255)==216&&(media[2]&255)==255;
  else if("image/png".equals(mime))valid=media[0]==(byte)137&&media[1]==80&&media[2]==78&&media[3]==71&&media[4]==13&&media[5]==10&&media[6]==26&&media[7]==10;
  else if("image/gif".equals(mime))valid=new String(media,0,6,"US-ASCII").matches("GIF8[79]a");
  else if("video/mp4".equals(mime)||"video/3gpp".equals(mime))valid="ftyp".equals(new String(media,4,4,"US-ASCII"));
  if(!valid)throw new IOException("type");
  this.number=number;this.mime=mime;this.caption=caption;this.media=media;
 }
 public byte[] encode()throws IOException{
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
  out.writeInt(0x53324d31);out.writeUTF(number);out.writeUTF(mime);out.writeUTF(caption);out.writeInt(media.length);out.write(media);out.flush();return bytes.toByteArray();
 }
 public static MediaPayload decode(byte[] bytes)throws IOException{
  if(bytes.length>MAX_WIRE)throw new IOException("size");
  DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=0x53324d31)throw new IOException("version");
  String number=in.readUTF(),mime=in.readUTF(),caption=in.readUTF();int size=in.readInt();
  if(size<12||size>MAX_MEDIA||size!=in.available())throw new IOException("size");
  byte[] media=new byte[size];in.readFully(media);return new MediaPayload(number,mime,caption,media);
 }
}
