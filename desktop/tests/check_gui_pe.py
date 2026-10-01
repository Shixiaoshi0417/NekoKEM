"""Require x64 GUI subsystem, PE mitigations, and only Windows system DLLs."""
from pathlib import Path
import re,struct,sys
exe=Path(sys.argv[1]).read_bytes();pe=struct.unpack_from('<I',exe,0x3c)[0]
assert exe[pe:pe+4]==b'PE\0\0' and struct.unpack_from('<H',exe,pe+4)[0]==0x8664
optional=pe+24
assert struct.unpack_from('<H',exe,optional)[0]==0x20b
assert struct.unpack_from('<H',exe,optional+68)[0]==2,'GUI subsystem required'
assert struct.unpack_from('<H',exe,optional+70)[0]&0x160==0x160,'ASLR/high-entropy VA/NX required'
dlls={name.lower() for name in re.findall(r'DLL Name: (\S+)',Path(sys.argv[2]).read_text())}
system={'advapi32.dll','bcrypt.dll','comctl32.dll','comdlg32.dll','crypt32.dll','d3d11.dll','dcomp.dll','dwmapi.dll','dwrite.dll','dxgi.dll','gdi32.dll','imm32.dll','iphlpapi.dll','kernel32.dll','msimg32.dll','msvcrt.dll','ntdll.dll','ole32.dll','oleaut32.dll','opengl32.dll','powrprof.dll','psapi.dll','rpcrt4.dll','secur32.dll','setupapi.dll','shcore.dll','shell32.dll','shlwapi.dll','ucrtbase.dll','urlmon.dll','user32.dll','userenv.dll','uxtheme.dll','version.dll','winhttp.dll','winmm.dll','winspool.drv','ws2_32.dll','wtsapi32.dll'}
assert dlls and all(name.startswith('api-ms-win-') or name in system for name in dlls),dlls
print('Windows x64 GUI subsystem, ASLR/high-entropy VA/NX and system-only DLLs:',sorted(dlls))
