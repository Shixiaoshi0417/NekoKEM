"""Verify Linux GUI hardening and isolate Core's pinned OpenSSL from WebKit."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess


def output(*arguments):
    return subprocess.check_output(arguments, text=True)


def verify(binary, architecture, prefix):
    binary, prefix = Path(binary), Path(prefix)
    assert binary.read_bytes()[:4] == b'\x7fELF'
    header = output('readelf', '-h', str(binary))
    machine = 'AArch64' if architecture == 'aarch64' else 'Advanced Micro Devices X86-64'
    assert re.search(r'Type:\s+DYN\b', header), 'GUI must retain PIE/ASLR'
    assert re.search(r'Machine:\s+' + re.escape(machine) + r'\s*$', header, re.M), header
    segments = output('readelf', '-lW', str(binary))
    assert 'GNU_RELRO' in segments
    stack = next(line for line in segments.splitlines() if 'GNU_STACK' in line)
    assert re.search(r'\sRW\s', stack) and not re.search(r'\sRWE\s', stack), stack
    dynamic = output('readelf', '-dW', str(binary))
    assert 'BIND_NOW' in dynamic or re.search(r'\(FLAGS_1\).*\bNOW\b', dynamic), dynamic
    assert not re.search(r'\((?:RPATH|RUNPATH|TEXTREL)\)', dynamic), dynamic
    libraries = re.findall(r'\(NEEDED\).*?\[([^\]]+)\]', dynamic)
    assert libraries and not any(re.search(r'lib(?:crypto|ssl)\.', name) for name in libraries), libraries
    symbols = output('readelf', '--dyn-syms', '-W', str(binary))
    exported = {line.split()[-1].split('@')[0] for line in symbols.splitlines()
                if re.search(r'\b(?:GLOBAL|WEAK)\s+DEFAULT\s+(?!UND\b)\S+\s+', line)}
    crypto_symbols = {line.split()[-1] for line in output('nm', '-g', '--defined-only', str(prefix/'lib/libcrypto.a')).splitlines()
                      if len(line.split()) >= 3}
    assert not (exported & crypto_symbols), 'Pinned OpenSSL must not interpose WebKitGTK TLS symbols'
    assert not any(name.startswith(('nekokem_', 'desktop_', 'hybrid_', 'atomic_file_')) for name in exported)
    assert '__stack_chk_fail' in symbols, 'C17 Core must retain stack protection'
    versions = output('readelf', '--version-info', '-W', str(binary))
    glibc = max((tuple(map(int, match.split('.'))) for match in re.findall(r'GLIBC_([0-9.]+)', versions)), default=(0, 0))
    assert glibc <= (2, 39), f'Build exceeds the tested Ubuntu 24.04 glibc baseline: {glibc}'
    manifest = json.loads((prefix/'nekokem-build-manifest.json').read_text())
    assert manifest['architecture'] == architecture and manifest['openssl_version'] == '4.0.3'
    assert '-fPIC' in manifest['cflags'] and '-O3' in manifest['cflags']
    return {
        'architecture': architecture, 'target': architecture+'-unknown-linux-gnu',
        'version': '3.3.1', 'tested_distribution': 'Ubuntu 24.04',
        'required_glibc': '.'.join(map(str, glibc)),
        'openssl_version': '4.0.3', 'openssl_source_sha256': manifest['openssl_source_sha256'],
        'openssl_dynamic_exports': 0, 'core_dynamic_exports': 0,
        'exe_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
        'elf_dependencies': libraries, 'pie': True, 'full_relro': True,
        'executable_stack': False, 'webview': 'system-webkit2gtk-4.1',
        'apk_signing_material_used': False,
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('binary', type=Path)
    parser.add_argument('--arch', choices=['x86_64', 'aarch64'], required=True)
    parser.add_argument('--openssl-prefix', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.binary, args.arch, args.openssl_prefix), indent=2))
