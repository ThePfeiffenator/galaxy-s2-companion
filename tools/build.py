"""Run a selected local build. Does not install or access devices."""
from pathlib import Path
import argparse, os, subprocess, sys
ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT/'.local/work'
TARGETS = {
 'services': ['legacy-services/build.py'],
 'calls': ['sms-companion-v12/build.py'],
 'identity': ['stock-identity/build.py'],
 'native-calls': ['stock-sms-native/build.py'],
 'native-installer': ['stock-install/build.py'],
 'bluetooth-patch': ['stock-bluetooth-fix/build_patch.py'],
 'bluetooth-installer': ['stock-bluetooth-install/build.py'],
 'trust-store': ['legacy-services/build_trust_store.py'],
 'telegram-engine': ['telegram-pixel-engine/build_engine.py'],
 'telegram-assets': ['telegram-classic/prepare_assets.py'],
 'telegram-s2': ['telegram-s2-client/build.py'],
 'telegram-pixel': ['telegram-pixel-app/build.py'],
 'google-accounts': ['pixel-consolidation/build_google.py'],
 'gmail': ['gmail-bridge/build_s2.py'],
 'store': ['play-store-investigation/build.py'],
 'store-installer': ['play-store-investigation/installer/build.py'],
 'youtube': ['legacy-services/termux/prepare_youtube.py','legacy-services/streaming/build_youtube.py','youtube-google/build.py'],
 'rif': ['reddit-compatibility/build_rif.py'],
 'google-widget': ['google-widget/build_widget.py'],
 'music': ['gallery-music/build_music.py'],
 'launcher': ['launcher-cleanup/build.py'],
}
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('target', choices=TARGETS);a=p.parse_args()
 if not WORK.is_dir():p.error('Run tools/configure.py first')
 for name in ('JAVA_HOME','ANDROID_SDK_ROOT'):
  if not os.environ.get(name):p.error('Set '+name)
 from doctor import problems
 errors=problems(a.target)
 if errors:p.error('\n'.join(errors))
 for rel in TARGETS[a.target]:
  subprocess.run([sys.executable,str(WORK/rel)],check=True,cwd=WORK)
 print('Local build finished. This does not establish device compatibility.')
if __name__=='__main__':main()
