"""Provision app pairing files on explicitly named USB devices. No root/system edits."""
from pathlib import Path
import argparse, base64, json, os, secrets, subprocess
from local_config import load
from configure import mappings
ROOT=Path(__file__).resolve().parents[1]
def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('feature',choices=['services','telegram','music','rcs','media'])
 p.add_argument('--config',type=Path,default=ROOT/'local.json')
 p.add_argument('--apply',action='store_true',help='Write private pairing files to the specified devices')
 a=p.parse_args();config=load(a.config);mappings(config)
 if config['pixel_serial']==config['s2_serial']:p.error('The two phones must have different serials')
 if not a.apply:p.error('Review docs first; --apply is required for device writes')
 adb=str(Path(os.environ['ANDROID_SDK_ROOT'])/'platform-tools/adb.exe')
 def run(serial,*args,data=None):
  return subprocess.run([adb,'-s',serial,*args],input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True).stdout
 def preflight(serial,pkg,name,data):
  if run(serial,'get-state').strip()!=b'device':raise RuntimeError('USB device not ready')
  result=subprocess.run([adb,'-s',serial,'shell','run-as',pkg,'cat','files/'+name],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
  got=result.stdout.replace(b'\r',b'').strip();error=(result.stdout+result.stderr).replace(b'\r',b'').strip()
  if got==data.strip():return
  # Only an exact missing-file result permits first provisioning. Permission,
  # missing-package and malformed-existing-file failures must not become rekeying.
  if error in {('cat: files/'+name+': No such file or directory').encode(),('cat: files/'+name+': No such file or directory\n').encode()}:return
  raise RuntimeError('Existing private file differs or cannot be checked; preserved. Use the installed app pairing or recover the original local key.')
 def write(serial,pkg,name,data):
  if run(serial,'get-state').strip()!=b'device':raise RuntimeError('USB device not ready')
  # Names and packages below are constants. Private data goes to stdin, not command arguments/logs.
  # S2 adbd predates exec-out; line-ending normalization is safe for these text files only.
  run(serial,'shell','run-as',pkg,'mkdir','-p','files')
  run(serial,'shell','run-as',pkg,'dd','of=files/'+name,'bs='+str(len(data)),'count=1',data=data)
  run(serial,'shell','run-as',pkg,'chmod','600','files/'+name)
  got=run(serial,'shell','run-as',pkg,'cat','files/'+name).replace(b'\r',b'')
  if got.rstrip(b'\n')!=data.rstrip(b'\n'):raise RuntimeError('Private file verification failed')
 private=ROOT/'.local/pairing';private.mkdir(parents=True,exist_ok=True)
 keyfile=private/(a.feature+'.key')
 if not keyfile.exists():
  keyfile.write_bytes((base64.b64encode(secrets.token_bytes(32)) if a.feature=='telegram' else secrets.token_hex(32).encode())+b'\n')
  os.chmod(keyfile,0o600)
 data=keyfile.read_bytes()
 writes=[]
 if a.feature in ('services','telegram'):
  stem='galaxyservices' if a.feature=='services' else 'galaxychat'
  name='link.key' if a.feature=='services' else 'pair-key.txt'
  for role in ('pixel','s2'):writes.append((config[role+'_serial'],'org.retrobridge.'+stem+'.'+role,name,data))
  if a.feature=='services':writes.append((config['s2_serial'],'org.retrobridge.galaxyservices.s2','network.txt',config['wifi_ssid'].encode()))
 elif a.feature=='media':
  for role in ('pixel','s2'):writes.append((config[role+'_serial'],'org.retrobridge.galaxyservices.'+role,'media-relay.key',data))
 elif a.feature=='rcs':
  writes.append((config['pixel_serial'],'org.retrobridge.galaxyservices.pixel','rcs-relay.key',data))
  writes.append((config['s2_serial'],'org.retrobridge.callprobe','rcs-relay.key',data))
 else:
  writes.append((config['pixel_serial'],'org.retrobridge.galaxyservices.pixel','music-relay.key',data))
  print('Copy .local/pairing/music.key into modern-phone Termux ~/galaxy-music/relay.key through a private, authorized transfer.')
 for args in writes:preflight(*args)
 for args in writes:write(*args)
 print('Pairing files verified; key contents were not printed. Keep .local private.')
if __name__=='__main__':main()
