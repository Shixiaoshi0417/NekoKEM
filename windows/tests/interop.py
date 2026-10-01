"""Actual CLI interoperability against the pre-port main executable.
All generated keys/passwords are disposable public test fixtures.
"""
import os
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

if phase == 'generate':
    run('keygen', password=PASSWORD*2)
    for name, data in [('binary', bytes(range(256))*512),('empty',b'')]:
        (root/name).write_bytes(data)
        run('encrypt','hybrid',name,name+'.linux.nkem','keys/public.key')
    shutil.copyfile(root/'keys/public.key', root/'linux-public.key')
    shutil.copyfile(root/'keys/private.key.enc', root/'linux-private.enc')
    print('Pre-port Linux main generated v3/NKPR fixtures')
elif phase == 'windows':
    assert os.name == 'nt'
    harness = str(Path(exe).with_name('windows_core_tests.exe'))
    subprocess.run([harness,'secure-copy',str(root/'linux-private.enc'),str(root/'imported.enc')],
                   cwd=root, check=True, timeout=30)
    run('keygen', password=PASSWORD*2)
    shutil.copyfile(root/'keys/public.key',root/'windows-public.key')
    shutil.copyfile(root/'keys/private.key.enc',root/'windows-private.enc')
    for name in ['binary','empty']:
        run('decrypt','hybrid',name+'.linux.nkem',name+'.from-linux','imported.enc',password=PASSWORD)
        assert (root/(name+'.from-linux')).read_bytes() == (root/name).read_bytes()
        run('encrypt','hybrid',name,name+'.windows.nkem','keys/public.key')
        run('decrypt','hybrid',name+'.windows.nkem',name+'.roundtrip','keys/private.key.enc',password=PASSWORD)
        assert (root/(name+'.roundtrip')).read_bytes() == (root/name).read_bytes()
    print('Windows decrypted pre-port Linux v3/NKPR and generated return fixtures')
elif phase == 'verify':
    (root/'windows-private.enc').chmod(0o600)
    for name in ['binary','empty']:
        run('decrypt','hybrid',name+'.windows.nkem',name+'.from-windows','windows-private.enc',password=PASSWORD)
        assert (root/(name+'.from-windows')).read_bytes() == (root/name).read_bytes()
    print('Pre-port Linux main decrypted Windows v3/NKPR; binary and empty data match')
else:
    raise SystemExit('Unknown phase')
