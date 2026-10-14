"""Exact-file compatibility candidate; no device access or system writes."""
import pathlib,subprocess,struct,hashlib,os
P=pathlib.Path(__file__).resolve().parent
B=pathlib.Path(os.environ['ANDROID_SDK_ROOT']+'/ndk/28.1.13356709/toolchains/llvm/prebuilt/windows-x86_64/bin')
src=P.parent/'peggle-debug-tools/symbols/system/lib/libandroid_runtime.so'
b=bytearray(src.read_bytes())
expected='ec764fdfaccb460819256cd48e666181ddfde6227e9bae407baac4820b9be473'
assert len(b)==816612 and hashlib.sha256(b).hexdigest()==expected
assert b[0x7cc82:0x7cc86]==bytes.fromhex('002857d1')
assert not any(b[0xbb48c:0xbc000])
assert b[0xc4fa8:0xc4fb1]==b'Services\0'
assert struct.unpack_from('<8I',b,84)==(1,0,0,0,0xbb48c,0xbb48c,5,4096)
(P/'tmp').mkdir(exist_ok=True)
env=dict(os.environ,TMP=str(P/'tmp'),TEMP=str(P/'tmp'))
def run(args):return subprocess.run([str(a) for a in args],check=True,capture_output=True,text=True,env=env).stdout
run([B/'clang.exe','--target=armv7a-linux-androideabi21','-c',P/'compat.S','-o',P/'compat.o'])
run([B/'ld.lld.exe','-T',P/'compat.ld',P/'compat.o','-o',P/'compat.elf'])
run([B/'llvm-objcopy.exe','--dump-section',f'.hook={P/"hook.bin"}','--dump-section',f'.compatibility={P/"trampoline.bin"}',P/'compat.elf'])
hook=(P/'hook.bin').read_bytes();tramp=(P/'trampoline.bin').read_bytes()
assert len(hook)==4 and 32<=len(tramp)<=128
b[0x7cc82:0x7cc86]=hook
b[0xbb490:0xbb490+len(tramp)]=tramp
end=0xbb490+len(tramp)
struct.pack_into('<II',b,84+16,end,end)
out=P/'libandroid_runtime.so';out.write_bytes(b)
original=src.read_bytes()
allowed=set(range(0x7cc82,0x7cc86))|set(range(0xbb490,end))|set(range(100,108))
changed=[i for i in range(len(b)) if original[i]!=b[i]]
assert set(changed)<=allowed
dis=run([B/'llvm-objdump.exe','-d','--triple=thumbv7-none-linux-gnueabi','--no-show-raw-insn',P/'compat.elf'])
(P/'trampoline-disassembly.txt').write_text(dis)
report=f'ORIGINAL {len(original)} {expected}\nCANDIDATE {len(b)} {hashlib.sha256(b).hexdigest()}\nHOOK {hook.hex()}\nTRAMPOLINE 0xbb490 {len(tramp)} bytes\nRX_END 0x{end:x}\nCHANGED_BYTES {len(changed)}\nNo device execution or installation.\n'
(P/'patch-receipt.txt').write_text(report)
print(report);print(dis)
