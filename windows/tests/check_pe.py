"""Reject loss of PE mitigations and non-system DLL dependencies."""
from pathlib import Path
import re
import struct
import sys
exe = Path(sys.argv[1]).read_bytes()
pe = struct.unpack_from('<I', exe, 0x3c)[0]
assert exe[pe:pe+4] == b'PE\0\0'
assert struct.unpack_from('<H', exe, pe+4)[0] == 0x8664, 'Expected x64'
optional = pe+24
assert struct.unpack_from('<H', exe, optional)[0] == 0x20b
mitigations = struct.unpack_from('<H', exe, optional+70)[0]
assert mitigations & 0x160 == 0x160, 'ASLR/high-entropy VA/NX required'
text = Path(sys.argv[2]).read_text()
dlls = {s.lower() for s in re.findall(r'DLL Name: (\S+)', text)}
assert dlls and all(d.startswith('api-ms-win-') or d in {
    'kernel32.dll','advapi32.dll','bcrypt.dll','crypt32.dll','shell32.dll',
    'ole32.dll','user32.dll','ws2_32.dll','ntdll.dll','ucrtbase.dll'} for d in dlls), dlls
print('PE x64, ASLR, high-entropy VA, NX and system-only DLL imports verified:', sorted(dlls))
