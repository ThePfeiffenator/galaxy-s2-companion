# First connection, permissions and trust

These are new-install device steps, **not part of an offline audit**. Use explicit serials and approved checkpoints. Do not repeat them on a working pair merely to test documentation.

1. Install the two services with your common signer. Complete calls separately; the S2 combined screen is in `org.retrobridge.callprobe`.
2. Keep services paused until `python tools/pair_devices.py services --apply` provisions the private key. It preflights destinations and refuses a different existing key. Recover an existing pairing instead of clearing it.
3. On the modern phone, Galaxy Companion → Connection → App permissions opens Android settings. Grant Nearby devices/Wi-Fi permissions and notifications. Pair Bluetooth and approve requested message sharing. The app does not silently grant these permissions.
4. Grant selected features' permissions before starting them: microphone for speech; precise/background location for navigation; calendar; Contacts/SMS and separate notification-listener access for RCS. Permit the companion, optional Telegram and Termux to run in the background in battery settings.
5. Resume both services. If started before pairing/permissions, pause and resume now: the proxy reads its key at startup and does not recover that initial failure merely because a file appeared. Do not interrupt an active call.
6. On Android 16+, let the companion create its private Wi-Fi. The S2 receives generated credentials over authenticated bonded Bluetooth. An existing ordinary phone hotspot instead needs an S2 saved network matching config's `wifi_ssid`, with its password entered separately.
7. Check the intended S2 network and managed proxy `127.0.0.1:8766`. Its DHCP gateway must be the modern phone, serving authenticated port 8765. A shared home router is not equivalent: forwarding uses the gateway. Preserve other proxies.
8. Set the S2's clock/date correctly. Authentication allows only two minutes of skew; TLS also checks dates. A wrong clock can look like broken pairing.

Optional [contact syncing and notification forwarding](CONTACTS_CAMERA_NOTIFICATIONS.md) each start disabled. Enable them deliberately in the modern Companion Services tab; Contacts needs Contacts permission, and forwarding needs the separate notification-listener grant. Camera backup is enabled on the S2. The [AP Mobile widget](AP_MOBILE.md) is an additional owner-built app.

YouTube/RIF/music need the additional [runtime setup](MEDIA_APPS.md). Google authorization is separate from Bluetooth pairing.

## Public certificate transfer

After the paired modern service starts, it creates `files/ca.pem`. Transfer only this public certificate, not `authority.p12` or its password. This Python command preserves bytes across PowerShell versions; set the SDK environment first:

```powershell
@'
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0, 'tools')
from local_config import load
cfg = load(Path('local.json'))
adb = str(Path(os.environ['ANDROID_SDK_ROOT'])/'platform-tools/adb.exe')
data = subprocess.check_output([adb, '-s', cfg['pixel_serial'], 'exec-out',
    'run-as', 'org.retrobridge.galaxyservices.pixel', 'cat', 'files/ca.pem'])
if not data.startswith(b'-----BEGIN CERTIFICATE-----') or b'PRIVATE KEY' in data:
    raise SystemExit('Public certificate unavailable; nothing written')
Path('.local/work/legacy-services/public-ca.pem').write_bytes(data)
'@ | python -
python -m pip install cryptography
python tools/build.py trust-store
```

`pixel_serial` is the normalized internal alias of `modern_serial`. With the owner's HTTPS-trust approval, stage the built `galaxy-trust-store` via the reviewed S2 root method. Run `--check`, then `--install`; verify its reported certificate and read-only mount. `--remove` removes only that certificate. Retain the hash/name/helper privately. Never install an audit-generated CA; a CA from another phone will not authenticate this service.
