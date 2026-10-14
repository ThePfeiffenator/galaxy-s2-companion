"""Fetch only the four pinned open-source TLS jars from Maven Central."""
from pathlib import Path
import hashlib, urllib.request
ROOT=Path(__file__).resolve().parents[1]/'.local/work/legacy-services/lib'
PINS={
 'bcpkix':'cf70d32101e53c3f92d55ba5c5f1329af51a9a44057fd21e81e20cfd6cd64953',
 'bcprov':'fc50334d4d87b4272e72fa95ca748ead05bdef02ef760a5b13f64ffee317cd81',
 'bctls':'c0dbcd24ceeb78a263661a38d243a3c82620f002a41f49c5399a9635764a963a',
 'bcutil':'e72ef8a0fcad295207529b268ee02eb72acc7c83ec2e3a9df90cbed9380e3990',
}
def main():
 ROOT.mkdir(parents=True,exist_ok=True)
 for name,pin in PINS.items():
  artifact=name+'-jdk15to18';file=artifact+'-1.86.jar';dest=ROOT/file
  data=dest.read_bytes() if dest.exists() else urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/bouncycastle/'+artifact+'/1.86/'+file,timeout=60).read()
  if hashlib.sha256(data).hexdigest()!=pin:raise SystemExit('Dependency hash mismatch: '+file)
  dest.write_bytes(data);print('Verified',file)
if __name__=='__main__':main()
