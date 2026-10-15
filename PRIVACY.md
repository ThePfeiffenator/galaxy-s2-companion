# Privacy and trust

The intended deployment is two owner-controlled phones. The PC is for setup, not an ongoing cloud relay. This repository contains no hosted service and collects no project analytics.

- Calls and SMS pass through the modern phone's Bluetooth services. Contact copies, call logs and received messages on the S2 are local private data.
- The optional RCS notification listener can read notifications. Its RCS path filters for Google Messages; the separately enabled general-notification path is described below. Contacts access resolves a sender to a unique phone number; SMS access suppresses messages already handled by the SMS path. Queued text and receipt state are kept in private app storage. Android notification access itself is broader than this code's filter.
- Optional incoming/outgoing media relay stores private attachment queues and delivery receipts. Notification access does not guarantee access to original RCS attachments.
- Gmail/YouTube authorization stays on the modern Android phone. Gmail data is cached on the S2; YouTube controls can change the authorized account. Review each Google permission.
- APKs imported into the modern Android phone library remain private on that modern Android phone and transfer only to its paired S2. The public repository includes no library entries.
- Telegram authorization and the TDLib session stay in modern Android phone private storage. The S2 has local message/media caches. App pairing keys are secrets too.
- YouTube Music OAuth credentials and tokens belong in modern Android phone Termux private storage. The music integration requires a broad Google permission in the tested workflow even though its implemented operations are library reads and playback. Review Google's consent screen yourself.
- Optional automatic contacts copy the modern Contacts provider's supported fields and small photos to Samsung Contacts. The S2 keeps private pre-change snapshots and ownership markers. Unmatched local contacts are retained; a uniquely matching previous import can become managed. Disable contact sync to stop copying without deleting existing copies.
- Optional general notification forwarding shares the authorized listener with RCS, but has its own off-by-default switch and app exclusions. It copies app names/icons, titles and previews, excluding duplicate Messages/Gmail/Telegram and service alerts. Cached previews remain private on the S2. Notification access is broader than these filters; Android may withhold protected content. The package-visibility permission resolves app labels/icons and does not upload an installed-app list to a project service.
- Camera backup copies new JPEGs and MP4/3GP videos into Pictures/Galaxy S2 on the modern phone, retaining original bytes and any embedded metadata. It does not delete either copy, mirror deletions or upload to a project cloud. Private temporary chunks and completion receipts support resumable transfers.
- AP Mobile fetches public NASA feeds/articles/images through the modern phone. No account is required. The old widget's appearance is retained; its former AP backend is not restored.
- The compatibility certificate allows the modern Android phone proxy to decrypt and re-encrypt HTTPS traffic routed through it. Installing that certificate into the S2 system trust store affects all S2 applications. The CA private key must remain on the modern Android phone. Remove this trust when retiring the setup.

Helpers are built debuggable for controlled private provisioning with `run-as`. Treat an authorized USB debugging computer as trusted. This is not a security-audited consumer release. Revoke USB debugging authorizations you no longer need.

Keep source-only backups separate from personal recovery backups. Personal backups may contain keys, message databases, configuration, tokens and device identifiers. Never put them in issues, pull requests, screenshots or public releases. `.gitignore` is a convenience, not a privacy audit.
