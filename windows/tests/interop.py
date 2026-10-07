"""Historical format interoperability and current Linux/Windows CLI equality.
All generated keys/passwords are disposable public test fixtures.
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
    assert fingerprint('linux-public.key') == (root/'linux-fingerprint.txt').read_bytes()
    # keygen never replaces keys; the Linux phase may have left keys/ behind.
    run('keygen', '--replace', password=PASSWORD*2)
    shutil.copyfile(root/'keys/public.key',root/'windows-public.key')
    shutil.copyfile(root/'keys/private.key.enc',root/'windows-private.enc')
    for name in ['binary','empty']:
        run('decrypt','hybrid',name+'.linux.nkem',name+'.from-linux','imported.enc',password=PASSWORD)
        assert (root/(name+'.from-linux')).read_bytes() == (root/name).read_bytes()
        run('encrypt','hybrid',name,name+'.windows.nkem','keys/public.key')
        run('decrypt','hybrid',name+'.windows.nkem',name+'.roundtrip','keys/private.key.enc',password=PASSWORD)
        assert (root/(name+'.roundtrip')).read_bytes() == (root/name).read_bytes()
    (root/'windows-fingerprint.txt').write_bytes(fingerprint('windows-public.key'))
    print('Windows decrypted pre-port Linux v3/NKPR and matched fingerprint')
elif phase == 'verify':
    (root/'windows-private.enc').chmod(0o600)
    assert fingerprint('windows-public.key') == (root/'windows-fingerprint.txt').read_bytes()
    for name in ['binary','empty']:
        run('decrypt','hybrid',name+'.windows.nkem',name+'.from-windows','windows-private.enc',password=PASSWORD)
        assert (root/(name+'.from-windows')).read_bytes() == (root/name).read_bytes()
    print('Pre-port Linux main decrypted Windows v3/NKPR; binary and empty data match')
else:
    raise SystemExit('Unknown phase')
