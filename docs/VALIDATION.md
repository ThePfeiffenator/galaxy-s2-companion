# Validation status

This page separates the working development installation from the generalized public kit. A successful compile is not a successful phone installation.

## Development phones

The owner confirmed calls in both directions, two-way audio, mute, screen-off incoming ringing, ordinary SMS, Telegram text/media/reactions, camera transfer, navigation/voice input, web/widget access, YouTube/RIF playback and seeking, and Play Music library/artwork/playback. Gmail inbox/actions/attachments and an authorized self-addressed mail test were exercised. YouTube subscriptions and account controls were tested. The Store's APK import, native installation progress and a real app launch were checked.

Reboot and no-PC checks were performed for the main services. After the latest unified UI update, the owner again confirmed a call with both phones unplugged and clear audio in both directions. These observations apply to the established development installation, not a second phone pair using the public namespace.

## Messaging limits

Real one-to-one RCS notification text was received and acknowledged through the relay; the owner manually replied and confirmed text went through. Inline action submission is not a recipient delivery receipt. Group/history, no-notification cases and loss of a live reply action remain limitations.

Outgoing photo/video relay paths were exercised with authorized samples. An incoming MMS photo was displayed in stock Samsung Messaging. **A real incoming carrier video and full original RCS attachments have not been verified.** Notification access may provide only a preview. Do not claim full RCS/MMS parity from the successful text or photo checks. A complete independent reboot/no-PC matrix for all media-message cases is still outstanding.

## Public source checks — October 14, 2026

The public kit uses `org.retrobridge.*`, synthetic device identities for build testing, and a newly generated local signing key. No generalized test APK was installed over the owner's working setup.

Completed locally:

- Built and verified current modern Android/S2 compatibility services, S2 calls/control app, Google account component and privileged Store installer helper.
- Built the identity verifier; its 17 checks passed.
- Built native launcher/worker and ran 113 SMS, 316 batch, 10 socket-address, 23 mute and 15 reconnect/epoch checks, plus bridge parsing and SHA-256 vectors.
- Ran the first-install transaction's eight C ordering/failure/recovery checks.
- Built the exact-firmware Bluetooth patch/installer and checked imports against the supplied old libc.
- Built the trust-store helper with a short-lived synthetic certificate and checked its old-libc imports; built the API 15 launcher helper. Neither helper was installed or run on a phone during the audit.
- Decoded locally supplied original APKs and built/signed the Store, Gmail, final Google-enabled YouTube, RIF, Google widget, Play Music. Original inputs, decoded resources and resulting APKs remain outside the public files.
- Repeated the build from a fresh GitHub checkout and new synthetic configuration/signing key. Original owner-supplied APKs were decoded afresh; installed compiler/SDK prerequisites were reused. The six original-app patches listed above and all core targets built without borrowing the previous generated build tree.
- Rebuilt TDLib from pinned upstream source, including host generation, OpenSSL 3.5.9 extraction/compilation and the arm64 ThinLTO link. Built/signed both Telegram companions using that newly generated library and locally extracted reference artwork. Alignment/signature checks passed. No prebuilt TDLib library was substituted in this fresh audit.
- Assembled the complete credential-free modern runtime bundle from pinned upstream plus public overlays. Extracted it into an isolated host folder, installed npm dependencies, initialized public visitor context and obtained the expected loopback health response. The test backend was then stopped. This was a host smoke test, **not a fresh Termux/device or account test**. Pinned music Python dependencies also installed into a fresh virtual environment.
- Eight setup-tool tests cover configuration aliases, bad/duplicate identities, SSID limits, source injection, staged identity changes, local-edit preservation and generated-cache exclusion. The audit added explicit input, native-deployment, pairing/permissions, runtime and compatibility instructions.
- Parsed public Python, JavaScript, XML and JSON; checked local documentation links. Ran source inventory/file-type/credential/device-identifier checks and an additional private developer-identifier audit. The APK catalog contains zero apps. Reviewed interface screenshots contain no account/message/device identifiers; the modern screenshot retains only a standard sRGB color tag.

The old public source export had a package-length-dependent Unix socket bug; its corrected exact-length implementation and tests are retained. The current Store export also handles an empty owner-provided catalog instead of depending on a developer app collection.

## Follow-up changes on the development pair

The clean-setup audit itself did not change the working phones. The owner separately authorized the following targeted updates afterward:

- Music now copies AAC packets rather than independently encoding each six-second piece. Synthetic FFmpeg tests reproduced 14–37 ms overlaps in the previous path and measured less than 0.04 ms rounding at joins with the fix, including source-chunk boundaries, seeking and the partial ending. Both public-track and uploaded-audio paths passed local checks. The owner reported smooth playback so far after installing the matching update, including the requested screen-off test. This does not establish every track/network condition.
- Saved/bookmarked playlists are read with the existing authorized session. Full pagination and catalog import were checked on the development pair, and the owner confirmed that the previously missing playlist appears and plays on the S2. Seven synthetic adapter tests cover deduplication, ordered repeats, filtering, cursors and layout failures. The broader private music regression suite passed 28 checks. Saved albums remain unsupported.
- Telegram disables conversation transcript scrolling in the chat-list view and resets the reused view synchronously on entry. The owner confirmed that Chats now appears at the top immediately, without the earlier bottom-to-top jump. The update builds and the existing 71 client-rule and seven host SQLite checks pass; those automated checks do not themselves validate Android scrolling.

These private-namespace updates do not establish a complete public-namespace deployment. APK and runtime rollback copies were preserved outside the repository. The Peggle fix is excluded from the current public source kit at the owner's request.

## Fresh emulator installation — October 14, 2026

Two new emulator installations extended the earlier host-only audit: Android 4.0.4/API 15 ARMv7 and Android 16/API 36 x86_64 with ARM64 translation. A separate ADB server excluded all real USB devices. No working phone was accessed or changed, and no real account or pairing data was copied.

All 13 public APKs installed. Twelve activity entry points launched without a recorded fatal exception after correcting the call builder's obsolete native source. It now generates JNI headers and checks 11 exports. The optional modern Telegram companion loaded its freshly built TDLib library. The account component remained disconnected and the APK library empty. Legacy screenshots were black, so those launch checks do not establish visual correctness.

Fresh Termux setup exposed a bootstrap C++ library mismatch; the clean-install script now updates the bootstrap before installing media dependencies. A retry from empty storage completed. Video and authenticated music health checks passed, including expected rejection without the music key. The empty catalog was served over verified TLS, with invalid authentication/replays rejected. The native installer's self-tests passed and its production guard rejected the generic emulator without changing the radio binary or mount table.

A modern-emulator reboot exposed the need to explicitly configure Termux/Companion battery exemptions. After saving those settings, video and music started automatically following reboot/unlock without opening either app. No real playback, account sign-in, radio pairing or silent Store install was tested. See [the emulator procedure and evidence boundaries](EMULATOR_TESTING.md).

## Not established by these checks

A complete clean install using only this public namespace on a second pair of phones remains unverified. Native deployment/recovery, fresh API registration, startup permissions and each upstream service need the operator's device checkpoints. The empty Store feature screen was compiled but not visually tested on a second phone. Other S2 variants, modern Android versions and manufacturers, carriers and Google Messages versions are not certified by this export.

Follow the manual in stages. Keep a private record of build hashes, actual installed identities and physical results. Do not weaken guards or describe packaging checks as end-to-end validation.
