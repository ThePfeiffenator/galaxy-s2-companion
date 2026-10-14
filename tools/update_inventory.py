"""Refresh source hashes after reviewing a public source change; no device access."""
from pathlib import Path
import hashlib
import json

ROOT=Path(__file__).resolve().parents[1]
rows=[]
for path in sorted((ROOT/'source').rglob('*')):
    if not path.is_file() or '__pycache__' in path.parts or path.suffix in ('.pyc','.pyo'): continue
    # Hash actual Git-oriented LF bytes, without rewriting or accepting CRLF drift.
    data=path.read_bytes()
    if path.suffix != '.png' and b'\r\n' in data:
        raise SystemExit('Normalize this text file to LF first: ' + str(path.relative_to(ROOT)))
    rows.append({'path':path.relative_to(ROOT).as_posix(), 'sha256':hashlib.sha256(data).hexdigest()})
(ROOT/'source-inventory.json').write_text(json.dumps(rows,indent=2)+'\n',encoding='utf-8',newline='\n')
print('Updated',len(rows),'source hashes. Run privacy_audit.py before publishing.')
