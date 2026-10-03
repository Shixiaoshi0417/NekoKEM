#!/usr/bin/env python3
"""Create and verify a deterministic native CLI archive without GNU tar."""
import gzip
import hashlib
import io
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile

output, repo, epoch = Path(sys.argv[1]), Path(sys.argv[2]), int(sys.argv[3])
name = 'NekoKEM-macos-arm64'
launcher = b'''#!/bin/bash
set -e
cd -- "$(dirname -- "$0")"
exec ./nekokem "$@"
'''
files = {
    'nekokem': ((output/'nekokem').read_bytes(), 0o755),
    'NekoKEM.command': (launcher, 0o755),
    'README.md': ((repo/'macos/README.md').read_bytes(), 0o644),
    'SECURITY-DESIGN.md': ((repo/'macos/SECURITY-DESIGN.md').read_bytes(), 0o644),
    'LICENSE': ((repo/'LICENSE').read_bytes(), 0o644),
    'OPENSSL-LICENSE.txt': ((output/'OPENSSL-LICENSE.txt').read_bytes(), 0o644),
    'build-metadata.json': ((output/'build-metadata.json').read_bytes(), 0o644),
}
checksums = ''.join(f'{hashlib.sha256(data).hexdigest()}  {path}\n'
                    for path, (data, _) in sorted(files.items()))
files['SHA256SUMS'] = (checksums.encode('ascii'), 0o644)
archive = output/f'{name}.tar.gz'
with archive.open('wb') as stream, gzip.GzipFile(filename='', mode='wb', fileobj=stream, mtime=0) as zipped:
    with tarfile.open(mode='w', fileobj=zipped, format=tarfile.GNU_FORMAT) as tar:
        directory = tarfile.TarInfo(name)
        directory.type = tarfile.DIRTYPE
        directory.mode = 0o755
        directory.mtime = epoch
        tar.addfile(directory)
        for path, (data, mode) in sorted(files.items()):
            member = tarfile.TarInfo(f'{name}/{path}')
            member.size, member.mode, member.mtime = len(data), mode, epoch
            tar.addfile(member, io.BytesIO(data))
with tempfile.TemporaryDirectory(prefix='nekokem-package-verify-') as temp:
    with tarfile.open(archive, 'r:gz') as tar:
        assert {member.name for member in tar} == {name, *(f'{name}/{path}' for path in files)}
        for path, (expected, mode) in files.items():
            member = tar.getmember(f'{name}/{path}')
            assert member.isfile() and member.mode == mode and member.uid == member.gid == 0
            extracted = tar.extractfile(member).read()
            assert extracted == expected
            target = Path(temp)/path
            target.write_bytes(extracted)
            target.chmod(mode)
    for line in checksums.splitlines():
        digest, path = line.split('  ', 1)
        assert hashlib.sha256((Path(temp)/path).read_bytes()).hexdigest() == digest
    subprocess.run([sys.executable, str(repo/'macos/tests/check_macho.py'), str(Path(temp)/'nekokem')], check=True)
digest = hashlib.sha256(archive.read_bytes()).hexdigest()
(output/'macos-SHA256SUMS.txt').write_text(f'{digest}  {archive.name}\n')
print(f'Created and verified {archive}: {digest}')
