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

## Modern Android phone Termux runtime

Install Termux from an [official distribution source](https://github.com/termux/termux-app#installation). Android may require the owner to approve installation. Do not mix Termux/plugin signatures from different distributions. Open it once to initialize its private home directory.

Build the transfer bundle from a fresh upstream checkout:

```powershell
git clone https://github.com/ftde0/yt2009.git .local/upstream/yt2009
python tools/prepare_runtime.py .local/upstream/yt2009
```

The assembler uses exactly commit `9127347e763e97ad6cbcb88b94d5e436487ee9f7`, ignoring the checkout's current files/caches. It writes `.local/runtime/modern-runtime.tar.gz`: the pinned yt2009 archive, startup scripts and all music Python/JS sources. **No keys, client credentials, account tokens or media caches are in this bundle.** The inner `yt2009-pixel.tar.gz` name is historical.

For a **new** Termux installation, transfer modern-runtime.tar.gz to the modern phone's Download folder by USB/file transfer. In Termux run `termux-setup-storage` and approve file access, then:

```sh
cd "$HOME"
# Stop if existing runtime/account directories are present; preserve them.
test ! -e yt2009 && test ! -e galaxy-music || exit 1
umask 077
tar -xzf "$HOME/storage/downloads/modern-runtime.tar.gz" -C "$HOME"
bash "$HOME/install_packages.sh" --fresh-install
bash "$HOME/setup_backend.sh"
bash "$HOME/configure_startup.sh"
chmod 700 "$HOME/galaxy-youtube.sh" "$HOME/galaxy-music/start.sh"
# Allow Termux to reload allow-external-apps after changing its properties.
termux-reload-settings
```

Do not use the clean-install extraction as an upgrade over credentials or edited runtime files. Stop on each failed command; only initial setup should create the runtime directories. The explicit `--fresh-install` step updates the new Termux bootstrap packages before installing current media dependencies. Without that update, an old bundled C++ library can prevent FFmpeg from loading. It preserves existing package configuration files and refuses an existing video runtime or music client configuration. This is not an upgrade recipe for a working installation. `setup_backend.sh` installs npm dependencies and initializes public visitor context, so it requires working internet. Upstream refusal is an error, not a reason to copy somebody else's cookies.

The final locations are `~/yt2009`, `~/galaxy-youtube.sh` and `~/galaxy-music`. Galaxy Companion needs Termux's `com.termux.permission.RUN_COMMAND` in addition to the Termux `allow-external-apps` property. Grant it through Android settings if exposed, or after reviewing that permission:

```powershell
& "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe" -s YOUR_MODERN_SERIAL shell pm grant org.retrobridge.galaxyservices.pixel com.termux.permission.RUN_COMMAND
```

This is a device step, excluded from offline audits. See [Termux RUN_COMMAND](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent). In Android's app battery settings, allow unrestricted/background operation for **both Termux and Galaxy Companion**, and allow their service notifications. Labels vary by manufacturer. Termux's permission and `allow-external-apps` setting alone do not establish background startup: the fresh Android 16 emulator rejected it with `BackgroundServiceStartNotAllowedException` until battery exemptions were configured. Do not grant overlay permission for these background commands; a foreground terminal window is not required.

Resume Galaxy Companion, wait for its runtime check, then in Termux check `http://127.0.0.1:8768/galaxy-health` (for example with Python urllib.request). It must return `Galaxy YouTube ready`. Logs stay privately in `~/yt2009/galaxy-backend.log`. Keep the backend bound to loopback; do not expose it via a tunnel. A successful npm install does not prove runtime startup.

The current YouTube profile uses low-bitrate 360p H.264 and six-second chunks, scaled without stretching to the S2's 800×480 screen. This accounts for simultaneous Bluetooth/Wi-Fi airtime. YouTube playback prepares short compatible media segments so playback can begin before a full conversion completes. Test first playback, sustained audio/video, a different video and seeking several minutes ahead. A cached video alone does not test extraction/startup. RIF's video adapter uses the same modern Android phone runtime; test more than one host/post format.

Music uses a separate `galaxy-audio.js` adapter registered by the runtime assembler. It transfers AAC audio without video and copies existing audio packets across six-second pieces, avoiding fresh encoder delay/padding at each join. Uploaded tracks use the same packet-copy approach in `music_uploads.py`. Use the matching Music APK and runtime together; older runtime bundles have no `galaxy-audio` route. New versioned caches prevent mixing old re-encoded fragments with the fixed stream. Keep the existing `galaxy-stream.js` video adapter.

RIF provides public browsing only. There is no shared Reddit API credential or account workaround. Public feeds and websites can change or disappear; no promise is made that a particular feed will remain available. Failure should be reported honestly instead of treating a cached page as live access.

## Play Music interface with YouTube Music

The modern Android phone music runtime serves the owner's catalog and resolves playback. The complete runtime bundle above already supplies the Python/JS files and `start.sh` in private Termux `~/galaxy-music`. Run `bash ~/galaxy-music/prepare_music_runtime.sh` there. This music runtime also depends on the installed yt2009 tree, including for uploaded audio; complete the video runtime prerequisites even if you only want Music. This installs the versions pinned in the script. Runtime authentication files must have private permissions.

Create your own Google Cloud project and YouTube Data API/OAuth configuration. Use a client appropriate for the device authorization flow in `music_auth.py` (TVs and limited-input devices), save its downloaded installed-client JSON as private `~/galaxy-music/client.json`, and review the requested scope. The tested private-library/upload workflow required `https://www.googleapis.com/auth/youtube`, whose Google consent text includes management rights. The bridge implements reading and playback; the permission itself is broader. The account owner must approve it.

In Termux:

```sh
cd ~/galaxy-music
python music_auth.py startfull
# Complete the displayed verification URL/code yourself, then:
python music_auth.py poll
python music_auth.py check
```

Do not paste the token or client secret into a chat. The check writes private catalog samples locally; do not publish them. Link the service endpoint with `python tools/pair_devices.py music --apply` on the PC, then transfer the resulting private `.local/pairing/music.key` to Termux `~/galaxy-music/relay.key` using a private authorized method. For manual setup, open that local key file privately, then in Termux use `read -r -s -p "Pairing key: " relay_key`, paste the key, and write it with `(umask 077; printf "%s\n" "$relay_key" > "$HOME/galaxy-music/relay.key")`; `unset relay_key` afterward. Do this only if relay.key is absent; never replace an existing different key. Avoid shared-storage copies of credentials. Keep the same key in modern Android phone Galaxy Services. Start its loopback backend with the staged `start.sh` and verify catalog reads before launching Play Music on the S2.

Google OAuth projects in Testing can issue refresh tokens with a seven-day lifetime for this kind of scope. To avoid that testing expiry, complete your own project/app information and switch the consent configuration to the appropriate production status. Google may require verified homepage/privacy/terms and domain ownership. Use your own site; no developer-owned site or account is shared by this repository. Production status does not prevent all future revocation or expiration. See [Google's token expiration guidance](https://developers.google.com/identity/protocols/oauth2#expiration).

Owned playlists and likes use YouTube Data. Saved/bookmarked playlists use a read-only TV library-browse adapter with the same approved authorization; it includes paging, skips duplicate owned lists and preserves track order/repeats. The adapter makes no account changes. Its upstream interface is unofficial and may change. A failed refresh retains the previous private catalog instead of replacing it with an incomplete result. Saved albums and uploads absent from imported lists remain outside the current scope.

The catalog refreshes when requested after its one-hour cache lifetime. Use **Refresh library** in the S2 app to reimport the latest available catalog; it does not force a premature upstream refresh. Saved playlists are playable from the original Playlists view. Test a bookmarked list, liked songs, owned playlists and uploaded tracks separately. Verify list thumbnails, album names, full artwork aspect ratio, pause/resume, seeking and sustained screen-off audio. An uploaded/private track can take a different resolution path from a public catalog track.

Offline checks: `python source/gallery-music/test_music_playlists.py` uses synthetic responses; `node source/gallery-music/test_audio_segments.cjs` needs FFmpeg/ffprobe on PATH and generates its own audio. Neither test accesses accounts or phones. They do not replace a listening check.

## Camera and reboot behavior

Camera backup is in Galaxy Services and does not require the media extraction runtime. It scans new JPEGs in supported camera directories, hashes content, transfers privately and records receipts. Originals are never removed. Test one new photo after reboot without opening helper apps.

For media startup, restart the modern Android phone, unlock it once and leave Termux/Galaxy Services closed. The services should start their private runtimes and the S2 should reconnect. Test again without the PC and with the modern Android phone locked. On another Android build, runtime installation does not establish that background startup permissions are sufficient.
