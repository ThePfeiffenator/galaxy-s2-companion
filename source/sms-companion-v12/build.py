from pathlib import Path
import hashlib,json,os,subprocess,zipfile,shutil
P=Path(__file__).resolve().parent
SDK=Path(os.environ['ANDROID_SDK_ROOT'])
J=Path(os.environ['JAVA_HOME']+'/bin')
BT=SDK/'build-tools/36.1.0';AND=SDK/'platforms/android-36/android.jar'
audio=P/'native/libprobe.so'
audio.parent.mkdir(exist_ok=True)
classes=(P/'classes').resolve();assert classes.parent==P and classes.name=='classes'
if classes.exists():shutil.rmtree(classes)
for name in ['classes','dex','tmp','gen','compiled']:(P/name).mkdir(exist_ok=True)
env=dict(os.environ,JAVA_HOME=str(J.parent),PATH=str(J)+os.pathsep+os.environ['PATH'],TMP=str(P/'tmp'),TEMP=str(P/'tmp'))
def run(args):subprocess.run([str(x) for x in args],env=env,check=True)
clang=SDK/'ndk/28.1.13356709/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
run([clang,'--target=armv7a-linux-androideabi21','-shared','-fPIC','-O2','-marm','-fno-stack-protector','-U_FORTIFY_SOURCE','-D_FORTIFY_SOURCE=0','-Wl,--hash-style=both','-Wl,--no-rosegment','-Wl,-z,max-page-size=4096',P.parent/'call-feasibility/probe.c','-ldl','-o',audio])
run([clang,'--target=armv7a-linux-androideabi21','-shared','-fPIC','-O2','-marm','-fno-stack-protector','-U_FORTIFY_SOURCE','-D_FORTIFY_SOURCE=0','-Wl,--hash-style=both','-Wl,--no-rosegment','-Wl,-z,max-page-size=4096',P/'hfp_discovery.c','-o',P/'native/libgalaxyhfp.so'])
run([BT/'aapt2.exe','compile','--dir',P/'res','-o',P/'compiled/resources.zip'])
run([BT/'aapt2.exe','link','-o',P/'base.apk','--manifest',P/'AndroidManifest.xml','-I',AND,'--java',P/'gen',P/'compiled/resources.zip'])
sources=sorted(P.glob('*.java'))+sorted((P/'gen').rglob('*.java'))
run([J/'javac.exe','-source','8','-target','8','-encoding','UTF-8','-classpath',AND,'-d',P/'classes',*sources])
run([J/'java.exe','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','15','--lib',AND,'--output',P/'dex',*sorted((P/'classes').rglob('*.class'))])
with zipfile.ZipFile(P/'base.apk') as src,zipfile.ZipFile(P/'unsigned.apk','w',zipfile.ZIP_DEFLATED) as dst:
 for name in src.namelist():dst.writestr(name,src.read(name),compress_type=zipfile.ZIP_STORED if name=='resources.arsc' else zipfile.ZIP_DEFLATED)
 dst.write(P/'dex/classes.dex','classes.dex')
 for name in ['libprobe.so','libgalaxyhfp.so']:dst.write(P/'native'/name,'lib/armeabi-v7a/'+name)
key=P.parent/'signing-private'
run([BT/'zipalign.exe','-f','4',P/'unsigned.apk',P/'aligned.apk'])
apk=P/'galaxy-companion-v14.apk'
run([J/'java.exe','-jar',BT/'lib/apksigner.jar','sign','--ks',key/'signing.p12','--ks-pass','file:'+str(key/'signing-password.txt'),'--min-sdk-version','15','--out',apk,P/'aligned.apk'])
run([J/'java.exe','-jar',BT/'lib/apksigner.jar','verify','--min-sdk-version','15',apk]);run([BT/'zipalign.exe','-c','4',apk])
receipt={'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'audio_library_sha256':hashlib.sha256(audio.read_bytes()).hexdigest(),'audio_library_built_from_source':True,'sources':{str(f.relative_to(P)):hashlib.sha256(f.read_bytes()).hexdigest() for f in sources+[P/'hfp_discovery.c',P/'build.py',P/'AndroidManifest.xml']},'resources':{str(f.relative_to(P)):hashlib.sha256(f.read_bytes()).hexdigest() for f in (P/'res').rglob('*') if f.is_file()}}
(P/'build-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8');print(json.dumps({k:v for k,v in receipt.items() if k!='sources'}))
