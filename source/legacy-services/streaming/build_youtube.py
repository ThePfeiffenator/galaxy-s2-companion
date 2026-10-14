import os
from pathlib import Path
import shutil,subprocess,hashlib,json
P=Path(__file__).resolve().parent
base=P.parent/'termux/youtube-candidate-r1'
dst=P/'youtube-candidate'
if not dst.exists():shutil.copytree(base,dst,ignore=shutil.ignore_patterns('build','dist'))
rel=Path('smali/com/google/android/youtube/core/player/bi.smali')
s=(base/rel).read_text(encoding='utf-8')
begin=s.index('.method private l()Z')
end=s.index('.end method',begin)+len('.end method')
new=''' .method private l()Z
    .locals 2
    iget-boolean v0, p0, Lorg/retrobridge/youtube2012/core/player/bi;->u:Z
    if-eqz v0, :not_ready
    iget-boolean v0, p0, Lorg/retrobridge/youtube2012/core/player/bi;->s:Z
    if-nez v0, :ready
    # Android 4.0 NuPlayer discovers dimensions only after start().
    # Bypass that dimension gate only for our paired Pixel's HLS origin.
    iget-object v0, p0, Lorg/retrobridge/youtube2012/core/player/bi;->C:Lorg/retrobridge/youtube2012/core/model/Stream;
    if-eqz v0, :not_ready
    iget-object v0, v0, Lorg/retrobridge/youtube2012/core/model/Stream;->uri:Landroid/net/Uri;
    if-eqz v0, :not_ready
    invoke-virtual {v0}, Landroid/net/Uri;->toString()Ljava/lang/String;
    move-result-object v0
    const-string v1, "http://127.0.0.1:8768/sabr_hls_adapter/galaxy/"
    invoke-virtual {v0, v1}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :not_ready
    :ready
    const/4 v0, 0x1
    return v0
    :not_ready
    const/4 v0, 0x0
    return v0
.end method'''.lstrip()
(dst/rel).write_text(s[:begin]+new+s[end:],encoding='utf-8')
J=Path(os.environ['JAVA_HOME']+'/bin/java.exe')
BT=Path(os.environ['ANDROID_SDK_ROOT']+'/build-tools/36.1.0')
K=P.parents[1]/'signing-private'
def run(a):subprocess.run(list(map(str,a)),check=True)
run([J,'-jar',P.parents[1]/'peggle-debug-tools/apktool.jar','b','-p',P.parents[1]/'peggle-debug-tools/framework',dst,'-o',P/'youtube-unsigned.apk'])
run([BT/'zipalign.exe','-f','4',P/'youtube-unsigned.apk',P/'youtube-aligned.apk'])
out=P/'YouTube-2012-Streaming.apk'
run([J,'-jar',BT/'lib/apksigner.jar','sign','--ks',K/'signing.p12','--ks-pass','file:'+str(K/'signing-password.txt'),'--min-sdk-version','13','--out',out,P/'youtube-aligned.apk'])
run([J,'-jar',BT/'lib/apksigner.jar','verify','--min-sdk-version','13',out])
print(json.dumps({'apk':str(out),'sha256':hashlib.sha256(out.read_bytes()).hexdigest()}))
