"""Assemble a clean modern-phone runtime from pinned source, never private caches."""
from pathlib import Path
import argparse, io, json, subprocess, tarfile
ROOT=Path(__file__).resolve().parents[1];W=ROOT/'.local/work'
COMMIT='9127347e763e97ad6cbcb88b94d5e436487ee9f7'
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('yt2009_checkout',type=Path);a=p.parse_args()
 actual=subprocess.check_output(['git','-C',str(a.yt2009_checkout),'rev-parse',COMMIT+'^{commit}'],text=True).strip()
 if actual!=COMMIT:raise SystemExit('Wrong upstream revision')
 raw=subprocess.check_output(['git','-C',str(a.yt2009_checkout),'archive',COMMIT])
 dest=ROOT/'.local/runtime';dest.mkdir(parents=True,exist_ok=True)
 overlays={
  'back/backend.js':'legacy-services/browsing/backend.js',
  'back/yt2009templates.js':'legacy-services/browsing/yt2009templates.js',
  'back/yt2009utils.js':'legacy-services/browsing/yt2009utils.js',
  'back/galaxy-stream.js':'youtube-google/galaxy-stream-fixed.js',
  'back/galaxy-audio.js':'youtube-google/galaxy-audio.js',
  'back/galaxy-browse.js':'legacy-services/browsing/galaxy-browse.js',
  'back/galaxy-reddit-media.js':'reddit-compatibility/media-v05/galaxy-reddit-media.js',
  'galaxy-init.js':'legacy-services/termux/galaxy-init.js',
 }
 data={}
 with tarfile.open(fileobj=io.BytesIO(raw)) as src:
  for member in src.getmembers():
   if member.isfile():data[member.name]=src.extractfile(member).read()
 for name,path in overlays.items():data[name]=(W/path).read_bytes()
 backend=data['back/backend.js'].decode();marker='require("./galaxy-reddit-media").register(app);'
 if marker not in backend:backend=backend.replace('const app = express();','const app = express();\n'+marker)
 audio='require("./galaxy-audio").register(app);'
 if audio not in backend:backend=backend.replace('const app = express();','const app = express();\n'+audio)
 if '/galaxy-health' not in backend:
  backend=backend.replace('const app = express();','const app = express();\napp.get("/galaxy-health",(req,res)=>res.send("Galaxy YouTube ready"));')
 data['back/backend.js']=backend.encode()
 config=dict(port=8768,env='dev',ip='127.0.0.1',useSSL=False,disableWs=True,auto_maintain=True,maintain_max_size=0.25,maintain_max_cache_size=5,max_1080=True,gdata_auth=False,file_limit=1)
 data['back/config.json']=json.dumps(config).encode()
 with tarfile.open(dest/'yt2009-pixel.tar.gz','w:gz') as out:
  for name,content in sorted(data.items()):
   info=tarfile.TarInfo('yt2009/'+name);info.size=len(content);info.mode=0o600;out.addfile(info,io.BytesIO(content))
 print('Created .local/runtime/yt2009-pixel.tar.gz from pinned upstream and public overlays.')
 # A complete, credential-free transfer bundle avoids guessing which music and
 # startup files to copy. Keep the inner archive name for setup_backend.sh.
 bundle={'yt2009-pixel.tar.gz':(dest/'yt2009-pixel.tar.gz').read_bytes()}
 for name in ('install_packages.sh','setup_backend.sh','configure_startup.sh','galaxy-youtube.sh'):
  bundle[name]=(W/'legacy-services/termux'/name).read_bytes()
 for name in ('music_albums.py','music_auth.py','music_server.py','music_playlists.py','music_signature.py',
              'music_tracks.py','music_uploads.py','upload_sabr.js','prepare_music_runtime.sh','start.sh'):
  bundle['galaxy-music/'+name]=(W/'gallery-music'/name).read_bytes()
 with tarfile.open(dest/'modern-runtime.tar.gz','w:gz') as out:
  for name,content in sorted(bundle.items()):
   info=tarfile.TarInfo(name);info.size=len(content);info.mode=0o600;out.addfile(info,io.BytesIO(content))
 print('Created .local/runtime/modern-runtime.tar.gz with all startup and music source files; no keys/accounts.')
if __name__=='__main__':main()
