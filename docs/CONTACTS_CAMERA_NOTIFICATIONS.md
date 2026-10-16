# Contacts, camera backup and forwarded notifications

These optional features use the existing paired phone connection. A PC is needed
to build and install updates; normal syncing runs between the phones. Complete
[first connection and permissions](FIRST_CONNECTION.md), including the S2's
private certificate, before enabling them. Telegram remains a separate option.

## Install or update the components

Use your existing configuration and signing key for an upgrade. Back up the
installed packages and their private data first. Never replace a working private
namespace installation with the public namespace just to update a feature.

```powershell
python tools/configure.py
python tools/build.py services
python tools/build.py calls
```

Configure refuses to overwrite independently edited `.local/work` sources. If it
stops, preserve and reconcile those edits; do not delete the build tree or create
a different signing key. The calls package also supplies the S2's combined
Companion interface. Its camera page now shows both photo and video counts.

Install the modern and S2 service APKs with your existing signer, explicit device
serials and `adb install -r`, as in [manual setup](MANUAL_SETUP.md). The public
modern service is version 38 / 0.18.0 and the S2 service is version 17 / 0.12.0.
The S2 calls/interface package is version 16 / 0.16.0. Updating that package also
requires the installed path/hash/UID checks and matching native configuration
described in [recovery](RECOVERY.md) and [calls](CALLS_SMS.md). Do not interrupt a
call to update it. No native firmware guard has been relaxed for these features.

If you already have these features and only want **Clear on phone**, follow the
[notification-only upgrade](#notification-only-upgrade) below. It does not need
a calls/interface APK update or a charging/native configuration change.

## Automatic contacts

1. Back up existing S2 contacts before the first sync.
2. On the modern phone, open **Galaxy Companion → Services → Contacts**.
3. Enable **Automatic contact sync**, tap **Contacts access**, and grant it.
4. Keep both services running. After the next sync, check Samsung Contacts and
   the original dialer's search on the S2.

Sync is **one way, modern phone to S2**, using contacts visible to the modern
phone's Contacts provider. It is not limited to one selected account. Supported
fields include names, numbers, email addresses, postal addresses, organizations,
websites, dates, notes, nicknames and small contact photos. Make authoritative
edits on the modern phone; edits on the S2 do not upload back.

The S2 identifies its managed rows separately from unrelated local contacts. A
unique local contact with matching name, phone numbers and email addresses can
be adopted to avoid duplicating a previous import. Once adopted it is managed by
the sync. Unmatched local contacts remain untouched. Modern-phone deletions can
remove this source's managed copies only after a complete validated snapshot;
permission errors, failed connections and incomplete responses are not treated
as an empty contact list. Turning sync off retains the existing S2 copies.

Before changing contacts the helper saves a private snapshot under its own
`files/contact-backups/`, retaining the first snapshot and recent backups. These
contain personal information and are not an automatic restore utility. Do not
upload them. Consult a private backup before any manual restoration or reset.

Updates are checked about once a minute while the connection is available.
Test with a harmless contact you create yourself: add it on the modern phone,
edit it, verify both changes on the S2, then delete that test contact and verify
only its managed copy disappears. Do not delete real contacts as a test.

## Camera photos and videos in one album

On the S2, open **Galaxy Companion → Services → Camera backup** and enable
**Back up new photos and videos**. No Google Photos permission or Google account
is required by the bridge.

- New `.jpg` / `.jpeg`, `.mp4` and `.3gp` files in the supported `DCIM/Camera`
  directories are eligible, including the supported external-card locations.
  The original enable time is retained across updates and toggles; this is not
  a historical gallery import.
- Photos are limited to 32 MiB; videos to 4 GiB. Videos are copied without
  transcoding. Normal file metadata, including embedded location if present,
  remains in the copy.
- Both media types are published to **`Pictures/Galaxy S2` on the modern phone**
  and share the same Gallery album. Gallery applications may take a moment to
  refresh. The bridge does not itself upload media to a cloud photo service.
- Videos transfer in resumable 256 KiB pieces. Chunk and complete-file checksums
  are verified before publication, and receipt journals suppress duplicates.
  Keep room for a temporary video plus its published copy, with additional free
  space. An interrupted large transfer can take several retry cycles.
- The S2 originals are retained. Deleting a modern-phone backup does not delete
  the S2 original or automatically re-upload that completed file. Do not clear
  receipts to force a retry; copy a deliberately restored file separately.

Earlier video builds used `Movies/Galaxy S2`. This update changes **new** video
backups; it does not silently move or restore existing media. If you have older
copies, move those yourself with the modern phone's Files application after
verifying your backup. Existing photo locations are unchanged.

Take a harmless new photo and short video with the original S2 Camera. Allow
roughly two minutes for a short clip under normal conditions, then check both
in `Pictures/Galaxy S2`. Longer clips or a reconnect can take longer. Confirm the
video opens and plays; a counter alone is not proof of a complete backup.

## Selected notification forwarding

On the modern phone, open **Galaxy Companion → Services → Notification
forwarding**. Enable **Forward app notifications**, tap **Notification access**,
and authorize **Galaxy Companion notifications** in Android's settings. This
uses the same listener as the optional RCS relay, but has its own enable switch.
RCS does not have to be enabled for general notifications.

Forwarding starts **off**. Once enabled, eligible ordinary apps are included;
use the switches under **Apps seen on this phone** to exclude individual apps.
An app appears there after its notification has been observed. The filter
excludes Google Messages, Gmail, Telegram, companion helpers and core service
alerts to avoid duplicating their existing S2 integrations. Ongoing/progress,
playback, call and group-summary notifications are also filtered.

Rows on the S2 show the normal app name, title, preview and that app's icon.
Android 4's small status-bar icon remains the shared Companion symbol because
it must be a packaged resource. The larger icon in the notification row is the
source app's actual icon. `QUERY_ALL_PACKAGES` on the modern helper supports
resolving those labels/icons, including apps without launcher entries.

The relay can only forward content Android exposes to the notification
listener. Protected or hidden content is not bypassed. It checks periodically,
so delivery is not instantaneous. Reboot recovery initially restores current
alerts silently; new eligible alerts can use the S2's notification sound.
Tapping opens a local detail view. Opening that screen alone leaves the modern
phone's notification in place. It does not remotely launch the source app or
forward its reply/action buttons.

If you dismiss an alert on the **modern phone**, its S2 copy is removed on the
next successful sync. Normal checks run about every **15 seconds while awake**,
with a wake check roughly **once a minute while the S2 sleeps**. Allow extra time
for network recovery or a busy sync; this is periodic synchronization, not an
instant push. During a connection failure, existing S2 alerts are retained until
a complete host snapshot can be read. This removal sync is already part of
notification forwarding and needs no additional switch.

### Clear an alert on the modern phone

1. Tap a forwarded notification in the S2's notification drawer.
2. Tap **Clear on phone** in its detail screen.
3. The S2 confirms **Cleared on phone** after the modern phone reports that the
   matching alert is gone. If it was already removed, it reports that instead.

This uses your existing pairing, private Wi-Fi connection, certificate and
notification-listener permission. There is no new account, permission prompt,
cloud service or PC dependency. It has the same Android dismissal effect as
swiping away that alert on the modern phone; the source app can respond to its
normal dismissal callback. The bridge does not open the app or send a mark-read
or delete-message action.

The button applies only to **general forwarded notifications**. Samsung
Messaging, Gmail and Telegram have their own S2 clients and notification behavior.

- Non-clearable alerts have a disabled button. Service and ongoing alerts are
  generally filtered before forwarding.
- If the notification has changed, open its latest alert before clearing it.
  The old screen does not intentionally dismiss the updated content.
- If forwarding is turned off or that app is excluded, the alert is retained.
- If the connection is unavailable or removal cannot be confirmed, the screen
  reports failure and lets you retry. Dismissal requests are not queued for
  later or automatically replayed.

### Notification-only upgrade

Back up the two installed service APKs and keep your existing signing key,
pairing and app data. Do not clear storage, uninstall the helpers or regenerate
the pairing key. Wait until calls and message transfers are idle.

Update your source checkout, then run:

```powershell
python tools/configure.py
python tools/build.py services
```

If configure reports independent local edits, reconcile them first as described
above. Replace `MODERN_SERIAL` and `S2_SERIAL` below with the actual serials from
your own `adb devices` inventory:

```powershell
adb -s MODERN_SERIAL install -r ".local/work/legacy-services/pixel/galaxy-services-pixel.apk"
adb -s S2_SERIAL install -r ".local/work/legacy-services/s2/galaxy-services-s2.apk"
```

Install **both** service updates. An older modern service cannot handle the new
button; an older S2 service does not display it. Keep each package's existing
signer. The separate S2 calls/interface APK, root call configuration and optional
charging controller do not change in this upgrade. Allow private Wi-Fi to
reconnect after the modern service restarts. If services stay stopped, open
Galaxy Companion on each phone and check its connection controls.

The existing forwarding switch and app exclusions are retained. For a fresh
setup, enable forwarding and notification access using the steps above. Do not
turn on unrelated RCS or account features just for this button.

### Check dismissal without the PC

Use a harmless notification you are willing to dismiss; do not send a real
message to someone solely for this check without permission.

1. Let it appear on both phones. On the S2, open the notification detail and
   confirm that the host alert remains until you press the button.
2. Tap **Clear on phone**. Check that only the matching modern-phone alert
   disappears and other notifications remain.
3. With a different forwarded alert, dismiss it on the modern phone. Wait for
   the next sync and check that its S2 copy disappears without pressing anything
   on the S2. Other alerts should remain.
4. Unplug both phones from the PC, keep the modern phone nearby with the saved
   connection active, and repeat both directions with harmless alerts.
5. As part of initial setup, repeat after restarting each phone and unlocking
   the modern phone once. Leave helper screens closed to check startup recovery.

For a connection error, verify private Wi-Fi and notification access before
retrying. If the host already removed the alert, a retry safely reports that.
To roll back this feature, reinstall your previously saved service APKs using
the same signer and retain their data; do not replace the separate calls APK or
clear shared RCS message journals. If Android rejects a version downgrade, stop
and preserve the working installation rather than uninstalling it. See
[recovery](RECOVERY.md).

Disabling the forwarding switch clears mirrored alerts after the next successful
poll. Do not revoke the shared listener if you still need RCS notification
replies. Titles/previews and icons can be cached in private S2 helper storage;
review the S2's lock-screen privacy before forwarding sensitive apps.

## Telegram previews and final no-PC check

The optional [Telegram](TELEGRAM.md) S2 client, version 14 / 0.6.4, now uses the
conversation title and message preview. Group previews include the sender;
media has a short label when no text is available. The existing gray icon and
classic interface are preserved. Build it separately with your existing key and
reference assets; no new Telegram authorization is needed for an in-place update.

Restart each phone, unlock the modern phone once, and leave helper screens
closed. Give the private Wi-Fi link time to recover. Then unplug both phones
from the PC, lock the modern phone and repeat the contact, camera and selected
notification checks. Observe an authorized incoming Telegram message if using
Telegram. Record real device results separately from successful builds or host
tests; see [validation](VALIDATION.md).
