# Phone compatibility

The companion is intended for a **modern Android phone**, not a particular brand. The only physically tested modern phone is a Pixel 9 Pro. Other manufacturers have not been validated yet.

| Component | Requirement / current boundary |
| --- | --- |
| Old phone's native dialer/audio | **Sprint Epic 4G Touch SPH-D710, IMM76I.FL24, Android 4.0.4**, exact firmware hashes, root and recovery. This is not a generic Galaxy S2 port. |
| Complete modern-phone setup | **Android 16 / API 36 or newer is the current baseline**, subject to the feature checks below. No manufacturer check restricts it to Pixel. |
| Automatic private Wi-Fi | Android 16's configured local-only hotspot API, permitted 2.4 GHz channel, S2-compatible WPA2 security, Nearby devices permission and concurrent upstream internet. OEM hotspot/background policies can differ. |
| Calls / SMS | Bluetooth Classic HFP audio-gateway and MAP message-server roles, working cellular service, approved pairing/message access. Profiles and carrier behavior need physical verification. |
| RCS relay | Specifically **Google Messages**, supported one-to-one notification/reply actions, and Contacts/SMS/notification access. Another brand's default messaging app is not automatically supported. |
| Google accounts | Working Google Play services plus the owner's API registrations/consent. A phone without Google services does not meet this feature's prerequisites. |
| Voice recognition | An on-device recognizer/downloaded language supporting supplied-audio and segmented recognition. Test both S2 app and keyboard entry points. |
| Optional Telegram | The supplied native recipe builds **arm64-v8a**, not x86 or 32-bit modern-phone code. Requires a separate authorized Telegram session. |
| Media runtimes | Official Termux, Node/FFmpeg/Python, background operation and external-command permission. Vendor battery restrictions can prevent recovery. |
| Boot recovery | One PIN unlock, retained permissions, services not force-stopped and permitted background operation. Test each phone, then without USB. |

Several APK manifests declare minSdk 26, but the combined service uses newer APIs. **This does not establish Android 8–15 compatibility.** A manual 2.4 GHz WPA2 hotspot may be a porting experiment, but is not a documented substitute for a complete tested older-Android installation.

Source folders, output names, package IDs and internal keys containing `pixel` are historical identifiers for the modern-phone role. Keep them stable: renaming packages affects installed storage, signature permissions and OAuth. New config uses `modern_serial` / `modern_bluetooth`; the tools also accept the previous `pixel_*` names. Some original app labels still say “Pixel”; these are legacy labels, not hardware restrictions.

See Android's [configured hotspot API](https://developer.android.com/reference/android/net/wifi/WifiManager) and Termux's [RUN_COMMAND setup](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent). These references do not replace physical compatibility tests.
