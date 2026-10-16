# Recovery and updates

Keep private backups before each checkpoint: original firmware files, app data where possible, installed APKs, signer/key files, exact config, file hashes, modes/owners and the command used to install. Protect keys and account sessions separately from the public source checkout.

## Native telephony and Bluetooth

The guarded native installers preserve originals and offer inspection/preflight/restore paths. Review their source-defined backup locations and modes before applying them. A restore must verify that the backup is the exact original, stop the affected service, restore all related artifacts consistently and remount `/system` read-only. Do not restore a single guessed binary or clear journals to make errors disappear.

Record an independent recovery route before modifying `/system/bin/rild` or a framework library. If the system will not boot, the app-level Pause button cannot repair it. Recovery must use your already validated device-specific recovery environment.

An APK update can change its installed path as well as its hash. The native bridge checks both and its signer/UID. Preserve the previous APK and config as a matched pair. Generate a new guarded config update for the actual installed candidate; a stale configuration is expected to reject it. The first-install source is not a generic upgrade/recovery tool for arbitrary modified installations.

## Battery protection

See [charging installation and recovery](CHARGING.md) for the matching guarded boot installer. Disable the cap and verify charging restoration before stopping the supervisor or removing its executable. Preserve the original boot script and old installer. An APK update also changes the charging identity pin: remove the old backend with its matching installer, complete the APK/call-config update, then rebuild charging for the new identity. Do not apply a newly built installer over an unknown prior binary or restore an older APK alone.

## Message journals

Never delete a journal merely because a send or receive is marked uncertain. First compare the modern Android phone and Samsung Messaging state. A send may already have reached the recipient. A receive batch may have reached Samsung's provider before its acknowledgment was lost. Resolve that ambiguity before any manual retry or journal repair.

Disable the optional RCS relay from its modern Android phone setup screen to stop notification ingestion. Revoking notification access is also possible in Android settings. Removing the S2 pairing file changes routing behavior and should be a deliberate, documented rollback, not an automatic fallback.

## Notification-only service updates

Keep the modern and S2 **data-service** APKs as a matched saved pair, along with
the existing signer and app data. This does not require replacing the separate
S2 calls/interface APK or changing native call/charging identity pins. Preserve
the service data because it also contains pairing and message-transfer state.
Dismissal actions are not stored in a retry queue; a connection failure should
be retried only for the selected alert after reconnection. See the
[notification guide](CONTACTS_CAMERA_NOTIFICATIONS.md).

## Accounts and trust

Revoke Telegram sessions in Telegram and Google OAuth access in the Google account when retiring the setup. A normal APK update with the same signer preserves app storage; uninstalling does not. Android Keystore-encrypted data may not be restorable by copying files to another modern Android phone.

Remove the exact Galaxy Services CA installed on the S2 using the trust tool's reviewed removal path. Do not remove unrelated certificates. Disabling the proxy alone does not remove system trust. Keep the modern Android phone CA private key secret until the trust has been removed from all clients.

Pause the helper before changing the S2's saved Wi-Fi proxy. Only remove the loopback proxy owned by this setup. Do not overwrite another network's proxy settings. Restore hidden launcher components from the saved component list rather than resetting the entire launcher.

Contact sync keeps managed ownership markers and private pre-change snapshots on the S2. Disable its modern-phone switch before manually restoring contacts; do not clear ownership or import a snapshot blindly, which can duplicate or misidentify contacts. General notification forwarding can be disabled independently of RCS; revoking their shared listener affects both.

Camera video transfers keep durable offsets, pending MediaStore URIs and completed hashes. Preserve those journals across updates. A deleted completed backup is not automatically re-uploaded, and older Movies/Galaxy S2 files are not automatically moved by the shared-album update.

Camera backup never removes originals. Retain them until independently verifying the modern Android phone copy and your normal photo backup. Media caches can be regenerated; authorization files and signing keys cannot.
