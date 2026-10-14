import os
from pathlib import Path
import hashlib,json,re,shutil,subprocess
P=Path(__file__).resolve().parent; W=P.parent; D=P/'candidate'; SRC=P/'decoded'
OLD='com.andrewshu.android.reddit'; NEW='org.retrobridge.rif2012'
J=Path(os.environ['JAVA_HOME']+'/bin/java.exe')
BT=Path(os.environ['ANDROID_SDK_ROOT']+'/build-tools/36.1.0'); K=W/'signing-private'
for f in SRC.rglob('*'):
 if not f.is_file() or 'build' in f.relative_to(SRC).parts:continue
 dst=D/f.relative_to(SRC);dst.parent.mkdir(parents=True,exist_ok=True)
 if f.suffix in ('.smali','.xml','.yml') and 'original' not in f.relative_to(SRC).parts:
  text=f.read_text(encoding='utf-8').replace(OLD,NEW).replace(OLD.replace('.','/'),NEW.replace('.','/')).replace('"com.reddit"','"org.retrobridge.rif2012.account"')
  dst.write_text(text,encoding='utf-8')
 else:shutil.copyfile(f,dst)

def edit(rel,fn):
 f=D/rel;s=f.read_text(encoding='utf-8');t=fn(s);assert t!=s,rel;f.write_text(t,encoding='utf-8')
base='smali/com/andrewshu/android/reddit/'
edit(base+'f.smali',lambda s:s.replace('"http"','"https"'))
def reroute(s):
 needle='    invoke-virtual {p0, v5}, Lorg/retrobridge/rif2012/http/b;->b(Ljava/lang/String;)Lorg/apache/http/client/methods/HttpUriRequest;'
 assert s.count(needle)==1
 s=s.replace(needle,'    invoke-static {v5}, Lorg/retrobridge/rif2012/http/b;->galaxyFeedUrl(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v5\n'+needle)
 s=s.replace('.locals 13','.locals 14',1)
 needle='    move-result v10\n\n    const/16 v11, 0x190'
 assert s.count(needle)==1
 notice='''    move-result v10
    const/16 v11, 0x190
    if-lt v10, v11, :galaxy_status_end
    iget-object v11, p0, Lorg/retrobridge/rif2012/http/b;->d:Landroid/content/Context;
    instance-of v11, v11, Landroid/app/Activity;
    if-eqz v11, :galaxy_status_end
    const-string v12, "Reddit page unavailable. Try again later."
    const/16 v11, 0x193
    if-ne v10, v11, :galaxy_status_limit
    const-string v12, "Reddit is blocking this page. Try later."
    goto :galaxy_status_show
    :galaxy_status_limit
    const/16 v11, 0x1ad
    if-ne v10, v11, :galaxy_status_busy
    const-string v12, "Reddit rate limit. Please try later."
    goto :galaxy_status_show
    :galaxy_status_busy
    const/16 v11, 0x1f7
    if-ne v10, v11, :galaxy_status_timeout
    const-string v12, "Another page is loading. Tap to retry."
    goto :galaxy_status_show
    :galaxy_status_timeout
    const/16 v11, 0x1f8
    if-ne v10, v11, :galaxy_status_show
    const-string v12, "Reddit took too long. Tap to retry."
    :galaxy_status_show
    iget-object v11, p0, Lorg/retrobridge/rif2012/http/b;->d:Landroid/content/Context;
    check-cast v11, Landroid/app/Activity;
    new-instance v13, Lorg/retrobridge/rif2012/http/b$1;
    invoke-direct {v13, p0, v12}, Lorg/retrobridge/rif2012/http/b$1;-><init>(Lorg/retrobridge/rif2012/http/b;Ljava/lang/String;)V
    invoke-virtual {v11, v13}, Landroid/app/Activity;->runOnUiThread(Ljava/lang/Runnable;)V
    :galaxy_status_end
    const/16 v11, 0x190'''
 s=s.replace(needle,notice)
 return s+'''
.method private static galaxyFeedUrl(Ljava/lang/String;)Ljava/lang/String;
    .locals 4
    invoke-static {p0}, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;
    move-result-object v0
    invoke-virtual {v0}, Landroid/net/Uri;->getHost()Ljava/lang/String;
    move-result-object v1
    const-string v2, "api.reddit.com"
    invoke-virtual {v2, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v3
    if-nez v3, :route
    const-string v2, "oauth.reddit.com"
    invoke-virtual {v2, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v3
    if-nez v3, :route
    const-string v2, "www.reddit.com"
    invoke-virtual {v2, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v3
    if-nez v3, :route
    return-object p0
    :route
    invoke-virtual {v0}, Landroid/net/Uri;->buildUpon()Landroid/net/Uri$Builder;
    move-result-object v0
    const-string v1, "https"
    invoke-virtual {v0, v1}, Landroid/net/Uri$Builder;->scheme(Ljava/lang/String;)Landroid/net/Uri$Builder;
    const-string v1, "reddit.galaxy-services.invalid"
    invoke-virtual {v0, v1}, Landroid/net/Uri$Builder;->authority(Ljava/lang/String;)Landroid/net/Uri$Builder;
    invoke-virtual {v0}, Landroid/net/Uri$Builder;->build()Landroid/net/Uri;
    move-result-object v0
    invoke-virtual {v0}, Landroid/net/Uri;->toString()Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method
'''
edit(base+'http/b.smali',reroute)
def proxy(s):
 start=s.index('.method private static i()Lorg/apache/http/impl/client/DefaultHttpClient;');before=s[:start];s=s[start:].replace('.locals 3','.locals 5',1)
 needle='    new-instance v1, Lorg/retrobridge/rif2012/http/c$1;'
 assert s.count(needle)==1
 code='''    new-instance v2, Lorg/apache/http/HttpHost;
    const-string v3, "127.0.0.1"
    const/16 v4, 0x223e
    invoke-direct {v2, v3, v4}, Lorg/apache/http/HttpHost;-><init>(Ljava/lang/String;I)V
    const-string v1, "http.route.default-proxy"
    invoke-interface {v0, v1, v2}, Lorg/apache/http/params/HttpParams;->setParameter(Ljava/lang/String;Ljava/lang/Object;)Lorg/apache/http/params/HttpParams;

'''
 return before+s.replace(needle,code+needle,1)
edit(base+'http/c.smali',proxy)
def login(s):
 parent=re.search(r'^\.super (.+)$',s,re.M).group(1)
 for name in ['onCreate','onResume']:
  sig='(Landroid/os/Bundle;)V' if name=='onCreate' else '()V'
  args='{p0, p1}' if name=='onCreate' else '{p0}'
  code=f'.method protected {name}{sig}\n    .locals 2\n    invoke-super {args}, {parent}->{name}{sig}\n'
  if name=='onCreate':code+='''    const-string v0, "Browsing only"
    const/4 v1, 0x0
    invoke-static {p0, v0, v1}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;
    move-result-object v0
    invoke-virtual {v0}, Landroid/widget/Toast;->show()V
    invoke-virtual {p0}, Landroid/app/Activity;->finish()V
'''
  code+='    return-void\n.end method'
  s,n=re.subn(r'\.method protected '+name+re.escape(sig)+r'.*?\.end method',lambda _:code,s,flags=re.S);assert n==1
 return s
edit(base+'login/oauth2/OAuth2Activity.smali',login)
# Public website values preserve native scores/counts; label the loaded comment subset honestly.
edit('res/values/strings.xml',lambda s:s.replace('<string name="no_more_comments">no more comments</string>','<string name="no_more_comments">end of loaded comments</string>').replace('No threads here. Tap to refresh.','No posts loaded. Tap to retry.').replace('No comments here. Tap to refresh.','No comments loaded. Tap to retry.').replace('<string name="user_agent">reddit is fun (Android) %s</string>','<string name="user_agent">GalaxyLegacyReader/0.3 (RIF %s personal compatibility)</string>'))
edit('AndroidManifest.xml',lambda s:s.replace('android:allowBackup="true"','android:allowBackup="false" android:debuggable="true"'))
edit('apktool.yml',lambda s:s.replace('versionCode: 373','versionCode: 376').replace('versionName: 3.6.16','versionName: 3.6.16-Pixel-3'))
def run(args):subprocess.run(list(map(str,args)),check=True)
run([J,'-jar',W/'peggle-debug-tools/apktool.jar','b',D,'-o',P/'unsigned.apk'])
run([BT/'zipalign.exe','-f','4',P/'unsigned.apk',P/'aligned.apk'])
out=P/'RIF-Pixel-Reader.apk'
run([J,'-jar',BT/'lib/apksigner.jar','sign','--ks',K/'signing.p12','--ks-pass','file:'+str(K/'signing-password.txt'),'--min-sdk-version','15','--out',out,P/'aligned.apk'])
run([J,'-jar',BT/'lib/apksigner.jar','verify','--min-sdk-version','15',out])
(P/'build-receipt.json').write_text(json.dumps({'package':NEW,'version':'3.6.16-Pixel-3','original_sha256':hashlib.sha256((P/'originals/reddit-is-fun-3.6.16.apk').read_bytes()).hexdigest(),'apk_sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'changes':['isolated package and account type','API URLs use paired Pixel public-website translation','Apache client uses existing verified compatibility TLS proxy','login unavailable in read-only build','public website post totals and nested loaded comments'],'device_tested':False},indent=2)+'\n',encoding='utf-8')
