"""Execute the actual C policy in WebAssembly; no phone, network or sensor writes."""
from pathlib import Path
import os
import subprocess

P = Path(__file__).resolve().parent


def main():
    tools = Path(os.environ['ANDROID_SDK_ROOT'])/'ndk/28.1.13356709/toolchains/llvm/prebuilt/windows-x86_64/bin'
    (P/'tmp').mkdir(exist_ok=True)
    env = dict(os.environ, TMP=str(P/'tmp'), TEMP=str(P/'tmp'))
    subprocess.run([str(x) for x in [tools/'clang.exe', '--target=wasm32', '-std=c11', '-O2',
                    '-nostdlib', '-Wall', '-Wextra', '-Werror', '-Wl,--no-entry',
                    '-Wl,--export=charge_policy_tests', P/'charge_policy.c', '-o', P/'policy.wasm']],
                   check=True, env=env)
    code = "const fs=require('fs'); WebAssembly.instantiate(fs.readFileSync(process.argv[1]),{}).then(x=>{const n=x.instance.exports.charge_policy_tests();if(n!==40)throw Error('Policy check '+n);console.log(n+' C charging policy checks passed');}).catch(e=>{console.error(e);process.exit(1)});"
    subprocess.run(['node', '-e', code, str(P/'policy.wasm')], check=True)


if __name__ == '__main__':
    main()
