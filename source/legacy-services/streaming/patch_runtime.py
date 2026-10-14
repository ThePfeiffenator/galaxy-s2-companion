"""Reproducible overlay; upstream sources and the known-good v0.1 archive stay intact."""
from pathlib import Path
import re
P=Path(__file__).resolve().parent
def patch_backend(text):
 marker='require("./galaxy-stream").register(app);'
 if marker in text:return text
 assert text.count('const app = express();')==1
 return text.replace('const app = express();','const app = express();\n'+marker)
def patch_templates(text):
 for name in ('streamHQ','streamHD','streamStd'):
  pattern=r'let '+name+r' = `[^`]+`'
  assert len(re.findall(pattern,text))==1,name
  text=re.sub(pattern,'let '+name+' = `http://${config.ip}:${config.port}/sabr_hls_adapter/galaxy/${id}/index.m3u8`',text)
 return text
def patch(rel,data):
 if rel=='back/backend.js':return patch_backend(data.decode()).encode()
 if rel=='back/yt2009templates.js':return patch_templates(data.decode()).encode()
 return data
