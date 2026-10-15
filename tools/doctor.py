"""Check local build prerequisites only. Never runs ADB, downloads or installs."""
from pathlib import Path
import argparse
import importlib.util
import os
import shutil
import sys
from build import TARGETS

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / '.local/work'
API15 = 'telegram-s2-client/sdk/platform/android-4.0.4/android.jar'
LIBC = 'peggle-debug-tools/symbols/system/lib/libc.so'
APKTOOL = 'peggle-debug-tools/apktool.jar'
BAKSMALI = 'call-feasibility/baksmali-2.5.2.jar'
INPUTS = {
    'services': [API15],
    'ap-mobile': [APKTOOL, BAKSMALI, 'ap-mobile/inputs/APNews.apk', 'ap-mobile/inputs/APNews.odex', 'ap-mobile/inputs/framework/core.odex'],
    'native-calls': [LIBC],
    'charging': [LIBC, 'sms-companion-v12/galaxy-companion-v14.apk', 'charging/inputs/identity.json', 'charging/inputs/install-recovery.original.sh'],
    'native-installer': [LIBC, 'call-feasibility/stock-original/rild', 'call-feasibility/stock-original/libril.so'],
    'bluetooth-patch': ['peggle-debug-tools/symbols/system/lib/libandroid_runtime.so'],
    'bluetooth-installer': [LIBC],
    'trust-store': [LIBC, 'legacy-services/public-ca.pem'],
    'gmail': [API15, APKTOOL, BAKSMALI, 'gmail-investigation/decoded/apktool.yml'],
    'store': [API15, APKTOOL, BAKSMALI, 'play-store-investigation/decoded/apktool.yml'],
    'youtube': [API15, APKTOOL, BAKSMALI, 'legacy-app-investigation/youtube-original-decoded/apktool.yml'],
    'rif': [API15, APKTOOL, BAKSMALI, 'reddit-compatibility/decoded/apktool.yml'],
    'google-widget': [API15, APKTOOL, BAKSMALI, 'google-widget/decoded/apktool.yml'],
    'music': [API15, APKTOOL, BAKSMALI, 'gallery-music/music-clean/apktool.yml'],
    'telegram-assets': ['telegram-classic/reference-decoded/AndroidManifest.xml'],
    'telegram-s2': [API15],
    'telegram-pixel': ['telegram-pixel-engine/candidate/jniLibs/arm64-v8a/libtdjsonjava.so'],
}


def problems(target=None):
    errors = []
    def need(path, label):
        if not path.is_file():
            errors.append('Missing ' + label)
    if sys.version_info < (3, 12):
        errors.append('Use Python 3.12 or newer (safe archive extraction is required)')
    if os.name != 'nt':
        errors.append('Build recipes currently require Windows')
    for program in ('git', 'node'):
        if not shutil.which(program): errors.append('Missing PATH tool: ' + program)
    for name in ('JAVA_HOME', 'ANDROID_SDK_ROOT'):
        if not os.environ.get(name): errors.append('Set ' + name)
    java = Path(os.environ.get('JAVA_HOME', '__missing__'))
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT', '__missing__'))
    for tool in ('java.exe', 'javac.exe', 'keytool.exe'): need(java/'bin'/tool, 'JAVA_HOME/bin/' + tool)
    for rel in ('platforms/android-36/android.jar', 'build-tools/36.1.0/aapt2.exe',
                'build-tools/36.1.0/zipalign.exe', 'build-tools/36.1.0/lib/d8.jar',
                'build-tools/36.1.0/lib/apksigner.jar',
                'ndk/28.1.13356709/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'):
        need(sdk/rel, 'SDK/' + rel)
    if target in (None, 'services', 'google-accounts'):
        need(Path(os.environ.get('GRADLE_EXECUTABLE', '__missing__')), 'GRADLE_EXECUTABLE (Gradle 8.9)')
    if target:
        if not WORK.is_dir(): errors.append('Run tools/configure.py first')
        for rel in INPUTS.get(target, []): need(WORK/rel, '.local/work/' + rel + ' (see INPUTS.md)')
        if target in ('services', 'calls', 'google-accounts', 'store-installer', 'gmail', 'store',
                      'youtube', 'rif', 'google-widget', 'music', 'ap-mobile', 'telegram-s2', 'telegram-pixel'):
            for name in ('signing.p12', 'signing-password.txt'): need(WORK/'signing-private'/name, 'private signing files; run create_signing_key.py only on a new setup')
        if target == 'trust-store' and importlib.util.find_spec('cryptography') is None:
            errors.append('Install Python cryptography in this build environment')
        if target == 'services':
            for name in ('bcpkix', 'bcprov', 'bctls', 'bcutil'):
                need(WORK/'legacy-services/lib'/(name+'-jdk15to18-1.86.jar'), name+' TLS dependency; run fetch_dependencies.py')
        if target == 'telegram-engine':
            for name in ('cmake.exe', 'ninja.exe'): need(sdk/'cmake/3.22.1/bin'/name, 'SDK CMake 3.22.1/' + name)
            engine=WORK/'telegram-pixel-engine'
            for rel in ('td/CMakeLists.txt', 'downloads/openssl-3.5.9.tar.gz'): need(engine/rel, 'Telegram input ' + rel)
            msys=Path(os.environ.get('TDLIB_MSYS_ROOT', str(engine/'tools/msys64')))
            for name in ('bash.exe', 'perl.exe', 'make.exe', 'gperf.exe'): need(msys/'usr/bin'/name, 'MSYS2 usr/bin/' + name)
    return errors


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', choices=TARGETS)
    args=parser.parse_args()
    errors=problems(args.target)
    for error in errors: print(error)
    print('FAIL' if errors else 'PASS', 'local prerequisites; versions, licenses and phone compatibility still require review.')
    return bool(errors)


if __name__ == '__main__': sys.exit(main())
