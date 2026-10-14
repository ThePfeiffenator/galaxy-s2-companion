"""Remove an embedded one-entry android table which shadows the real framework in apktool."""
from pathlib import Path
import struct, zipfile
p=Path(__file__).resolve().parent
with zipfile.ZipFile(p/'originals/Music2.apk') as source:
    data=source.read('resources.arsc')
    kind,header,size=struct.unpack_from('<HHI',data)
    assert kind==2 and size==len(data)
    chunks=[]; offset=header; removed=0
    while offset<len(data):
        kind,head,size=struct.unpack_from('<HHI',data,offset)
        chunk=data[offset:offset+size]
        if kind==0x200 and struct.unpack_from('<I',chunk,8)[0]==1:
            removed+=1
        else: chunks.append(chunk)
        offset+=size
    assert removed==1
    result=bytearray(data[:header]+b''.join(chunks))
    struct.pack_into('<I',result,4,len(result))
    struct.pack_into('<I',result,8,struct.unpack_from('<I',data,8)[0]-1)
    with zipfile.ZipFile(p/'music-decode-input.apk','w') as dest:
        for name in source.namelist():
            if not name.startswith('META-INF/'):
                dest.writestr(name,bytes(result) if name=='resources.arsc' else source.read(name))
print('Prepared resource table for framework-aware decoding; original APK unchanged.')
