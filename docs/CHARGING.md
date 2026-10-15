# Optional 80% charging limit

Galaxy Companion can pause hardware charging at **80%** and resume at **75%**.
The setting survives restarting the S2. It runs locally on the S2, with no PC,
modern phone, internet service or new ongoing notification needed for charging.
The switch is under **Galaxy Companion → Settings → Battery protection**.

This is an exact-firmware extension for **SPH-D710 / IMM76I.FL24 / Android
4.0.4**, kernel `3.0.15-1173404` built December 24, 2012. It is not a generic
Galaxy S2 charging tool. It requires the separately established S2 root/recovery
method from [manual setup](MANUAL_SETUP.md). Do not remove a compatibility guard.

## Behavior and limits

- The five-point gap avoids rapid on/off switching. The limit starts **off** on
  a new installation; the owner enables it after checking normal charging.
- A native supervisor starts from the existing `flash_recovery` init service.
  The original recovery script is preserved and runs before the controller.
- Galaxy Companion schedules a brief wake check while plugged in with the
  limit enabled. Closing its screen is fine. Force-stop removes its alarms;
  reopen the app to restore them. Unplugging cancels unnecessary charge alarms.
- A failed native worker is restarted, with stock charging restoration attempted
  first. This does not guarantee recovery from every kernel/hardware failure.
- The old system charging icon may still show a charger while charging is
  paused. The Battery protection screen checks the actual charger-enable state.
- The controller requires Android to be running. Fully powered-off charging
  and AC wall charging have not been qualified. Do not assume the cap applies
  before Android starts or while the phone is shut down.

It uses the stock battery ONLINE callback, not direct GPIO/register writes or
fabricated sensor values. The read and write cable enums differ on this kernel;
do not replace the controller with a shell loop that writes back the read value.
Before restoring its own pause it independently checks physical cable presence,
battery presence, stock Good health, valid state of charge, 10–40 °C and
2.5–4.25 V. This matters because the stock cable-none path resets some counters.
The normal charger retains its own protections. A temperature/battery wait is
not an invitation to bypass these checks.

## 1. Prepare the matching Companion

Finish [calling/SMS setup](CALLS_SMS.md) first. Build `calls` from this revision,
whose public Companion manifest is version 16 (0.16.0). The historical output
filename remains `.local/work/sms-companion-v12/galaxy-companion-v14.apk`.

On a **fresh installation**, use that APK in the normal first-install identity
and native-call configuration sequence. On an **existing installation**, an APK
update changes its hash and can change its path: preserve the old APK/config
pair and complete the guarded configuration upgrade described in
[recovery](RECOVERY.md) before proceeding. The first-install native installer
is not an arbitrary existing-install updater. Do not install a new APK and leave
the call verifier pinned to its previous bytes.

No stock APKs, original boot scripts, developer identities, signing keys or
prebuilt charging executables are supplied. Keep all captured inputs under
ignored `.local/`. Do not copy another person's recovery archive.

## 2. Capture your inputs

From PowerShell in the configured repository, set `$S2` to the explicitly
selected S2 serial. These commands read the device; they do not install anything:

```powershell
$S2 = 'YOUR_ACTUAL_S2_SERIAL'
New-Item -ItemType Directory -Force .local/work/charging/inputs
adb -s $S2 shell getprop ro.product.model
adb -s $S2 shell getprop ro.build.display.id
adb -s $S2 shell cat /proc/version
adb -s $S2 shell pm path org.retrobridge.callprobe
adb -s $S2 shell dumpsys package org.retrobridge.callprobe
adb -s $S2 pull /system/etc/install-recovery.sh .local/work/charging/inputs/install-recovery.original.sh
adb -s $S2 pull /init.rc .local/work/charging/inputs/init.rc
adb -s $S2 pull /init.smdk4210.rc .local/work/charging/inputs/init.smdk4210.rc
```

Record the actual `userId` from the package dump, not a PID or a guessed UID.
Pull the installed APK from the exact path returned by `pm path` into
`.local/work/charging/inputs/installed-companion.apk`. Compare its SHA-256 with
your locally built candidate using `Get-FileHash -Algorithm SHA256`; they must
match. Inspect its package/signature using the SDK's `apksigner`/`aapt2` tools.

Create `.local/work/charging/inputs/identity.json` using those observed values:

```json
{
  "model": "SPH-D710",
  "build": "IMM76I.FL24",
  "kernel": "PASTE_THE_ACTUAL_PROC_VERSION_LINE",
  "package": "org.retrobridge.callprobe",
  "uid": null,
  "apk_path": "PASTE_THE_ACTUAL_INSTALLED_APK_PATH",
  "apk_sha256": "PASTE_THE_ACTUAL_INSTALLED_APK_SHA256"
}
```

Replace `null` with the measured numeric UID. This example intentionally cannot
build. The builder accepts only the observed Android 4 `-1.apk`/`-2.apk` paths,
the public package, exact firmware, and a hash equal to the built candidate.

Inspect the captured init files: `flash_recovery` must run
`/system/etc/install-recovery.sh` as root, and debugfs must be mounted at
`/sys/kernel/debug`. The original boot script must have SHA-256
`d421241570b61c655d79e0a460f0395a4520c49411fc90b9b4e61b441bff1842`.
Any different script or preexisting charging controller requires a separate
review; do not overwrite another root tool's boot hook. Preserve the original
script's bytes with `adb pull`, not a text redirection that changes line endings.

Stage your own stock `/system/lib/libc.so` using the existing `prepare_inputs.py
libc` command in [inputs](INPUTS.md). Under the reviewed root entry point,
read the relevant power-supply nodes and debugfs GPIO labels before deployment:
`battery/capacity`, `temp`, `voltage_now`, `present`, `health`, `online`, and
`MAX8922 TA_nCONNECTE` / `MAX8922 CHG_EN` in `/sys/kernel/debug/gpio`.
Confirm sensible live values and mode 0444 on `battery/online`. Do not write
these nodes manually to satisfy the checks.

## 3. Build and inspect locally

```powershell
python tools/doctor.py --target charging
python tools/build.py charging
python tools/test_charging_setup.py
```

The build runs 40 tests against the actual C policy, compiles ARM32 non-PIE
executables, checks imports against your old libc, and generates local UID/APK
and installer hash pins. It neither contacts nor changes a phone. Outputs:

- `.local/work/charging/galaxy-charge-manager`
- `.local/work/charging/galaxy-charge-install`
- `.local/work/charging/install-recovery.sh`
- `.local/work/charging/build-receipt.json`

Review the generated script: the original bytes must remain its prefix, with
only the controller startup appended. The native IPC accepts STATUS/ENABLE/
DISABLE from root or the exact installed Companion UID and pinned APK. There
is no setuid executable or app-writable charging node.

## 4. Install the guarded backend

Stop if a call is active. Obtain the owner's authorization for this persistent
system change and verify an independent recovery route first. The commands
below are explicit staging steps; use the same selected `$S2` throughout:

```powershell
adb -s $S2 shell mkdir -p /data/local/tmp/charge-limit-input
adb -s $S2 push .local/work/charging/galaxy-charge-manager /data/local/tmp/charge-limit-input/galaxy-charge-manager
adb -s $S2 push .local/work/charging/install-recovery.sh /data/local/tmp/charge-limit-input/install-recovery.sh
adb -s $S2 push .local/work/charging/galaxy-charge-install /data/local/tmp/galaxy-charge-install
adb -s $S2 shell chmod 700 /data/local/tmp/galaxy-charge-install
```

Run these on the S2 **through your already reviewed root entry point**, one at
a time, inspecting output after each. No universal `su` or rooting exploit is
assumed by this guide:

```sh
/data/local/tmp/galaxy-charge-install --preflight
/data/local/tmp/galaxy-charge-install --apply
/data/local/tmp/galaxy-charge-install --inspect
```

Require `CHARGE_PREFLIGHT_OK`, then `CHARGE_INSTALL result=0`, then
`new_boot=1 manager=1 backup=1 system_readonly=1`. Old ADB shells can return a
successful host exit code for a failed device command; check the markers.
The installer saves the original to
`/system/etc/galaxy-charge-recovery/install-recovery.original.sh` and makes
atomic, hash-checked replacements. Stop and investigate a mismatch; do not
substitute blind `cp`/mount commands. Keep this exact installer privately for
its matching rollback.

Restart the S2 normally, unlock it once, and leave Companion closed at first.
Read STATUS through root:

```sh
/system/bin/galaxy-charge-manager --client STATUS
```

Expect `enabled:false`, normal sensor values, `fault:false`, and limit/resume
80/75. An ordinary ADB shell client is deliberately unauthorized. Verify init's
`flash_recovery` service is running; it now owns the long-lived supervisor.
Then open Battery protection, enable the switch, close the app normally and
verify STATUS reports `enabled:true`. No new permanent notification is added.

## 5. Verify operation

Record the installed hashes and tests in a private handoff. Check:

1. At a safe percentage below 75%, the charger remains enabled normally.
2. Natural charging reaches 80%, the limiter reports paused and the hardware
   charger-enable state is off. Do not rely on the old battery icon alone.
3. While plugged in, the battery naturally falls to 75% and charging resumes.
   Do not fake percentage, temperature or health to force this test.
4. With the app closed and screen off, `dumpsys alarm` records delivered
   `org.retrobridge.callprobe.CHARGE_TICK` events. API 15's pending-intent entry
   omits the action; the Alarm Stats section shows delivered actions/counts.
5. Unplug/replug and reboot with the switch enabled: settings persist, no fault
   appears, wake alarms resume and the correct charging state is restored.
6. With USB unplugged, verify calls/audio, texts and browsing still work. The
   charging feature does not modify the modem, call routing or private Wi-Fi.

Development testing used bounded lower thresholds to verify real pause/resume,
not a full natural 80%/75% cycle. That private test does not qualify a different
phone or the public namespace. See [validation](VALIDATION.md).

## Disable, remove, or update

For everyday reversal, turn **Limit to 80%** off. Wait for `enabled:false`,
`paused:false`, `fault:false`; restoration may wait for safe battery conditions.

For complete removal, first meet those conditions and ensure no call is active.
As root, use Android init's `stop flash_recovery`, then confirm both current
controller processes have exited and its charge guard socket is gone. Never
kill a PID copied from an old log. Run your saved matching installer:

```sh
/data/local/tmp/galaxy-charge-install --restore
/data/local/tmp/galaxy-charge-install --inspect
```

Require `result=0 restored_original=1 manager=0 system_readonly=1`; inspection
must show `old_boot=1 new_boot=0 manager=0`. The original backup is retained.
Keep Companion installed unless also rolling back its matching call identity.
Reboot and verify stock charging and existing connections. If any expected
hash/state differs, stop rather than weakening the restore guard.

For a later Companion/controller update, disable and remove the old charging
backend with its **old matching installer** first. Preserve all backups, update
the APK and call configuration together, capture the new identity, and rebuild
and reinstall charging. This source kit does not supply an automatic in-place
upgrade across unknown previous native binaries. Never copy a private developer
APK hash, UID, boot script or recovery configuration into your own setup.
