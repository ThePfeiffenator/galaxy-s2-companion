import os
from pathlib import Path
import shutil,subprocess,re,hashlib,json
P=Path(__file__).resolve().parent
W=P.parent
J=Path(os.environ['JAVA_HOME']+'/bin')
BT=Path(os.environ['ANDROID_SDK_ROOT']+'/build-tools/36.1.0')
BASE=W/'legacy-services/streaming/youtube-candidate'
D=P/'decoded'
if not D.exists():shutil.copytree(BASE,D,ignore=shutil.ignore_patterns('build','dist'))
path=D/'smali/com/google/android/youtube/core/async/UserAuthorizer.smali'
s=(BASE/'smali/com/google/android/youtube/core/async/UserAuthorizer.smali').read_text()
def method(signature,body):
 global s
 pat=r'(?m)^\.method '+re.escape(signature)+r'\n.*?^\.end method'
 s,n=re.subn(pat,'.method '+signature+'\n'+body+'\n.end method',s,flags=re.S|re.M)
 assert n==1,(signature,n)
method('private a(Landroid/app/Activity;Ljava/lang/String;Landroid/os/Bundle;Lorg/retrobridge/youtube2012/core/async/bc;)V','''    .locals 1
    iget-object v0, p0, Lorg/retrobridge/youtube2012/core/async/UserAuthorizer;->h:Ljava/util/List;
    invoke-interface {v0, p4}, Ljava/util/List;->add(Ljava/lang/Object;)Z
    invoke-static {p1, p0}, Lorg/retrobridge/youtube2012/core/async/PixelSignIn;->connect(Landroid/app/Activity;Lorg/retrobridge/youtube2012/core/async/UserAuthorizer;)V
    return-void''')
method('public final declared-synchronized b()V','''    .locals 0
    # The selected account lives on Pixel, not in S2 AccountManager.
    return-void''')
method('public final declared-synchronized a(Lorg/retrobridge/youtube2012/core/async/bc;)V','''    .locals 5
    invoke-direct {p0}, Lorg/retrobridge/youtube2012/core/async/UserAuthorizer;->e()Ljava/lang/String;
    move-result-object v1
    if-eqz v1, :missing
    invoke-direct {p0}, Lorg/retrobridge/youtube2012/core/async/UserAuthorizer;->f()Ljava/lang/String;
    move-result-object v2
    new-instance v0, Lorg/retrobridge/youtube2012/core/model/UserAuth;
    sget-object v3, Lorg/retrobridge/youtube2012/core/model/UserAuth$AuthMethod;->CLIENTLOGIN:Lorg/retrobridge/youtube2012/core/model/UserAuth$AuthMethod;
    const-string v4, "paired-pixel"
    invoke-direct {v0, v1, v2, v3, v4}, Lorg/retrobridge/youtube2012/core/model/UserAuth;-><init>(Ljava/lang/String;Ljava/lang/String;Lorg/retrobridge/youtube2012/core/model/UserAuth$AuthMethod;Ljava/lang/String;)V
    invoke-interface {p1, v0}, Lorg/retrobridge/youtube2012/core/async/bc;->a(Lorg/retrobridge/youtube2012/core/model/UserAuth;)V
    return-void
    :missing
    new-instance v0, Ljava/io/IOException;
    const-string v1, "Sign in through Pixel"
    invoke-direct {v0, v1}, Ljava/io/IOException;-><init>(Ljava/lang/String;)V
    invoke-interface {p1, v0}, Lorg/retrobridge/youtube2012/core/async/bc;->a(Ljava/lang/Exception;)V
    return-void''')
path.write_text(s)

# Keep only our loopback relay on HTTP; external hosts retain HTTPS upgrades.
request_path=D/'smali/com/google/android/youtube/core/async/GDataRequest.smali'
request=(BASE/'smali/com/google/android/youtube/core/async/GDataRequest.smali').read_text()
needle='.method private static b(Landroid/net/Uri;)Landroid/net/Uri;\n    .locals 2'
assert request.count(needle)==1
request=request.replace(needle,needle+'''\n
    const-string v0, "127.0.0.1:8768"
    invoke-virtual {p0}, Landroid/net/Uri;->getAuthority()Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :external_host
    return-object p0
    :external_host
''')
# Keep action identity out of aj.e: that map participates in the native read-cache key.
request=request.replace('# direct methods','.field public galaxyActionId:Ljava/lang/String;\n\n# direct methods',1)
needle='.method private constructor <init>(Landroid/net/Uri;Lorg/retrobridge/youtube2012/core/model/UserAuth;Ljava/util/Map;[B)V\n    .locals 0'
assert request.count(needle)==1
request=request.replace(needle,needle.replace('.locals 0','.locals 1'))
needle='    invoke-direct {p0, p1, p2, p3, p4}, Lorg/retrobridge/youtube2012/core/async/aj;-><init>(Landroid/net/Uri;Lorg/retrobridge/youtube2012/core/model/UserAuth;Ljava/util/Map;[B)V'
request=request.replace(needle,needle+"\n    invoke-static {}, Ljava/util/UUID;->randomUUID()Ljava/util/UUID;\n    move-result-object v0\n    invoke-virtual {v0}, Ljava/util/UUID;->toString()Ljava/lang/String;\n    move-result-object v0\n    iput-object v0, p0, Lorg/retrobridge/youtube2012/core/async/GDataRequest;->galaxyActionId:Ljava/lang/String;")
# Request copies made for transport/auth retries retain the same durable action ID.
for signature,source in [('public static a(Landroid/net/Uri;Lorg/retrobridge/youtube2012/core/async/GDataRequest;)Lorg/retrobridge/youtube2012/core/async/GDataRequest;','p1'),('public final a(Lorg/retrobridge/youtube2012/core/model/UserAuth;)Lorg/retrobridge/youtube2012/core/async/GDataRequest;','p0')]:
 pattern=r'(?m)^\.method '+re.escape(signature)+r'\n.*?^\.end method'
 m=re.search(pattern,request,flags=re.S|re.M);assert m,signature
 block=m.group();assert block.count('    return-object v0')==1
 block=block.replace('    return-object v0','    iget-object v1, '+source+', Lorg/retrobridge/youtube2012/core/async/GDataRequest;->galaxyActionId:Ljava/lang/String;\n    iput-object v1, v0, Lorg/retrobridge/youtube2012/core/async/GDataRequest;->galaxyActionId:Ljava/lang/String;\n    return-object v0')
 request=request[:m.start()]+block+request[m.end():]
request_path.write_text(request)
# Set protected action headers only after the HTTP method is known.
converter_path=D/'smali/com/google/android/youtube/core/converter/http/bo.smali'
converter=(BASE/'smali/com/google/android/youtube/core/converter/http/bo.smali').read_text()
needle='    move-result-object v2\n\n    .line 50'
assert converter.count(needle)==1
converter=converter.replace(needle,'''    move-result-object v2
    instance-of v0, p1, Lorg/retrobridge/youtube2012/core/async/GDataRequest;
    if-eqz v0, :not_galaxy_action
    move-object v0, p1
    check-cast v0, Lorg/retrobridge/youtube2012/core/async/GDataRequest;
    invoke-static {v2, v0}, Lorg/retrobridge/youtube2012/core/async/PixelSignIn;->actionHeaders(Lorg/apache/http/client/methods/HttpUriRequest;Lorg/retrobridge/youtube2012/core/async/GDataRequest;)V
    :not_galaxy_action
    .line 50''')
converter_path.write_text(converter)
# Rewrite only known public thumbnail URLs at request time, including URLs in
# existing cached models. Apache HttpClient ignores the old system Wi-Fi proxy.
thumb_path=D/'smali/com/google/android/youtube/core/converter/http/HttpMethod$2.smali'
thumb=(BASE/'smali/com/google/android/youtube/core/converter/http/HttpMethod$2.smali').read_text()
needle='    invoke-direct {v0, v1}, Lorg/apache/http/client/methods/HttpGet;-><init>(Ljava/lang/String;)V'
assert thumb.count(needle)==1
thumb=thumb.replace(needle,'    invoke-static {v1}, Lorg/retrobridge/youtube2012/core/async/PixelSignIn;->thumbnailUrl(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v1\n'+needle)
thumb_path.write_text(thumb)
# Restore the normal logging after the parser diagnosis.
shutil.copyfile(BASE/'smali/com/google/android/youtube/core/async/e.smali',D/'smali/com/google/android/youtube/core/async/e.smali')
# Some current channels have no profile image. Preserve the original placeholder.
avatar_path=D/'smali/com/google/android/youtube/app/honeycomb/phone/aa.smali'
avatar=(BASE/'smali/com/google/android/youtube/app/honeycomb/phone/aa.smali').read_text()
needle='    invoke-interface {v0, v1, p3}, Lorg/retrobridge/youtube2012/core/b/ag;->a(Landroid/net/Uri;Lorg/retrobridge/youtube2012/core/async/l;)V'
assert avatar.count(needle)==1
avatar=avatar.replace(needle,'    if-eqz v1, :no_avatar\n'+needle+'\n    :no_avatar')
avatar_path.write_text(avatar)

# Keep the old folders, but stop unavailable cloud lists from timing out forever.
library_path=D/'smali/com/google/android/youtube/app/honeycomb/phone/a.smali'
library=(BASE/'smali/com/google/android/youtube/app/honeycomb/phone/a.smali').read_text()
needle='.method public final onClick(Landroid/view/View;)V\n    .locals 1'
assert library.count(needle)==1
library=library.replace(needle,needle+'''\n
    invoke-static {p1}, Lorg/retrobridge/youtube2012/core/async/PixelSignIn;->unavailableLibrary(Landroid/view/View;)Z
    move-result v0
    if-eqz v0, :supported_library
    return-void
    :supported_library
''')
library_path.write_text(library)
strings=D/'res/values/strings.xml';labels=strings.read_text()
labels=re.sub(r'(<string name="channel_favorites">).*?(</string>)',r'\g<1>Liked videos\2',labels)
for name,url in {
 'uri_help':'https://support.google.com/youtube/',
 'uri_contextual_help':'https://support.google.com/youtube/',
 'uri_mobile_terms':'https://policies.google.com/terms',
 'uri_youtube_terms':'https://www.youtube.com/static?template=terms',
 'uri_mobile_privacy':'https://policies.google.com/privacy',
 'uri_youtube_privacy':'https://policies.google.com/privacy',
}.items():
 labels=re.sub(r'(<string name="'+name+r'">).*?(</string>)',lambda m:m.group(1)+url+m.group(2),labels)
strings.write_text(labels)
# Keep the original channel tabs with accurate labels and unavailable states.
for name,value in {'galaxy_channel_likes_unavailable':'This channel does not share its liked videos here.','galaxy_recent_uploads':'Recent uploads'}.items():
 if 'name="'+name+'"' not in labels:labels=labels.replace('</resources>','    <string name="'+name+'">'+value+'</string>\n</resources>')
strings.write_text(labels)
for layout in D.glob('res/layout*/channel_activity.xml'):
 text=layout.read_text().replace('yt:textWhenEmpty="@string/no_favorites_found"','yt:textWhenEmpty="@string/galaxy_channel_likes_unavailable"').replace('android:tag="@string/channel_activity"','android:tag="@string/galaxy_recent_uploads"')
 layout.write_text(text)

player_path=D/'smali/com/google/android/youtube/core/player/bi.smali'
player=(BASE/'smali/com/google/android/youtube/core/player/bi.smali').read_text()
release='    invoke-virtual {v0}, Landroid/media/MediaPlayer;->release()V'
assert player.count(release)==1
player_path.write_text(player.replace(release,'    invoke-static {v0}, Lorg/retrobridge/youtube2012/core/async/PixelSignIn;->releasePlayer(Landroid/media/MediaPlayer;)V'))
yml=D/'apktool.yml';v=yml.read_text();v=re.sub(r'versionCode: \d+','versionCode: 4031',v);v=re.sub(r'versionName: .*','versionName: 4.0.23-pixel.10',v);yml.write_text(v)
stub=P/'stubs';stub.mkdir(exist_ok=True)
(stub/'UserAuthorizer.java').write_text('package org.retrobridge.youtube2012.core.async; public class UserAuthorizer { protected void a(android.app.Activity a,org.retrobridge.youtube2012.core.model.UserAuth u){} void a(Exception e){} }')
(stub/'GDataRequest.java').write_text('package org.retrobridge.youtube2012.core.async; public class GDataRequest {public String galaxyActionId; public org.retrobridge.youtube2012.core.model.UserAuth d;}')
(stub/'UserAuth.java').write_text('package org.retrobridge.youtube2012.core.model; public class UserAuth {public String b; public enum AuthMethod {CLIENTLOGIN,OAUTH2} public UserAuth(String a,String b,AuthMethod c,String d){} }')
def run(args):
 r=subprocess.run(list(map(str,args)),capture_output=True,text=True,timeout=240)
 if r.returncode:raise RuntimeError(r.stdout+r.stderr)
 return r.stdout
classes=P/'classes';classes.mkdir(exist_ok=True);dex=P/'dex';dex.mkdir(exist_ok=True)
android=W/'telegram-s2-client/sdk/platform/android-4.0.4/android.jar'
run([J/'javac.exe','-source','8','-target','8','-encoding','UTF-8','-classpath',android,'-d',classes,*stub.glob('*.java'),P/'PixelSignIn.java'])
run([J/'java.exe','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','13','--lib',android,'--output',dex,*classes.rglob('*.class')])
dis=P/'helper-smali'
run([J/'java.exe','-jar',W/'call-feasibility/baksmali-2.5.2.jar','d',dex/'classes.dex','-o',dis])
for f in dis.rglob('PixelSignIn*.smali'):
 dest=D/'smali'/f.relative_to(dis);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(f,dest)
run([J/'java.exe','-jar',W/'peggle-debug-tools/apktool.jar','b',D,'-o',P/'unsigned.apk'])
run([BT/'zipalign.exe','-f','4',P/'unsigned.apk',P/'aligned.apk'])
out=P/'YouTube-2012-Google.apk';key=W/'signing-private'
run([J/'java.exe','-jar',BT/'lib/apksigner.jar','sign','--ks',key/'signing.p12','--ks-pass','file:'+str(key/'signing-password.txt'),'--min-sdk-version','13','--out',out,P/'aligned.apk'])
run([J/'java.exe','-jar',BT/'lib/apksigner.jar','verify','--min-sdk-version','13',out])
receipt={'apk_sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'versionCode':4031,'device_tested':False}
(P/'build-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt))
