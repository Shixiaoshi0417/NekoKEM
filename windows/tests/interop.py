"""Historical format interoperability and current Linux/Windows CLI equality.
All generated keys/passwords are disposable public test fixtures.

The historical Linux CLI reads only two-component keys, NKPR v1 and
single-recipient NKEM v3, so Windows returns unsigned v3 files for the
historical key and checks the legacy export of its own identity key;
signed v5 files, identity keys and upgrades are checked natively.
"""
import os
import json
import re
from pathlib import Path
import shutil
import subprocess
import sys

PASSWORD = b'cross-platform-public-test-password\n'
phase, executable, directory = sys.argv[1:]
exe = str(Path(executable).resolve())
root = Path(directory).resolve()
root.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, LC_ALL='C.UTF-8')

def run(*args, password=b'', ok=True):
    p = subprocess.run([exe, '--lang', 'en', *map(str,args)], cwd=root,
                       input=password, capture_output=True, env=env, timeout=60)
    assert (p.returncode == 0) == ok, (args,p.returncode,p.stdout,p.stderr)
    return p

def fingerprint(path):
    """The key's only fingerprint: identity for an identity key, legacy otherwise."""
    p = run(password=b'4\n1\n'+str(path).encode('utf-8')+b'\n5\n')
    found = re.findall(rb'(?<![0-9A-F])(?:[0-9A-F]{2}:){31}[0-9A-F]{2}(?![0-9A-F])', p.stdout)
    assert len(found) == 1, (p.stdout,p.stderr)
    return found[0]

def cli_contract():
    """Compare actual Linux/Windows output, normalizing only argv[0]."""
    cases = [('help', ['--help'], b''), ('menu', [], b'0\n5\n'),
             ('version', ['--version'], b''), ('invalid', ['unknown'], b''),
             ('invalid-language', ['--lang=invalid'], b'')]
    result = {}
    for language in ['en', 'zh-CN', 'zh-TW', 'ja', 'ko']:
        for name, args, data in cases:
            p = subprocess.run([exe, '--lang', language, *args], cwd=root,
                               input=data, capture_output=True, env=env, timeout=30)
            result[language + '/' + name] = [p.returncode,
                p.stdout.decode('utf-8').replace(exe, '<program>'),
                p.stderr.decode('utf-8').replace(exe, '<program>')]
    return result

if phase == 'contract':
    (root/'linux-cli-contract.json').write_text(json.dumps(cli_contract(), ensure_ascii=False), encoding='utf-8')
    print('Current Linux CLI generated the exact five-language output contract')
elif phase == 'generate':
    (root/'linux-cli-contract.json').write_text(json.dumps(cli_contract(), ensure_ascii=False), encoding='utf-8')
    run('keygen', password=PASSWORD*2)
    for name, data in [('binary', bytes(range(256))*512),('empty',b'')]:
        (root/name).write_bytes(data)
        run('encrypt','hybrid',name,name+'.linux.nkem','keys/public.key')
    shutil.copyfile(root/'keys/public.key', root/'linux-public.key')
    shutil.copyfile(root/'keys/private.key.enc', root/'linux-private.enc')
    (root/'linux-fingerprint.txt').write_bytes(fingerprint('linux-public.key'))
    print('Pre-port Linux main generated v3/NKPR fixtures and fingerprint')
elif phase == 'windows':
    assert os.name == 'nt'
    assert cli_contract() == json.loads((root/'linux-cli-contract.json').read_text(encoding='utf-8')), 'Linux CLI help/menu/options differ'
    print('All five languages: Linux CLI help, menu, version and invalid options match exactly')
    harness = str(Path(exe).with_name('windows_core_tests.exe'))
    subprocess.run([harness,'secure-copy',str(root/'linux-private.enc'),str(root/'imported.enc')],
                   cwd=root, check=True, timeout=30)
    legacy = (root/'linux-fingerprint.txt').read_bytes()
    assert fingerprint('linux-public.key') == legacy
    # keygen never replaces keys; the Linux phase may have left keys/ behind.
    run('keygen', '--replace', password=PASSWORD*2)
    shutil.copyfile(root/'keys/public.key',root/'windows-public.key')
    identity = fingerprint('windows-public.key')
    run('export-legacy-public-key','windows-public.key','windows-legacy.key')
    windows_legacy = fingerprint('windows-legacy.key')
    assert windows_legacy != identity
    (root/'windows-legacy-fingerprint.txt').write_bytes(windows_legacy)
    for name in ['binary','empty']:
        run('decrypt','hybrid',name+'.linux.nkem',name+'.from-linux','imported.enc',password=PASSWORD)
        assert (root/(name+'.from-linux')).read_bytes() == (root/name).read_bytes()
        # Signed by default; Core verifies the signer against the trusted key.
        run('encrypt','hybrid',name,name+'.signed.nkem','keys/public.key',password=PASSWORD)
        assert (root/(name+'.signed.nkem')).read_bytes()[4] == 5
        p = run('decrypt','hybrid','--trust','windows-public.key','--require-trusted',
                name+'.signed.nkem',name+'.roundtrip','keys/private.key.enc',password=PASSWORD)
        assert identity in p.stdout, p.stdout
        assert (root/(name+'.roundtrip')).read_bytes() == (root/name).read_bytes()
        # The historical CLI reads only unsigned single-recipient v3.
        run('encrypt','hybrid','--unsigned',name,name+'.windows.nkem','linux-public.key')
        assert (root/(name+'.windows.nkem')).read_bytes()[4] == 3
    # Upgrading the historical key keeps its encryption keys and legacy fingerprint.
    # Core replaces only privately owned key files, as keygen leaves them.
    for source, target in [('linux-public.key','upgraded-public.key'),('linux-private.enc','upgraded.enc')]:
        subprocess.run([harness,'secure-copy',str(root/source),str(root/target)],
                       cwd=root, check=True, timeout=30)
    run('upgrade-key','upgraded-public.key','upgraded.enc',password=PASSWORD)
    run('export-legacy-public-key','upgraded-public.key','upgraded-legacy.key')
    assert fingerprint('upgraded-legacy.key') == legacy and fingerprint('upgraded-public.key') != legacy
    run('decrypt','hybrid','binary.linux.nkem','binary.upgraded','upgraded.enc',password=PASSWORD)
    assert (root/'binary.upgraded').read_bytes() == (root/'binary').read_bytes()
    print('Windows decrypted pre-port Linux v3/NKPR, signed v5, upgraded the historical key and matched fingerprints')
elif phase == 'verify':
    (root/'linux-private.enc').chmod(0o600)
    assert fingerprint('windows-legacy.key') == (root/'windows-legacy-fingerprint.txt').read_bytes()
    for name in ['binary','empty']:
        run('decrypt','hybrid',name+'.windows.nkem',name+'.from-windows','linux-private.enc',password=PASSWORD)
        assert (root/(name+'.from-windows')).read_bytes() == (root/name).read_bytes()
    print('Pre-port Linux main decrypted Windows v3 and read its legacy public key; binary and empty data match')
else:
    raise SystemExit('Unknown phase')
