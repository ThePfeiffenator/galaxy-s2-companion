import os
from pathlib import Path
import subprocess,zipfile,hashlib,json
P=Path(__file__).resolve().parent;W=P.parents[1];J=Path((os.environ['JAVA_HOME'] + '/bin'));BT=Path((os.environ['ANDROID_SDK_ROOT'] + '/build-tools/36.1.0'));A=W/'telegram-s2-client/sdk/platform/android-4.0.4/android.jar'
def run(args):
 r=subprocess.run(list(map(str,args)),capture_output=True,text=True,timeout=180)
 if r.returncode:raise RuntimeError(r.stdout+r.stderr)
 return r.stdout
for name in ['classes','dex']:(P/name).mkdir(exist_ok=True)
run([BT/'aapt2.exe','link','-o',P/'base.apk','--manifest',P/'AndroidManifest.xml','-I',A])
run([J/'javac.exe','-source','8','-target','8','-encoding','UTF-8','-classpath',A,'-d',P/'classes',P/'VerifiedInstaller.java'])
run([J/'java.exe','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','15','--lib',A,'--output',P/'dex',*(P/'classes').rglob('*.class')])
with zipfile.ZipFile(P/'base.apk') as a,zipfile.ZipFile(P/'unsigned.apk','w',zipfile.ZIP_DEFLATED) as b:
 for n in a.namelist():b.writestr(n,a.read(n))
 b.write(P/'dex/classes.dex','classes.dex')
run([BT/'zipalign.exe','-f','4',P/'unsigned.apk',P/'aligned.apk']);out=P/'Store-Installer-S2.apk';key=W/'signing-private'
run([J/'java.exe','-jar',BT/'lib/apksigner.jar','sign','--ks',key/'signing.p12','--ks-pass','file:'+str(key/'signing-password.txt'),'--min-sdk-version','15','--out',out,P/'aligned.apk'])
run([J/'java.exe','-jar',BT/'lib/apksigner.jar','verify','--min-sdk-version','15',out]);print(json.dumps({'file':out.name,'bytes':out.stat().st_size,'sha256':hashlib.sha256(out.read_bytes()).hexdigest()}))
