# Manual setup

Download this repository with GitHub's **Code → Download ZIP**, extract it and open PowerShell in its root folder, or clone it with Git. Commands below run from that folder. This is a Windows-oriented source kit; account registration and exact-firmware system installation are separate reviewed checkpoints, not an unattended script.

## 1. Inventory and recovery first

The native modules support **SPH-D710 / IMM76I.FL24 / Android 4.0.4** only. Record `ro.product.model`, `ro.build.display.id`, Android version and kernel before proceeding. Back up the old phone's applications and data using a method you can actually restore. Keep original `/system/bin/rild`, `/system/lib/libril.so`, `/system/lib/libandroid_runtime.so`, framework files and their modes/owners. Confirm how you will recover a phone that does not boot normally.

Use explicit device serials on every ADB command. Pair the phones in Bluetooth settings, approve the matching code, and approve the Pixel's requested contact/message sharing. Enable USB debugging only on computers you trust. Do not expose ADB over the internet.

The Pixel does not need root for the supplied companion apps. The S2's stock dialer/audio integration and system certificate do need privileged system changes. This kit does not supply a universal rooting procedure. Establish an appropriate, reviewed root/recovery method for your exact firmware separately. Do not unlock or flash a phone as an incidental setup step.

## 2. Build environment

The scripts currently target Windows and PowerShell. Install Python 3, JDK 17, Gradle 8.9, Node.js, Git, Android platform-tools, SDK platform 36, build-tools 36.1.0 and NDK 28.1.13356709. Telegram's native engine also needs the tools in docs/TELEGRAM.md. Put `node`, `git` and `python` on PATH.

```powershell
$env:JAVA_HOME = 'C:\Path\To\jdk-17'
$env:ANDROID_SDK_ROOT = 'C:\Path\To\Android\Sdk'
$env:GRADLE_EXECUTABLE = 'C:\Path\To\gradle-8.9\bin\gradle.bat'
Copy-Item config.example.json local.json
```

Edit `local.json` with your own actual ADB serials and Bluetooth addresses. Use a simple private Wi-Fi name. Leave the three native identity fields null until their values have been measured. Never publish this file.

```powershell
python tools/configure.py
python tools/create_signing_key.py
python tools/fetch_dependencies.py
```

`configure.py` creates private build sources under `.local/work`. It does not contact phones. It refuses to overwrite independently changed local sources. `create_signing_key.py` creates your own signing key; back it up privately before installing apps. Do not replace a signing key for an existing installation.

Stage the Android 4.0.4/API 15 `android.jar` from an Android SDK archive and your own original firmware files:

```powershell
python tools/prepare_inputs.py android15 'C:\PrivateInputs\android-15\android.jar'
python tools/prepare_inputs.py libc 'C:\PrivateInputs\libc.so'
python tools/prepare_inputs.py rild 'C:\PrivateInputs\rild'
python tools/prepare_inputs.py libril 'C:\PrivateInputs\libril.so'
python tools/prepare_inputs.py libandroid_runtime 'C:\PrivateInputs\libandroid_runtime.so'
```

The native builds compare exact firmware hashes. A mismatch is a stop condition. Source adaptations for another firmware require separate analysis and validation.

## 3. Calls and SMS checkpoint

Follow docs/CALLS_SMS.md. This includes building/installing the helper, observing its installed UID/path/signature, building the identity verifier and native modules, producing a fully pinned configuration, and using guarded installers. Do not proceed to other features while calls are unreliable.

Verify outbound and inbound calls through the original Samsung Phone app, clear audio in both directions, mute/unmute and hangup. Then test a short SMS, a multipart SMS and Unicode with the owner's approved recipients. The receive journal must be healthy. Test reconnection before adding RCS.

## 4. Pixel and S2 compatibility services

```powershell
python tools/build.py services
```

Install `.local/work/legacy-services/pixel/galaxy-services-pixel.apk` on the Pixel and the sibling `s2/galaxy-services-s2.apk` on the S2, using explicit ADB serials and `install -r`. Start each app once. The combined S2 front end is in the calling companion; its signature-protected service controls require both S2 packages to share your signing key. Grant only the permissions needed for enabled features; review the Pixel's Nearby devices, notifications, location and background operation settings.

```powershell
python tools/pair_devices.py services --apply
```

This provisions a shared private service key and S2 network name. Start the services, allow their private Wi-Fi/hotspot setup, and confirm the S2 connects to the Pixel's intended network. The Pixel startup and hotspot code depends on newer Android APIs; a different Android version may require a supported hotspot configuration instead. Inspect the Pixel and S2 `HotspotLink.java` files and S2 `DeviceLink.java` rather than guessing which APIs exist.

The S2 service listens on loopback port 8766 and authenticates forwarding to Pixel port 8765. Web/media backends remain on Pixel loopback. Never port-forward them to the internet.

For HTTPS compatibility, review PRIVACY.md. Galaxy Services creates a unique CA on the Pixel. Transfer **only the public `ca.pem`**, never `authority.p12` or its password, to `.local/work/legacy-services/public-ca.pem`. Install Python's `cryptography` dependency for this build. Build with `python tools/build.py trust-store`, inspect its exact guarded destination and recovery behavior, then run its root `--check` before `--install`. Its removal command is `--remove`. The system trust change requires the owner's approval. Do not disable certificate verification to work around a setup error.

Verify a simple web search and destination page, then voice input from an app and the keyboard separately. For navigation, enable the S2's allowed mock-location source as required by this old Android version and test the displayed position while stationary. Check calendar/weather separately before relying on them.

## 5. Optional RCS relay

Only enable this after ordinary SMS and the private HTTPS service work.

```powershell
python tools/pair_devices.py rcs --apply
```

On the Pixel open Galaxy Services → Message relay. Enable the relay, grant Contacts and SMS access, then enable its notification access. Leave Google Messages notifications enabled. Read docs/CALLS_SMS.md for limitations and test requirements. There is no account login or direct RCS API in this feature.

## 6. Optional applications

- Google authorization and original Gmail: [docs/GOOGLE_ACCOUNTS.md](GOOGLE_ACCOUNTS.md).
- 2012 Store and your own APK library: [docs/STORE.md](STORE.md).
- Telegram: docs/TELEGRAM.md.
- YouTube, Reddit is fun and Play Music/YouTube Music: docs/MEDIA_APPS.md.
- Camera backup: enable it in the S2 Galaxy Companion Services tab. Take a new harmless JPEG with the original Camera, wait for transfer and verify it in Pixel `Pictures/Galaxy S2`. Existing older photos and non-JPEG media are outside the current backup scope.
- Original launcher duplicates: use the launcher cleanup tool only after verifying each replacement. Hide launcher components, not entire service packages; keep a restoration list. Do not delete original app data.
- Peggle: supply your own original APK with `prepare_inputs.py peggle`, stage apktool, then build the exact atomic patch with target `peggle`. It rejects a different native binary. Back up the existing save first; re-signing may require a deliberate migration rather than an in-place install.

## 7. Daily operation and final checks

Restart one phone at a time. The Pixel normally needs one PIN unlock after reboot before credential-encrypted account storage is available. Leave the helper app screens closed. Confirm Bluetooth, private Wi-Fi, notifications and runtimes recover. Repeat with the S2. Then unplug both USB cables, lock the Pixel and test each feature you intend to use.

Battery restrictions, force-stop, revoked permissions, changed Bluetooth sharing permissions or a disabled hotspot can prevent reconnection. A foreground service notification is part of Android's background operation rules. Do not hide it by disabling the service you depend on.

Keep a private record of installed hashes and test outcomes. Do not report a feature complete based solely on compilation or a successful ADB command.
