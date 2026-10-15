# Classic Telegram companion

The S2 renders a classic chat interface while a separately authorized TDLib session runs on the modern Android phone. It does not reuse or extract the official Telegram app's private session. Keep the old reference APK local; it supplies classic artwork during your build.

## Build prerequisites

In addition to the main guide, install Visual Studio 2022 Build Tools with C++ and the SDK CMake/Ninja package:

```powershell
& "$env:ANDROID_SDK_ROOT\cmdline-tools\latest\bin\sdkmanager.bat" "cmake;3.22.1"
```

Install [MSYS2](https://www.msys2.org/) and, in its MSYS shell, install `make`, `perl` and `gperf` with `pacman -S --needed make perl gperf`. In PowerShell set `TDLIB_MSYS_ROOT` to your MSYS2 root (for example `C:\Tools\msys64`). The default is `.local/work/telegram-pixel-engine/tools/msys64`. Review `host_environment.cmd` for the actual Visual Studio installation path. Compiler tools can be shared across builds; a prebuilt TDLib library is not a from-source validation.

After `configure.py`, clone and pin TDLib:

```powershell
git clone https://github.com/tdlib/td.git .local/work/telegram-pixel-engine/td
git -C .local/work/telegram-pixel-engine/td checkout c15d3f5a5de6e3ba5839822c451152e5e18bb700
New-Item -ItemType Directory -Force .local/work/telegram-pixel-engine/downloads
```

Download OpenSSL **3.5.9** from the [official source releases](https://openssl-library.org/source/) to that downloads directory as `openssl-3.5.9.tar.gz`. The build verifies the pinned SHA-256 and extracts it to `deps/openssl-3.5.9` on a fresh setup. Do not substitute a different release or bypass its hash. It runs host source generation, OpenSSL compilation, then the arm64 Android build. Logs are under the module's `logs` directory.

```powershell
python tools/doctor.py --target telegram-engine
python tools/build.py telegram-engine
```

Locate `libtdjsonjava.so` under `.local/work/telegram-pixel-engine/build-arm64` (use `Get-ChildItem -Recurse -Filter libtdjsonjava.so`). Stage the exact result with `python tools/prepare_inputs.py tdlib PATH_TO_LIBRARY`, then build the apps. The library is for the **arm64 modern phone**, not the ARM32 S2. Retain the included upstream licenses. Allow tens of minutes, substantial disk space and several GB of RAM; the final ThinLTO link can be quiet for a long time. The recipe strips debug information from the linked library and preserves 16 KiB alignment.

Stage apktool 3.0.3 with `prepare_inputs.py apktool`. Supply your own classic Telegram 3.9.0 reference APK:

```powershell
python tools/prepare_inputs.py telegram-classic 'C:\PrivateInputs\Telegram-3.9.0.apk'
python tools/build.py telegram-assets
python tools/build.py telegram-pixel
python tools/build.py telegram-s2
```

The reference is an old version used for appearance, not a claim that every asset dates to 2013. No vendor artwork is included in this repository. The public modern Android phone icon fallback is generic; local reference extraction supplies the S2's requested classic appearance.

## Pair and authorize

Install each resulting APK on its intended phone with your local signer. Pair the phones over Bluetooth first. Before first launching the modern Android phone app, `python tools/pair_devices.py telegram --apply` can stage the same private app pairing key on both phones. If the modern Android phone app already generated its key, use its “Show app-pairing key” control and enter that existing key on the S2 instead of replacing it with a new one. The app rejects a different staged key.

Obtain your own Telegram API ID and hash at [Telegram's application registration](https://core.telegram.org/api/obtaining_api_id). Enter them in the modern Android phone companion, enter your Telegram phone number and finish login there. Keep codes/passwords on the phone. Do not commit API credentials, the encrypted preference store or TDLib databases.

The transport currently coordinates with the established Bluetooth call connection. Get the calls checkpoint working first. Grant the modern Android phone companion Nearby devices and notification access as requested by Android, and allow background operation. Start the S2 companion once and verify its status.

## Verify

With your own authorization, use Saved Messages for text, image, camera/gallery, video and file tests. Check group avatars, history paging and reconnect behavior without messaging another person. A photo accepted by the camera must still pass the app's preview/send step. Voice-message support was deliberately deferred.

Reactions are included. Test adding/removing one on an authorized message and compare with the modern phone's Telegram view. Reaction icons are rendered by the modern Android phone and cached privately on the S2; no system font replacement is needed.

S2 client 0.6.4 shows the chat/person title and a message preview in incoming notifications, with the sender included for groups and a short label for media. It retains the gray notification icon. Preview formatting has synthetic checks; confirm a naturally arriving or explicitly authorized message on your own setup, with the chat closed.

The chat list opens at the top when returning from a conversation or reopening the list. Conversation history retains its own scrolling behavior; background chat-list refreshes do not repeatedly pull a browsing user back to the top.

Restart each phone in turn and unlock the modern Android phone once after boot. Leave the companion screens closed, then open Telegram on the S2. Finish with both USB cables removed and the modern Android phone locked. Logout/revocation belongs on the modern Android phone setup screen; uninstalling the modern Android phone app loses its private session and may require a fresh login.
