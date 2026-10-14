# Instructions for an assistant setting this up

Read README.md, docs/AI_SETUP.md and docs/RECOVERY.md first. Inspect current devices and local work before editing or installing anything. A successful build does not establish compatibility with a phone.

Never commit `.local`, `local.json`, owner data, generated packages, credentials or signing material. Do not read or print message bodies, account credentials or contact lists when counts or status suffice. Do not send a real call/message, accept account terms, choose between signed-in identities or authorize an account without the owner's authorization.

Native edits require the exact device, build and file hashes enforced in source. Never remove a guard to make an installation pass. Do not flash a ROM, erase data, unlock a bootloader or modify the modern phone's system partition as a shortcut. Preserve existing keys, pairings and backups.

Use the staged setup order and checkpoints. Resolve unknown UIDs, APK paths, fingerprints and Bluetooth addresses from the owner's devices. Do not invent them or use the example values. Prefer a clear blocked checkpoint to an unverified system write. Keep a private handoff of completed checks and remaining gates; publish only sanitized documentation.

Stop before interrupting an active call or a message with an uncertain send result. Never automatically replay an uncertain send or receive batch. Test offline-from-PC operation, including one unlock after a modern Android phone reboot, before calling a feature complete.

If the owner requests a no-device audit, that boundary supersedes the inventory/install steps: no ADB or phone UI actions. Test a fresh checkout with synthetic local configuration and record all external inputs. Historical `pixel` source paths/package identifiers do not restrict the phone brand; keep them stable.
