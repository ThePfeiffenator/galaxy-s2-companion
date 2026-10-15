# AP Mobile: original widget with science and space news

This optional patch keeps the supported Samsung AP Mobile screens and widget.
The modern phone supplies public NASA science/space headlines, images and links
in the old protocol. It does not restore the discontinued Associated Press
service or imply an AP/NASA endorsement. Articles are attributed to NASA and
open at their official source in the S2 browser.

## Inputs and local build

Use the **SPH-D710 / Android 4.0.4 / IMM76I.FL24** stock AP Mobile application
(version 12.01.09.01), its matching `.odex`, and the framework `.odex` files from
that same firmware. No APK, firmware, decoded application code or Samsung
artwork is distributed in this repository. The recipe verifies the stock input
hashes in [input-hashes.json](../source/ap-mobile/input-hashes.json). A mismatch
requires separate porting, not removal of the check.

First complete [build prerequisites](INPUTS.md), `tools/configure.py`, your own
signing key, apktool 3.0.3 and executable baksmali 2.5.2. Stage your original
inputs; `C:\PrivateInputs` below is only an example location:

```powershell
python tools/prepare_inputs.py ap-mobile 'C:\PrivateInputs\APNews.apk'
python tools/prepare_inputs.py ap-mobile-odex 'C:\PrivateInputs\APNews.odex'
python tools/prepare_inputs.py ap-mobile-framework 'C:\PrivateInputs\framework'
python tools/doctor.py --target ap-mobile
python tools/build.py ap-mobile
```

The framework input is the directory containing the required original `.odex`
files, including `twframework.odex`. It is not the apktool `framework-res.apk`
cache. Preparation verifies the pinned files and refuses to overwrite different
inputs. Obtain these files from your own matching firmware/backup; keep them in
ignored local storage. The builder starts from the original odex each time,
uses baksmali to deodex it and compiles only the changed code. It preserves the
original binary resources, including Samsung's TouchWiz attributes.

Result: `.local/work/ap-mobile/apnews-companion.apk`, package
`org.retrobridge.apnews`, signed with your local key. The patch uses the paired
S2 proxy at `127.0.0.1:8766`, separates its provider/actions from the stock app,
and guards an original callback that could run before the news list existed.
It does not replace the stock system APK or its app data.

## Install and add the widget

1. Build/install the current modern and S2 [compatibility services](MANUAL_SETUP.md).
   Complete their authenticated connection and private HTTPS trust. Confirm the
   original browser can open a page through the modern phone.
2. Install the resulting AP Mobile compatibility APK on the S2 with an explicit
   device serial and `adb install -r`. Keep your previous signed copy for rollback.
3. Remove the old AP Mobile widget from the home screen using the launcher UI.
   This removes a widget placement, not the original system application.
4. Add the new compatibility app's AP Mobile widget from the launcher widget
   picker in the desired position. The new package needs its own widget binding;
   installing an APK cannot transfer the original widget's saved binding.
5. Open the headline list once and check **Science & Space**, thumbnails and a
   readable official NASA article. Use **Galaxy Companion → Services → Science
   & space** on the modern phone to see feed status.

The S2 helper asks the compatible widget to refresh at startup and about every
30 minutes. The modern helper caches headlines and converts images for the old
widget. If upstream requests fail, cached headlines can remain visible; a
visible cached headline alone does not prove current connectivity. There is no
separate account or Termux setup for this feed.

Restart each phone, unlock the modern phone once and leave helpers closed.
After the link recovers, unplug both USB cables and confirm headlines, pictures
and article links still work with the modern phone locked. Keep the supported
Samsung launcher/TouchWiz environment; this widget is not claimed to work on a
generic emulator or another S2 firmware.

To roll back, remove the compatibility widget and re-add the original one if
desired. The original discontinued feed will still be unavailable. Uninstalling
only `org.retrobridge.apnews` does not remove Samsung's original package.
