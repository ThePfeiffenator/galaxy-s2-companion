import hashlib
import os
import pathlib
import subprocess
import zipfile

P=pathlib.Path(__file__).resolve().parent
SDK=pathlib.Path(os.environ['ANDROID_SDK_ROOT'])
J=pathlib.Path(os.environ['JAVA_HOME']+'/bin')
BT=SDK/'build-tools/36.1.0'
AND=SDK/'platforms/android-36/android.jar'
ENV=dict(os.environ,JAVA_HOME=str(J.parent),PATH=str(J)+os.pathsep+os.environ['PATH'])
def run(args): subprocess.run([str(a) for a in args],env=ENV,check=True)
for name in ['classes','test-classes','dex']: (P/name).mkdir(exist_ok=True)
run([J/'javac.exe','-source','8','-target','8','-classpath',AND,'-d',P/'classes',P/'IdentityMain.java',P/'IdentityChecks.java'])
run([J/'javac.exe','-source','8','-target','8','-d',P/'test-classes',P/'IdentityChecks.java',P/'IdentityChecksTest.java'])
run([J/'java.exe','-cp',P/'test-classes','org.retrobridge.galaxyidentity.IdentityChecksTest',P])
classes=sorted((P/'classes').rglob('*.class'))
run([J/'java.exe','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','15','--lib',AND,'--output',P/'dex']+classes)
jar=P/'galaxy-companion-identity.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z: z.write(P/'dex/classes.dex','classes.dex')
print('JAR_SHA256='+hashlib.sha256(jar.read_bytes()).hexdigest())
print('JAR_BYTES='+str(jar.stat().st_size))
