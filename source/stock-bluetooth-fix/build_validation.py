"""Host-only JNI and API15 dex validation helper build."""
import pathlib,subprocess,os,re,hashlib,zipfile
P=pathlib.Path(__file__).resolve().parent
SDK=pathlib.Path(os.environ['ANDROID_SDK_ROOT'])
B=SDK/'ndk/28.1.13356709/toolchains/llvm/prebuilt/windows-x86_64/bin'
J=pathlib.Path(os.environ['JAVA_HOME']+'/bin')
AND=SDK/'platforms/android-36/android.jar'
for n in ['classes','dex','tmp']:(P/n).mkdir(exist_ok=True)
env=dict(os.environ,TMP=str(P/'tmp'),TEMP=str(P/'tmp'),JAVA_HOME=str(J.parent))
def run(args):return subprocess.run([str(a) for a in args],check=True,capture_output=True,text=True,env=env).stdout
run([B/'clang.exe','--target=armv7a-linux-androideabi21','-std=c11','-marm','-fPIC','-shared','-O2','-Wall','-Wextra','-Werror','-fno-stack-protector','-U_FORTIFY_SOURCE','-D_FORTIFY_SOURCE=0','-Wl,--hash-style=both','-Wl,--no-rosegment','-Wl,-z,max-page-size=4096',P/'validation.c','-ldl','-o',P/'libbtvalidation.so'])
audit=run([B/'llvm-readelf.exe','-h','-d','--dyn-syms',P/'libbtvalidation.so'])
(P/'validation-elf-audit.txt').write_text(audit)
libc=run([B/'llvm-readelf.exe','--dyn-syms',P.parent/'peggle-debug-tools/symbols/system/lib/libc.so'])
exports=set(re.findall(r'\b(?:GLOBAL|WEAK)\s+DEFAULT\s+(?!UND)\w+\s+(\w+)(?:@|\s|$)',libc))
imports=set(re.findall(r'\bUND\s+(\w+)(?:@|\s|$)',audit))
assert not imports-exports-{'dlopen','dlsym'},imports-exports
runtime=run([B/'llvm-readelf.exe','--dyn-syms',P/'libandroid_runtime.so'])
assert '_ZN7android30parse_remote_device_propertiesEP7_JNIEnvP15DBusMessageIter' in runtime
run([J/'javac.exe','-source','8','-target','8','-classpath',AND,'-d',P/'classes',P/'ValidationMain.java'])
run([J/'java.exe','-cp',SDK/'build-tools/36.1.0/lib/d8.jar','com.android.tools.r8.D8','--min-api','15','--lib',AND,'--output',P/'dex']+sorted((P/'classes').rglob('*.class')))
with zipfile.ZipFile(P/'bluetooth-validation.jar','w',zipfile.ZIP_DEFLATED) as z:z.write(P/'dex/classes.dex','classes.dex')
lines=[]
for name in ['libandroid_runtime.so','libbtvalidation.so','bluetooth-validation.jar']:
 b=(P/name).read_bytes();lines.append(f'{name} {len(b)} {hashlib.sha256(b).hexdigest()}')
receipt='\n'.join(lines)+'\nHost build/import audit passed; no device execution.\n'
(P/'validation-build-result.txt').write_text(receipt);print(receipt,end='')
