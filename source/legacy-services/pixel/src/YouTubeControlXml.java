package org.retrobridge.galaxyservices.pixel;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.json.*;

/** Parses only the small Atom requests emitted by the original Android client. */
final class YouTubeControlXml {
 static final String ATOM="http://www.w3.org/2005/Atom",YT="http://gdata.youtube.com/schemas/2007";
 final String operation;final JSONObject params;
 YouTubeControlXml(String o,JSONObject p){operation=o;params=p;}
 static Element xml(byte[] body)throws Exception {
  if(body.length==0||body.length>16384)throw new IOException("Invalid action size");
  String text=Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
  // The client emits simple XML only. Reject declarations, entities, comments and CDATA before parsing.
  if(text.contains("<!")||text.indexOf('\u0000')>=0)throw new IOException("XML declarations are not allowed");
  DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setExpandEntityReferences(false);
  DocumentBuilder b=f.newDocumentBuilder();b.setEntityResolver((publicId,systemId)->{throw new SAXException("External entity rejected");});
  b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){public void error(SAXParseException e)throws SAXException{throw e;}public void fatalError(SAXParseException e)throws SAXException{throw e;}});
  Element root=b.parse(new InputSource(new StringReader(text))).getDocumentElement();
  if(!ATOM.equals(root.getNamespaceURI())||!"entry".equals(root.getLocalName()))throw new IOException("Expected Atom entry");
  if(root.getElementsByTagName("*").getLength()>12)throw new IOException("Action XML too complex");return root;
 }
 static Element child(Element root,String ns,String name,boolean optional)throws Exception {
  Element found=null;NodeList children=root.getChildNodes();
  for(int i=0;i<children.getLength();i++){Node n=children.item(i);if(n instanceof Element&&ns.equals(n.getNamespaceURI())&&name.equals(n.getLocalName())){if(found!=null)throw new IOException("Duplicate "+name);found=(Element)n;}}
  if(found==null&&!optional)throw new IOException("Missing "+name);if(found!=null&&found.getElementsByTagName("*").getLength()!=0)throw new IOException("Nested action value");return found;
 }
 static String text(Element r,String name)throws Exception{return child(r,ATOM,name,false).getTextContent();}
 static String id(String s,String regex)throws IOException {if(!s.matches(regex))throw new IOException("Invalid resource ID");return s;}
 static String video(Element root)throws Exception {String value=text(root,"id");if(value.startsWith("http://127.0.0.1:8768/feeds/api/videos/"))value=value.substring(value.lastIndexOf('/')+1);return id(value,"[A-Za-z0-9_-]{11}");}
 static YouTubeControlXml parse(String method,String path,byte[] body)throws Exception {
  if(path==null||!path.matches("/feeds/api/[A-Za-z0-9_/-]+")||path.contains("//"))throw new IOException("Invalid action path");
  Element root=body.length==0?null:xml(body);JSONObject p=new JSONObject();String op;
  String users="/feeds/api/users/default/";
  if(method.equals("POST")&&path.matches("/feeds/api/videos/[A-Za-z0-9_-]{11}/ratings")){
   op="rate";p.put("video",path.split("/")[4]);p.put("rating",id(child(root,YT,"rating",false).getAttribute("value"),"like|dislike|none"));
  }else if(method.equals("POST")&&path.equals(users+"favorites")){
   op="rate";p.put("video",video(root)).put("rating","like");
  }else if(method.equals("DELETE")&&path.matches(users+"favorites/[A-Za-z0-9_-]{11}")){
   op="rate";p.put("video",path.substring(path.lastIndexOf('/')+1)).put("rating","none");
  }else if(method.equals("POST")&&path.equals(users+"subscriptions")){
   op="subscribe";p.put("channel",id(child(root,YT,"username",false).getTextContent(),"UC[A-Za-z0-9_-]{22}"));
  }else if(method.equals("DELETE")&&path.matches(users+"subscriptions/[A-Za-z0-9_-]{10,512}")){
   op="unsubscribe";p.put("id",path.substring(path.lastIndexOf('/')+1));
  }else if(method.equals("POST")&&path.equals(users+"playlists")){
   op="playlistCreate";p.put("title",text(root,"title")).put("description",text(root,"summary")).put("privacy",child(root,YT,"private",true)==null?"public":"private");
  }else if((method.equals("PUT")||method.equals("DELETE"))&&path.matches("/feeds/api/(users/default/playlists|playlists)/[A-Za-z0-9_-]{10,80}")){
   op=method.equals("DELETE")?"playlistDelete":"playlistUpdate";p.put("id",path.substring(path.lastIndexOf('/')+1));
   if(method.equals("PUT")){p.put("title",text(root,"title")).put("description",text(root,"summary"));if(child(root,YT,"private",true)!=null)p.put("privacy","private");}
  }else if(method.equals("POST")&&path.matches("/feeds/api/playlists/[A-Za-z0-9_-]{10,80}")){
   op="playlistAdd";p.put("playlist",path.substring(path.lastIndexOf('/')+1)).put("video",video(root));
  }else if(method.equals("DELETE")&&path.matches("/feeds/api/playlists/[A-Za-z0-9_-]{10,80}/[A-Za-z0-9_-]{10,512}")){
   op="playlistRemove";p.put("id",path.substring(path.lastIndexOf('/')+1));
  }else if(method.equals("POST")&&path.matches("/feeds/api/videos/[A-Za-z0-9_-]{11}/comments")){
   op="comment";p.put("video",path.split("/")[4]).put("text",text(root,"content"));
  }else throw new IOException("This YouTube action is not available");
  if(method.equals("DELETE")&&body.length!=0)throw new IOException("Unexpected delete body");return new YouTubeControlXml(op,p);
 }
}
