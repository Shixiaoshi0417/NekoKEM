"""Check that the EXE embeds the shared Android-derived ICO payloads."""
from pathlib import Path
import hashlib,struct,sys
exe=Path(sys.argv[1]).read_bytes();ico=Path(sys.argv[2]).read_bytes()
def word(data,offset):return struct.unpack_from('<H',data,offset)[0]
def dword(data,offset):return struct.unpack_from('<I',data,offset)[0]
pe=dword(exe,0x3c);optional=pe+24;sections=optional+word(exe,pe+20)
def file_offset(rva):
 for i in range(word(exe,pe+6)):
  section=sections+i*40;virtual=dword(exe,section+12)
  if virtual<=rva<virtual+max(dword(exe,section+8),dword(exe,section+16)):
   return dword(exe,section+20)+rva-virtual
 raise AssertionError('Unmapped resource RVA')
root=file_offset(dword(exe,optional+112+16))
def entries(relative):
 directory=root+relative
 count=word(exe,directory+12)+word(exe,directory+14)
 return [struct.unpack_from('<II',exe,directory+16+i*8) for i in range(count)]
icons=[]
for kind,branch in entries(0):
 if kind!=3:continue
 for _,identity in entries(branch&0x7fffffff):
  for _,language in entries(identity&0x7fffffff):
   data=root+(language&0x7fffffff)
   start=file_offset(dword(exe,data));size=dword(exe,data+4)
   icons.append(hashlib.sha256(exe[start:start+size]).hexdigest())
expected=[]
for i in range(word(ico,4)):
 size=dword(ico,6+i*16+8);start=dword(ico,6+i*16+12)
 expected.append(hashlib.sha256(ico[start:start+size]).hexdigest())
assert expected and set(expected).issubset(icons),'Embedded icon differs from Android-derived ICO'
print('All shared Android icon payloads verified in EXE')
