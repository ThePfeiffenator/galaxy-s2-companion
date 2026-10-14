"""Generate fixed install pins from explicitly selected, finalized local artifacts.
No device operations. Restore never consumes this generated artifact table.
"""
import argparse,hashlib,json,pathlib,re
P=pathlib.Path(__file__).resolve().parent
p=argparse.ArgumentParser()
for arg in ['launcher','worker','identity','config']:p.add_argument('--'+arg,required=True,type=pathlib.Path)
a=p.parse_args()
def read(file):
 data=file.read_bytes()
 if not data or len(data)>1024*1024:raise SystemExit('Invalid artifact size: '+str(file))
 return data,hashlib.sha256(data).hexdigest()
items={key:read(getattr(a,key)) for key in ['launcher','worker','identity','config']}
raw=items['config'][0]
if b'\r' in raw or b'\x00' in raw or not raw.endswith(b'\n'):raise SystemExit('Config must use LF text with final newline')
lines=raw.decode('ascii').splitlines();values={}
for line in lines:
 if not re.fullmatch(r'[a-z_][a-z0-9_]*=[!-~]+',line):raise SystemExit('Malformed config')
 k,v=line.split('=',1)
 if k in values:raise SystemExit('Duplicate config field')
 values[k]=v
expected={'version':'1','device':'SPH-D710','build':'IMM76I.FL24','app_uid':'@CALL_APP_UID@','app_cert_sha256':'@APP_SIGNER_SHA256@','identity_jar_path':'/system/framework/galaxy-companion-identity.jar','identity_jar_sha256':items['identity'][1],'launcher_path':'/system/bin/rild','launcher_sha256':items['launcher'][1],'worker_path':'/system/bin/galaxy-companion-ril','worker_sha256':items['worker'][1]}
if set(values)!=set(expected)|{'app_apk_path','app_apk_sha256'}:raise SystemExit('Wrong config key set')
for k,v in expected.items():
 if values[k]!=v:raise SystemExit('Config mismatch: '+k)
if not re.fullmatch(r'/data/app/org\.retrobridge\.callprobe-[12]\.apk',values['app_apk_path']) or not re.fullmatch(r'[0-9a-f]{64}',values['app_apk_sha256']):raise SystemExit('Invalid installed app identity')
if items['identity'][1]!='@IDENTITY_JAR_SHA256@':raise SystemExit('Identity verifier not reviewed version')
stage='/data/local/galaxy-companion-stage/'
definitions=[('worker','galaxy-companion-ril','/system/bin/galaxy-companion-ril',0o755,0),('identity','galaxy-companion-identity.jar','/system/framework/galaxy-companion-identity.jar',0o644,0),('config','production.conf','/system/etc/galaxy-companion.conf',0o600,0),('launcher','galaxy-companion-launcher','/system/bin/rild',0o755,2000)]
rows=[];receipt=[]
for key,name,target,mode,gid in definitions:
 data,sha=items[key];rows.append(' {"%s","%s","%s",%d,0%o,0,%d},'%(stage+name,target,sha,len(data),mode,gid));receipt.append(dict(name=key,local=str(getattr(a,key).resolve()),source=stage+name,target=target,sha256=sha,size=len(data),mode=oct(mode),uid=0,gid=gid))
(P/'install_manifest.h').write_text('/* Generated only from explicit finalized files. */\n#define INSTALL_MANIFEST_READY 1\nstatic const Artifact artifacts[4]={\n'+'\n'.join(rows)+'\n};\n',newline='\n')
(P/'manifest-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print('Pinned four finalized local artifacts. Rebuild and independently review before device installation.')
