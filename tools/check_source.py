"""Parse public source and check local documentation links; no downloads/devices."""
from pathlib import Path
import ast,json,re,subprocess,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
def main():
 counts={'python':0,'javascript':0,'xml':0,'json':0,'local_links':0}
 for p in sorted(ROOT.rglob('*')):
  if not p.is_file() or any(x in {'.local','.git','__pycache__'} for x in p.relative_to(ROOT).parts) or p.name=='local.json':continue
  if p.suffix=='.py':ast.parse(p.read_text(encoding='utf-8-sig'));counts['python']+=1
  elif p.suffix in {'.js','.cjs'}:
   subprocess.run(['node','--check',str(p)],check=True,capture_output=True);counts['javascript']+=1
  elif p.suffix=='.xml':ET.parse(p);counts['xml']+=1
  elif p.suffix=='.json':json.loads(p.read_text(encoding='utf-8'));counts['json']+=1
  elif p.suffix=='.md':
   for target in re.findall(r'\]\(([^)]+)\)',p.read_text(encoding='utf-8')):
    if '://' in target or target.startswith('#'):continue
    target=target.split('#')[0]
    if not (p.parent/target).is_file():raise SystemExit('Missing local link in '+str(p.relative_to(ROOT))+': '+target)
    counts['local_links']+=1
 print(json.dumps(counts,sort_keys=True))
 print('PASS source parsing and local links. Not a build, service, or phone test.')
if __name__=='__main__':main()
