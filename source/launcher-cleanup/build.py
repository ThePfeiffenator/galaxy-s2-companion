from pathlib import Path
import subprocess,os,zipfile
P=Path(__file__).resolve().parent;W=P.parent
SDK=Path(os.environ['ANDROID_SDK_ROOT']);J=Path(os.environ['JAVA_HOME']+'/bin');BT=SDK/'build-tools/36.1.0';AND=W/'telegram-s2-client/sdk/platform/android-4.0.4/android.jar'
for name in ['classes','dex','tmp']:(P/name).mkdir(exist_ok=True)
env=dict(os.environ,TMP=str(P/'tmp'),TEMP=str(P/'tmp'))
def run(args):subprocess.run([str(a) for a in args],check=True,env=env)
run([J/'javac.exe','-source','8','-target','8','-encoding','UTF-8','-cp',AND,'-d',P/'classes',P/'LauncherEntries.java'])
run([J/'java.exe','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','15','--lib',AND,'--output',P/'dex',*sorted((P/'classes').glob('*.class'))])
with zipfile.ZipFile(P/'launcher-entries.jar','w',zipfile.ZIP_DEFLATED) as z:z.write(P/'dex/classes.dex','classes.dex')
(P/'launcher-entries.sh').write_text('#!/system/bin/sh\nexport CLASSPATH=/data/local/tmp/launcher-entries.jar\nexec app_process /system/bin LauncherEntries "$@"\n',encoding='utf-8',newline='\n')
print('Built exact-pair launcher settings helper against Android API 15.')
