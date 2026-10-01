"""Measure CLI throughput without changing crypto parameters or the I/O path."""
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys
import tempfile
import time

executables = [str(Path(p).resolve()) for p in sys.argv[1:]]
results = []
for exe in executables:
    with tempfile.TemporaryDirectory(prefix='nekokem-throughput-') as temp:
        root = Path(temp)
        chunk = bytes(range(256))*4096
        with (root/'plain').open('wb') as output:
            for _ in range(64): output.write(chunk)
        password = b'benchmark-public-test-password\n'
        def run(*args, secret=b''):
            started = time.perf_counter()
            subprocess.run([exe,'--lang','en',*args],cwd=root,input=secret,
                           stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,check=True,timeout=120)
            return time.perf_counter()-started
        run('keygen',secret=password*2)
        encrypt, decrypt = [], []
        for _ in range(3):
            encrypt.append(run('encrypt','hybrid','plain','cipher.nkem','keys/public.key'))
            decrypt.append(run('decrypt','hybrid','cipher.nkem','output','keys/private.key.enc',secret=password))
        assert (root/'plain').read_bytes() == (root/'output').read_bytes()
        results.append({'executable':exe,'bytes':64*1024*1024,'samples':3,
                        'encrypt_MiB_s':64/statistics.median(encrypt),
                        'decrypt_MiB_s_including_Argon2':64/statistics.median(decrypt)})
print(json.dumps({'system':platform.platform(),'machine':platform.machine(),'results':results},indent=2))
