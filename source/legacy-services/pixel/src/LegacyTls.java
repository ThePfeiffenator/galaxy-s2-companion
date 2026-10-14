package org.retrobridge.galaxyservices.pixel;
import org.bouncycastle.tls.*;import org.bouncycastle.tls.crypto.*;import org.bouncycastle.tls.crypto.impl.jcajce.*;
import java.io.*;import java.net.*;import org.retrobridge.galaxyservices.Wire;
/** Legacy protocols are limited to this authenticated S2-facing endpoint. */
final class LegacyTls extends DefaultTlsServer {
 static final java.security.Provider PROVIDER=new org.bouncycastle.jce.provider.BouncyCastleProvider();
 final JcaTlsCrypto crypto;final Certificates.Identity identity;final org.bouncycastle.tls.Certificate chain;
 LegacyTls(JcaTlsCrypto c,Certificates.Identity identity,java.security.cert.X509Certificate ca)throws Exception {super(c);crypto=c;this.identity=identity;chain=new org.bouncycastle.tls.Certificate(new TlsCertificate[]{c.createCertificate(identity.cert.getEncoded()),c.createCertificate(ca.getEncoded())});}
 protected ProtocolVersion[] getSupportedVersions(){return new ProtocolVersion[]{ProtocolVersion.TLSv12,ProtocolVersion.TLSv11,ProtocolVersion.TLSv10};}
 protected int[] getSupportedCipherSuites(){return new int[]{CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA};}
 public void notifyAlertRaised(short level,short description,String message,Throwable cause){if(level==AlertLevel.fatal)android.util.Log.w("GalaxyLegacyTLS","Fatal TLS alert "+description,cause);}
 protected TlsCredentialedSigner getRSASignerCredentials()throws IOException {SignatureAndHashAlgorithm alg=TlsUtils.isTLSv12(context)?new SignatureAndHashAlgorithm(HashAlgorithm.sha256,SignatureAlgorithm.rsa):null;return new JcaDefaultTlsCredentialedSigner(new TlsCryptoParameters(context),crypto,identity.key,chain,alg);}
 protected TlsCredentialedDecryptor getRSAEncryptionCredentials()throws IOException {return new JceDefaultTlsCredentialedDecryptor(crypto,chain,identity.key);}
 static TlsServerProtocol accept(Socket browser,Certificates certs,String host)throws Exception {
  JcaTlsCrypto crypto=new JcaTlsCryptoProvider().setProvider(PROVIDER).create(Certificates.RANDOM);
  TlsServerProtocol p=new TlsServerProtocol(browser.getInputStream(),browser.getOutputStream());p.accept(new LegacyTls(crypto,certs.identity(host),certs.ca));return p;
 }
 static void bridge(final TlsServerProtocol legacy,final Socket browser,final Socket remote)throws Exception {
  Thread upload=new Thread(new Runnable(){public void run(){try{Wire.copy(legacy.getInputStream(),remote.getOutputStream());try{remote.shutdownOutput();}catch(Exception ignored){}}catch(Exception e){Wire.close(remote);}}},"legacy-upload");upload.setDaemon(true);upload.start();
  try{Wire.copy(remote.getInputStream(),legacy.getOutputStream());}finally{try{legacy.close();}catch(Exception ignored){}Wire.close(browser);Wire.close(remote);upload.join(1000);}
 }
}
