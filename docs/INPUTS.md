# Local prerequisites and original inputs

Run commands from the repo root in PowerShell. Start with [compatibility](COMPATIBILITY.md), preserving any existing signing keys. This page performs no device installation.

## Toolchain

Use Python **3.12+**, JDK **17**, Gradle **8.9**, Node.js, Git and Android command-line tools from their official projects. Put `python`, `node` and `git` on PATH. Set `JAVA_HOME`, `ANDROID_SDK_ROOT` and the full path to Gradle's `bin/gradle.bat` as `GRADLE_EXECUTABLE`. No Gradle wrapper is bundled. First builds require network access for dependencies.

Install SDK components with the official [sdkmanager](https://developer.android.com/tools/sdkmanager), reviewing its license prompts yourself:

```powershell
& "$env:ANDROID_SDK_ROOT\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-36" "build-tools;36.1.0" "ndk;28.1.13356709"
python tools/doctor.py
```

`doctor.py` checks local file/tool availability; it never runs ADB, downloads or installs. Review tool versions separately. After configuration/input staging, use `python tools/doctor.py --target services`, or another target. `build.py` runs that preflight before compiling.

## Inputs in ignored .local

| Input kind | Owner-supplied input | Used by |
| --- | --- | --- |
| `android15` | Android 4.0.4/API 15 SDK android.jar | Legacy helper compilation |
| `apktool` | Official apktool 3.0.3 JAR | Original APK decoding/building |
| `baksmali` | Official baksmali 2.5.2 executable JAR | Compiled helper insertion |
| `framework` | Original framework-res.apk from the supported S2 | Resource decoding; import before APKs |
| `libc` | Original /system/lib/libc.so | Old-Android native import audit |
| `libandroid_runtime` | Original /system/lib/libandroid_runtime.so | Guarded Bluetooth patch |
| `rild`, `libril` | Original /system/bin/rild and /system/lib/libril.so | Native pins/recovery fixture |

Obtain API 15 from an official SDK archive or your licensed SDK installation. If the current SDK manager no longer offers it, acquire that archived platform separately. Do not substitute API 36 or a random file named android.jar. Apktool's [installation guide](https://apktool.org/docs/install/) links official releases; baksmali is in [smali 2.5.2](https://github.com/JesusFreke/smali/releases/tag/v2.5.2). Never use firmware from another S2 model.

```powershell
python tools/prepare_inputs.py apktool 'C:\PrivateInputs\apktool_3.0.3.jar'
python tools/prepare_inputs.py baksmali 'C:\PrivateInputs\baksmali-2.5.2.jar'
python tools/prepare_inputs.py framework 'C:\PrivateInputs\framework-res.apk'
python tools/prepare_inputs.py android15 'C:\PrivateInputs\android-15\android.jar'
```

Use the manual's commands for firmware. `fetch_dependencies.py` separately downloads/hash-checks four pinned Bouncy Castle 1.86 JARs. Record provenance/version/SHA-256 privately (`Get-FileHash -Algorithm SHA256 PATH`); a filename is not provenance.

Optional original APK reference versions: Store 3.7.15, Gmail 4.1.2, Music 4.3.615.481147, RIF 3.6.16, Google Quick Search Box 1.4.1.488656, Telegram 3.9.0. AP Mobile 12.01.09.01 additionally needs its matching odex and pinned FL24 framework files; see [AP Mobile inputs](AP_MOBILE.md). YouTube must satisfy its layout guards. Arbitrary versions are not supported.

Input preparation refuses to overwrite a different existing file or decoded directory. YouTube's first candidate preparation also refuses an existing tree. On failure inspect the error; use a separate fresh materialization for a clean rebuild, preserving your signing files and declared original inputs. Do not delete credentials or edits to force success.

## Configure in stages

Copy config.example.json to local.json. Fill real identities for a real installation; leave native UID/hash fields null initially. Configure, generate a key **once**, fetch dependencies and stage inputs. Build calls, measure its real installed identity, then complete the native identity sequence. Finish configure passes **before** generating Telegram artwork or editing materialized sources: configure deliberately preserves changed files.

Synthetic identities are only for offline compilation tests. Never install native artifacts built with synthetic UID/path values. Installed identity still requires a device checkpoint. Keep `.local`, originals, signed outputs and receipts private.
