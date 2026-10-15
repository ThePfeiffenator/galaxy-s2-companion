"""Replace strings without recompiling original vendor resources or changing indices."""
import struct
def u16(b,p):return struct.unpack_from('<H',b,p)[0]
def u32(b,p):return struct.unpack_from('<I',b,p)[0]
def len8(b,p):
 a=b[p];return (((a&127)<<8)|b[p+1],p+2) if a&128 else (a,p+1)
def len16(b,p):
 a=u16(b,p);return (((a&32767)<<16)|u16(b,p+2),p+4) if a&32768 else (a,p+2)
def pack8(n):return bytes([128|(n>>8),n&255]) if n>127 else bytes([n])
def pack16(n):return struct.pack('<HH',32768|(n>>16),n&65535) if n>32767 else struct.pack('<H',n)
def pool(b,replace):
 typ,header,size=struct.unpack_from('<HHI',b);assert typ==1 and len(b)==size
 count,styles,flags,start,styleStart=struct.unpack_from('<IIIII',b,8);utf8=bool(flags&256);data=bytearray();offsets=[]
 for i in range(count):
  pos=start+u32(b,header+i*4)
  if utf8:
   _,pos=len8(b,pos);length,pos=len8(b,pos);s=b[pos:pos+length].decode('utf-8')
  else:
   length,pos=len16(b,pos);s=b[pos:pos+length*2].decode('utf-16le')
  s=replace(s);offsets.append(len(data));raw=s.encode('utf-8' if utf8 else 'utf-16le');units=len(s.encode('utf-16le'))//2
  data.extend((pack8(units)+pack8(len(raw))+raw+b'\0') if utf8 else (pack16(units)+raw+b'\0\0'))
 while len(data)%4:data.append(0)
 new=bytearray(b[:start]);new[header:header+4*count]=struct.pack('<'+'I'*count,*offsets);new.extend(data)
 if styles:struct.pack_into('<I',new,24,len(new));new.extend(b[styleStart:])
 struct.pack_into('<I',new,4,len(new));return bytes(new)
def patch(data,replace):
 typ,header,size=struct.unpack_from('<HHI',data);assert typ==3 and size==len(data)
 out=bytearray(data[:header]);pos=header
 while pos<len(data):
  t,h,n=struct.unpack_from('<HHI',data,pos);assert h>=8 and n>=h and pos+n<=len(data)
  b=data[pos:pos+n];out.extend(pool(b,replace) if t==1 else b);pos+=n
 struct.pack_into('<I',out,4,len(out));return bytes(out)
