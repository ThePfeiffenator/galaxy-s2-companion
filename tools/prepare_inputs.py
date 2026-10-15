"""Copy your local inputs and decode original APKs. No downloads/device operations."""
from pathlib import Path
import argparse, os, shutil, subprocess, sys, json, hashlib
ROOT=Path(__file__).resolve().parents[1];W=ROOT/'.local/work'
PLAIN={
 'ap-mobile':'ap-mobile/inputs/APNews.apk',
 'ap-mobile-odex':'ap-mobile/inputs/APNews.odex',
 'android15':'telegram-s2-client/sdk/platform/android-4.0.4/android.jar',
 'apktool':'peggle-debug-tools/apktool.jar',
 'baksmali':'call-feasibility/baksmali-2.5.2.jar',
 'libc':'peggle-debug-tools/symbols/system/lib/libc.so',
 'libandroid_runtime':'peggle-debug-tools/symbols/system/lib/libandroid_runtime.so',
 'rild':'call-feasibility/stock-original/rild',
 'libril':'call-feasibility/stock-original/libril.so',
 'tdlib':'telegram-pixel-engine/candidate/jniLibs/arm64-v8a/libtdjsonjava.so',
}
APKS={
 'gmail':('gmail-investigation/Gmail-original.apk','gmail-investigation/decoded'),
 'store':('play-store-investigation/Play-Store-original.apk','play-store-investigation/decoded'),
 'youtube':('legacy-app-investigation/originals/YouTube.apk','legacy-app-investigation/youtube-original-decoded'),
 'rif':('reddit-compatibility/originals/reddit-is-fun-3.6.16.apk','reddit-compatibility/decoded'),
 'google-widget':('google-widget/GoogleQuickSearchBox-original.apk','google-widget/decoded'),
 'music':('gallery-music/originals/Music2.apk','gallery-music/music-clean'),
 'telegram-classic':('telegram-classic/reference.apk','telegram-classic/reference-decoded'),
}
def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('kind',choices=list(PLAIN)+list(APKS)+['framework','bouncycastle','ap-mobile-framework']);p.add_argument('file',type=Path);a=p.parse_args()
 if not W.is_dir():p.error('Run configure.py first')
 if a.kind=='ap-mobile-framework':
  if not a.file.is_dir():p.error('Expected the original firmware framework directory')
  pins=json.loads((ROOT/'source/ap-mobile/input-hashes.json').read_text(encoding='utf-8'))
  copies=[]
  for rel,digest in pins.items():
   if not rel.startswith('framework/'):continue
   src=a.file/Path(rel).name;dest=W/'ap-mobile/inputs'/rel
   if not src.is_file() or hashlib.sha256(src.read_bytes()).hexdigest()!=digest:p.error('Missing or incompatible FL24 input: '+Path(rel).name)
   if dest.exists() and dest.read_bytes()!=src.read_bytes():p.error('Different existing input preserved: '+rel)
   copies.append((src,dest))
  for src,dest in copies:dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(src,dest)
  print('Verified and staged',len(copies),'original framework inputs. No device operations.');return
 if not a.file.is_file():p.error('Input file does not exist')
 def copy(rel):
  dest=W/rel;dest.parent.mkdir(parents=True,exist_ok=True)
  if dest.exists() and dest.read_bytes()!=a.file.read_bytes():raise SystemExit('Existing input differs; preserved: '+rel)
  shutil.copyfile(a.file,dest);return dest
 if a.kind in PLAIN:copy(PLAIN[a.kind])
 elif a.kind=='bouncycastle':
  if a.file.name not in [n+'-jdk15to18-1.86.jar' for n in ('bcpkix','bcprov','bctls','bcutil')]:p.error('Expected pinned Bouncy Castle 1.86 jar')
  copy('legacy-services/lib/'+a.file.name)

 else:
  java=str(Path(os.environ['JAVA_HOME'])/'bin/java.exe');tool=W/'peggle-debug-tools/apktool.jar'
  framework=W/'peggle-debug-tools/framework';framework.mkdir(exist_ok=True)
  if a.kind=='framework':subprocess.run([java,'-jar',str(tool),'if','-p',str(framework),str(a.file.resolve())],check=True)
  else:
   src,out=APKS[a.kind];apk=copy(src);dest=W/out
   if dest.exists():raise SystemExit('Decoded directory exists; refusing to overwrite local edits: '+out)
   if a.kind=='music':
    subprocess.run([sys.executable,str(W/'gallery-music/prepare_music_decode.py')],check=True)
    apk=W/'gallery-music/music-decode-input.apk'
   subprocess.run([java,'-jar',str(tool),'d','-p',str(framework),str(apk),'-o',str(dest)],check=True)
 print('Prepared local input. Nothing installed on either phone.')
if __name__=='__main__':main()
