import os
from pathlib import Path
import re,shutil,subprocess,hashlib,json
P=Path(__file__).resolve().parent; W=P.parent
D=P/'s2-decoded'; B=W/'gmail-investigation/decoded'
J=Path(os.environ['JAVA_HOME']+'/bin')
BT=Path(os.environ['ANDROID_SDK_ROOT']+'/build-tools/36.1.0')
ANDROID=W/'telegram-s2-client/sdk/platform/android-4.0.4/android.jar'
OLD='com.google.android.gm'; NEW='org.retrobridge.gmail2012'
def rename(s):
 return s.replace('com.google.android.gmail.SuggestionProvider',NEW+'.SuggestionProvider').replace(OLD,NEW).replace(OLD.replace('.','/'),NEW.replace('.','/')).replace('gmail-ls','galaxy-gmail-ls').replace('"com.google"','"org.retrobridge.gmail2012.pixel"')
if not D.exists():shutil.copytree(B,D,ignore=shutil.ignore_patterns('build','dist'))
for f in B.rglob('*'):
 if f.is_file() and f.suffix in ('.smali','.xml','.yml') and f.relative_to(B).parts[0] not in ('original','build','dist'):
  dest=D/f.relative_to(B);dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(rename(f.read_text(encoding='utf-8')),encoding='utf-8')
def patch(file,signature,body):
 p=D/'smali/com/google/android/gm'/file;s=p.read_text(encoding='utf-8')
 s,n=re.subn(r'(?m)^\.method '+re.escape(signature)+r'\n.*?^\.end method','.method '+signature+'\n'+body+'\n.end method',s,flags=re.S|re.M)
 assert n==1,(file,signature,n);p.write_text(s,encoding='utf-8')
# All network work goes through the paired Pixel. No legacy ClientLogin or mail.google.com calls.
patch('provider/MailEngine.smali','public performBackgroundSync(Landroid/content/SyncResult;Landroid/os/Bundle;)V','''    .locals 0
    invoke-static {p0, p1}, Lorg/retrobridge/gmail2012/provider/PixelMail;->sync(Lorg/retrobridge/gmail2012/provider/MailEngine;Landroid/content/SyncResult;)V
    return-void''')
patch('provider/MailEngine.smali','sync(Landroid/content/SyncResult;Landroid/os/Bundle;)V','''    .locals 0
    invoke-static {p0, p1}, Lorg/retrobridge/gmail2012/provider/PixelMail;->sync(Lorg/retrobridge/gmail2012/provider/MailEngine;Landroid/content/SyncResult;)V
    return-void''')
patch('provider/MailEngine.smali','private runSyncLoop(Lorg/retrobridge/gmail2012/provider/MailEngine$MailEngineSyncObserver;ZLorg/retrobridge/gmail2012/provider/MailEngine$SyncInfo;Landroid/content/SyncResult;Landroid/os/Bundle;)Z','''    .locals 1
    invoke-static {p0}, Lorg/retrobridge/gmail2012/provider/PixelOutbox;->kick(Lorg/retrobridge/gmail2012/provider/MailEngine;)V
    const/4 v0, 0x0
    return v0''')
patch('provider/MailEngine.smali','private runHttpRequestInternal(Lorg/apache/http/client/methods/HttpUriRequest;)Lorg/apache/http/HttpResponse;','''    .locals 2
    new-instance v0, Ljava/io/IOException;
    const-string v1, "Use the paired Pixel connection"
    invoke-direct {v0, v1}, Ljava/io/IOException;-><init>(Ljava/lang/String;)V
    throw v0''')
patch('provider/MailEngine.smali','private calculateUnknownSyncRationalesAndPurgeInBackground()V','    .locals 0\n    return-void')
patch('provider/AttachmentManager.smali','public maybeStartNextAttachmentDownload()V','    .locals 0\n    return-void')
patch('provider/AttachmentManager.smali','public queryAndStartDownloadingAttachment(JJLorg/retrobridge/gmail2012/provider/Gmail$Attachment;Lorg/retrobridge/gmail2012/provider/Gmail$AttachmentRendition;Z[Ljava/lang/String;)Landroid/database/Cursor;','''    .locals 1
    invoke-static/range {p0 .. p8}, Lorg/retrobridge/gmail2012/provider/PixelAttachments;->query(Ljava/lang/Object;JJLorg/retrobridge/gmail2012/provider/Gmail$Attachment;Lorg/retrobridge/gmail2012/provider/Gmail$AttachmentRendition;Z[Ljava/lang/String;)Landroid/database/Cursor;
    move-result-object v0
    return-object v0''')
for typ in ['ConversationCursorLogic','MessageCursorLogic']:
 patch('provider/MailEngine$'+typ+'.smali',('protected' if typ=='ConversationCursorLogic' else 'public')+' runInternal()V','''    .locals 1
    iget-object v0, p0, Lorg/retrobridge/gmail2012/provider/MailEngine$'''+typ+''';->this$0:Lorg/retrobridge/gmail2012/provider/MailEngine;
    invoke-static {v0, p0}, Lorg/retrobridge/gmail2012/provider/PixelMail;->fetch(Lorg/retrobridge/gmail2012/provider/MailEngine;Ljava/lang/Object;)V
    return-void''')
# Disable upstream debug logging: message bodies, subjects and account names stay private.
p=D/'smali/com/google/android/gm/provider/LogUtils.smali';s=p.read_text()
s=re.sub(r'(?m)(^\.method[^\n]+ (?:v|d|i|w|e|wtf)\([^\n]+\)I)\n.*?^\.end method',r'\1\n    .locals 1\n    const/4 v0, 0x0\n    return v0\n.end method',s,flags=re.S|re.M)
s=re.sub(r'(?m)(^\.method[^\n]+ (?:v|d|i|w|e)\([^\n]+\)V)\n.*?^\.end method',r'\1\n    .locals 0\n    return-void\n.end method',s,flags=re.S|re.M)
s=re.sub(r'(?m)(^\.method[^\n]+isLoggable\([^\n]+\)Z)\n.*?^\.end method',r'\1\n    .locals 1\n    const/4 v0, 0x0\n    return v0\n.end method',s,flags=re.S|re.M);p.write_text(s)
m=D/'AndroidManifest.xml';s=m.read_text();s=re.sub(r'\s*<meta-data android:name="com.google.android.backup.api_key"[^>]+/>','',s)
s=s.replace('<application ','<application android:debuggable="true" android:allowBackup="false" ',1)
s=s.replace('<application ', '<uses-permission android:name="android.permission.AUTHENTICATE_ACCOUNTS"/><application ',1)
# Original launcher aliases retain their icon, but bootstrap the local Pixel-backed account first.
s=s.replace('android:targetActivity="GmailActivity"','android:targetActivity=".provider.PixelSetup"')
s=s.replace('<activity android:label="@string/activity_gmail" android:name="GmailActivity"', '<activity android:name=".provider.PixelSetup" android:theme="@android:style/Theme.Holo.Light" android:exported="true"/><activity android:label="@string/activity_gmail" android:name="GmailActivity"')
if 'android:name=".provider.PixelSetup"' not in s:
 s=s.replace('<activity ', '<activity android:name=".provider.PixelSetup" android:theme="@android:style/Theme.Holo.Light" android:exported="true"/><activity ',1)
s=s.replace('</application>','''<service android:name=".provider.PixelAuthenticator" android:exported="true" android:permission="android.permission.BIND_ACCOUNT_AUTHENTICATOR"><intent-filter><action android:name="android.accounts.AccountAuthenticator"/></intent-filter><meta-data android:name="android.accounts.AccountAuthenticator" android:resource="@xml/pixel_authenticator"/></service>
<receiver android:name=".provider.PixelBoot" android:exported="false"><intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/></intent-filter></receiver>
</application>''');m.write_text(s)
(D/'res/xml/pixel_authenticator.xml').write_text('''<account-authenticator xmlns:android="http://schemas.android.com/apk/res/android" android:accountType="org.retrobridge.gmail2012.pixel" android:label="@string/activity_gmail" android:icon="@mipmap/ic_launcher_mail" android:smallIcon="@mipmap/ic_launcher_mail"/>''')
# Use the original manifest's icon resource without guessing its resource type.
icon=re.search(r'<application[^>]*android:icon="([^"]+)"',s).group(1)
p=D/'res/xml/pixel_authenticator.xml';p.write_text(p.read_text().replace('@mipmap/ic_launcher_mail',icon))
y=D/'apktool.yml';s=y.read_text();s=re.sub(r'versionCode: \d+','versionCode: 404',s);s=re.sub(r'versionName: .*','versionName: 4.1.2-pixel.1',s);y.write_text(s)
for p in D.glob('res/values*/styles.xml'):
 s=p.read_text();s=s.replace('parent="@android:attr/listViewWhiteStyle"','parent="@android:style/Widget.Holo.Light.ListView"').replace('parent="@android:attr/autoCompleteTextViewStyle"','parent="@android:style/Widget.Holo.Light.AutoCompleteTextView"');p.write_text(s)
def run(args):
 r=subprocess.run(list(map(str,args)),capture_output=True,text=True,timeout=240)
 if r.returncode:raise RuntimeError(r.stdout+r.stderr)
 return r.stdout
classes=P/'s2-classes';dex=P/'s2-dex';dis=P/'s2-helper-smali'
for p in [classes,dex,dis]:
 if p.exists():
  assert p.resolve().parent==P.resolve();shutil.rmtree(p)
 p.mkdir()
run([J/'javac.exe','-source','8','-target','8','-encoding','UTF-8','-classpath',ANDROID,'-d',classes,*list((P/'s2-stubs').rglob('*.java')),*list((P/'s2-src').rglob('*.java'))])
run([J/'java.exe','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','14','--lib',ANDROID,'--output',dex,*classes.rglob('*.class')])
run([J/'java.exe','-jar',W/'call-feasibility/baksmali-2.5.2.jar','d',dex/'classes.dex','-o',dis])
# Remove stale generated helpers so diagnostics never survive a release rebuild.
for old in (D/'smali/org/retrobridge/gmail2012/provider').glob('Pixel*.smali'):old.unlink()
for f in dis.rglob('Pixel*.smali'):
 dest=D/'smali'/f.relative_to(dis);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(f,dest)
run([J/'java.exe','-jar',W/'peggle-debug-tools/apktool.jar','b',D,'-o',P/'s2-unsigned.apk'])
run([BT/'zipalign.exe','-f','4',P/'s2-unsigned.apk',P/'s2-aligned.apk'])
key=W/'signing-private';out=P/'Gmail-2012-Pixel.apk'
run([J/'java.exe','-jar',BT/'lib/apksigner.jar','sign','--ks',key/'signing.p12','--ks-pass','file:'+str(key/'signing-password.txt'),'--min-sdk-version','14','--out',out,P/'s2-aligned.apk'])
run([J/'java.exe','-jar',BT/'lib/apksigner.jar','verify','--min-sdk-version','14',out])
print(json.dumps({'apk':out.name,'sha256':hashlib.sha256(out.read_bytes()).hexdigest()}))
