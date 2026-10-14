# Native deployment worksheet

This requires a validated root/recovery method for the exact supported S2, owner authorization and an idle phone. There is no universal `su`/partition/bootloader command. An offline audit stops before deployment.

## Finalize identity

After installing calls, observe its UID and APK path. Pull/hash that installed APK and inspect its certificate with `apksigner verify --print-certs`. Add UID and **certificate SHA-256** to local.json, configure, build identity, hash that JAR, add the hash, configure again, and build native-calls.

Copy **`.local/work/stock-sms-native/config.example`** into `.local/work/stock-install/production.conf`. Fill every installed APK, signer, launcher and worker value. Guards permit the reviewed `/data/app/org.retrobridge.callprobe-1.apk` or `-2.apk` forms; a different actual path requires investigation, never guessing. Use ASCII/LF with a final newline. PowerShell 5's default encoding is unsuitable; Python can write with `encoding='ascii', newline='\n'`.

```powershell
python .local/work/stock-install/freeze_manifest.py --launcher .local/work/stock-sms-native/galaxy-companion-launcher --worker .local/work/stock-sms-native/galaxy-companion-ril --identity .local/work/stock-identity/galaxy-companion-identity.jar --config .local/work/stock-install/production.conf
python tools/build.py native-installer
```

Review manifest-receipt.json: it lists each staged name, hash, size, target and ownership. A build without a frozen manifest is not a production candidate. **Never deploy fixture-seed.tar or the fixture executable as production.**

## Stage using the reviewed root method

Transfer to a temporary non-privileged location, then have the approved root entry point create `/data/local/galaxy-companion-stage/`, root-owned 0700. Reject symlinks/unexpected existing files. Copy these artifacts, verify device bytes against local hashes and set root ownership with no group/other write bits:

| Local path under .local/work | Staged basename | Mode |
| --- | --- | --- |
| stock-sms-native/galaxy-companion-ril | galaxy-companion-ril | 0755 |
| stock-identity/galaxy-companion-identity.jar | galaxy-companion-identity.jar | 0644 |
| stock-install/production.conf | production.conf | 0600 |
| stock-sms-native/galaxy-companion-launcher | galaxy-companion-launcher | 0755 |
| stock-install/galaxy-companion-install | galaxy-companion-install | 0700 |

Do not change factory `/data` or `/data/local` metadata to satisfy a guard. With `/system` read-only and no active call, run the staged installer as root: `--self-test`, `--inspect`, `--preflight`, then authorized `--apply`. Stop on failure. It owns the remount/backup/transaction sequence; do not overwrite rild yourself afterward.

Original rild is preserved at `/system/etc/galaxy-companion-recovery/rild.original`. `--restore` validates that backup before restoring it. Retain the executable/receipts and the independent recovery route for a non-booting phone.

## Bluetooth patch is separate

Build bluetooth-patch and bluetooth-installer. The patched output is **`.local/work/stock-bluetooth-fix/libandroid_runtime.so`**; the original stays under peggle-debug-tools/symbols/system/lib. Never confuse them.

Stage the candidate and `.local/work/stock-bluetooth-install/bluetooth-library-install` in a reviewed root-owned directory. The candidate must satisfy its exact size/hash and regular-file/metadata checks. Use its actual absolute path as CANDIDATE:

- `bluetooth-library-install --inspect`
- `bluetooth-library-install --preflight CANDIDATE`
- `bluetooth-library-install --apply CANDIDATE`
- Recovery: `bluetooth-library-install --restore`, or `--restore ORIGINAL_RESCUE` with the exact original.

This installer has **no --self-test switch**. Its isolated `--exercise ROOT CANDIDATE ORIGINAL` fixture is not real preflight. Backup: `/system/etc/galaxy-companion-recovery/libandroid_runtime.original.so`. Never weaken its original/patched hashes or exact mount checks. Agree on restart timing so the relevant process reloads the library, then physically test call audio.

Finish [calls/SMS checks](CALLS_SMS.md) and retain [recovery records](RECOVERY.md). Native identity upgrades are separate operations; this worksheet does not authorize replacing a working pinned APK with an arbitrary build.
