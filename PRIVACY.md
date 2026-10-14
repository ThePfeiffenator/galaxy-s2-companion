# Privacy and trust

The intended deployment is two owner-controlled phones. The PC is for setup, not an ongoing cloud relay. This repository contains no hosted service and collects no project analytics.

- Calls and SMS pass through the Pixel's Bluetooth services. Contact copies, call logs and received messages on the S2 are local private data.
- The optional RCS notification listener can read notifications. Its code filters for Google Messages. Contacts access resolves a sender to a unique phone number; SMS access suppresses messages already handled by the SMS path. Queued text and receipt state are kept in private app storage. Android notification access itself is broader than this code's filter.
- Optional incoming/outgoing media relay stores private attachment queues and delivery receipts. Notification access does not guarantee access to original RCS attachments.
- Gmail/YouTube authorization stays on the Pixel. Gmail data is cached on the S2; YouTube controls can change the authorized account. Review each Google permission.
- APKs imported into the Pixel library remain private on that Pixel and transfer only to its paired S2. The public repository includes no library entries.
- Telegram authorization and the TDLib session stay in Pixel private storage. The S2 has local message/media caches. App pairing keys are secrets too.
- YouTube Music OAuth credentials and tokens belong in Pixel Termux private storage. The music integration requires a broad Google permission in the tested workflow even though its implemented operations are library reads and playback. Review Google's consent screen yourself.
- Camera backup copies new camera JPEGs to the Pixel. It does not delete either copy or mirror deletions.
- The compatibility certificate allows the Pixel proxy to decrypt and re-encrypt HTTPS traffic routed through it. Installing that certificate into the S2 system trust store affects all S2 applications. The CA private key must remain on the Pixel. Remove this trust when retiring the setup.

Helpers are built debuggable for controlled private provisioning with `run-as`. Treat an authorized USB debugging computer as trusted. This is not a security-audited consumer release. Revoke USB debugging authorizations you no longer need.

Keep source-only backups separate from personal recovery backups. Personal backups may contain keys, message databases, configuration, tokens and device identifiers. Never put them in issues, pull requests, screenshots or public releases. `.gitignore` is a convenience, not a privacy audit.
