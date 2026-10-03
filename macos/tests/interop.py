#!/usr/bin/env python3
"""Strict current CLI equality and historical Linux NKEM v3/NKPR v1 exchange."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

PASSWORD = b'cross-platform-public-test-password\n'
phase, executable, directory = sys.argv[1:]
exe = str(Path(executable).resolve())
root = Path(directory).resolve()
root.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, LC_ALL='C.UTF-8')


def run(*args, password=b'', cwd=root):
    process = subprocess.run([exe, '--lang', 'en', *map(str, args)],
                             cwd=cwd, input=password, capture_output=True,
                             env=env, timeout=60)
    assert process.returncode == 0, (args, process.returncode, process.stdout, process.stderr)
    return process


def fingerprint(path):
    process = run(password=b'4\n1\n'+str(path).encode('utf-8')+b'\n5\n')
    matches = re.findall(rb'(?<![0-9A-F])(?:[0-9A-F]{2}:){31}[0-9A-F]{2}(?![0-9A-F])', process.stdout)
    assert len(matches) == 1, (process.stdout, process.stderr)
    return matches[0]


if phase == 'macos':
    assert sys.platform == 'darwin', 'Run the macOS phase on native macOS'
    # Reuse the existing 25-case helper verbatim, including exact --version output.
    helper = Path(__file__).resolve().parents[2]/'windows/tests/interop.py'
    with tempfile.TemporaryDirectory(prefix='nekokem-contract-') as contract_dir:
        subprocess.run([sys.executable, str(helper), 'contract', exe, contract_dir], check=True, timeout=120)
        actual = json.loads((Path(contract_dir)/'linux-cli-contract.json').read_text(encoding='utf-8'))
    expected = json.loads((root/'linux-cli-contract.json').read_text(encoding='utf-8'))
    assert len(actual) == len(expected) == 25 and actual == expected, 'Linux/macOS help, menu, version or option output differs'
    print('All 25 current Linux/macOS CLI contract cases match exactly')
    (root/'linux-private.enc').chmod(0o600)
    assert fingerprint('linux-public.key') == (root/'linux-fingerprint.txt').read_bytes()
    # Artifact extraction need not preserve the original keys/ directory's mode.
    # Generate in a fresh private directory without editing historical fixtures.
    with tempfile.TemporaryDirectory(prefix='nekokem-macos-fixtures-', dir=root) as work:
        run('keygen', password=PASSWORD*2, cwd=work)
        shutil.copyfile(Path(work)/'keys/public.key', root/'macos-public.key')
        shutil.copyfile(Path(work)/'keys/private.key.enc', root/'macos-private.enc')
    (root/'macos-private.enc').chmod(0o600)
    for name in ['binary', 'empty']:
        run('decrypt', 'hybrid', name+'.linux.nkem', name+'.from-linux', 'linux-private.enc', password=PASSWORD)
        assert (root/(name+'.from-linux')).read_bytes() == (root/name).read_bytes()
        run('encrypt', 'hybrid', name, name+'.macos.nkem', 'macos-public.key')
        run('decrypt', 'hybrid', name+'.macos.nkem', name+'.roundtrip-macos', 'macos-private.enc', password=PASSWORD)
        assert (root/(name+'.roundtrip-macos')).read_bytes() == (root/name).read_bytes()
    (root/'macos-fingerprint.txt').write_bytes(fingerprint('macos-public.key'))
    print('macOS decrypted historical Linux NKEM v3/NKPR v1 and matched fingerprints')
elif phase == 'verify':
    (root/'macos-private.enc').chmod(0o600)
    assert fingerprint('macos-public.key') == (root/'macos-fingerprint.txt').read_bytes()
    for name in ['binary', 'empty']:
        run('decrypt', 'hybrid', name+'.macos.nkem', name+'.from-macos', 'macos-private.enc', password=PASSWORD)
        assert (root/(name+'.from-macos')).read_bytes() == (root/name).read_bytes()
    print('Historical Linux decrypted native macOS NKEM v3/NKPR v1; binary and empty data match')
else:
    raise SystemExit('Unknown phase: use macos or verify')
