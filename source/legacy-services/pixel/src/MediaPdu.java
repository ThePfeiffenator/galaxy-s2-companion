package org.retrobridge.galaxyservices.pixel;
import android.content.Context;import org.retrobridge.galaxyservices.MediaPayload;
import org.retrobridge.galaxyservices.codec.pdu.*;import java.io.*;import java.util.Arrays;

final class MediaPdu {
 static byte[] encode(Context c,String id,MediaPayload data)throws Exception{
  if(!id.matches("[0-9a-f]{32}"))throw new IOException("id");
  boolean photo=data.mime.startsWith("image/");String ext=data.mime.equals("image/jpeg")?"jpg":data.mime.equals("image/png")?"png":data.mime.equals("image/gif")?"gif":data.mime.equals("video/3gpp")?"3gp":"mp4";
  String name="attachment."+ext;
  String smil="<smil><head><layout><root-layout width=\"320\" height=\"320\"/><region id=\"Media\" left=\"0\" top=\"0\" width=\"320\" height=\"240\" fit=\"meet\"/><region id=\"Text\" left=\"0\" top=\"240\" width=\"320\" height=\"80\"/></layout></head><body><par dur=\"5000ms\"><"+(photo?"img":"video")+" src=\""+name+"\" region=\"Media\"/>"+(data.caption.length()>0?"<text src=\"caption.txt\" region=\"Text\"/>":"")+"</par></body></smil>";
  SendReq req=new SendReq();req.setTransactionId(("S2"+id).getBytes("US-ASCII"));req.setTo(new EncodedStringValue[]{new EncodedStringValue(data.number)});req.setDate(System.currentTimeMillis()/1000);req.setMessageClass("personal".getBytes("US-ASCII"));req.setExpiry(7*86400);req.setDeliveryReport(PduHeaders.VALUE_NO);req.setReadReport(PduHeaders.VALUE_NO);
  PduBody body=new PduBody();body.addPart(part("application/smil","smil.xml",smil.getBytes("UTF-8")));body.addPart(part(data.mime,name,data.media));if(data.caption.length()>0)body.addPart(part("text/plain","caption.txt",data.caption.getBytes("UTF-8")));req.setBody(body);req.setMessageSize(data.media.length);
  byte[] bytes=new PduComposer(c,req).make();if(bytes==null||bytes.length>MediaPayload.MAX_WIRE)throw new IOException("encoding");
  GenericPdu generic=new PduParser(bytes).parse();if(!(generic instanceof SendReq))throw new IOException("roundtrip");SendReq parsed=(SendReq)generic;
  if(parsed.getTo()==null||parsed.getTo().length!=1||!data.number.equals(parsed.getTo()[0].getString())||parsed.getBody().getPartsNum()!=(data.caption.length()>0?3:2)||!Arrays.equals(data.media,parsed.getBody().getPart(1).getData()))throw new IOException("roundtrip");
  if(data.caption.length()>0&&!Arrays.equals(data.caption.getBytes("UTF-8"),parsed.getBody().getPart(2).getData()))throw new IOException("caption roundtrip");return bytes;
 }
 static PduPart part(String mime,String name,byte[] bytes)throws Exception{PduPart p=new PduPart();p.setContentType(mime.getBytes("US-ASCII"));p.setContentId(name.getBytes("US-ASCII"));p.setContentLocation(name.getBytes("US-ASCII"));p.setName(name.getBytes("US-ASCII"));if(mime.equals("text/plain"))p.setCharset(106);p.setData(bytes);return p;}
}
