"""Pinned, workspace-local TDLib arm64 JSONJava build. No device/account access."""
import os, pathlib, subprocess, sys, json, hashlib, shutil, tarfile
ROOT = pathlib.Path(__file__).resolve().parent
SDK = pathlib.Path(os.environ['ANDROID_SDK_ROOT'])
NDK = SDK/'ndk/28.1.13356709'
CMAKE = SDK/'cmake/3.22.1/bin/cmake.exe'
NINJA = SDK/'cmake/3.22.1/bin/ninja.exe'
MSYS = pathlib.Path(os.environ.get('TDLIB_MSYS_ROOT', str(ROOT/'tools/msys64')))/'usr/bin'
SOURCE = ROOT/'td'
COMMIT = 'c15d3f5a5de6e3ba5839822c451152e5e18bb700'
OPENSSL_SHA = '603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a'
(ROOT/'logs').mkdir(exist_ok=True)
def run(name,args,cwd=ROOT,env=None):
    print('BEGIN',name,flush=True)
    with (ROOT/'logs'/f'{name}.log').open('w',encoding='utf-8') as log:
        result=subprocess.run([str(x) for x in args],cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT)
    if result.returncode:
        print((ROOT/'logs'/f'{name}.log').read_text(errors='replace')[-7000:])
        raise SystemExit(result.returncode)
    print('PASS',name,flush=True)
def host_environment():
    env=dict(os.environ)
    result=subprocess.check_output(['cmd.exe','/d','/c',str(ROOT/'host_environment.cmd')],text=True)
    for line in result.splitlines():
        if '=' in line:
            key,value=line.split('=',1);env[key]=value
    return env
def main():
    actual=subprocess.check_output(['git','rev-parse','HEAD'],cwd=SOURCE,text=True).strip()
    assert actual==COMMIT,actual
    archive=ROOT/'downloads/openssl-3.5.9.tar.gz'
    if hashlib.sha256(archive.read_bytes()).hexdigest()!=OPENSSL_SHA:
        raise SystemExit('OpenSSL source hash mismatch')
    action=sys.argv[1] if len(sys.argv)>1 else 'all'
    if action in ('host','all'):
        env=host_environment()
        run('host-configure',[CMAKE,'-S',SOURCE/'example/android','-B',ROOT/'build-host','-G','Ninja',
            '-DCMAKE_MAKE_PROGRAM='+str(NINJA),'-DCMAKE_BUILD_TYPE=Release','-DCMAKE_TRY_COMPILE_CONFIGURATION=Release','-DTD_ANDROID_JSON_JAVA=ON',
            '-DTD_GENERATE_SOURCE_FILES=ON','-DGPERF_EXECUTABLE='+str(MSYS/'gperf.exe')],env=env)
        run('host-generate',[CMAKE,'--build',ROOT/'build-host','--parallel','4'],env=env)
    if action in ('openssl','all'):
        # The original recipe assumed an already extracted source tree.
        # Extract the pinned archive on a fresh setup, preserving existing builds.
        if not (ROOT/'deps/openssl-3.5.9').exists():
            with tarfile.open(archive) as source:
                source.extractall(ROOT/'deps', filter='data')
        env=dict(os.environ);env['MSYSTEM']='MSYS'
        run('openssl-build',[MSYS/'bash.exe','--noprofile','--norc',ROOT/'build_openssl.sh'],env=env)
    if action in ('android','all'):
        run('android-configure',[CMAKE,'-S',SOURCE/'example/android','-B',ROOT/'build-arm64','-G','Ninja',
            '-DCMAKE_MAKE_PROGRAM='+str(NINJA),'-DCMAKE_TOOLCHAIN_FILE='+str(NDK/'build/cmake/android.toolchain.cmake'),
            '-DOPENSSL_ROOT_DIR='+str(ROOT/'openssl-arm64'),'-DOPENSSL_USE_STATIC_LIBS=ON',
            '-DCMAKE_BUILD_TYPE=RelWithDebInfo','-DANDROID_ABI=arm64-v8a','-DANDROID_STL=c++_static',
            '-DANDROID_PLATFORM=android-26','-DTD_ANDROID_JSON_JAVA=ON','-DGPERF_EXECUTABLE='+str(MSYS/'gperf.exe'),
            '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,--strip-debug'])
        run('android-build',[CMAKE,'--build',ROOT/'build-arm64','--target','tdjni','--parallel','4'])
if __name__=='__main__':main()
