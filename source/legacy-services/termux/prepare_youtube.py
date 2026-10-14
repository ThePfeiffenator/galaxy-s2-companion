"""Build a separate test copy of the user's original YouTube, preserving assets."""
from pathlib import Path
import shutil,subprocess,hashlib,json,sys
P=Path(__file__).resolve().parent
W=P.parents[1]
SRC=W/'legacy-app-investigation/youtube-original-decoded'
DST=P/'youtube-candidate-r1'
verify='--verify' in sys.argv
if not verify:
 if DST.exists():raise SystemExit('Candidate already exists; refusing to replace edits')
 shutil.copytree(SRC,DST)
changed=[]
differences=[]
for p in SRC.rglob('*'):
 if not p.is_file() or 'original' in p.relative_to(SRC).parts or p.suffix not in ('.xml','.smali','.yml'):continue
 s=p.read_text(encoding='utf-8');t=s.replace('com.google.android.youtube','org.retrobridge.youtube2012').replace('com/google/android/youtube','org/retrobridge/youtube2012')
 t=t.replace('http://gdata.youtube.com/schemas/2007#live.event','http://gdata.youtube.com/schemas/2007#galaxy-vod')
 if p.name=='DeviceAuthorizer.smali':
  t=t.replace('https://www.google.com/youtube/accounts/registerDevice','http://127.0.0.1:8768/youtube/accounts/registerDevice').replace('AES/ECB/PKCS5Padding','AES/ECB/ZeroBytePadding')
 if p.name=='GDataRequestFactory.smali':
  t=t.replace('"gdata.youtube.com"','"127.0.0.1:8768"').replace('const-string v1, "https"','const-string v1, "http"')
  t=t.replace('->authority(Ljava/lang/String;)', '->encodedAuthority(Ljava/lang/String;)')
 q=DST/p.relative_to(SRC)
 if verify:
  if q.read_text(encoding='utf-8')!=t:differences.append(str(p.relative_to(SRC)))
 elif s!=t:q.write_text(t,encoding='utf-8')
 if s!=t:changed.append(str(p.relative_to(SRC)))
if verify:
 print(json.dumps({'source_reproduction_matches':not differences,'differences':differences,'changed_files':len(changed)}))
 raise SystemExit(1 if differences else 0)
(P/'youtube-patch-receipt.json').write_text(json.dumps({'original_sha256':hashlib.sha256((W/'legacy-app-investigation/originals/YouTube.apk').read_bytes()).hexdigest(),'candidate_package':'org.retrobridge.youtube2012','changed':changed,'installed':False},indent=2),encoding='utf-8')
print('Prepared separate YouTube candidate; changed files:',len(changed))
