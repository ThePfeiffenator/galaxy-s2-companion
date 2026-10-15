"""Build pinned charging artifacts from the operator's inputs. No ADB/install."""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
from profile import boot_script, validate_identity
from test_policy import main as policy_tests

P = Path(__file__).resolve().parent
B = Path(os.environ['ANDROID_SDK_ROOT'])/'ndk/28.1.13356709/toolchains/llvm/prebuilt/windows-x86_64/bin'


def main():
    apk = P.parent/'sms-companion-v12/galaxy-companion-v14.apk'
    identity = json.loads((P/'inputs/identity.json').read_text(encoding='utf-8-sig'))
    uid, sha = validate_identity(identity, apk.read_bytes())
    original = (P/'inputs/install-recovery.original.sh').read_bytes()
    new = boot_script(original)
    (P/'tmp').mkdir(exist_ok=True)
    env = dict(os.environ, TMP=str(P/'tmp'), TEMP=str(P/'tmp'))
    policy_tests()
    (P/'app_identity.h').write_text('#define CHARGE_APP_UID %d\n#define CHARGE_APP_SHA "%s"\n' % (uid, sha), encoding='ascii')
    (P/'install-recovery.sh').write_bytes(new)

    def run(args):
        return subprocess.run([str(a) for a in args], check=True, capture_output=True, text=True, env=env).stdout

    libc = run([B/'llvm-readelf.exe', '--dyn-syms', P.parent/'peggle-debug-tools/symbols/system/lib/libc.so'])
    exports = set(re.findall(r'\b(?:GLOBAL|WEAK)\s+DEFAULT\s+(?!UND)\w+\s+(\w+)(?:@|\s|$)', libc))
    common = [B/'clang.exe', '--target=armv7a-linux-androideabi21', '-std=c11', '-marm',
              '-fno-pie', '-no-pie', '-O2', '-Wall', '-Wextra', '-Werror', '-fno-stack-protector',
              '-U_FORTIFY_SOURCE', '-D_FORTIFY_SOURCE=0', '-Wl,--hash-style=both',
              '-Wl,--no-rosegment', '-Wl,-z,max-page-size=4096']
    receipts = {}

    def native(name, sources):
        run(common + [P/s for s in sources] + [P/'sha256.c', '-ldl', '-o', P/name])
        audit = run([B/'llvm-readelf.exe', '-h', '--dyn-syms', P/name])
        missing = set(re.findall(r'\bUND\s+(\w+)(?:@|\s|$)', audit)) - exports - {'dlopen', 'dlsym'}
        if missing or 'EXEC (Executable file)' not in audit or 'Machine:                           ARM' not in audit:
            raise RuntimeError('ARM32/old-libc audit failed: '+repr(sorted(missing)))
        (P/(name+'-elf.txt')).write_text(audit, encoding='utf-8')
        receipts[name] = hashlib.sha256((P/name).read_bytes()).hexdigest()

    native('galaxy-charge-manager', ['charge_manager.c', 'charge_policy.c'])
    pins = {'OLD_SCRIPT_SHA': hashlib.sha256(original).hexdigest(),
            'NEW_SCRIPT_SHA': hashlib.sha256(new).hexdigest(),
            'MANAGER_SHA': receipts['galaxy-charge-manager']}
    (P/'install_manifest.h').write_text(''.join('#define %s "%s"\n' % item for item in pins.items()), encoding='ascii')
    native('galaxy-charge-install', ['install_charge.c'])
    receipt = dict(policy_checks=40, old_libc_imports_verified=True, apk_sha256=sha,
                   artifacts=receipts, pins=pins, device_access=False)
    (P/'build-receipt.json').write_text(json.dumps(receipt, indent=2)+'\n', encoding='utf-8')
    print('Built charging manager and guarded installer; 40 C checks and old-libc audits passed. No device access.')


if __name__ == '__main__':
    main()
