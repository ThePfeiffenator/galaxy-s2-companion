"""Synthetic schema/preview checks. Downloads one hash-pinned JSON jar; no devices."""
from pathlib import Path
import os,hashlib,subprocess,urllib.request
P=Path(__file__).resolve().parent;W=P.parent;J=Path(os.environ['JAVA_HOME'])/'bin'
jar=P/'json-20250517.jar';expected='3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796'
raw=jar.read_bytes() if jar.exists() else urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar',timeout=60).read()
if hashlib.sha256(raw).hexdigest()!=expected:raise SystemExit('JSON dependency hash mismatch')
jar.write_bytes(raw);classes=P/'classes';classes.mkdir(exist_ok=True)
sources=[P/'ContactChecks.java',P/'NoticeChecks.java',W/'legacy-services/common/ContactWire.java',W/'legacy-services/common/Wire.java',W/'telegram-s2-client/MessageNotice.java']
subprocess.run(list(map(str,[J/'javac.exe','-encoding','UTF-8','-classpath',jar,'-d',classes,*sources])),check=True)
for name in ['ContactChecks','org.retrobridge.galaxychat.s2.NoticeChecks']:
 subprocess.run([str(J/'java.exe'),'-cp',str(classes)+os.pathsep+str(jar),name],check=True)
