import pathlib, subprocess, os, zipfile, hashlib, json, shutil
P=pathlib.Path(__file__).resolve().parent
SDK=pathlib.Path(os.environ['ANDROID_SDK_ROOT'])
J=pathlib.Path(os.environ['JAVA_HOME']+'/bin')
BT=SDK/'build-tools/36.1.0'; AND=P/'sdk/platform/android-4.0.4/android.jar'
ENV=dict(os.environ,JAVA_HOME=str(J.parent),PATH=str(J)+os.pathsep+os.environ['PATH'])
def run(args):subprocess.run([str(a) for a in args],env=ENV,check=True)
(P/'native-tmp').mkdir(exist_ok=True)
ENV.update(TMP=str(P/'native-tmp'),TEMP=str(P/'native-tmp'))
NATIVE=SDK/'ndk/28.1.13356709/toolchains/llvm/prebuilt/windows-x86_64/bin'
run([NATIVE/'clang.exe','--target=armv7a-linux-androideabi21','-shared','-fPIC','-O2','-marm','-fno-stack-protector','-U_FORTIFY_SOURCE','-D_FORTIFY_SOURCE=0','-Wl,--hash-style=both','-Wl,--no-rosegment','-Wl,-z,max-page-size=4096',P/'legacy_discovery.c','-o',P/'libgalaxysdp.so'])

classes=(P/'classes').resolve()
assert classes.parent==P.resolve() and classes.name=='classes'
if classes.exists():shutil.rmtree(classes)
for d in ['classes','dex','res/drawable','compiled','gen']:(P/d).mkdir(parents=True,exist_ok=True)
(P/'AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.retrobridge.galaxychat.s2" android:versionCode="11" android:versionName="0.6.1">
<uses-sdk android:minSdkVersion="15" android:targetSdkVersion="15"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/><uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/><uses-permission android:name="android.permission.BLUETOOTH"/><uses-permission android:name="android.permission.WAKE_LOCK"/>
<application android:label="Telegram" android:icon="@drawable/classic_ic_launcher" android:theme="@style/ClassicTelegram" android:debuggable="true" android:allowBackup="false">
<activity android:name=".ChatActivity" android:exported="true" android:windowSoftInputMode="adjustResize"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>
<activity android:name=".MediaActivity" android:exported="false"/><provider android:name=".MediaProvider" android:authorities="org.retrobridge.galaxychat.s2.media" android:exported="false" android:grantUriPermissions="true"/>
<receiver android:name=".BootReceiver" android:exported="false"><intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/><action android:name="android.intent.action.MY_PACKAGE_REPLACED"/></intent-filter></receiver>
<service android:name=".ChatService" android:exported="false"/>
<service android:name=".NotificationLinkService" android:exported="true" android:permission="org.retrobridge.callprobe.permission.NOTIFICATION_LINK"/>
</application></manifest>''',encoding='utf-8')
shutil.copyfile(P.parent/'telegram-companion/Wire.java',P/'Wire.java')
run([BT/'aapt2.exe','compile','--dir',P/'res','-o',P/'compiled/resources.zip'])
run([BT/'aapt2.exe','link','-o',P/'base.apk','--manifest',P/'AndroidManifest.xml','-I',AND,'--java',P/'gen',P/'compiled/resources.zip'])
sources=[P/(n+'.java') for n in ['ReactionIcons','ReactionUi','ClassicUi','NotificationLinkService','ClientRules','ChatStore','BootReceiver','ChatService','ChatActivity','MediaClient','LegacyDiscovery','AttachmentUi','AttachmentJob','MediaProvider','MediaActivity','Wire']]
run([J/'javac.exe','-source','8','-target','8','-encoding','UTF-8','-classpath',AND,'-d',P/'classes']+sources+list((P/'gen').rglob('*.java')))
run([J/'java.exe','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','15','--lib',AND,'--output',P/'dex']+list((P/'classes').rglob('*.class')))
with zipfile.ZipFile(P/'base.apk') as a,zipfile.ZipFile(P/'unsigned.apk','w',zipfile.ZIP_DEFLATED) as b:
 for n in a.namelist():b.writestr(n,a.read(n),compress_type=zipfile.ZIP_STORED if n=='resources.arsc' else zipfile.ZIP_DEFLATED)
 b.write(P/'dex/classes.dex','classes.dex')
 b.write(P/'libgalaxysdp.so','lib/armeabi-v7a/libgalaxysdp.so')
key=P.parent/'signing-private'
run([BT/'zipalign.exe','-f','4',P/'unsigned.apk',P/'aligned.apk'])
run([J/'java.exe','-jar',BT/'lib/apksigner.jar','sign','--ks',key/'signing.p12','--ks-pass','file:'+str(key/'signing-password.txt'),'--min-sdk-version','15','--out',P/'galaxy-chat-s2.apk',P/'aligned.apk'])
run([J/'java.exe','-jar',BT/'lib/apksigner.jar','verify','--min-sdk-version','15',P/'galaxy-chat-s2.apk'])
run([BT/'zipalign.exe','-c','4',P/'galaxy-chat-s2.apk'])
files=sources+[P/'AndroidManifest.xml',P/'build.py',AND,P/'galaxy-chat-s2.apk']
receipt={'package':'org.retrobridge.galaxychat.s2','minSdk':15,'targetSdk':15,'debuggable':True,'device_tested':False,'files':{str(f.relative_to(P)):{'sha256':hashlib.sha256(f.read_bytes()).hexdigest(),'bytes':f.stat().st_size} for f in files}}
(P/'build-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
print('APK_SHA256='+receipt['files']['galaxy-chat-s2.apk']['sha256'])
