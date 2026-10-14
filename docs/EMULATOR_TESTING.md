# Isolated emulator installation checks

An emulator can catch missing build inputs, native linkage errors, package-install failures and runtime setup omissions without touching a working phone. It cannot replace the Samsung firmware, Bluetooth audio, carrier, account and physical checks in the manual.

## Tested environment — October 14, 2026

Two new AVD data directories were created from installed Android SDK images. No existing emulator userdata, phone app data, accounts, credentials or pairing keys were copied. Existing SDK/compiler installations and the public-namespace APKs from the fresh-checkout build audit were reused; the corrected call APK was rebuilt during this test.

| Role | System image | Emulator |
| --- | --- | --- |
| Old Android | Android 4.0.4, API 15, default ARMv7, image revision 5; 480 by 800 display | 26.1.4 classic engine, SwiftShader, 16-bit framebuffer |
| Modern Android | Android 16, API 36, `android-36.1/google_apis_playstore/x86_64`, image revision 4 | 36.3.10 with WHPX; image includes ARM64 native translation |

The old ARMv7 image needs an emulator that can run that image. Do not assume the current emulator binary supports it. The modern x86 image's successful TDLib load uses its ARM64 translation and does not establish support for every emulator image or real CPU.

Termux was the official GitHub release v0.118.3, x86_64 APK. Its SHA-256 was checked against the release's checksum: `3550e61f4d9eb49b712fd1bd9519dc37085a4d8eb597c57a340f0a64859b7144`. The video runtime used the pinned upstream commit and overlays assembled by `tools/prepare_runtime.py`; music used the pinned Python packages. No Google or Telegram account was authorized.

## Keep real devices outside the test

Use a separate local ADB server, new AVD directories and loopback emulator transports. The [ADB manual](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/user/adb.1.md) documents `--one-device` as a USB-device filter. For this test the server was restricted to a deliberately nonexistent USB serial:

```powershell
# In a dedicated terminal; leave the normal port-5037 server alone.
& "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe" -L tcp:5048 --one-device LAB_ONLY_NO_USB_DEVICE server nodaemon
```

Put AVD configuration/data and emulator Android-user state under an ignored `.local/emulator-lab` directory. Set `ANDROID_AVD_HOME`, `ANDROID_USER_HOME`, `ANDROID_EMULATOR_HOME` and `ANDROID_SDK_HOME` for the emulator processes to those lab directories. Point them at server port 5048 with `ANDROID_ADB_SERVER_PORT` and `ADB_SERVER_SOCKET=tcp:127.0.0.1:5048`; disable mDNS auto-connect. The recorded emulator console/ADB pairs were 5680/5681 and 5682/5683, with no snapshots restored. Check that chosen ports are unused before launching anything.

Connect explicitly to `127.0.0.1:5681` or `127.0.0.1:5683`. Every subsequent ADB command must include `-H 127.0.0.1 -P 5048 -s` followed by that exact loopback serial. Confirm `ro.kernel.qemu=1` and the expected image before installing. Never use the default server, `-d`, an unqualified install, or a global `kill-server`. If the lab server stops, restart it with the same USB filter before continuing; do not let an ADB command implicitly create an unfiltered server.

Only stop emulators whose console identity matches the lab AVD names. Stop the separate lab server when finished. Do not publish AVD images, APKs, keys or raw device logs.

## Checks and results

All 13 public APKs installed into empty emulator package storage:

| Emulator | Packages |
| --- | --- |
| Modern | Galaxy Companion, Google account component, optional Telegram companion |
| API 15 | Call/SMS companion, compatibility service, Store installer helper, Store, Gmail, YouTube, RIF, Google widget, Play Music, optional Telegram client |

The 12 packages with activity entry points launched without a recorded fatal exception after the call-native fix. The modern account component showed disconnected accounts, and its App library showed zero apps. The Store installer helper has no launcher; it was installed as an ordinary app, not made privileged. Silent installation was not tested.

YouTube's activity name contains `$`; preserve that character through both the host shell and Android shell. A mistaken activity name or stripped `$` is a harness error, not evidence of an app defect. The corrected activity launched into the original phone HomeActivity. The S2 service intentionally redirects to the unified call companion.

The API 15 emulator's screenshot output was black. Changing its framebuffer depth also stalled its activity manager, so the original working 16-bit configuration was restored. Legacy activity checks used process/activity/log evidence; **legacy visual layout and button behavior were not verified**. The modern Telegram setup deliberately blocks screenshots with `FLAG_SECURE`; its native TDLib library loaded without a fatal error.

Additional runtime checks:

- Fresh Termux dependency installation completed after the bootstrap fix. The video service returned HTTP 200 with `Galaxy YouTube ready`.
- The music service returned HTTP 403 without its synthetic key and HTTP 200 with `ready=true`, `signed_in=false` with the key. No library or playback claim follows from that health check.
- After saving battery exemptions for Termux and Galaxy Companion, the modern emulator was rebooted and unlocked without opening either app. Both runtime health endpoints recovered automatically, with no recorded fatal exception. The emulator's exemptions were applied through its separate ADB server and verified after reboot. An earlier immediate reboot discarded the settings before Android saved them; that harness error was corrected before the successful check.
- The modern companion served its empty catalog over a hostname-verified TLS connection using a newly generated lab key and CA. Invalid authentication and a replayed request were rejected. No certificate was added to the host's system trust store.
- The production native installer's SHA-256/transaction self-tests passed on API 15. Its inspection/preflight rejected the generic emulator. The emulator radio binary and mount table were unchanged; no apply/restore operation ran.

## Setup defects corrected

1. The call builder was compiling an old Activity-level audio prototype. Current Java calls native methods on `CompanionService`, causing `UnsatisfiedLinkError: nativeProbe` at launch. The kit now includes the current native implementation beside its Java code. Generated JNI headers check argument types, and the build checks all 11 expected JNI exports. The corrected APK was freshly installed and launched on API 15.
2. The old Termux bootstrap's C++ library could not load the current FFmpeg dependencies. `install_packages.sh --fresh-install` now updates the fresh bootstrap before installing the media packages, preserves package configuration files and rejects an existing video runtime or music client configuration. A second installation from empty Termux storage completed successfully. This is not an upgrade command for a working phone.
3. The fresh modern image blocked background Termux starts after reboot. The media guide now explicitly requires reviewing battery/background settings for both Termux and Galaxy Companion, in addition to the RUN_COMMAND permission and Termux property. Once the exemptions persisted, the reboot check passed. Opening an app once is not a reboot test.

## Boundaries for repeating the test

Build with your own supplied original APKs, fresh synthetic configuration and local signing key. Use [inputs](INPUTS.md), [manual setup](MANUAL_SETUP.md) and [media setup](MEDIA_APPS.md), stopping before any hardware-specific stage. Use generated keys only inside the lab. Record installations, actual launches, health responses and reboot observations separately.

Do not spoof Samsung properties or weaken native checks to advance an emulator installation. Do not expect an API 15 AVD to reproduce the physical Bluetooth/SCO and private Wi-Fi pairing. The TLS check above used a temporary loopback forward and test client, not a paired S2 radio link.

Still required on an authorized compatible phone pair: native deployment and recovery, calls/audio, SMS/RCS/MMS, private Wi-Fi reconnection, GPS/microphone/camera behavior, privileged Store installation, fresh Google/Telegram authorization, real media playback and operation without the PC. These emulator checks are a supplement to those gates, not a certification of a complete fresh phone installation.
