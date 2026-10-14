import pathlib,hashlib,subprocess,os,json,re
from cryptography import x509
from cryptography.hazmat.primitives.asymmetric import padding
P=pathlib.Path(__file__).resolve().parent
data=(P/'public-ca.pem').read_bytes();cert=x509.load_pem_x509_certificate(data)
cert.public_key().verify(cert.signature,cert.tbs_certificate_bytes,padding.PKCS1v15(),cert.signature_hash_algorithm)
assert cert.extensions.get_extension_for_class(x509.BasicConstraints).value.ca
name=format(int.from_bytes(hashlib.md5(cert.subject.public_bytes()).digest()[:4],'little'),'08x')+'.0'
(P/'trust_certificate.h').write_text('#define CERT_NAME "'+name+'"\nstatic const unsigned char certificate[]={'+','.join(map(str,data))+'};\n')
B=pathlib.Path(os.environ['ANDROID_SDK_ROOT']+'/ndk/28.1.13356709/toolchains/llvm/prebuilt/windows-x86_64/bin')
env=dict(os.environ);(P/'native-tmp').mkdir(exist_ok=True);env.update(TMP=str(P/'native-tmp'),TEMP=str(P/'native-tmp'))
args=[B/'clang.exe','--target=armv7a-linux-androideabi21','-std=c11','-D_GNU_SOURCE','-marm','-fno-pie','-no-pie','-O2','-Wall','-Wextra','-Werror','-fno-stack-protector','-U_FORTIFY_SOURCE','-D_FORTIFY_SOURCE=0','-Wl,--hash-style=both','-Wl,--no-rosegment','-Wl,-z,max-page-size=4096',P/'trust_store.c','-o',P/'galaxy-trust-store']
subprocess.run(list(map(str,args)),check=True,env=env)
audit=subprocess.check_output([str(B/'llvm-readelf.exe'),'-h','-d','--dyn-syms',str(P/'galaxy-trust-store')],text=True)
libc=subprocess.check_output([str(B/'llvm-readelf.exe'),'--dyn-syms',str(P.parent/'peggle-debug-tools/symbols/system/lib/libc.so')],text=True)
exports=set(re.findall(r'\b(?:GLOBAL|WEAK)\s+DEFAULT\s+(?!UND)\w+\s+(\w+)(?:@|\s|$)',libc))
missing=set(re.findall(r'\bUND\s+(\w+)(?:@|\s|$)',audit))-exports
assert not missing,missing
assert 'EXEC (Executable file)' in audit
(P/'trust-store-receipt.json').write_text(json.dumps({'name':name,'pem_sha256':hashlib.sha256(data).hexdigest(),'helper_sha256':hashlib.sha256((P/'galaxy-trust-store').read_bytes()).hexdigest(),'old_libc_imports':True,'device_installed':False},indent=2)+'\n')
print('Guarded single-certificate installer built; old libc imports verified. Target='+name)
