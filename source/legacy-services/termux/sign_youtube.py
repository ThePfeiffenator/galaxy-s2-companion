import os
import subprocess
from pathlib import Path
P=Path(__file__).resolve().parent
BT=Path(os.environ['ANDROID_SDK_ROOT']+'/build-tools/36.1.0')
J=Path(os.environ['JAVA_HOME']+'/bin/java.exe')
K=P.parents[1]/'signing-private'
def run(a):subprocess.run(list(map(str,a)),check=True)
run([BT/'zipalign.exe','-f','4',P/'youtube-candidate-unsigned.apk',P/'youtube-candidate-aligned.apk'])
run([J,'-jar',BT/'lib/apksigner.jar','sign','--ks',K/'signing.p12','--ks-pass','file:'+str(K/'signing-password.txt'),'--min-sdk-version','13','--out',P/'youtube-2012-pixel.apk',P/'youtube-candidate-aligned.apk'])
run([J,'-jar',BT/'lib/apksigner.jar','verify','--min-sdk-version','13',P/'youtube-2012-pixel.apk'])
print('Separate YouTube candidate signed and verified')
