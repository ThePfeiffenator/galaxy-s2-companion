import pathlib, subprocess, zipfile, shutil, hashlib, json, sys, os
P=pathlib.Path(__file__).resolve().parent
SDK=pathlib.Path(os.environ['ANDROID_SDK_ROOT'])
J=pathlib.Path(os.environ['JAVA_HOME']+'/bin')
BT=SDK/'build-tools/36.1.0'
def run(a):
 temp=P/'native-tmp';temp.mkdir(exist_ok=True)
 r=subprocess.run(list(map(str,a)),capture_output=True,text=True,timeout=240,env=dict(os.environ,TMP=str(temp),TEMP=str(temp)))
 if r.returncode: raise RuntimeError(r.stdout+r.stderr)
 return r.stdout
for role in sys.argv[1:] or ['pixel','s2']:
 if role=='pixel':
  subprocess.run([sys.executable,str(P/'build_pixel.py')],check=True)
  continue
 q=P/role; api=26 if role=='pixel' else 15
 android=SDK/'platforms/android-36/android.jar' if role=='pixel' else P.parent/'telegram-s2-client/sdk/platform/android-4.0.4/android.jar'
 for name in ['classes','dex','gen','compiled']:
  d=(q/name).resolve(); assert d.parent==q.resolve() and d.name in ['classes','dex','gen','compiled']
  if d.exists():shutil.rmtree(d)
  d.mkdir(parents=True)
 libs=list((P/'lib').glob('*.jar')) if role=='pixel' else []
 if role=='s2':
  native=SDK/'ndk/28.1.13356709/toolchains/llvm/prebuilt/windows-x86_64/bin'
  run([native/'clang.exe','--target=armv7a-linux-androideabi21','-shared','-fPIC','-O2','-marm','-fno-stack-protector','-U_FORTIFY_SOURCE','-D_FORTIFY_SOURCE=0','-Wl,--hash-style=both','-Wl,--no-rosegment','-Wl,-z,max-page-size=4096',q/'legacy_discovery.c','-o',q/'libgalaxywifisdp.so'])
 run([BT/'aapt2.exe','compile','--dir',q/'res','-o',q/'compiled/resources.zip'])
 run([BT/'aapt2.exe','link','-o',q/'base.apk','--manifest',q/'AndroidManifest.xml','-I',android,'--java',q/'gen',q/'compiled/resources.zip'])
 src=list((q/'src').glob('*.java'))+list((P/'common').rglob('*.java'))+list((q/'gen').rglob('*.java'))
 run([J/'javac.exe','-source','8','-target','8','-encoding','UTF-8','-classpath',';'.join(map(str,[android]+libs)),'-d',q/'classes',*src])
 run([J/'java.exe','-Xmx2g','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api',api,'--lib',android,'--output',q/'dex',*list((q/'classes').rglob('*.class')),*libs])
 with zipfile.ZipFile(q/'base.apk') as a,zipfile.ZipFile(q/'unsigned.apk','w',zipfile.ZIP_DEFLATED) as b:
  for n in a.namelist():b.writestr(n,a.read(n),compress_type=zipfile.ZIP_STORED if n=='resources.arsc' else zipfile.ZIP_DEFLATED)
  for d in (q/'dex').glob('*.dex'):b.write(d,d.name)
  if role=='s2':b.write(q/'libgalaxywifisdp.so','lib/armeabi-v7a/libgalaxywifisdp.so')
  for a in (q/'assets').rglob('*'):
   if a.is_file():b.write(a,'assets/'+a.relative_to(q/'assets').as_posix())
  if libs:b.write(P/'BOUNCY-CASTLE-LICENSE.txt','assets/licenses/BOUNCY-CASTLE-LICENSE.txt')
  for jar in libs:
   with zipfile.ZipFile(jar) as z:
    for n in z.namelist():
     if 'LICENSE' in n.upper() and not n.endswith('.class'):b.writestr('assets/licenses/'+jar.stem+'/'+n,z.read(n))
 key=P.parent/'signing-private'
 run([BT/'zipalign.exe','-f','4',q/'unsigned.apk',q/'aligned.apk'])
 out=q/('galaxy-services-'+role+'.apk')
 run([J/'java.exe','-jar',BT/'lib/apksigner.jar','sign','--ks',key/'signing.p12','--ks-pass','file:'+str(key/'signing-password.txt'),'--min-sdk-version',api,'--out',out,q/'aligned.apk'])
 run([J/'java.exe','-jar',BT/'lib/apksigner.jar','verify','--min-sdk-version',api,out])
 receipt={'role':role,'apk_sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'bytes':out.stat().st_size,'sources':{str(s.relative_to(P)):hashlib.sha256(s.read_bytes()).hexdigest() for s in src},'dependencies':{l.name:hashlib.sha256(l.read_bytes()).hexdigest() for l in libs},'resources':{str(r.relative_to(P)):hashlib.sha256(r.read_bytes()).hexdigest() for r in (q/'res').rglob('*') if r.is_file()},'device_tested':False}
 (q/'build-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
 print(json.dumps({k:receipt[k] for k in ['role','apk_sha256','bytes']}))
