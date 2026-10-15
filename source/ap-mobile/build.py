"""Build the owner's exact FL24 AP Mobile as a separate compatibility app.
No device access, downloads, original APKs or vendor resources are distributed.
"""
from pathlib import Path
import hashlib,shutil,re,subprocess,json,os,zipfile
P=Path(__file__).resolve().parent;W=P.parent;D=P/'patched'
JAVA=Path(os.environ['JAVA_HOME'])/'bin/java.exe'
BT=Path(os.environ['ANDROID_SDK_ROOT'])/'build-tools/36.1.0'
def run(args):
 r=subprocess.run(list(map(str,args)),capture_output=True,text=True,timeout=240)
 if r.returncode:raise RuntimeError(r.stdout+r.stderr)
 if 'Error occurred' in r.stderr or 'Could not resolve' in r.stderr:raise RuntimeError(r.stderr)
 return r.stdout

def clean(name):
 dest=(P/name).resolve()
 if dest.parent!=P.resolve() or name not in {'original-smali','patched','code'}:raise ValueError('Build path')
 if dest.exists():shutil.rmtree(dest)
 dest.mkdir()
 return dest

# Verify all required stock inputs before changing generated build directories.
pins=json.loads((P/'input-hashes.json').read_text())
for rel,digest in pins.items():
 f=P/'inputs'/rel
 if not f.is_file() or hashlib.sha256(f.read_bytes()).hexdigest()!=digest:
  raise SystemExit('Missing or incompatible FL24 input: '+rel)
for name in ('original-smali','patched','code'):clean(name)
run([JAVA,'-jar',W/'call-feasibility/baksmali-2.5.2.jar','deodex','--api','15',
 '-d',P/'inputs/framework','-c','twframework.odex','-o',P/'original-smali',P/'inputs/APNews.odex'])
if not list((P/'original-smali').rglob('*.smali')):raise RuntimeError('Empty deodex result')
# Compile code separately; retain the owner's original binary resources below.
(P/'code/apktool.yml').write_text('version: 3.0.3\napkFileName: code.apk\n',encoding='utf-8')
(P/'code/AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.retrobridge.apnews"><application/></manifest>',encoding='utf-8')
original='com.sec.android.widgetapp.apnews';package='org.retrobridge.apnews'
assert hashlib.sha256((P/'inputs/APNews.apk').read_bytes()).hexdigest()=='90a851811bd0da6742ec9835750c4b4b3cd999a2af5b7352a0b8fdcad84ae9c8'
actions=['Updated.result','News.updated','action.widget.news.Updated_image_each','APNewsWidgetIntents.ACTION_SERVICE_UPDATE']
def patch(text):
 text=text.replace(original,package).replace(original.replace('.','/'),package.replace('.','/'))
 text=text.replace('"ApNews"','"org.retrobridge.apnews.provider"').replace('"APNews"','"org.retrobridge.apnews.provider"').replace('content://ApNews','content://org.retrobridge.apnews.provider').replace('content://APNews','content://org.retrobridge.apnews.provider')
 text=text.replace('APNewsWidget.action.','GalaxyAPNewsWidget.action.').replace('apnews.widget.action.','galaxyapnews.widget.action.')
 for action in actions:text=text.replace('"'+action+'"','"galaxy.'+action+'"')
 return text
for source in (P/'original-smali').rglob('*.smali'):
 dest=D/'smali'/source.relative_to(P/'original-smali');dest.parent.mkdir(parents=True,exist_ok=True);s=patch(source.read_text(encoding='utf-8'))
 if source.name=='APNewsDataClient.smali':
  assert s.count('Landroid/net/Proxy;->getHost(')==6 and s.count('Landroid/net/Proxy;->getPort(')==3
  s=s.replace('Landroid/net/Proxy;->getHost(','Lorg/retrobridge/apnews/BridgeProxy;->getHost(').replace('Landroid/net/Proxy;->getPort(','Lorg/retrobridge/apnews/BridgeProxy;->getPort(')
 if source.name=='APNewsMain.smali':
  # An image can finish before the first list is initialized. Populate it instead
  # of dereferencing the null list in Samsung's original image callback.
  start=s.index('.method public updateImageData(Ljava/lang/String;)V')
  at=s.index('    .line 390',start)
  s=s[:at]+'''    iget-object v3, p0, Lorg/retrobridge/apnews/activity/apnews/APNewsMain;->mAPNewsList:Ljava/util/ArrayList;
    if-nez v3, :bridge_list_ready
    invoke-direct {p0, p1, v5}, Lorg/retrobridge/apnews/activity/apnews/APNewsMain;->fillData(Ljava/lang/String;Z)V
    return-void
    :bridge_list_ready

'''+s[at:]
 dest.write_text(s,encoding='utf-8')
bridge=D/'smali/org/retrobridge/apnews/BridgeProxy.smali';bridge.parent.mkdir(parents=True,exist_ok=True)
bridge.write_text('''.class public final Lorg/retrobridge/apnews/BridgeProxy;
.super Ljava/lang/Object;
.method public static getHost(Landroid/content/Context;)Ljava/lang/String;
 .registers 1
 const-string p0, "127.0.0.1"
 return-object p0
.end method
.method public static getPort(Landroid/content/Context;)I
 .registers 1
 const/16 p0, 0x223e
 return p0
.end method
''',encoding='utf-8')
# Keep original binary resources, including Samsung framework attributes.
import zipfile,struct,binary_xml
shutil.copytree(D/'smali',P/'code/smali',dirs_exist_ok=True)
run([JAVA,'-jar',W/'peggle-debug-tools/apktool.jar','b',P/'code','-o',P/'apnews-code-only.apk'])
with zipfile.ZipFile(P/'apnews-code-only.apk') as built:(P/'apnews-classes.dex').write_bytes(built.read('classes.dex'))
def replace_string(value):
 value=value.replace(original,package).replace('content://ApNews','content://org.retrobridge.apnews.provider').replace('content://APNews','content://org.retrobridge.apnews.provider')
 if value in ('ApNews','APNews'):return 'org.retrobridge.apnews.provider'
 if value in actions:return 'galaxy.'+value
 return value.replace('APNewsWidget.action.','GalaxyAPNewsWidget.action.').replace('apnews.widget.action.','galaxyapnews.widget.action.')
with zipfile.ZipFile(P/'inputs/APNews.apk') as src,zipfile.ZipFile(P/'apnews-unsigned.apk','w',zipfile.ZIP_DEFLATED) as dst:
 for name in src.namelist():
  if name.startswith('META-INF/') or name=='classes.dex':continue
  data=src.read(name)
  if name.endswith('.xml') and data[:2]==b'\x03\x00':data=binary_xml.patch(data,replace_string)
  if name=='resources.arsc':
   data=bytearray(data);offset=struct.unpack_from('<H',data,2)[0]
   while offset<len(data):
    kind,head,size=struct.unpack_from('<HHI',data,offset)
    if kind==0x200:
     old_name=bytes(data[offset+12:offset+268]).decode('utf-16le').split('\0')[0];assert old_name==original
     encoded=package.encode('utf-16le');data[offset+12:offset+268]=encoded+b'\0'*(256-len(encoded))
    offset+=size
  dst.writestr(name,data,compress_type=zipfile.ZIP_STORED if name=='resources.arsc' else zipfile.ZIP_DEFLATED)
 dst.write(P/'apnews-classes.dex','classes.dex')
run([BT/'zipalign.exe','-f','4',P/'apnews-unsigned.apk',P/'apnews-aligned.apk'])
key=W/'signing-private';run([JAVA,'-jar',BT/'lib/apksigner.jar','sign','--ks',key/'signing.p12','--ks-pass','file:'+str(key/'signing-password.txt'),'--min-sdk-version','15','--out',P/'apnews-companion.apk',P/'apnews-aligned.apk'])
run([JAVA,'-jar',BT/'lib/apksigner.jar','verify','--min-sdk-version','15',P/'apnews-companion.apk'])
print('AP Mobile compatibility APK:',hashlib.sha256((P/'apnews-companion.apk').read_bytes()).hexdigest())
