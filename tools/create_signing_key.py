"""Create a new local signing key. Never imports the author's signing identity."""
from pathlib import Path
import os, secrets, subprocess
root=Path(__file__).resolve().parents[1]/'.local/work/signing-private'
root.mkdir(parents=True,exist_ok=True)
key=root/'signing.p12';password=root/'signing-password.txt'
if key.exists() or password.exists():raise SystemExit('Signing files already exist; refusing to replace them.')
password.write_text(secrets.token_urlsafe(36)+'\n')
os.chmod(password,0o600)
try:
 subprocess.run([str(Path(os.environ['JAVA_HOME'])/'bin/keytool.exe'),'-genkeypair','-keystore',str(key),'-storetype','PKCS12','-storepass:file',str(password),'-keypass:file',str(password),'-alias','retrobridge','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=Personal Retro Phone Bridge'],check=True)
except Exception:
 print('Key creation failed. Inspect the local signing files before retrying.');raise
print('New signing key saved privately. Back up both files outside the repository.')
