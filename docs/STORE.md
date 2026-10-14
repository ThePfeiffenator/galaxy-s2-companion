# 2012 Store and your private APK library

The original Store interface is locally patched to browse installers held on your Pixel. It skips Google login because this catalog does not use Google's Store service. It does not unlock purchases, remove DRM, or make an incompatible app run.

## Build

Supply your own original **Play Store 3.7.15** APK. Complete the common build environment, apktool/baksmali setup and paired HTTPS services first.

```powershell
python tools/prepare_inputs.py store 'C:\PrivateInputs\Play-Store-original.apk'
python tools/build.py store
python tools/build.py store-installer
```

The Store output is `.local/work/play-store-investigation/Play-Store-2012-Catalog.apk`. The helper output is `.local/work/play-store-investigation/installer/Store-Installer-S2.apk`. Both must be signed with the same locally generated key as the companion setup. Install the Store on the S2 with explicit `adb -s YOUR_S2_SERIAL install -r ...` after checking whether an existing installation/data migration is involved.

The Store installer helper needs a deliberate privileged installation into `/system/app/GalaxyStoreInstaller.apk` on the supported S2. Ordinary `adb install` will not grant its system install permission. Before a system write, back up the original state, measure the built helper hash and signer, confirm the exact device/build, available space and the observed `/system` mount, and review its manifest and `VerifiedInstaller.java`. Stage your verified APK, copy it under a temporary filename using your reviewed root method, verify it, set root ownership and mode 0644, rename atomically and remount read-only. Reboot at an agreed idle time. There is deliberately no blind shell script with a hard-coded partition device in this kit.

This helper accepts only the matching signed Store and checks the package, version, length, SHA-256 and signer before installation. Do not grant arbitrary apps access or remove its replacement/downgrade checks. Without this privileged checkpoint the native Install button cannot complete; do not present a successful download as a successful installation. Recovery is removal of this specific helper through the reviewed root/recovery method, restoring the previous state if one existed; never delete other system apps.

## Add your own APKs

1. Copy a legally obtained APK to the Pixel using your preferred local transfer or download it yourself.
2. Open Galaxy Companion on the Pixel, then **App library → Add APK**.
3. Select the APK in Android's file picker. Review the app name/version and complete the import.
4. On the S2, open the modified Play Store and choose **Refresh**. Your library entry and icon should appear.
5. Tap Install. The verified file transfers from the Pixel and the old Store shows progress. Launch it and check actual behavior.

The public library starts empty. Its featured tiles use original generic artwork and populate from your imported files. It contains neither the developer's installed-app list nor an archive.org collection.

Import requires a complete APK with minSdk at most 15, a legacy APK signature, and either no native code or compatible `armeabi`/`armeabi-v7a` code. Split packages and APK bundles are not supported. These checks establish basic packaging compatibility, not that an app is safe or that its retired servers still work. Expansion/OBB files, paid-app licensing and additional game data are not supplied by the catalog.

## Preserve existing apps for reinstallation

My apps can show installed non-system applications even before an installer has been imported. **An installed listing alone is not a backup.** To make one reinstallable, obtain its actual APK from your own backup or use `adb -s YOUR_S2_SERIAL shell pm path PACKAGE` to find its installed path, pull that exact file to private storage, then add it on the Pixel. Preserve any required game data and saves separately. Keep the APK's original signature; do not re-sign an unrelated app merely to import it. Verify the library has a complete copy before uninstalling anything.

## Sharing a catalog

Supply your own files unless you have explicit redistribution permission. Being free to install, no longer sold, or hosted on an archive is insufficient. For a redistributable app retain its license, attribution, source/source-offer obligations and exact version/hash. Do not add proprietary APKs, owner backups or extracted third-party artwork to this repository or its releases. This initial release bundles no APKs, including free ones.

## Checkpoint

Test the empty featured screen, import, refresh, details, one approved install, app launch and My apps. Check a caller without the Store's identity cannot invoke the privileged helper. Test again with the Pixel locked and both phones unplugged. Record downloaded, installed and launched as separate results.
