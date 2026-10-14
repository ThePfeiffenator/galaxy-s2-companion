package org.retrobridge.callprobe;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.text.*;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Narrow SMS bMessage/listing codec. No Android dependencies or content-bearing errors. */
final class MapBMessage {
    static final int MAX_BODY=262144, MAX_TEXT=16384;
    static byte[] utf8(String value) throws IOException {
        if(value==null) throw new IOException("Missing text");
        try {
            ByteBuffer b=Charset.forName("UTF-8").newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
            byte[] out=new byte[b.remaining()]; b.get(out); return out;
        } catch(CharacterCodingException e) { throw new IOException("Invalid text encoding"); }
    }
    static String decode(byte[] data, int offset, int length, String charset) throws IOException {
        try { return Charset.forName(charset).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data,offset,length)).toString(); }
        catch(CharacterCodingException e) { throw new IOException("Invalid character encoding"); }
    }
    static String handle(String value) throws IOException {
        if(value==null || !value.matches("[0-9A-Fa-f]{1,16}")) throw new IOException("Invalid MAP handle");
        return value.toUpperCase(Locale.US);
    }
    static byte[] encodeText(String number,String text) throws IOException {
        if(number==null || !number.matches("\\+?[0-9]{1,20}")) throw new IOException("Unsupported recipient");
        if(text==null || text.length()==0 || text.indexOf(0)>=0 || utf8(text).length>MAX_TEXT)
            throw new IOException("Invalid SMS text length");
        // MAP escapes every END:MSG occurrence, including an already slash-prefixed one.
        byte[] content=utf8(text.replace("END:MSG","/END:MSG"));
        String head="BEGIN:BMSG\r\nVERSION:1.0\r\nSTATUS:UNREAD\r\nTYPE:SMS_GSM\r\n"
            +"FOLDER:telecom/msg/outbox\r\nBEGIN:BENV\r\nBEGIN:VCARD\r\nVERSION:2.1\r\nN:\r\nTEL:"
            +number+"\r\nEND:VCARD\r\nBEGIN:BBODY\r\nCHARSET:UTF-8\r\nLENGTH:"
            +(content.length+22)+"\r\nBEGIN:MSG\r\n";
        return MapObex.join(utf8(head),content,utf8("\r\nEND:MSG\r\nEND:BBODY\r\nEND:BENV\r\nEND:BMSG\r\n"));
    }
    private static final class Cursor {
        final byte[] b; int p, lines;
        Cursor(byte[] b) throws IOException { if(b==null||b.length>MAX_BODY)throw new IOException("bMessage size limit");this.b=b; }
        String line() throws IOException {
            if(++lines>256) throw new IOException("bMessage metadata limit");
            int start=p;
            while(p+1<b.length && !(b[p]==13 && b[p+1]==10)) {
                if(p-start>4096)throw new IOException("bMessage line limit"); p++;
            }
            if(p+1>=b.length)throw new IOException("Truncated bMessage");
            String s=decode(b,start,p-start,"UTF-8");p+=2;
            if(s.indexOf(0)>=0)throw new IOException("Invalid bMessage metadata");return s;
        }
        void expect(String s) throws IOException { if(!s.equals(line()))throw new IOException("Invalid bMessage structure"); }
        String card() throws IOException {
            String phone=null,version=null;
            for(int n=0;n<64;n++) {
                String l=line(); if("END:VCARD".equals(l)) {
                    if(!"2.1".equals(version)&&!"3.0".equals(version))throw new IOException("Unsupported vCard");
                    return phone;
                }
                int colon=l.indexOf(':'); if(colon<1)throw new IOException("Invalid vCard field");
                String key=l.substring(0,colon).toUpperCase(Locale.US),value=l.substring(colon+1);
                if("VERSION".equals(key))version=value;
                if(("TEL".equals(key)||key.startsWith("TEL;")) && phone==null && value.length()>0) {
                    if(value.length()>256 || value.indexOf('\\')>=0)throw new IOException("Unsupported sender vCard");
                    phone=value;
                }
            }
            throw new IOException("vCard limit");
        }
    }
    static MapMessage parse(String handle, byte[] bytes) throws IOException {
        handle=handle(handle);Cursor c=new Cursor(bytes);c.expect("BEGIN:BMSG");
        String version=c.line(); if(!"VERSION:1.0".equals(version)&&!"VERSION:1.1".equals(version))throw new IOException("Unsupported bMessage version");
        String type=null,folder=null,status=null,sender=null,l;
        for(;;) {
            l=c.line(); if(l.equals("BEGIN:VCARD")||l.equals("BEGIN:BENV"))break;
            if(l.startsWith("TYPE:")) {if(type!=null)throw new IOException("Duplicate type");type=l.substring(5);}
            else if(l.startsWith("FOLDER:")) {if(folder!=null)throw new IOException("Duplicate folder");folder=l.substring(7);}
            else if(l.startsWith("STATUS:")) {if(status!=null)throw new IOException("Duplicate status");status=l.substring(7);}
            else if(!l.startsWith("EXTENDEDDATA:"))throw new IOException("Unsupported bMessage metadata");
        }
        if(!"SMS_GSM".equals(type)&&!"SMS_CDMA".equals(type))throw new IOException("Unsupported message type");
        if(!"READ".equals(status)&&!"UNREAD".equals(status))throw new IOException("Invalid message status");
        if(folder==null||!folder.matches("(?:telecom/msg/)?[a-z]{1,32}"))throw new IOException("Invalid message folder");
        while("BEGIN:VCARD".equals(l)) {String s=c.card();if(sender==null)sender=s;l=c.line();}
        if(!"BEGIN:BENV".equals(l))throw new IOException("Missing envelope");
        int depth=1; l=c.line();
        for(;;) {
            if("BEGIN:VCARD".equals(l)) {c.card();l=c.line();}
            else if("BEGIN:BENV".equals(l)) {if(++depth>3)throw new IOException("Envelope limit");l=c.line();}
            else break;
        }
        if(!"BEGIN:BBODY".equals(l))throw new IOException("Missing body");
        int length=-1;String charset=null;
        for(;;) {
            l=c.line();if("BEGIN:MSG".equals(l))break;
            if(l.startsWith("LENGTH:")) {
                if(length!=-1 || !l.substring(7).matches("[0-9]{1,6}"))throw new IOException("Invalid body length");
                length=Integer.parseInt(l.substring(7));
            } else if(l.startsWith("CHARSET:")) {if(charset!=null)throw new IOException("Duplicate charset");charset=l.substring(8);}
            else if(l.startsWith("ENCODING:")) {if(!"ENCODING:8BIT".equals(l))throw new IOException("Unsupported SMS encoding");}
            else if(!l.startsWith("LANGUAGE:")&&!l.startsWith("PARTID:"))throw new IOException("Unsupported body field");
        }
        if(!"UTF-8".equalsIgnoreCase(charset)||length<22||length>MAX_BODY||length-22>bytes.length-c.p)
            throw new IOException("Invalid UTF8 body length");
        int n=length-22;
        String body=decode(bytes,c.p,n,"UTF-8").replace("/END:MSG","END:MSG");c.p+=n;
        if(body.indexOf(0)>=0||utf8(body).length>MAX_TEXT)throw new IOException("SMS body limit");
        c.expect("");c.expect("END:MSG");c.expect("END:BBODY");
        while(depth-->0)c.expect("END:BENV");c.expect("END:BMSG");
        if(c.p!=bytes.length)throw new IOException("Trailing bMessage data");
        return new MapMessage(handle,null,sender,null,body,folder,type);
    }
    static List<MapMessage> listing(byte[] bytes,final int maximum) throws IOException {return listing(bytes,maximum,null);}
    static List<MapMessage> listing(byte[] bytes,final int maximum,final String mseTime) throws IOException {
        if(bytes.length>MAX_BODY||maximum<0||maximum>100)throw new IOException("Listing limit");
        if(bytes.length==0)return Collections.emptyList();
        String xml=decode(bytes,0,bytes.length,"UTF-8");
        String upper=xml.toUpperCase(Locale.US);
        // Ban declarations before the platform SAX implementation sees them. This also protects
        // ICS parsers which do not implement the newer disallow-doctype feature.
        if(upper.contains("<!DOCTYPE")||upper.contains("<!ENTITY")||xml.indexOf(0)>=0)
            throw new IOException("XML declarations forbidden");
        final ArrayList<MapMessage> result=new ArrayList<MapMessage>();
        final HashSet<String> seen=new HashSet<String>();
        try {
            SAXParserFactory factory=SAXParserFactory.newInstance();factory.setNamespaceAware(false);
            XMLReader reader=factory.newSAXParser().getXMLReader();
            DefaultHandler handler=new DefaultHandler(){
                int depth; boolean root;
                @Override public InputSource resolveEntity(String pub,String sys)throws SAXException {throw new SAXException("External entity forbidden");}
                @Override public void startElement(String uri,String local,String name,Attributes a)throws SAXException {
                    depth++;
                    if(depth==1){if(root||!"MAP-msg-listing".equals(name))throw new SAXException("Invalid listing root");root=true;return;}
                    if(depth!=2||!"msg".equals(name)||result.size()>=maximum)throw new SAXException("Listing element limit");
                    try {
                        String h=handle(a.getValue("handle"));if(!seen.add(h))throw new IOException("Duplicate handle");
                        String date=bounded(a.getValue("datetime"),32),address=bounded(a.getValue("sender_addressing"),256);
                        String type=bounded(a.getValue("type"),16);
                        if(type!=null&&!"SMS_GSM".equals(type)&&!"SMS_CDMA".equals(type))throw new IOException("Unexpected listing type");
                        if(date!=null&&!date.matches("[0-9]{8}T[0-9]{6}(?:Z|[+-][0-9]{4})?"))throw new IOException("Invalid message date");
                        boolean inferred=date!=null&&date.length()==15&&mseTime!=null;
                        long millis=timestamp(date,mseTime);
                        result.add(new MapMessage(h,date,address,null,null,"telecom/msg/inbox",type,mseTime,millis,inferred&&millis!=-1));
                    }catch(IOException e){throw new SAXException("Invalid listing attributes");}
                }
                @Override public void endElement(String uri,String local,String name)throws SAXException {if(--depth<0)throw new SAXException("Invalid listing depth");}
                @Override public void endDocument()throws SAXException {if(!root||depth!=0)throw new SAXException("Incomplete listing");}
                @Override public void characters(char[] ch,int s,int n)throws SAXException {for(int i=s;i<s+n;i++)if(!Character.isWhitespace(ch[i]))throw new SAXException("Unexpected listing text");}
                @Override public void error(SAXParseException e)throws SAXException {throw new SAXException("Invalid listing XML");}
                @Override public void fatalError(SAXParseException e)throws SAXException {throw new SAXException("Invalid listing XML");}
            };
            reader.setContentHandler(handler);reader.setEntityResolver(handler);reader.setErrorHandler(handler);
            reader.parse(new InputSource(new StringReader(xml)));
            return Collections.unmodifiableList(result);
        }catch(Exception e){throw new IOException("Invalid MAP listing");}
    }
    private static String bounded(String s,int limit)throws IOException {
        if(s!=null && (s.length()>limit||s.indexOf(0)>=0))throw new IOException("Attribute limit");return s;
    }
    static long timestamp(String date,String mseTime) {
        if(date==null)return -1;
        String value=date;
        if(date.length()==15) {
            if(mseTime==null || timestamp(mseTime,null)==-1)return -1;
            if(mseTime.endsWith("Z"))value=date+"Z";
            else if(mseTime.length()==20)value=date+mseTime.substring(15);
            else return -1;
        }
        if(value.endsWith("Z"))value=value.substring(0,value.length()-1)+"+0000";
        if(!value.matches("[0-9]{8}T[0-9]{6}[+-][0-9]{4}"))return -1;
        int hours=Integer.parseInt(value.substring(16,18)),minutes=Integer.parseInt(value.substring(18));
        if(hours>14||minutes>59||(hours==14&&minutes!=0))return -1;
        SimpleDateFormat f=new SimpleDateFormat("yyyyMMdd'T'HHmmssZ",Locale.US);f.setLenient(false);
        ParsePosition p=new ParsePosition(0);Date parsed=f.parse(value,p);
        return parsed==null||p.getIndex()!=value.length()?-1:parsed.getTime();
    }
}
