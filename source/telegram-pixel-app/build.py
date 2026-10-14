import os
import pathlib, subprocess, zipfile, hashlib, json, shutil
P=pathlib.Path(__file__).resolve().parent
SDK=pathlib.Path(os.environ['ANDROID_SDK_ROOT'])
J=pathlib.Path(os.environ['JAVA_HOME']+'/bin')
BT=SDK/'build-tools/36.1.0'; AND=SDK/'platforms/android-36/android.jar'
native=P.parent/'telegram-pixel-engine/candidate'
lib=native/'jniLibs/arm64-v8a/libtdjsonjava.so'
assert lib.is_file(), 'Build the pinned TDLib engine first; see docs/TELEGRAM.md'
wire=P.parent/'telegram-companion/Wire.java'
wrapper=native/'java/org/drinkless/tdlib/JsonClient.java'
sources=[*sorted((P/'src').glob('*.java')),wire,wrapper]

classes=(P/'classes').resolve()
assert classes.parent==P.resolve() and classes.name=='classes'
if classes.exists():shutil.rmtree(classes)
for name in ['classes','dex']:(P/name).mkdir(exist_ok=True)
def run(args):
 r=subprocess.run([str(x) for x in args],capture_output=True,text=True,timeout=120)
 if r.returncode:raise SystemExit(r.stdout+r.stderr)
 return r.stdout.strip()
run([J/'javac.exe','-source','8','-target','8','-encoding','UTF-8','-classpath',AND,'-d',P/'classes',*sources])
run([J/'java.exe','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','26','--lib',AND,'--output',P/'dex',*sorted((P/'classes').rglob('*.class'))])
run([BT/'aapt2.exe','compile','--dir',P/'res','-o',P/'resources.zip'])
run([BT/'aapt2.exe','link','-o',P/'base.apk','--manifest',P/'AndroidManifest.xml','-I',AND,P/'resources.zip'])
with zipfile.ZipFile(P/'base.apk') as a,zipfile.ZipFile(P/'unsigned.apk','w',zipfile.ZIP_DEFLATED) as b:
 for name in a.namelist():b.writestr(name,a.read(name),compress_type=zipfile.ZIP_STORED if name=='resources.arsc' else zipfile.ZIP_DEFLATED)
 b.write(P/'dex/classes.dex','classes.dex')
 b.write(lib,'lib/arm64-v8a/libtdjsonjava.so',compress_type=zipfile.ZIP_STORED)
 for license in sorted((native/'licenses').rglob('*')):
  if license.is_file():b.write(license,'assets/licenses/'+str(license.relative_to(native/'licenses')).replace('\\','/'))
key=P.parent/'signing-private'
run([BT/'zipalign.exe','-f','-P','16','4',P/'unsigned.apk',P/'aligned.apk'])
run([J/'java.exe','-jar',BT/'lib/apksigner.jar','sign','--ks',key/'signing.p12','--ks-pass','file:'+str(key/'signing-password.txt'),'--min-sdk-version','26','--out',P/'galaxy-chat-pixel.apk',P/'aligned.apk'])
run([BT/'zipalign.exe','-c','-P','16','4',P/'galaxy-chat-pixel.apk'])
signature=run([J/'java.exe','-jar',BT/'lib/apksigner.jar','verify','--verbose','--print-certs','--min-sdk-version','26',P/'galaxy-chat-pixel.apk'])
receipt={'apk':{'bytes':(P/'galaxy-chat-pixel.apk').stat().st_size,'sha256':hashlib.sha256((P/'galaxy-chat-pixel.apk').read_bytes()).hexdigest()},'native_sha256':hashlib.sha256(lib.read_bytes()).hexdigest(),'min_api':26,'target_api':36,'sources':{str(s.relative_to(P.parent)):hashlib.sha256(s.read_bytes()).hexdigest() for s in sources},'signature':signature,'device_tests':False}
(P/'build-result.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'apk':receipt['apk'],'alignment':'16KiB native / 4byte resource passed','signature':'verified','device_tests':False}))
