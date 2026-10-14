from pathlib import Path
import hashlib
import os
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent
BASE = ROOT.parent.parent
LLVM = Path(os.environ['ANDROID_SDK_ROOT']+'/ndk/28.1.13356709/toolchains/llvm/prebuilt/windows-x86_64/bin')
OUTPUT = ROOT / 'bluetooth-library-install'
TEMP = ROOT / 'tmp'
TEMP.mkdir(exist_ok=True)
BUILD_ENV = dict(os.environ, TEMP=str(TEMP), TMP=str(TEMP))

command = [str(LLVM / 'clang.exe'), '--target=armv7a-linux-androideabi21',
           '-std=c11', '-marm', '-fno-pie', '-no-pie', '-O2', '-Wall', '-Wextra', '-Werror',
           '-fno-stack-protector', '-U_FORTIFY_SOURCE', '-D_FORTIFY_SOURCE=0',
           '-ffunction-sections', '-fdata-sections', '-Wl,--gc-sections', '-Wl,--hash-style=both',
           '-Wl,--no-rosegment', '-Wl,-z,max-page-size=4096',
           str(ROOT / 'install.c'), str(ROOT / 'sha256.c'), '-ldl', '-o', str(OUTPUT)]
subprocess.run(command, check=True, env=BUILD_ENV)

def symbols(path):
    return subprocess.check_output([str(LLVM/'llvm-readelf.exe'), '--dyn-syms', str(path)], text=True)

undefined = {m.group(1).split('@')[0] for line in symbols(OUTPUT).splitlines()
             if (m := re.search(r'\bUND[ \t]+(\S+)', line))}
exports = {'dlopen', 'dlsym'}  # Legacy libdl API already exercised on this phone.
for name in ('libc.so',):
    path = BASE / 'work/peggle-debug-tools/symbols/system/lib' / name
    exports.update(re.findall(r'\b(?:GLOBAL|WEAK)\s+DEFAULT\s+(?!UND)\S+\s+(\S+)', symbols(path)))
missing = undefined - exports
if missing:
    raise SystemExit('ICS missing dynamic imports: ' + ', '.join(sorted(missing)))
headers = subprocess.check_output([str(LLVM/'llvm-readelf.exe'), '-h', '-l', str(OUTPUT)], text=True)
if 'EXEC (Executable file)' not in headers or 'ARM' not in headers:
    raise SystemExit('Unexpected executable architecture/type')
sha = hashlib.sha256(OUTPUT.read_bytes()).hexdigest()
receipt = f'ARMv7 non-PIE executable built.\nSize: {OUTPUT.stat().st_size}\nSHA256: {sha}\nExact captured ICS libc dynamic import audit plus known legacy dlopen/dlsym: PASS ({len(undefined)} symbols)\nImports: {", ".join(sorted(undefined))}\nNo device calls or runtime tests in this build.\n'
(ROOT / 'build-result.txt').write_text(receipt, encoding='utf-8')
print(receipt)
