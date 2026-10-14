set -eu
mkdir -p /data/data/com.termux/files/home/.termux
python - <<'PY'
from pathlib import Path
p=Path.home()/'.termux/termux.properties'
lines=p.read_text().splitlines() if p.exists() else []
lines=[line for line in lines if not line.strip().startswith('allow-external-apps=')]
p.write_text('\n'.join(lines+['allow-external-apps=true'])+'\n')
PY
chmod 600 /data/data/com.termux/files/home/.termux/termux.properties
command -v flock
cd /data/data/com.termux/files/home/yt2009
node <<'JS'
const fs=require('fs'),p='back/backend.js';let s=fs.readFileSync(p,'utf8');if(!s.includes('/galaxy-health')){s=s.replace('const app = express();','const app = express();\napp.get("/galaxy-health",(req,res)=>res.send("Galaxy YouTube ready"));');fs.writeFileSync(p,s);}
JS
echo 'Private runtime startup configured'
