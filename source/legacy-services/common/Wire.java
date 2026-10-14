package org.retrobridge.galaxyservices;
import java.io.*;
import java.net.*;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;

public final class Wire {
 public static final int PORT=8765, LOCAL=8766;
 public static byte[] readFile(File f)throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(f)){copy(in,b);}return b.toByteArray();}
 public static void writeFile(File f,byte[] data)throws IOException {try(FileOutputStream o=new FileOutputStream(f)){o.write(data);o.getFD().sync();}}
 public static void copy(InputStream i,OutputStream o)throws IOException {byte[] b=new byte[16384];int n;while((n=i.read(b))>=0){o.write(b,0,n);o.flush();}}
 public static byte[] header(InputStream in)throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();int state=0;while(b.size()<65536){int c=in.read();if(c<0)throw new EOFException();b.write(c);state=(state==0&&c==13)?1:(state==1&&c==10)?2:(state==2&&c==13)?3:(state==3&&c==10)?4:0;if(state==4)return b.toByteArray();}throw new IOException("Header too large");}
 public static String line(InputStream in)throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();int c;while((c=in.read())>=0&&c!=10){if(b.size()>512)throw new IOException("Line too large");if(c!=13)b.write(c);}if(c<0)throw new EOFException();return b.toString("US-ASCII");}
 public static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte n:b)s.append(String.format(java.util.Locale.US,"%02x",n&255));return s.toString();}
 public static byte[] unhex(String s)throws IOException {if(s.length()%2!=0)throw new IOException("Bad hex");byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++){int a=Character.digit(s.charAt(i*2),16),c=Character.digit(s.charAt(i*2+1),16);if(a<0||c<0)throw new IOException("Bad hex");b[i]=(byte)(a*16+c);}return b;}
 public static byte[] mac(byte[] key,String prefix,byte[] h)throws Exception {Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));m.update(prefix.getBytes("US-ASCII"));return m.doFinal(h);}
 public static void tune(Socket s)throws SocketException{s.setTcpNoDelay(true);s.setSendBufferSize(262144);s.setReceiveBufferSize(262144);}
 public static void bridge(final Socket a,final Socket b)throws Exception {
  tune(a);tune(b);
  // A GET has no further upload bytes. Its idle upload half must not terminate an active download.
  // The response half retains its idle timeout and closes both sockets on completion/failure.
  a.setSoTimeout(0);
  Thread t=new Thread(new Runnable(){public void run(){try{copy(a.getInputStream(),b.getOutputStream());try{b.shutdownOutput();}catch(Exception ignored){}}catch(Exception e){close(b);}}},"upload");t.setDaemon(true);t.start();
  try{copy(b.getInputStream(),a.getOutputStream());}finally{close(a);close(b);t.join(1000);}
 }
 public static void close(Socket s){if(s!=null)try{s.close();}catch(Exception ignored){}}
 private Wire(){}
}
