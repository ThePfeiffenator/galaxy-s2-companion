"""Material front end; preserve the existing package/signing and private storage."""
from pathlib import Path
import subprocess,os,hashlib,json
P=Path(__file__).resolve().parent;SDK=Path(os.environ['ANDROID_SDK_ROOT'])
J=Path((os.environ['JAVA_HOME'] + '/bin'));BT=SDK/'build-tools/36.1.0'
G=Path(os.environ['GRADLE_EXECUTABLE'])
def run(a):
 r=subprocess.run(list(map(str,a)),cwd=P/'pixel-gradle',env=dict(os.environ,JAVA_HOME=str(J.parent),ANDROID_HOME=str(SDK)),capture_output=True,text=True,timeout=240)
 if r.returncode:raise RuntimeError(r.stdout+r.stderr)
 return r.stdout
run([G,'--no-daemon','assembleRelease'])
src=P/'pixel-gradle/build/outputs/apk/release/GalaxyCompanion-release-unsigned.apk';out=P/'pixel/galaxy-services-pixel.apk';key=P.parent/'signing-private'
run([J/'java.exe','-jar',BT/'lib/apksigner.jar','sign','--ks',key/'signing.p12','--ks-pass','file:'+str(key/'signing-password.txt'),'--min-sdk-version','26','--out',out,src])
run([J/'java.exe','-jar',BT/'lib/apksigner.jar','verify','--min-sdk-version','26',out])
receipt={'version':'0.18.0','bytes':out.stat().st_size,'sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'device_tested':False}
(P/'pixel/build-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt))
