import os
from pathlib import Path
import re,shutil,subprocess,hashlib,json,xml.etree.ElementTree as ET
P=Path(__file__).resolve().parent;W=P.parent;B=P/'decoded';D=P/'catalog-decoded'
J=Path((os.environ['JAVA_HOME'] + '/bin'));BT=Path((os.environ['ANDROID_SDK_ROOT'] + '/build-tools/36.1.0'));A=W/'telegram-s2-client/sdk/platform/android-4.0.4/android.jar'
def rename(s):
 s=s.replace('com.android.vending','org.retrobridge.play2012').replace('com/android/vending','org/retrobridge/play2012')
 for suffix in ['RecentSuggestionsProvider','AppIconProvider','QSBSuggestionsProvider2']:s=s.replace('com.google.android.finsky.'+suffix,'org.retrobridge.play2012.'+suffix)
 return s
if not D.exists():shutil.copytree(B,D,ignore=shutil.ignore_patterns('build','dist'))
for f in B.rglob('*'):
 if f.is_file() and f.suffix in ('.smali','.xml','.yml') and f.relative_to(B).parts[0] not in ('build','dist','original'):
  dest=D/f.relative_to(B);dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(rename(f.read_text(encoding='utf-8')),encoding='utf-8')
def patch(file,signature,body):
 p=D/'smali'/file;s=p.read_text();s,n=re.subn(r'(?m)^\.method '+re.escape(signature)+r'\n.*?^\.end method','.method '+signature+'\n'+body+'\n.end method',s,flags=re.S|re.M);assert n==1,(file,signature,n);p.write_text(s)
F='com/google/android/finsky/';HELP='Lorg/retrobridge/play2012/PixelCatalog;'
for signature in ['public static getFirstAccount(Landroid/content/Context;)Landroid/accounts/Account;','public static findAccount(Ljava/lang/String;Landroid/content/Context;)Landroid/accounts/Account;','public static getAccountFromPreferences(Landroid/content/Context;Lcom/google/android/finsky/config/PreferenceFile$SharedPreference;)Landroid/accounts/Account;']:
 patch(F+'api/AccountHandler.smali',signature,'    .locals 1\n    invoke-static {}, '+HELP+'->account()Landroid/accounts/Account;\n    move-result-object v0\n    return-object v0')
patch(F+'api/AccountHandler.smali','public static getAccounts(Landroid/content/Context;)[Landroid/accounts/Account;','''    .locals 3
    const/4 v0, 0x1
    new-array v0, v0, [Landroid/accounts/Account;
    invoke-static {}, '''+HELP+'''->account()Landroid/accounts/Account;
    move-result-object v1
    const/4 v2, 0x0
    aput-object v1, v0, v2
    return-object v0''')
patch(F+'activities/AuthenticatedActivity.smali','protected startInitializationActions(Z)V','    .locals 0\n    invoke-static {p0, p1}, '+HELP+'->initialize(Landroid/app/Activity;Z)V\n    return-void')
patch(F+'activities/AuthenticatedActivity.smali','public isTosAccepted()Z','    .locals 1\n    const/4 v0, 0x1\n    return v0') # No Google account or Google Store service is used by this catalog.
patch(F+'activities/MainActivity.smali','private checkHasPromoOffers(Ljava/lang/Runnable;)V','    .locals 0\n    invoke-interface {p1}, Ljava/lang/Runnable;->run()V\n    return-void')
for sig in ['private initializeBilling()V','private initializeBillingCountries()V']:patch(F+'activities/MainActivity.smali',sig,'    .locals 0\n    return-void')
patch(F+'activities/ListTab.smali','private getColumnCount(Landroid/content/Context;I)I','    .locals 1\n    const/4 v0, 0x1\n    return v0')
for sig in ['private cleanupOldFinsky()V','private enableBootReceiver()V']:patch(F+'FinskyApp.smali',sig,'    .locals 0\n    return-void')
patch(F+'services/DailyHygiene.smali','public static goMakeHygieneIfDirty(Landroid/content/Context;)V','    .locals 0\n    return-void')
patch('com/android/volley/toolbox/BasicNetwork.smali','public performRequest(Lcom/android/volley/Request;)Lcom/android/volley/NetworkResponse;','''    .locals 1
    invoke-static {p1}, '''+HELP+'''->network(Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v0
    check-cast v0, Lcom/android/volley/NetworkResponse;
    return-object v0''')
patch(F+'api/DfeApiContext.smali','public declared-synchronized getHeaders()Ljava/util/Map;','    .locals 1\n    new-instance v0, Ljava/util/HashMap;\n    invoke-direct {v0}, Ljava/util/HashMap;-><init>()V\n    return-object v0')
patch(F+'navigationmanager/NavigationManager.smali','public goToPurchase(Landroid/accounts/Account;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V','    .locals 0\n    invoke-static {p0, p2, p9}, '+HELP+'->install(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V\n    return-void')
patch(F+'navigationmanager/NavigationManager.smali','public goToMyDownloads(Lcom/google/android/finsky/api/model/DfeToc;)V','    .locals 0\n    invoke-static {p0, p1}, '+HELP+'->installed(Ljava/lang/Object;Ljava/lang/Object;)V\n    return-void')
patch(F+'utils/PackageManagerHelper.smali','public static uninstallPackage(Ljava/lang/String;)V','    .locals 0\n    invoke-static {p0}, '+HELP+'->uninstall(Ljava/lang/String;)V\n    return-void')
for method,result in [('getState','InstallerState'),('getProgress','InstallerProgressReport')]:
 patch(F+'receivers/InstallerImpl.smali','public '+method+'(Ljava/lang/String;)Lcom/google/android/finsky/receivers/Installer$'+result+';','    .locals 1\n    invoke-static {p1}, Lorg/retrobridge/play2012/PixelInstallerService;->'+('state' if method=='getState' else 'progress')+'(Ljava/lang/String;)Ljava/lang/Object;\n    move-result-object v0\n    check-cast v0, Lcom/google/android/finsky/receivers/Installer$'+result+';\n    return-object v0')
patch(F+'receivers/InstallerImpl.smali','public cancel(Ljava/lang/String;)V','    .locals 0\n    invoke-static {p1}, Lorg/retrobridge/play2012/PixelInstallerService;->cancel(Ljava/lang/String;)V\n    return-void')
patch(F+'receivers/InstallerImpl.smali','public uninstallAssetSilently(Ljava/lang/String;)V','    .locals 0\n    invoke-static {p1}, '+HELP+'->uninstall(Ljava/lang/String;)V\n    return-void')
patch(F+'activities/DetailsFlagContentViewBinder.smali','public bind(Landroid/view/View;Lcom/google/android/finsky/api/model/Document;Z)V','    .locals 1\n    const/16 v0, 0x8\n    invoke-virtual {p1, v0}, Landroid/view/View;->setVisibility(I)V\n    return-void')
patch(F+'activities/MainActivity.smali','private launchUrlInBrowser(Ljava/lang/String;)V','    .locals 0\n    invoke-static {p0}, '+HELP+'->about(Landroid/app/Activity;)V\n    return-void')
manifest=D/'AndroidManifest.xml';s=manifest.read_text();s=s.replace('<application ','<application android:debuggable="true" android:allowBackup="false" ',1)
s=re.sub(r' android:backupAgent="[^"]+"','',s)
# The clone has no billing, licensing, account, SMS or automatic maintenance role.
for perm in ['GET_ACCOUNTS','MANAGE_ACCOUNTS','AUTHENTICATE_ACCOUNTS','USE_CREDENTIALS','RECEIVE_SMS','SEND_SMS','SEND_SMS_NO_CONFIRMATION','INSTALL_PACKAGES','DELETE_PACKAGES','RECEIVE_BOOT_COMPLETED']:
 s=re.sub(r'\s*<uses-permission android:name="android.permission.'+perm+r'"/>','',s)
s=re.sub(r'<receiver\b.*?</receiver>',lambda m:m.group(0) if 'PackageMonitorReceiver$RegisteredReceiver' in m.group(0) else '',s,flags=re.S);s=re.sub(r'<service\b.*?</service>','',s,flags=re.S)
s=s.replace('</application>','<service android:name="org.retrobridge.play2012.PixelInstallerService" android:exported="false"/></application>');s=s.replace('</manifest>','<uses-permission android:name="org.retrobridge.storeinstaller.INSTALL_VERIFIED"/></manifest>');manifest.write_text(s)
p=D/'smali/com/google/android/finsky/layout/ScreenshotGallery.smali';s=p.read_text();s=s.replace('.method private loadImagesIfNecessary()V\n    .locals 15', '.method private loadImagesIfNecessary()V\n    .locals 15\n    iget-object v0, p0, Lcom/google/android/finsky/layout/ScreenshotGallery;->mDocument:Lcom/google/android/finsky/api/model/Document;\n    if-eqz v0, :catalog_gallery_original\n    invoke-virtual {v0}, Lcom/google/android/finsky/api/model/Document;->hasScreenshots()Z\n    move-result v0\n    if-nez v0, :catalog_gallery_original\n    const/16 v0, 0x8\n    invoke-virtual {p0, v0}, Lcom/google/android/finsky/layout/ScreenshotGallery;->setVisibility(I)V\n    return-void\n    :catalog_gallery_original');p.write_text(s)
base=D/'res/menu/base.xml';m=base.read_text();m=re.sub(r'\s*<item[^>]+android:id="@id/(?:accounts_item|settings_menu_item)"[^>]+/>','',m);m=m.replace('</group>', '<item android:id="@+id/catalog_refresh" android:title="Refresh" /></group>',1);base.write_text(m)
p=D/'smali/com/google/android/finsky/activities/MainActivity.smali';s=p.read_text();s=s.replace('.method public onOptionsItemSelected(Landroid/view/MenuItem;)Z\n    .locals 2', '.method public onOptionsItemSelected(Landroid/view/MenuItem;)Z\n    .locals 2\n    invoke-static {p0, p1}, Lorg/retrobridge/play2012/PixelCatalog;->menu(Landroid/app/Activity;Landroid/view/MenuItem;)Z\n    move-result v0\n    if-eqz v0, :catalog_original_menu\n    const/4 v0, 0x1\n    return v0\n    :catalog_original_menu');p.write_text(s)
p=D/'apktool.yml';s=p.read_text();s=re.sub(r'versionCode: \d+','versionCode: 8013021',s);s=re.sub(r'versionName: .*','versionName: 3.7.15-pixel.6',s);p.write_text(s)
assets=D/'assets/catalog';assets.mkdir(parents=True,exist_ok=True)
for asset in (W/'legacy-services/pixel/assets/catalog').iterdir():
 if asset.is_file():shutil.copyfile(asset,assets/asset.name)
def run(args):
 r=subprocess.run(list(map(str,args)),capture_output=True,text=True,timeout=240)
 if r.returncode:raise RuntimeError(r.stdout+r.stderr)
 return r.stdout
classes=P/'classes';dex=P/'dex';dis=P/'helper-smali'
for p in [classes,dex,dis]:
 if p.exists():assert p.resolve().parent==P.resolve();shutil.rmtree(p)
 p.mkdir()
run([J/'javac.exe','-source','8','-target','8','-encoding','UTF-8','-classpath',A,'-d',classes,*list((P/'src').glob('*.java'))])
run([J/'java.exe','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','15','--lib',A,'--output',dex,*classes.rglob('*.class')])
run([J/'java.exe','-jar',W/'call-feasibility/baksmali-2.5.2.jar','d',dex/'classes.dex','-o',dis])
for f in dis.rglob('*.smali'):
 dest=D/'smali'/f.relative_to(dis);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(f,dest)
run([J/'java.exe','-jar',W/'peggle-debug-tools/apktool.jar','b',D,'-o',P/'unsigned.apk'])
run([BT/'zipalign.exe','-f','4',P/'unsigned.apk',P/'aligned.apk'])
out=P/'Play-Store-2012-Catalog.apk';key=W/'signing-private'
run([J/'java.exe','-jar',BT/'lib/apksigner.jar','sign','--ks',key/'signing.p12','--ks-pass','file:'+str(key/'signing-password.txt'),'--min-sdk-version','15','--out',out,P/'aligned.apk'])
run([J/'java.exe','-jar',BT/'lib/apksigner.jar','verify','--min-sdk-version','15',out])
print(json.dumps({'apk':out.name,'sha256':hashlib.sha256(out.read_bytes()).hexdigest()}))
