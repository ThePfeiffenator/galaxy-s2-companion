# Media, browsing and original application interfaces

## Local APK inputs

Use your own original applications. Patches are version-specific and should fail if their expected smali/resources are missing. Stage apktool 3.0.3 and baksmali 2.5.2 with `tools/prepare_inputs.py`, and import your S2 framework resources with its `framework` option before decoding Samsung-dependent packages.

Known reference versions include Google Play Music 4.3.615.481147, Reddit is fun 3.6.16, Google Quick Search Box 1.4.1.488656 and Telegram 3.9.0. The YouTube recipe targets the legacy version layout encoded by its patch checks; compare your decoded APK before applying it. A filename alone is not proof of compatibility.

```powershell
python tools/prepare_inputs.py youtube 'C:\PrivateInputs\YouTube.apk'
python tools/prepare_inputs.py rif 'C:\PrivateInputs\reddit-is-fun-3.6.16.apk'
python tools/prepare_inputs.py google-widget 'C:\PrivateInputs\GoogleQuickSearchBox.apk'
python tools/prepare_inputs.py music 'C:\PrivateInputs\Music2.apk'
python tools/build.py youtube
python tools/build.py rif
python tools/build.py google-widget
python tools/build.py music
```

The final YouTube output is `.local/work/youtube-google/YouTube-2012-Google.apk`; the earlier streaming APK is an intermediate build. Connect subscriptions/comments and optional account controls using [Google accounts](GOOGLE_ACCOUNTS.md). History, Watch Later, uploads, reporting, feedback submission and TV pairing remain limited or unavailable.

Inspect each script's output path and manifest package before installing it. Patched packages may use a separate name to coexist with originals. Preserve app data and verify the replacement before hiding any original launcher entry.

## Pixel Termux runtime

Install Termux from an [official distribution source](https://github.com/termux/termux-app#installation). Android may require the owner to approve installation. Do not mix Termux/plugin signatures from different distributions. Open it once to initialize its private home directory.

Clone yt2009 locally and make the pinned commit available, then run:

```powershell
python tools/prepare_runtime.py 'C:\Path\To\yt2009-checkout'
```

The assembler reads a clean Git archive, not your checkout's caches or settings. It overlays the public compatibility files and writes `.local/runtime/yt2009-pixel.tar.gz`. Transfer this archive and the scripts under `.local/work/legacy-services/termux` to Termux private home using an authorized local transfer. In Termux, inspect and run `install_packages.sh`, `setup_backend.sh` and `configure_startup.sh`. The scripts install runtime dependencies and create the loopback backend. `configure_startup.sh` enables Termux external commands so Galaxy Services can start its known private scripts; review this setting yourself.

Stage `galaxy-youtube.sh` in Termux home at the path requested by `YouTubeRuntime.java`. Inspect its expected paths and executable mode. Galaxy Services also needs Termux's `com.termux.permission.RUN_COMMAND` permission. After reviewing this access, grant it in Android's app permissions if exposed, or explicitly with `adb -s YOUR_PIXEL_SERIAL shell pm grant org.retrobridge.galaxyservices.pixel com.termux.permission.RUN_COMMAND`. Start Galaxy Services and check the backend health endpoint on Pixel loopback. Do not bind the backend to a public interface or expose it over a tunnel.

The current YouTube profile uses low-bitrate 360p H.264 and six-second chunks, scaled without stretching to the S2's 800×480 screen. This accounts for simultaneous Bluetooth/Wi-Fi airtime. YouTube playback prepares short compatible media segments so playback can begin before a full conversion completes. Test first playback, sustained audio/video, a different video and seeking several minutes ahead. A cached video alone does not test extraction/startup. RIF's video adapter uses the same Pixel runtime; test more than one host/post format.

RIF provides public browsing only. There is no shared Reddit API credential or account workaround. Public feeds and websites can change or disappear; no promise is made that a particular feed will remain available. Failure should be reported honestly instead of treating a cached page as live access.

## Play Music interface with YouTube Music

The Pixel music runtime serves the owner's catalog and resolves playback. Transfer the Python/JS files and `start.sh` from `.local/work/gallery-music` into private Termux `~/galaxy-music`. Run `prepare_music_runtime.sh` there. This installs the versions pinned in the script. Runtime authentication files must have private permissions.

Create your own Google Cloud project and YouTube Data API/OAuth configuration. Use a client appropriate for the device authorization flow in `music_auth.py` (TVs and limited-input devices), save its downloaded installed-client JSON as private `~/galaxy-music/client.json`, and review the requested scope. The tested private-library/upload workflow required `https://www.googleapis.com/auth/youtube`, whose Google consent text includes management rights. The bridge implements reading and playback; the permission itself is broader. The account owner must approve it.

In Termux:

```sh
cd ~/galaxy-music
python music_auth.py startfull
# Complete the displayed verification URL/code yourself, then:
python music_auth.py poll
python music_auth.py check
```

Do not paste the token or client secret into a chat. The check writes private catalog samples locally; do not publish them. Link the service endpoint with `python tools/pair_devices.py music --apply` on the PC, then transfer the resulting private `.local/pairing/music.key` to Termux `~/galaxy-music/relay.key` using a private authorized method. Keep the same key in Pixel Galaxy Services. Start its loopback backend with the staged `start.sh` and verify catalog reads before launching Play Music on the S2.

Google OAuth projects in Testing can issue refresh tokens with a seven-day lifetime for this kind of scope. To avoid that testing expiry, complete your own project/app information and switch the consent configuration to the appropriate production status. Google may require verified homepage/privacy/terms and domain ownership. Use your own site; no developer-owned site or account is shared by this repository. Production status does not prevent all future revocation or expiration. See [Google's token expiration guidance](https://developers.google.com/identity/protocols/oauth2#expiration).

Test liked songs, owned playlists and uploaded tracks separately. Verify list thumbnails, album names, full artwork aspect ratio, pause/resume and seeking. An uploaded/private track can take a different resolution path from a public catalog track.

## Camera and reboot behavior

Camera backup is in Galaxy Services and does not require the media extraction runtime. It scans new JPEGs in supported camera directories, hashes content, transfers privately and records receipts. Originals are never removed. Test one new photo after reboot without opening helper apps.

For media startup, restart the Pixel, unlock it once and leave Termux/Galaxy Services closed. The services should start their private runtimes and the S2 should reconnect. Test again without the PC and with the Pixel locked. On another Android build, runtime installation does not establish that background startup permissions are sufficient.
