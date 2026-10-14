# Classic Telegram companion

The S2 renders a classic chat interface while a separately authorized TDLib session runs on the Pixel. It does not reuse or extract the official Telegram app's private session. Keep the old reference APK local; it supplies classic artwork during your build.

## Build prerequisites

In addition to the main guide, install Visual Studio 2022 Build Tools with C++, Android SDK CMake 3.22.1/Ninja, and an MSYS2 tool tree at `.local/work/telegram-pixel-engine/tools/msys64` containing bash, Perl, make and gperf. Review `host_environment.cmd` for your Visual Studio installation path.

Clone official TDLib into `.local/work/telegram-pixel-engine/td` and check out `c15d3f5a5de6e3ba5839822c451152e5e18bb700`. Download official OpenSSL 3.5.9 to that module's `downloads/openssl-3.5.9.tar.gz`; the build verifies its SHA-256. The pinned build may take substantial time and disk space.

```powershell
python tools/build.py telegram-engine
```

Locate the resulting `libtdjsonjava.so` under `build-arm64`, verify it is the built arm64 library, and stage it explicitly with `python tools/prepare_inputs.py tdlib PATH_TO_LIBRARY`. Retain the engine's license notices. This library belongs on the Pixel, not the ARM32 S2.

Stage apktool 3.0.3 with `prepare_inputs.py apktool`. Supply your own classic Telegram 3.9.0 reference APK:

```powershell
python tools/prepare_inputs.py telegram-classic 'C:\PrivateInputs\Telegram-3.9.0.apk'
python tools/build.py telegram-assets
python tools/build.py telegram-pixel
python tools/build.py telegram-s2
```

The reference is an old version used for appearance, not a claim that every asset dates to 2013. No vendor artwork is included in this repository. The public Pixel icon fallback is generic; local reference extraction supplies the S2's requested classic appearance.

## Pair and authorize

Install each resulting APK on its intended phone with your local signer. Pair the phones over Bluetooth first. Before first launching the Pixel app, `python tools/pair_devices.py telegram --apply` can stage the same private app pairing key on both phones. If the Pixel app already generated its key, use its “Show app-pairing key” control and enter that existing key on the S2 instead of replacing it with a new one. The app rejects a different staged key.

Obtain your own Telegram API ID and hash at [Telegram's application registration](https://core.telegram.org/api/obtaining_api_id). Enter them in the Pixel companion, enter your Telegram phone number and finish login there. Keep codes/passwords on the phone. Do not commit API credentials, the encrypted preference store or TDLib databases.

The transport currently coordinates with the established Bluetooth call connection. Get the calls checkpoint working first. Grant the Pixel companion Nearby devices and notification access as requested by Android, and allow background operation. Start the S2 companion once and verify its status.

## Verify

With your own authorization, use Saved Messages for text, image, camera/gallery, video and file tests. Check group avatars, history paging and reconnect behavior without messaging another person. A photo accepted by the camera must still pass the app's preview/send step. Voice-message support was deliberately deferred.

Reactions are included. Test adding/removing one on an authorized message and compare with the Pixel's Telegram view. Reaction icons are rendered by the Pixel and cached privately on the S2; no system font replacement is needed.

Restart each phone in turn and unlock the Pixel once after boot. Leave the companion screens closed, then open Telegram on the S2. Finish with both USB cables removed and the Pixel locked. Logout/revocation belongs on the Pixel setup screen; uninstalling the Pixel app loses its private session and may require a fresh login.
