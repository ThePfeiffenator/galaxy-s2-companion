import os
from pathlib import Path
import re,shutil,subprocess,hashlib,json
P=Path(__file__).resolve().parent;W=P.parent;SRC=P/'decoded';D=P/'candidate'
D.mkdir(exist_ok=True)
for f in SRC.rglob('*'):
 if not f.is_file() or 'build' in f.relative_to(SRC).parts:continue
 dst=D/f.relative_to(SRC);dst.parent.mkdir(parents=True,exist_ok=True)
 if f.suffix in ['.smali','.xml','.yml'] and 'original' not in f.relative_to(SRC).parts:
  s=f.read_text(encoding='utf-8').replace('com.google.android.googlequicksearchbox','org.retrobridge.google2012').replace('com/google/android/googlequicksearchbox','org/retrobridge/google2012');dst.write_text(s,encoding='utf-8')
 else:shutil.copyfile(f,dst)
def edit(rel,fn):
 f=D/rel;f.write_text(fn(f.read_text(encoding='utf-8')),encoding='utf-8')
base='smali/com/google/android/googlequicksearchbox/'
edit(base+'Config.smali',lambda s:s.replace('const-string v0, "com.google.android.voicesearch"','const-string v0, "org.retrobridge.galaxyservices.s2"'))
edit('res/values/bools.xml',lambda s:s.replace('<bool name="use_genie">true</bool>','<bool name="use_genie">false</bool>'))
edit(base+'google/SearchUrlHelper.smali',lambda s:re.sub(r'\.method public getSearchBaseUrl\(Landroid/os/Bundle;ZJ\)Ljava/lang/String;.*?\.end method','''.method public getSearchBaseUrl(Landroid/os/Bundle;ZJ)Ljava/lang/String;
    .locals 1
    const-string v0, "https://search.galaxy-services.invalid/search?hl=en"
    return-object v0
.end method''',s,flags=re.S))
def complete(s):
 s=s.replace('const-string v6, "http"','const-string v6, "https"')
 needle='invoke-virtual {v10, v11}, Lorg/retrobridge/google2012/google/SearchUrlHelper;->getSearchDomain(Z)Ljava/lang/String;'
 pos=s.index(needle);at=s.index('move-result-object v10',pos)+len('move-result-object v10')
 return s[:at]+'\n    const-string v10, "search.galaxy-services.invalid"'+s[at:]
edit(base+'google/complete/CompleteServerClient.smali',complete)
def voice(s):
 sig='.method public createVoiceWebSearchIntent(Landroid/os/Bundle;)Landroid/content/Intent;';start=s.index(sig);end=s.index('.end method',start)
 method=s[start:end].replace('.locals 3','.locals 5')
 needle='    :goto_0\n    return-object v0'
 code='''    :goto_0
    if-eqz v0, :no_target
    invoke-virtual {p0}, Lorg/retrobridge/google2012/VoiceSearch;->getContext()Landroid/content/Context;
    move-result-object v1
    new-instance v2, Landroid/content/Intent;
    const-class v3, Lorg/retrobridge/google2012/google/GoogleSearch;
    invoke-direct {v2, v1, v3}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
    const-string v3, "android.intent.action.WEB_SEARCH"
    invoke-virtual {v2, v3}, Landroid/content/Intent;->setAction(Ljava/lang/String;)Landroid/content/Intent;
    const/4 v3, 0x0
    const/high16 v4, 0x8000000
    invoke-static {v1, v3, v2, v4}, Landroid/app/PendingIntent;->getActivity(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;
    move-result-object v2
    const-string v1, "android.speech.extra.RESULTS_PENDINGINTENT"
    invoke-virtual {v0, v1, v2}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Landroid/os/Parcelable;)Landroid/content/Intent;
    :no_target
    return-object v0'''
 assert needle in method;method=method.replace(needle,code);return s[:start]+method+s[end:]
edit(base+'VoiceSearch.smali',voice)
# Signature-only settings privileges are neither requested nor needed by this compatibility copy.
edit('AndroidManifest.xml',lambda s:re.sub(r'\s*<uses-permission android:name="(?:android.permission.WRITE_SECURE_SETTINGS|android.permission.WRITE_SETTINGS|android.permission.MANAGE_ACCOUNTS|android.permission.USE_CREDENTIALS|com.google.android.providers.settings.permission.WRITE_GSETTINGS)"/>','',s).replace('<application ','<application android:debuggable="true" android:allowBackup="false" ',1))
edit('apktool.yml',lambda s:s.replace('versionCode: 141400000','versionCode: 1').replace('versionName: 1.4.1.488656','versionName: 1.4.1-Pixel-1'))
edit('AndroidManifest.xml',lambda s:s.replace('<receiver android:label="@string/app_name" android:name=".SearchWidgetProvider">','<receiver android:label="Google (Pixel)" android:name=".SearchWidgetProvider">'))
edit('res/values/strings.xml',lambda s:s.replace('<string name="app_name">Google Search</string>','<string name="app_name">Google (Pixel)</string>'))
J=Path(os.environ['JAVA_HOME']+'/bin/java.exe');BT=Path(os.environ['ANDROID_SDK_ROOT']+'/build-tools/36.1.0');K=W/'signing-private'
def run(a):subprocess.run(list(map(str,a)),check=True)
run([J,'-jar',W/'peggle-debug-tools/apktool.jar','b',D,'-o',P/'unsigned.apk'])
run([BT/'zipalign.exe','-f','4',P/'unsigned.apk',P/'aligned.apk'])
out=P/'Google-2012-Pixel.apk'
run([J,'-jar',BT/'lib/apksigner.jar','sign','--ks',K/'signing.p12','--ks-pass','file:'+str(K/'signing-password.txt'),'--min-sdk-version','8','--out',out,P/'aligned.apk'])
run([J,'-jar',BT/'lib/apksigner.jar','verify','--min-sdk-version','8',out])
(P/'build-receipt.json').write_text(json.dumps({'original_apk_sha256':hashlib.sha256((P/'GoogleQuickSearchBox-original.apk').read_bytes()).hexdigest(),'apk_sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'package':'org.retrobridge.google2012','original_system_apk_unchanged':True,'changes':['private authenticated search/suggestions endpoints','existing Pixel speech component and pending result target','isolated package and provider names','removed settings/account-writing privileges']},indent=2)+'\n',encoding='utf-8')
print('Original-interface search compatibility APK built and signed.')
