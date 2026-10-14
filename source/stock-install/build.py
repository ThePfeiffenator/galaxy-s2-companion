"""Build ARM executables + actual C transaction tests. No device commands."""
import pathlib, subprocess, os, re, hashlib, tarfile
P=pathlib.Path(__file__).resolve().parent
S=P.parent/'stock-sms-native'
B=pathlib.Path(os.environ['ANDROID_SDK_ROOT']+'/ndk/28.1.13356709/toolchains/llvm/prebuilt/windows-x86_64/bin')
(P/'tmp').mkdir(exist_ok=True)
ENV=dict(os.environ,TMP=str(P/'tmp'),TEMP=str(P/'tmp'))
def run(args,capture=False):
 return subprocess.run([str(a) for a in args],check=True,text=True,capture_output=capture,env=ENV)
def digest(data):return hashlib.sha256(data).hexdigest()
targets=[('/system/bin/galaxy-companion-ril',0o755,0),('/system/framework/galaxy-companion-identity.jar',0o644,0),('/system/etc/galaxy-companion.conf',0o600,0),('/system/bin/rild',0o755,2000)]
names=['worker','identity.jar','config','launcher']
stage='/data/local/galaxy-companion-stage/'
seed=P/'fixture-seed'
seed.mkdir(exist_ok=True)
def seedfile(name,data):
 p=seed/name.lstrip('/');p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
sentinel=b'Galaxy Companion isolated installer fixture v1\n'
seedfile('/FIXTURE_ONLY',sentinel)
rows=[]
for i,((target,mode,gid),name) in enumerate(zip(targets,names)):
 data=f'Fixture candidate artifact {i}: never executable on the real system.\n'.encode()
 seedfile(stage+name,data)
 rows.append(' {"%s","%s","%s",%d,0%o,0,%d},'%(stage+name,target,digest(data),len(data),mode,gid))
header='#define INSTALL_MANIFEST_READY 1\n#define FIXTURE_SENTINEL_SHA "%s"\n#define FIXTURE_SENTINEL_SIZE %d\nstatic const Artifact artifacts[4]={\n%s\n};\n'%(digest(sentinel),len(sentinel),'\n'.join(rows))
(P/'fixture_manifest.h').write_text(header,newline='\n')
if not (P/'install_manifest.h').exists():
 (P/'install_manifest.h').write_text('#define INSTALL_MANIFEST_READY 0\nstatic const Artifact artifacts[4]={\n'+ '\n'.join(rows)+'\n};\n',newline='\n')
stock=P.parent/'call-feasibility/stock-original'
for name,pin in [('rild','12f3c38e67d45f993d44b6a3b29c8f6f885386b1d8efcb444735d8ab4b76e4ad'),('libril.so','97770ea4d5a8568e230d225a7957ebb2141bcdabfa15c81ec122b2d5f3b11864')]:
 data=(stock/name).read_bytes()
 if digest(data)!=pin:raise RuntimeError('Original source pin failed '+name)
 seedfile('/system/'+('bin/' if name=='rild' else 'lib/')+name,data)
for directory in ['system/etc','system/framework']:(seed/directory).mkdir(parents=True,exist_ok=True)
setup=['#!/system/bin/sh','set -e','root="$1"','case "$root" in /data/local/tmp/galaxy-companion-exercise-*) ;; *) exit 2;; esac','suffix="${root#/data/local/tmp/galaxy-companion-exercise-}"','case "$suffix" in ""|*[!a-zA-Z0-9]*) exit 2;; esac']
# All structural validation precedes the first metadata/file mutation. Validate
# every component explicitly; chown/chmod must never follow a seeded symlink.
for name in ['/data','/data/local','/data/local/tmp']:
 setup += [f'[ ! -L "{name}" ] && [ -d "{name}" ] || exit 2']
setup += ['[ ! -L "$root" ] && [ -d "$root" ] || exit 2','[ "$(cd -P "$root" && pwd -P)" = "$root" ] || exit 2']
for p in sorted(seed.rglob('*')):
 rel=p.relative_to(seed).as_posix()
 setup += [f'[ ! -L "$root/{rel}" ] || exit 2']
 if p.is_dir():setup += [f'if [ -e "$root/{rel}" ] && [ ! -d "$root/{rel}" ]; then exit 2; fi']
 else:setup += [f'[ -f "$root/{rel}" ] || exit 2']
setup += ['chown 0.0 "$root"','chmod 0700 "$root"']
for p in sorted(seed.rglob('*')):
 rel=p.relative_to(seed).as_posix();gid=2000 if rel=='system/bin/rild' else 0
 mode=0o700 if p.is_dir() else 0o755 if rel=='system/bin/rild' else 0o644 if rel=='system/lib/libril.so' else 0o600
 if p.is_dir():setup += [f'if [ ! -d "$root/{rel}" ]; then mkdir "$root/{rel}"; fi']
 setup += [f'chown 0.{gid} "$root/{rel}"',f'chmod {mode:04o} "$root/{rel}"']
(P/'fixture-prepare.sh').write_text('\n'.join(setup)+'\n',newline='\n')
with tarfile.open(P/'fixture-seed.tar','w') as tar:
 for p in sorted(seed.rglob('*')):
  info=tar.gettarinfo(str(p),str(p.relative_to(seed)).replace('\\','/'));info.uid=0;info.gid=2000 if info.name=='system/bin/rild' else 0;info.uname='root';info.gname='root'
  info.mode=0o700 if p.is_dir() else 0o755 if info.name=='system/bin/rild' else 0o644 if info.name=='system/lib/libril.so' else 0o600
  if p.is_file():
   with p.open('rb') as f:tar.addfile(info,f)
  else:tar.addfile(info)
common=[B/'clang.exe','--target=armv7a-linux-androideabi21','-std=c11','-marm','-fno-pie','-no-pie','-O2','-Wall','-Wextra','-Werror','-fno-stack-protector','-U_FORTIFY_SOURCE','-D_FORTIFY_SOURCE=0','-ffunction-sections','-fdata-sections','-Wl,--gc-sections','-Wl,--hash-style=both','-Wl,--no-rosegment','-Wl,-z,max-page-size=4096','-I'+str(S)]
libc=run([B/'llvm-readelf.exe','--dyn-syms',P.parent/'peggle-debug-tools/symbols/system/lib/libc.so'],True).stdout
exports=set(re.findall(r'\b(?:GLOBAL|WEAK)\s+DEFAULT\s+(?!UND)\w+\s+(\w+)(?:@|\s|$)',libc))
receipts=[]
for name,flags in [('galaxy-companion-install',[]),('galaxy-companion-install-fixture',['-DFIXTURE_BUILD=1'])]:
 run(common+flags+[P/'install.c',P/'transaction.c',S/'sha256.c','-ldl','-o',P/name])
 audit=run([B/'llvm-readelf.exe','-h','-d','--dyn-syms',P/name],True).stdout;(P/(name+'-elf.txt')).write_text(audit)
 if 'EXEC (Executable file)' not in audit or 'AArch64' in audit:raise RuntimeError('Not ARM32 non-PIE')
 missing=sorted(set(re.findall(r'\bUND\s+(\w+)(?:@|\s|$)',audit))-exports-{'dlopen','dlsym'})
 if missing:raise RuntimeError('Missing old libc symbols '+repr(missing))
 data=(P/name).read_bytes();receipts.append(f'{name} {len(data)} {digest(data)}')
run([B/'clang.exe','--target=wasm32','-std=c11','-O0','-ffreestanding','-fno-builtin','-nostdlib','-Wall','-Wextra','-Werror','-Wl,--no-entry','-Wl,--export=transaction_self_test',P/'transaction.c',P/'wasm_memory.c','-o',P/'transaction_test.wasm'])
result=run(['node',P/'transaction_test.cjs'],True).stdout
(P/'build-result.txt').write_text(result+'PASS: direct imports available in captured S2 libc.\n'+'\n'.join(receipts)+'\nNo device access or POSIX fixture execution performed by this build.\n')
print(result.strip());print('\n'.join(receipts));print('No device access or POSIX fixture execution performed.')
