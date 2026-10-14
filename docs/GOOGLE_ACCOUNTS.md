# Google accounts, Gmail and YouTube

Google sign-in happens on the modern Android phone through Google's account chooser. The S2 receives data over the paired private service; it does not receive a Google password or OAuth access token. Having an account on the modern Android phone avoids a separate password login but does not replace the required consent.

## Register your own application

1. Build `google-accounts` after `services`, using your own signing key. Set `GRADLE_EXECUTABLE` to your Gradle 8.9 executable as described in the manual.
2. In your own Google Cloud project enable **Gmail API** and **YouTube Data API v3** for the features you want. The owner reviews any API terms.
3. Configure the Google Auth Platform branding/audience/data access. Use your own project name, support contact and any required homepage/privacy/terms pages. Do not reuse somebody else's OAuth project or website.
4. Create an **Android OAuth client** for package `org.retrobridge.galaxymail.pixel` and the **SHA-1 signing certificate fingerprint of your locally signed APK**. This is Google's Android registration fingerprint, not the SHA-256 APK hash used by the native S2 installer. Obtain it with `apksigner verify --print-certs` from your build-tools installation.
5. While the project is in Testing, add your own account as a test user. Sensitive/restricted scopes may require additional Google review or limits. Follow what the console says for your deployment; publishing this source does not grant public API approval.

The Android account component uses Google's AuthorizationClient; there is no shared client secret to paste into this source. Registration must match the installed public package name and local signer. Follow [Google's Android authorization setup](https://developer.android.com/identity/authorization) and [OAuth guidance](https://developers.google.com/identity/protocols/oauth2).

```powershell
python tools/build.py google-accounts
```

Install `.local/work/gmail-bridge/pixel/galaxy-mail-pixel.apk` on the modern Android phone. Open Galaxy Companion → Google accounts (or the Google Bridge component if shown). Choose **Connect Gmail** and/or **Connect YouTube**, select the correct account and review the permission prompts. Account choice and consent belong to the owner.

Gmail requests `gmail.modify`, which permits reading and changing mail, including sending. YouTube initially requests `youtube.readonly`; **Enable YouTube controls** requests `youtube`, which includes broad account management rights. The implemented controls include ratings, subscriptions, playlists and comments. Do not grant controls if you only want reading. Google's scope can be broader than the controls this interface exposes.

## Gmail on the S2

Supply your own original **Gmail 4.1.2** APK:

```powershell
python tools/prepare_inputs.py gmail 'C:\PrivateInputs\Gmail-original.apk'
python tools/build.py gmail
```

Inspect the build's output filename in `.local/work/gmail-bridge` and install it on the S2. Open the modified Gmail after the modern Android phone says connected. The local account bootstrap should select the paired modern Android phone account, without asking the old Gmail app to perform its obsolete Google login.

Verify inbox, reading a message, refresh, stars/labels and attachment download. Only send a self-addressed test after the owner explicitly approves it. Confirm delivery and attachment contents; a cleared outbox alone is insufficient. Do not capture mail bodies/account names in public diagnostics.

## Avoid Testing's seven-day renewal cycle

For external OAuth projects in Testing, Google's refresh-token policy commonly imposes a seven-day lifetime for these scopes. Complete your own project information, resolve the console's requirements and switch its audience/publishing status to **In production** when appropriate. Then renew the connection on the modern Android phone so it is authorized under that configuration. Production is not a promise of permanent credentials: revocation, password/security changes, provider policy and other expiration conditions still apply. See [Google's expiration rules](https://developers.google.com/identity/protocols/oauth2#expiration).

YouTube Music's Termux runtime is a separate limited-input-device authorization described in [media apps](MEDIA_APPS.md). Register its own appropriate client and preserve its private token files; the Android Gmail/YouTube account component does not replace that client.

## Checkpoint and recovery

Test Gmail and YouTube separately with the modern Android phone locked, then after a reboot and one PIN unlock. Never copy another person's Google session. If authorization is revoked, reconnect on the modern Android phone. To retire the setup, revoke the app in Google's account settings and remove its private local caches. Keep account data out of source backups and issue reports.
