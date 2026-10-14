"""Source-only publication checks. Also review every file manually; this is not a secret detector guarantee."""
from pathlib import Path
import hashlib,json,re,sys
R=Path(__file__).resolve().parents[1]
TEXT={'.md','.py','.java','.c','.h','.inc','.cjs','.js','.json','.xml','.sh','.cmd','.txt','.s','.ld','.example','.gradle','.properties'}
SPECIAL={'.gitignore','.gitattributes','LICENSE','config.example'}
PATTERNS={
 'user home path':re.compile(r'[A-Za-z]:[\\/]+Users[\\/]+[A-Za-z]'),
 'MAC address':re.compile(r'(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])'),
 'private key':re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
 'Google client secret':re.compile(r'GOCSPX-[A-Za-z0-9_-]{15,}'),
 'Google access token':re.compile(r'ya29\.[A-Za-z0-9_-]{30,}'),
 'GitHub token':re.compile(r'gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}'),
}
problems=[];files=[]
for path in sorted(R.rglob('*')):
 rel=path.relative_to(R)
 if any(p in {'.git','.local','__pycache__'} for p in rel.parts) or not path.is_file() or rel.as_posix()=='local.json':continue
 files.append(path)
 if path.suffix.lower()=='.png':
  if not rel.as_posix().startswith('source/') or not path.read_bytes().startswith(b'\x89PNG\r\n\x1a\n'):problems.append((str(rel),'unexpected image'))
  continue
 if path.suffix.lower() not in TEXT and path.name not in SPECIAL:problems.append((str(rel),'unexpected file type'));continue
 try:text=path.read_text(encoding='utf-8')
 except UnicodeError:problems.append((str(rel),'non-text file'));continue
 for label,pattern in PATTERNS.items():
  matches=pattern.findall(text)
  if label=='MAC address':matches=[m for m in matches if m.lower() not in {'00:00:00:00:00:00','02:00:00:00:00:00'}]
  if matches:problems.append((str(rel),label))
inventory=json.loads((R/'source-inventory.json').read_text())
actual={p.relative_to(R).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in files if p.is_relative_to(R/'source')}
expected={row['path']:row['sha256'] for row in inventory}
if actual!=expected:problems.append(('source-inventory.json','source set/hash mismatch; regenerate after review'))
for path,label in problems:print(label+': '+path)
print(f'Inspected {len(files)} publishable files; findings={len(problems)}. Ignored local data was not scanned for publication.')
sys.exit(bool(problems))
