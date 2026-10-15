# Third-party notices and inputs

- Modified yt2009 files retain their original notices. Upstream: https://github.com/ftde0/yt2009 ; runtime base commit: `9127347e763e97ad6cbcb88b94d5e436487ee9f7`. Apache-2.0.
- Telegram TDLib: https://github.com/tdlib/td ; build input commit `c15d3f5a5de6e3ba5839822c451152e5e18bb700`. Retain its Boost Software License and notices. Telegram names, logos and reference artwork remain their respective owners' property.
- Bouncy Castle: https://www.bouncycastle.org/ ; pinned 1.86 JDK15-to-18 artifacts. See source/legacy-services/BOUNCY-CASTLE-LICENSE.txt.
- Android Open Source Project MMS codec files under `source/legacy-services/common/codec` retain their Apache-2.0 notices. Google Material Components and Google Play services authorization are fetched as build dependencies; retain their applicable licenses.
- Termux: https://github.com/termux/termux-app . Install from the official project's documented distribution sources. Its application and packages have their own licenses.
- OpenSSL, FFmpeg, ImageMagick, Node.js, Python, yt-dlp, ytmusicapi and their dependencies are external build/runtime inputs. Follow their licenses when distributing any resulting binaries.
- Weather data uses Open-Meteo and GeoNames; preserve the attribution shown by Galaxy Services.
- AP Mobile compatibility uses official NASA JPL/news-release feeds, with source attribution and links. Feed text/images remain subject to their applicable source notices. The patch does not restore or redistribute the former AP service.
- Samsung/Google apps (including AP Mobile, Gmail and Play Store), Reddit is fun are user-supplied inputs. Their APKs, decoded resources, firmware files and patched binaries are not redistributed here. Owning a device does not transfer a third party's redistribution rights.

The root LICENSE covers first-party additions and compatible upstream files; it does not relicense external applications or assets. Build recipes that extract artwork do so only from the owner's local reference APK.

APK library policy: no installers are bundled. User-supplied files stay private. Any future redistributable app must carry explicit redistribution permission and all required notices/source; free price or archive hosting is not permission.
