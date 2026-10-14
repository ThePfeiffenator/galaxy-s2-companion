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

- Built and verified current Pixel/S2 compatibility services, S2 calls/control app, Google account component and privileged Store installer helper.
- Built the identity verifier; its 17 checks passed.
- Built native launcher/worker and ran 113 SMS, 316 batch, 10 socket-address, 23 mute and 15 reconnect/epoch checks, plus bridge parsing and SHA-256 vectors.
- Ran the first-install transaction's eight C ordering/failure/recovery checks.
- Built the exact-firmware Bluetooth patch/installer and checked imports against the supplied old libc.
- Decoded locally supplied original APKs and built/signed the Store, Gmail, final Google-enabled YouTube, RIF, Google widget, Play Music and exact Peggle patch. Original inputs, decoded resources and resulting APKs remain outside the public files.
- Built/signed both Telegram companions with current reactions/media code and local classic artwork. This used the previously built pinned TDLib native library; **TDLib itself was not rebuilt for this publication**.
- Assembled the Pixel yt2009 runtime archive from the pinned upstream Git archive plus public overlays, including the current mobile streaming profile. Runtime execution with fresh accounts was not tested during publication.
- Parsed public Python, JavaScript, XML and JSON; checked local documentation links. Ran source inventory/file-type/credential/device-identifier checks and an additional private developer-identifier audit. The catalog contains zero apps; the only published bitmap assets are metadata-free first-party icons.

The old public source export had a package-length-dependent Unix socket bug; its corrected exact-length implementation and tests are retained. The current Store export also handles an empty owner-provided catalog instead of depending on a developer app collection.

## Not established by these checks

A complete clean install using only this public namespace on a second pair of phones remains unverified. Native deployment/recovery, fresh API registration, startup permissions and each upstream service need the operator's device checkpoints. The empty Store feature screen was compiled but not visually tested on a second phone. Other S2 variants, Pixel versions, carriers and Google Messages versions are not certified by this export.

Follow the manual in stages. Keep a private record of build hashes, actual installed identities and physical results. Do not weaken guards or describe packaging checks as end-to-end validation.
