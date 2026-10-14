import os
from pathlib import Path
import subprocess,struct,zipfile,hashlib,json
p=Path(__file__).resolve().parent
llvm=Path(os.environ['ANDROID_SDK_ROOT']+'/ndk/28.1.13356709/toolchains/llvm/prebuilt/windows-x86_64/bin')
original=(p.parent/'peggle-diagnostics/installed-libpeggle.so').read_bytes()
assert hashlib.sha256(original).hexdigest()=='ccb1d53978e88893d19a105a58a5c7090d1519243f2c88a2dd19f4ddc6a7fdb2', 'Unsupported original Peggle native library'
sites=[(i,struct.unpack_from('<I',original,i)[0]) for i in range(0xe0f8,0x68c58,4) if struct.unpack_from('<I',original,i)[0] in (0xe10ec09c,0xe1002091)]
assert len(sites)==16,sites
src='.syntax unified\n.arm\n.text\n'
for i,(site,opcode) in enumerate(sites):
    src+=f'.global hook{i}\nhook{i}:\n'
    if opcode==0xe10ec09c:
        src+='''push {r0-r2}
mrs r2,cpsr
dmb ish
1: ldrex r0,[lr]
strex r1,r12,[lr]
cmp r1,#0
bne 1b
dmb ish
mov r12,r0
msr cpsr_f,r2
pop {r0-r2}
'''
    else:
        src+='''push {r3-r5}
mrs r5,cpsr
dmb ish
1: ldrex r3,[r0]
strex r4,r1,[r0]
cmp r4,#0
bne 1b
dmb ish
mov r2,r3
msr cpsr_f,r5
pop {r3-r5}
'''
    src+=f'.global back{i}\nback{i}: .word 0\n'
(p/'atomic_fix.S').write_text(src)
subprocess.run([str(llvm/'clang.exe'),'--target=armv7a-linux-androideabi','-marm','-nostdlib','-static','-Wl,-Ttext=0x76920,-e,hook0',str(p/'atomic_fix.S'),'-o',str(p/'atomic_fix.elf')],check=True)
subprocess.run([str(llvm/'llvm-objcopy.exe'),'-O','binary','--only-section=.text',str(p/'atomic_fix.elf'),str(p/'atomic_fix.bin')],check=True)
nm=subprocess.check_output([str(llvm/'llvm-nm.exe'),str(p/'atomic_fix.elf')],text=True)
symbols={m[2]:int(m[0],16) for line in nm.splitlines() if (m:=line.split()) and len(m)==3}
stub=bytearray((p/'atomic_fix.bin').read_bytes());assert len(stub)<0x77000-0x76920
lib=bytearray(original)
def word(b,o,v):struct.pack_into('<I',b,o,v)
def branch(address,target):
    assert (target-address-8)%4==0 and abs(target-address)<0x2000000
    return 0xea000000|(((target-address-8)//4)&0xffffff)
for i,(site,opcode) in enumerate(sites):
    word(lib,site,branch(site,symbols[f'hook{i}']))
    back=symbols[f'back{i}'];word(stub,back-0x76920,branch(back,site+4))
assert lib[0x76920:0x76920+len(stub)]==bytes(len(stub))
lib[0x76920:0x76920+len(stub)]=stub
word(lib,100,0x77000);word(lib,104,0x77000)
# Only the executable segment padding, its size fields, and 16 SWP instructions change.
allowed=set(range(100,108))|set(range(0x76920,0x76920+len(stub)))|{j for site,_ in sites for j in range(site,site+4)}
assert all(a==b or i in allowed for i,(a,b) in enumerate(zip(original,lib)))
assert len(lib)==len(original)
(p/'libpeggle-atomic-fix.so').write_bytes(lib)
for debug,source in [(False,p.parent/'peggle-diagnostics/peggle-original.apk')]:
    name='atomic-fix-debug-unsigned.apk' if debug else 'atomic-fix-unsigned.apk'
    with zipfile.ZipFile(source) as src,zipfile.ZipFile(p/name,'w') as dst:
        for info in src.infolist():
            if not info.filename.startswith('META-INF/'):
                dst.writestr(info,lib if info.filename=='lib/armeabi/libpeggle.so' else src.read(info))
report={'replaced_sites':[hex(x) for x,_ in sites],'stub_bytes':len(stub),'original_native_sha256':hashlib.sha256(original).hexdigest(),'patched_native_sha256':hashlib.sha256(lib).hexdigest()}
(p/'atomic-fix-manifest.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
sdk=Path(os.environ['ANDROID_SDK_ROOT']);bt=sdk/'build-tools/36.1.0';java=Path(os.environ['JAVA_HOME'])/'bin/java.exe';key=p.parent/'signing-private'
subprocess.run([str(bt/'zipalign.exe'),'-f','4',str(p/'atomic-fix-unsigned.apk'),str(p/'atomic-fix-aligned.apk')],check=True)
out=p/'Peggle-atomic-fix.apk'
subprocess.run([str(java),'-jar',str(bt/'lib/apksigner.jar'),'sign','--ks',str(key/'signing.p12'),'--ks-pass','file:'+str(key/'signing-password.txt'),'--min-sdk-version','15','--out',str(out),str(p/'atomic-fix-aligned.apk')],check=True)
subprocess.run([str(java),'-jar',str(bt/'lib/apksigner.jar'),'verify','--min-sdk-version','15',str(out)],check=True)
print('Signed personal patched APK:',out.name)
