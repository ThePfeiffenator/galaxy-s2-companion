# Original Phone and Messaging integration

## Architecture

The S2 acts as a Bluetooth hands-free client to the Pixel. A native worker presents call state to the S2's original telephony framework. The original dialer therefore controls a call on the Pixel. Native SCO/audio handling routes the microphone and earpiece. SMS uses the Pixel's Bluetooth MAP service and delivers encoded batches through the same local telephony integration.

This replaces the supported S2's cellular connection for these operations. It is not radio tower emulation, a baseband modification or carrier registration. The original SIM/radio capabilities are not a fallback once this native bridge is installed. Use the Pixel directly for emergency calls.

## Build and identity order

1. Build `calls` and install the resulting `.local/work/sms-companion-v12/galaxy-companion-v14.apk` on the S2. The directory/output names are historical; inspect the manifest version. Preserve data and use your own consistent signing key.
2. Record its actual package UID (`dumpsys package org.retrobridge.callprobe`), installed APK path (`pm path`), APK SHA-256, and signer SHA-256 (`apksigner verify --print-certs`). Do not assume a UID or `-1.apk` suffix.
3. Put `call_app_uid` and `app_signer_sha256` in ignored `local.json`; rerun configure. Build `identity`, record the resulting JAR's SHA-256, add `identity_jar_sha256` to local.json, rerun configure, then build `native-calls`.
4. Copy `source/stock-sms-native/config.example` into a private `production.conf`, with LF line endings. Fill every field with the exact built/installed identity, launcher and worker hashes. No `@TOKEN@` or `REPLACE_` text may remain.
5. Run `.local/work/stock-install/freeze_manifest.py` with explicit `--launcher`, `--worker`, `--identity` and `--config` paths. It verifies the configuration against your finalized artifacts. Build `native-installer` afterwards.
6. Review the build's actual C transaction tests and old-libc symbol audit. Stage the **real** artifacts, not fixture files, in the source-defined root-owned `/data/local/galaxy-companion-stage/` directory. Use the exact names in the generated manifest. The executable, staging directory, parents and files must pass its symlink, mode, owner and hash checks.
7. With a reviewed root entry point, run the installer's `--self-test`, `--inspect` and `--preflight`. Read its output and confirm the original files are preserved before `--apply`. The install transaction includes rollback and read-only remount checks. Do not issue independent blind `cp` commands over system files.

The configuration-only upgrade transaction used during development is distinct from first installation. For an APK update, the pinned APK path/hash must be updated by a guarded, reviewed transaction that preserves the previous configuration. Do not reinstall an arbitrary newly signed helper and assume the native identity verifier will accept it. Public first-install code is included; generalized automatic upgrades remain an advanced manual task.

## Bluetooth/audio framework patch

Build target `bluetooth-patch` against the exact original `libandroid_runtime.so`, then `bluetooth-installer`. Inspect the exact-version checks in `stock-bluetooth-fix/build_patch.py` and `stock-bluetooth-install/install.c`. Follow the same source-defined staging, self-test, preflight, backup and apply sequence. This prevents the stock Bluetooth phone service from rejecting the S2's client voice link. A successful HFP control connection alone does not prove audio works.

The native audio library is built from `source/call-feasibility/probe.c`. Test both microphone and earpiece. Do not change kernel addresses, audio routes or library offsets based on another model's documentation.

## SMS behavior

The Pixel must allow Bluetooth message access. The first MAP inbox snapshot establishes a baseline, excluding older history. New incoming SMS are journaled before being delivered as an atomic native batch. Multipart reference numbers are reserved to prevent collisions. An uncertain batch pauses reception for investigation; do not clear journals or replay it blindly.

Outgoing messages are journaled before submission. A connection loss after submission may have an unknown outcome. Check the Pixel before retrying. No automatic resend should turn one press of Send into two messages.

## RCS notification relay

The implementation uses Android's [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService) and [RemoteInput](https://developer.android.com/reference/android/app/RemoteInput). It is not an RCS client or access to Google Messages' full history.

The Pixel accepts one-to-one MessagingStyle text, resolves a unique sender number, excludes text already found in the SMS provider, and queues it privately. The S2 polls over authenticated private HTTPS. Delivery uses the existing stock Messaging receive path and journal. Receipts prevent the same notification text/timestamp from being inserted repeatedly.

Replies require a live, unique Google Messages reply action belonging to the correct conversation. The PendingIntent creator must be Google Messages and it must accept free-form input without a separate authentication requirement. A route that was known as RCS but loses its notification is rejected instead of silently sent as SMS. A new conversation with no RCS route uses the existing SMS path. This cannot reliably initiate a new RCS-only conversation.

Limitations:

- No old history, group conversations, typing indicators or read receipts. Media uses the separate path below; notification previews are not guaranteed original attachments.
- A contact with several distinct phone numbers is not guessed.
- No notification means no new relay event; opening the conversation on the Pixel can suppress its notification.
- Dismissing a notification can remove the reply action. Use the Pixel if the S2 reports that the conversation is unavailable.
- `SUBMITTED` means Google Messages accepted the inline action, not that the carrier or recipient confirmed delivery.
- With RCS pairing enabled, send routing needs the Pixel Wi-Fi compatibility service as well as Bluetooth. It fails closed if that lookup is unavailable.
- Notification/SMS timing is best effort. This implementation must be validated with the specific Google Messages version before relying on it.

## Photo and video relay

The current services include outgoing media staging on the Pixel and incoming MMS/notification-media queues. Incoming content is bounded, verified and inserted into stock Samsung Messaging only after its bytes are available. Text and media have separate routing/delivery states.

After pairing the services, run `python tools/pair_devices.py media --apply`. In the S2 companion open Services → Calls & messages → Picture & video messages and enable **Use Pixel for attachments**. This establishes a baseline without replaying old failed outbox items. Grant the Pixel message/media permissions and enable its supported relay controls. Retry an older failed message only at the owner's request.

In the development installation, outgoing generated photo/video paths were exercised and an incoming MMS photo was visibly received in Samsung Messaging. **Incoming real carrier video and full original RCS attachments remain unverified.** Google Messages may expose only a preview or no attachment at all through a notification. No group/history support is claimed. Test your specific carrier and Google Messages version with explicit permission; an SMS/MMS test does not prove RCS media works.

Review Pixel `IncomingMedia.java` and the corresponding S2 receive code for current size/type limits. Do not replace missing attachments with a claim of successful delivery. Do not replay ambiguous outgoing sends automatically.

## Required functional tests

With authorized test recipients, verify outgoing/incoming calls, screen-off ringing, answer/end, both audio directions and mute. Verify short/long/Unicode SMS, reconnection and no duplicate reception. For RCS, have a person send a confirmed RCS text while Google Messages is closed, check it appears once in stock Samsung Messaging, reply from the S2 yourself and confirm it arrives as RCS on the Pixel. Repeat after reconnecting and after rebooting either phone. Keep the PC unplugged for the final checks.
