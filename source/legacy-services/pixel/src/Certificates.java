package org.retrobridge.galaxyservices.pixel;
import android.content.Context;
import org.retrobridge.galaxyservices.Wire;
import java.io.*;import java.math.BigInteger;import java.security.*;import java.security.cert.*;import java.util.*;import javax.net.ssl.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

final class Certificates {
 static final class Identity {final PrivateKey key;final X509Certificate cert;Identity(PrivateKey k,X509Certificate c){key=k;cert=c;}}
 final KeyPair authority;final X509Certificate ca;
 final LinkedHashMap<String,Identity> contexts=new LinkedHashMap<String,Identity>(64,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Identity> e){return size()>64;}};
 static final SecureRandom RANDOM=new SecureRandom();
 Certificates(Context app)throws Exception {
  File dir=app.getFilesDir(), store=new File(dir,"authority.p12"), pass=new File(dir,"authority.pass");
  KeyStore ks=KeyStore.getInstance("PKCS12");
  if(store.exists()){
   char[] p=new String(Wire.readFile(pass),"US-ASCII").toCharArray();try(InputStream in=new FileInputStream(store)){ks.load(in,p);}
   ca=(X509Certificate)ks.getCertificate("ca");authority=new KeyPair(ca.getPublicKey(),(PrivateKey)ks.getKey("ca",p));
  }else{
   authority=pair();X500Name name=new X500Name("CN=Galaxy Services Personal CA");
   JcaX509v3CertificateBuilder b=new JcaX509v3CertificateBuilder(name,serial(),new Date(System.currentTimeMillis()-86400000L),new Date(System.currentTimeMillis()+315360000000L),name,authority.getPublic());
   b.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints,true,new BasicConstraints(0));
   b.addExtension(org.bouncycastle.asn1.x509.Extension.keyUsage,true,new KeyUsage(KeyUsage.keyCertSign|KeyUsage.cRLSign));
   ca=new JcaX509CertificateConverter().getCertificate(b.build(new JcaContentSignerBuilder("SHA256withRSA").build(authority.getPrivate())));
   ca.verify(authority.getPublic());byte[] secret=new byte[32];RANDOM.nextBytes(secret);char[] p=Wire.hex(secret).toCharArray();
   ks.load(null,p);ks.setKeyEntry("ca",authority.getPrivate(),p,new java.security.cert.Certificate[]{ca});
   Wire.writeFile(pass,new String(p).getBytes("US-ASCII"));try(FileOutputStream out=new FileOutputStream(store)){ks.store(out,p);out.getFD().sync();}
  }
  String pem="-----BEGIN CERTIFICATE-----\n"+android.util.Base64.encodeToString(ca.getEncoded(),android.util.Base64.DEFAULT)+"-----END CERTIFICATE-----\n";
  Wire.writeFile(new File(dir,"ca.pem"),pem.getBytes("US-ASCII"));
 }
 static KeyPair pair()throws Exception {KeyPairGenerator k=KeyPairGenerator.getInstance("RSA");k.initialize(2048,RANDOM);return k.generateKeyPair();}
 static BigInteger serial(){return new BigInteger(159,RANDOM).add(BigInteger.ONE);}
 synchronized Identity identity(String host)throws Exception {
  Identity found=contexts.get(host);if(found!=null)return found;
  KeyPair leaf=pair();X500Name issuer=new X500Name(ca.getSubjectX500Principal().getName());
  JcaX509v3CertificateBuilder b=new JcaX509v3CertificateBuilder(issuer,serial(),new Date(System.currentTimeMillis()-86400000L),new Date(System.currentTimeMillis()+2592000000L),new X500Name("CN="+host),leaf.getPublic());
  b.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints,true,new BasicConstraints(false));
  b.addExtension(org.bouncycastle.asn1.x509.Extension.keyUsage,true,new KeyUsage(KeyUsage.digitalSignature|KeyUsage.keyEncipherment));
  b.addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage,false,new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
  int kind=host.matches("[0-9.]+")?GeneralName.iPAddress:GeneralName.dNSName;
  b.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,false,new GeneralNames(new GeneralName(kind,host)));
  X509Certificate cert=new JcaX509CertificateConverter().getCertificate(b.build(new JcaContentSignerBuilder("SHA256withRSA").build(authority.getPrivate())));cert.verify(ca.getPublicKey());
  Identity identity=new Identity(leaf.getPrivate(),cert);contexts.put(host,identity);return identity;
 }
}
