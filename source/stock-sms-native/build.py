"""Local compilation and portable C tests only; never accesses a device."""
import pathlib, subprocess, os, re, hashlib
P=pathlib.Path(__file__).resolve().parent
B=pathlib.Path(os.environ['ANDROID_SDK_ROOT']+'/ndk/28.1.13356709/toolchains/llvm/prebuilt/windows-x86_64/bin')
(P/'tmp').mkdir(exist_ok=True)
ENV=dict(os.environ,TMP=str(P/'tmp'),TEMP=str(P/'tmp'))
def run(args,capture=False):
 return subprocess.run([str(a) for a in args],check=True,text=True,capture_output=capture,env=ENV)
common=[B/'clang.exe','--target=armv7a-linux-androideabi21','-std=c11','-marm','-fno-pie','-no-pie','-O2','-Wall','-Wextra','-Werror','-Wno-unused-function','-fno-stack-protector','-U_FORTIFY_SOURCE','-D_FORTIFY_SOURCE=0','-ffunction-sections','-fdata-sections','-Wl,--gc-sections','-Wl,--hash-style=both','-Wl,--no-rosegment','-Wl,-z,max-page-size=4096']
src=[P/'stock_ril.c',P/'bridge_core.c',P/'mute_core.c',P/'lifecycle.c',P/'sha256.c',P/'sms_core.c',P/'sms_tests.c',P/'sms_batch.c',P/'sms_batch_tests.c',P/'sms_address.c',P/'sms_address_tests.c']
libc=run([B/'llvm-readelf.exe','--dyn-syms',P.parent/'peggle-debug-tools/symbols/system/lib/libc.so'],True).stdout
exports=set(re.findall(r'\b(?:GLOBAL|WEAK)\s+DEFAULT\s+(?!UND)\w+\s+(\w+)(?:@|\s|$)',libc))
receipts=[]
for name,flags in [('galaxy-companion-launcher',['-DLAUNCHER_BUILD=1']),('galaxy-companion-ril',[])]:
 run(common+flags+src+['-ldl','-o',P/name])
 audit=run([B/'llvm-readelf.exe','-h','-d','--dyn-syms',P/name],True).stdout
 (P/(name+'-elf.txt')).write_text(audit)
 if 'EXEC (Executable file)' not in audit or 'AArch64' in audit: raise RuntimeError('Not ARM32 non-PIE')
 imports=set(re.findall(r'\bUND\s+(\w+)(?:@|\s|$)',audit))
 missing=sorted(imports-exports-{'dlopen','dlsym'})
 if missing:raise RuntimeError('Missing old libc imports: '+repr(missing))
 data=(P/name).read_bytes();receipts.append(f'{name} {len(data)} {hashlib.sha256(data).hexdigest()}')
run([B/'clang.exe','--target=wasm32','-std=c11','-O0','-ffreestanding','-fno-builtin','-nostdlib','-Wall','-Wextra','-Werror','-Wl,--no-entry','-Wl,--export=candidate_self_test','-Wl,--export=sms_test_count','-Wl,--export=sms_batch_test_count','-Wl,--export=sms_address_test_count',P/'bridge_core.c',P/'mute_core.c',P/'lifecycle.c',P/'sha256.c',P/'sms_core.c',P/'sms_tests.c',P/'sms_batch.c',P/'sms_batch_tests.c',P/'sms_address.c',P/'sms_address_tests.c',P/'candidate_self_test.c',P/'wasm_memory.c','-o',P/'self_test.wasm'])
result=run(['node',P/'self_test.cjs'],True).stdout
(P/'build-result.txt').write_text(result+'PASS: all direct imports present in actual S2 libc.\n'+'\n'.join(receipts)+'\n')
print(result.strip());print('\n'.join(receipts));print('No device access or activation occurred.')
