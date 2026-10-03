#!/usr/bin/env python3
"""Require native arm64 PIE, an explicit deployment target and ad-hoc signing."""
from pathlib import Path
import re
import subprocess
import sys


def output(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def check(path):
    binary = Path(path).resolve()
    assert binary.read_bytes()[:4] == b'\xcf\xfa\xed\xfe', 'Expected a thin 64-bit Mach-O'
    assert output('xcrun', 'lipo', '-archs', str(binary)).strip() == 'arm64'
    header = output('xcrun', 'otool', '-hv', str(binary))
    assert re.search(r'\bEXECUTE\b', header) and re.search(r'\bPIE\b', header), header
    libraries = output('xcrun', 'otool', '-L', str(binary))
    dependencies = [line.strip().split(' (', 1)[0] for line in libraries.splitlines()[1:] if line.strip()]
    allowed = {'/usr/lib/libSystem.B.dylib',
               '/System/Library/Frameworks/CoreFoundation.framework/Versions/A/CoreFoundation'}
    assert dependencies and set(dependencies) <= allowed, libraries
    assert '/usr/lib/libSystem.B.dylib' in dependencies
    commands = output('xcrun', 'otool', '-l', str(binary))
    build_commands = re.findall(r'cmd LC_BUILD_VERSION\n(.*?)(?=Load command|\Z)', commands, re.S)
    old_commands = re.findall(r'cmd LC_VERSION_MIN_MACOSX\n(.*?)(?=Load command|\Z)', commands, re.S)
    assert len(build_commands) + len(old_commands) == 1, commands
    block = (build_commands or old_commands)[0]
    if build_commands:
        assert re.search(r'platform (?:1|macos)\b', block, re.I), block
        minimum = re.search(r'\bminos ([0-9.]+)', block)
    else:
        minimum = re.search(r'\bversion ([0-9.]+)', block)
    assert minimum and tuple(map(int, minimum[1].split('.')))[:2] == (11, 0), block
    subprocess.run(['codesign', '--verify', '--strict', str(binary)], check=True)
    signing = output('codesign', '-dv', '--verbose=4', str(binary))
    assert 'Signature=adhoc' in signing, signing
    assert re.search(r'flags=.*\bruntime\b', signing), signing
    assert 'TeamIdentifier=not set' in signing, signing
    print(f'{binary.name}: arm64 PIE, macOS 11.0 deployment, system-only dylibs, ad-hoc hardened runtime verified')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: check_macho.py <nekokem>')
    check(sys.argv[1])
