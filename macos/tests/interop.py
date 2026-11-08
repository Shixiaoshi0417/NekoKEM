#!/usr/bin/env python3
"""Strict current CLI equality and historical Linux NKEM v3/NKPR v1 exchange.

The historical Linux CLI reads only two-component keys, NKPR v1 and
single-recipient NKEM v3, so macOS returns unsigned v3 files for the
historical key and checks the legacy export of its own identity key;
signed v5 files, identity keys and upgrades are checked natively.
"""
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


def fingerprints(path):
    """The key's fingerprint, then the legacy one for an identity key."""
    process = run(password=b'4\n1\n'+str(path).encode('utf-8')+b'\n5\n')
    matches = re.findall(rb'(?<![0-9A-F])(?:[0-9A-F]{2}:){31}[0-9A-F]{2}(?![0-9A-F])', process.stdout)
    assert len(matches) in (1, 2), (process.stdout, process.stderr)
    return matches


def fingerprint(path):
    matches = fingerprints(path)
    assert len(matches) == 1, matches
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
    legacy = (root/'linux-fingerprint.txt').read_bytes()
    assert fingerprint('linux-public.key') == legacy
    # Artifact extraction need not preserve the original keys/ directory's mode.
    # Generate in a fresh private directory without editing historical fixtures.
    with tempfile.TemporaryDirectory(prefix='nekokem-macos-fixtures-', dir=root) as work:
        run('keygen', password=PASSWORD*2, cwd=work)
        shutil.copyfile(Path(work)/'keys/public.key', root/'macos-public.key')
        shutil.copyfile(Path(work)/'keys/private.key.enc', root/'macos-private.enc')
    (root/'macos-private.enc').chmod(0o600)
    identity, macos_legacy = fingerprints('macos-public.key')
    run('export-legacy-public-key', 'macos-public.key', 'macos-legacy.key')
    assert fingerprint('macos-legacy.key') == macos_legacy
    (root/'macos-legacy-fingerprint.txt').write_bytes(macos_legacy)
    for name in ['binary', 'empty']:
        run('decrypt', 'hybrid', name+'.linux.nkem', name+'.from-linux', 'linux-private.enc', password=PASSWORD)
        assert (root/(name+'.from-linux')).read_bytes() == (root/name).read_bytes()
        run('encrypt', 'hybrid', '--sign-key', 'macos-private.enc', name, name+'.signed', 'macos-public.key',
            password=PASSWORD)
        assert (root/(name+'.signed')).read_bytes()[4] == 5
        process = run('decrypt', 'hybrid', '--trust', 'macos-public.key', '--require-trusted',
                      name+'.signed', name+'.roundtrip-macos', 'macos-private.enc', password=PASSWORD)
        assert identity in process.stdout, process.stdout
        assert (root/(name+'.roundtrip-macos')).read_bytes() == (root/name).read_bytes()
        # The historical CLI reads only unsigned single-recipient v3.
        run('encrypt', 'hybrid', '--unsigned', name, name+'.macos.nkem', 'linux-public.key')
        assert (root/(name+'.macos.nkem')).read_bytes()[4] == 3
    # Upgrading the historical key keeps its encryption keys and legacy fingerprint.
    shutil.copyfile(root/'linux-public.key', root/'upgraded-public.key')
    shutil.copyfile(root/'linux-private.enc', root/'upgraded.enc')
    (root/'upgraded.enc').chmod(0o600)
    run('upgrade-key', 'upgraded-public.key', 'upgraded.enc', password=PASSWORD)
    upgraded, upgraded_legacy = fingerprints('upgraded-public.key')
    assert upgraded_legacy == legacy and upgraded != legacy
    run('decrypt', 'hybrid', 'binary.linux.nkem', 'binary.upgraded', 'upgraded.enc', password=PASSWORD)
    assert (root/'binary.upgraded').read_bytes() == (root/'binary').read_bytes()
    print('macOS decrypted historical Linux NKEM v3/NKPR v1, signed v5, upgraded the historical key and matched fingerprints')
elif phase == 'verify':
    (root/'linux-private.enc').chmod(0o600)
    assert fingerprint('macos-legacy.key') == (root/'macos-legacy-fingerprint.txt').read_bytes()
    for name in ['binary', 'empty']:
        run('decrypt', 'hybrid', name+'.macos.nkem', name+'.from-macos', 'linux-private.enc', password=PASSWORD)
        assert (root/(name+'.from-macos')).read_bytes() == (root/name).read_bytes()
    print('Historical Linux decrypted native macOS NKEM v3 and read its legacy public key; binary and empty data match')
else:
    raise SystemExit('Unknown phase: use macos or verify')
