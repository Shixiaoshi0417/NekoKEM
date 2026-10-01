"""Native Windows CLI regressions, including Unicode and unsafe filesystem inputs."""
import ctypes
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

EXE = str(Path(sys.argv.pop(1)).resolve())
HARNESS = str(Path(EXE).with_name('windows_core_tests.exe'))
PASSWORD = b'windows-cli-public-test-password\n'

class Tests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='nekokem-cli-')
        self.root = Path(self.temp.name)
    def tearDown(self):
        self.temp.cleanup()
    def run_cli(self,*args,password=b'',ok=True):
        p = subprocess.run([EXE,*map(str,args)],cwd=self.root,input=password,
                           capture_output=True,timeout=30)
        self.assertEqual(p.returncode == 0,ok,(args,p.stdout,p.stderr))
        return p
    def generate(self):
        self.run_cli('--lang','en','keygen',password=PASSWORD*2)
    def test_version_help_languages_and_unicode_paths(self):
        self.assertEqual(self.run_cli('--version').stdout,b'NekoKEM 3.2.0\n')
        for lang,text in [('en','Usage:'),('zh-CN','用法'),('zh-TW','用法'),('ja','使用方法'),('ko','사용법')]:
            p = self.run_cli('--lang',lang,'--help')
            self.assertIn(text,p.stdout.decode('utf-8'))
        self.generate()
        plain = self.root/'中文-日本語-한국어-😀.bin'
        plain.write_bytes(bytes(range(256))*4096)
        encrypted = self.root/'加密😀.nkem'
        output = self.root/'解密😀.bin'
        self.run_cli('encrypt','hybrid',plain,encrypted,'keys/public.key')
        self.run_cli('decrypt','hybrid',encrypted,output,'keys/private.key.enc',password=PASSWORD)
        self.assertEqual(plain.read_bytes(),output.read_bytes())
    def test_wrong_password_tamper_versions_and_preserved_output(self):
        self.generate()
        (self.root/'plain').write_bytes(b'original\x00\r\n\x1a')
        self.run_cli('encrypt','hybrid','plain','valid.nkem','keys/public.key')
        self.run_cli('decrypt','hybrid','valid.nkem','output','keys/private.key.enc',password=PASSWORD)
        sentinel = (self.root/'output').read_bytes()
        self.run_cli('decrypt','hybrid','valid.nkem','output','keys/private.key.enc',password=b'wrong\n',ok=False)
        valid = (self.root/'valid.nkem').read_bytes()
        for offset,value in [(4,1),(4,2),(5,4),(10,1),(12,0),(24,0),(25,0),(26,0),(-1,valid[-1]^1)]:
            data = bytearray(valid); data[offset] = value
            (self.root/'bad.nkem').write_bytes(data)
            self.run_cli('decrypt','hybrid','bad.nkem','output','keys/private.key.enc',password=PASSWORD,ok=False)
            self.assertEqual((self.root/'output').read_bytes(),sentinel)
        for data in [valid[:-1],valid+b'X']:
            (self.root/'bad.nkem').write_bytes(data)
            self.run_cli('decrypt','hybrid','bad.nkem','output','keys/private.key.enc',password=PASSWORD,ok=False)
        self.assertFalse(any('.tmp.' in p.name or '.bak.' in p.name for p in self.root.iterdir()))
    def test_private_acl_and_nonblocking_device_rejection(self):
        self.generate()
        (self.root/'plain').write_bytes(b'data')
        self.run_cli('encrypt','hybrid','plain','valid.nkem','keys/public.key')
        key = self.root/'keys/private.key.enc'
        before = key.read_bytes()
        # Explicit broad read grant must make a private key unacceptable.
        subprocess.run(['icacls',str(key),'/grant','*S-1-1-0:R'],check=True,capture_output=True)
        self.run_cli('decrypt','hybrid','valid.nkem','output',key,password=PASSWORD,ok=False)
        self.assertFalse((self.root/'output').exists())
        self.assertEqual(key.read_bytes(),before)
        for path in ['NUL','CON',r'\\.\pipe\nekokem-no-writer','plain:secret']:
            started = time.monotonic()
            self.run_cli('encrypt','hybrid',path,'output','keys/public.key',ok=False)
            self.assertLess(time.monotonic()-started,5)
    def test_public_size_and_tail_limits(self):
        self.generate()
        (self.root/'plain').write_bytes(b'data')
        public = self.root/'keys/public.key'
        data = public.read_bytes()
        public.write_bytes(data+b'X')
        self.run_cli('encrypt','hybrid','plain','output',public,ok=False)
        public.write_bytes(data+b' '*(1048576-len(data)))
        self.run_cli('encrypt','hybrid','plain','output',public)
        public.write_bytes(public.read_bytes()+b' ')
        self.run_cli('encrypt','hybrid','plain','output',public,ok=False)
    def test_reparse_points_and_untrusted_parent(self):
        self.generate()
        (self.root/'plain').write_bytes(b'data')
        # Junction creation does not require Developer Mode or symlink privilege.
        (self.root/'actual').mkdir()
        subprocess.run(['cmd','/c','mklink','/J',str(self.root/'junction'),str(self.root/'actual')],
                       check=True,capture_output=True)
        try:
            self.run_cli('encrypt','hybrid','plain','junction/output','keys/public.key',ok=False)
            self.assertFalse((self.root/'actual/output').exists())
        finally:
            os.rmdir(self.root/'junction')
        subprocess.run(['icacls',str(self.root/'actual'),'/grant','*S-1-1-0:M'],check=True,capture_output=True)
        self.run_cli('encrypt','hybrid','plain','actual/output','keys/public.key',ok=False)
    def test_existing_insecure_output_is_never_replaced(self):
        self.generate()
        (self.root/'plain').write_bytes(b'data')
        (self.root/'output').write_bytes(b'sentinel')
        subprocess.run(['icacls',str(self.root/'output'),'/grant','*S-1-1-0:R'],check=True,capture_output=True)
        self.run_cli('encrypt','hybrid','plain','output','keys/public.key',ok=False)
        self.assertEqual((self.root/'output').read_bytes(),b'sentinel')
    def test_password_and_prompt_bounds(self):
        self.run_cli('keygen',password=b'\n\n',ok=False)
        self.run_cli('keygen',password=b'a'*1025+b'\n'+b'a'*1025+b'\n',ok=False)
        p = self.run_cli('--lang','en',password=b'a'*5000+b'\n5\n')
        self.assertIn(b'Input exceeds 4096 bytes',p.stderr)
        self.assertFalse((self.root/'keys/private.key.enc').exists())

if __name__ == '__main__':
    assert os.name == 'nt', 'Run on native Windows'
    unittest.main(verbosity=2)
