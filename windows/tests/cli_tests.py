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
    def run_cli(self,*args,password=b'',ok=True,env=None):
        p = subprocess.run([EXE,*map(str,args)],cwd=self.root,input=password,
                           capture_output=True,timeout=30,env=env)
        detail = (args,p.returncode,p.stdout,p.stderr)
        if (p.returncode == 0) != ok:
            acl = subprocess.run(['icacls',str(self.root)],capture_output=True,timeout=10)
            detail += (acl.stdout,)
        self.assertEqual(p.returncode == 0,ok,detail)
        return p
    def generate(self):
        self.run_cli('--lang','en','keygen','hybrid',password=PASSWORD*2)
    def test_automatic_language_and_persistent_override(self):
        # CI runs under a disposable Windows account; never overwrite a user preference.
        directory = Path(os.environ['LOCALAPPDATA'])/'NekoKEM'
        preference = directory/'language'
        self.assertFalse(preference.exists(), 'Language tests require a fresh CI account')
        existed = directory.exists()
        env = dict(os.environ)
        for name in ['LC_ALL','LC_MESSAGES','LANG']:
            env.pop(name,None)
        kernel = ctypes.windll.kernel32
        kernel.GetUserDefaultUILanguage.restype = ctypes.c_ushort
        tag = ctypes.create_unicode_buffer(85)
        self.assertTrue(kernel.LCIDToLocaleName(kernel.GetUserDefaultUILanguage(),tag,85,0))
        def match(value):
            value = value.lower().replace('_','-').split('.')[0].split('@')[0]
            if value == 'zh' or value.startswith('zh-'):
                if value.split('-')[-1] in ['tw','hk','mo'] or value.startswith('zh-hant'):
                    return 'zh-TW'
                return 'zh-CN'
            if value == 'ja' or value.startswith('ja-'): return 'ja'
            if value == 'ko' or value.startswith('ko-'): return 'ko'
            return 'en'
        def help_for(language):
            return self.run_cli('--lang',language,'--help',env=env).stdout
        automatic = help_for(match(tag.value))
        self.assertEqual(self.run_cli('--help',env=env).stdout,automatic)
        self.assertEqual(self.run_cli('--lang=system','--help',env=env).stdout,automatic)
        env.update(LANG='ja_JP.UTF-8',LC_MESSAGES='zh_HK.UTF-8',LC_ALL='ko_KR.UTF-8')
        for name, expected in [('LC_ALL','ko'),('LC_MESSAGES','zh-TW'),('LANG','ja')]:
            self.assertEqual(self.run_cli('--help',env=env).stdout,help_for(expected))
            env.pop(name)
        try:
            for language in ['en','zh-CN','zh-TW','ja','ko']:
                self.run_cli('--set-lang',language,env=env)
                self.assertEqual(self.run_cli('--help',env=env).stdout,help_for(language))
                self.assertEqual(self.run_cli('--lang=system','--help',env=env).stdout,automatic)
            self.run_cli('--set-lang','invalid',ok=False,env=env)
            self.assertEqual(self.run_cli('--help',env=env).stdout,help_for('ko'))
            self.run_cli('--set-lang','system',env=env)
            self.assertEqual(self.run_cli('--help',env=env).stdout,automatic)
        finally:
            preference.unlink(missing_ok=True)
            if not existed and directory.exists(): directory.rmdir()
    def test_version_help_languages_and_unicode_paths(self):
        self.assertEqual(self.run_cli('--version').stdout,b'NekoKEM 4.1.0\n')
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
    def test_all_five_menu_actions_and_pasted_public_key(self):
        data = bytes(range(256))+b'\x00\r\nmenu'
        (self.root/'plain.bin').write_bytes(data)
        commands = (b'1\n'+PASSWORD*2+
            b'2\n1\nkeys/public.key\nplain.bin\n'+
            b'3\n1\nkeys/private.key.enc\nencrypted/plain.bin.nkem\n'+PASSWORD+
            b'4\n1\nkeys/public.key\n5\n')
        p = self.run_cli('--lang','en',password=commands)
        self.assertEqual(p.stderr,b'')
        self.assertEqual((self.root/'plaintext/plain.bin').read_bytes(),data)
        public = (self.root/'keys/public.key').read_bytes()
        by_path = self.run_cli('--lang','en',password=b'4\n1\nkeys/public.key\n5\n')
        pasted = self.run_cli('--lang','en',password=b'4\n2\n'+public+b'5\n')
        import re
        pattern = rb'(?:[0-9A-F]{2}:){31}[0-9A-F]{2}'
        self.assertEqual(re.findall(pattern,pasted.stdout),re.findall(pattern,by_path.stdout))
        self.assertEqual(pasted.stderr,b'')
    def test_wrong_password_tamper_versions_and_preserved_output(self):
        self.generate()
        (self.root/'plain').write_bytes(b'original\x00\r\n\x1a')
        self.run_cli('encrypt','hybrid','plain','valid.nkem','keys/public.key')
        self.run_cli('decrypt','hybrid','valid.nkem','output','keys/private.key.enc',password=PASSWORD)
        sentinel = (self.root/'output').read_bytes()
        self.run_cli('decrypt','hybrid','valid.nkem','output','keys/private.key.enc',password=b'wrong\n',ok=False)
        valid = (self.root/'valid.nkem').read_bytes()
        for offset,value in [(4,1),(4,2),(5,4),(10,1),(12,1),(24,0),(25,0),(26,0),(-1,valid[-1]^1)]:
            data = bytearray(valid); data[offset] = value
            (self.root/'bad.nkem').write_bytes(data)
            self.run_cli('decrypt','hybrid','bad.nkem','output','keys/private.key.enc',password=PASSWORD,ok=False)
            self.assertEqual((self.root/'output').read_bytes(),sentinel)
        zero_peer = bytearray(valid); zero_peer[32:88] = bytes(56)
        malformed_kem = bytearray(valid); malformed_kem[88] ^= 1
        for data in [valid[:-1],valid+b'X',zero_peer,malformed_kem]:
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
        (self.root/'actual/input').write_bytes(b'untrusted directory input')
        subprocess.run(['icacls',str(self.root/'actual'),'/grant','*S-1-1-0:M'],check=True,capture_output=True)
        self.run_cli('encrypt','hybrid','plain','actual/output','keys/public.key',ok=False)
        self.run_cli('encrypt','hybrid','actual/input','unsafe-source.nkem','keys/public.key',ok=False)
        self.assertFalse((self.root/'unsafe-source.nkem').exists())
    def test_existing_insecure_output_is_never_replaced(self):
        self.generate()
        (self.root/'plain').write_bytes(b'data')
        (self.root/'output').write_bytes(b'sentinel')
        subprocess.run(['icacls',str(self.root/'output'),'/grant','*S-1-1-0:R'],check=True,capture_output=True)
        self.run_cli('encrypt','hybrid','plain','output','keys/public.key',ok=False)
        self.assertEqual((self.root/'output').read_bytes(),b'sentinel')
    def test_utf8_and_exact_password_limit_with_crlf(self):
        password = ('中文-日本語-한국어-😀'+'a'*16).encode('utf-8')+b'\r\n'
        self.run_cli('keygen',password=password*2)
        (self.root/'plain').write_bytes(b'data')
        self.run_cli('encrypt','hybrid','plain','cipher.nkem','keys/public.key')
        self.run_cli('decrypt','hybrid','cipher.nkem','output','keys/private.key.enc',password=password)
        self.assertEqual((self.root/'output').read_bytes(),b'data')
        password = b'a'*1024+b'\r\n'
        self.run_cli('keygen','--replace',password=password*2)
        self.run_cli('encrypt','hybrid','plain','cipher.nkem','keys/public.key')
        self.run_cli('decrypt','hybrid','cipher.nkem','output','keys/private.key.enc',password=password)
        self.assertEqual((self.root/'output').read_bytes(),b'data')
    def test_password_and_prompt_bounds(self):
        p = self.run_cli('--lang','en','keygen',password=b'\n\n',ok=False)
        self.assertIn(b'Password must not be empty',p.stderr)
        p = self.run_cli('--lang','en','keygen',password=b'a'*1025+b'\n'+b'a'*1025+b'\n',ok=False)
        self.assertIn(b'Password exceeds 1024 bytes',p.stderr)
        p = self.run_cli('--lang','en',password=b'a'*5000+b'\n5\n')
        self.assertIn(b'Input exceeds 4096 bytes',p.stderr)
        self.assertFalse((self.root/'keys/private.key.enc').exists())

if __name__ == '__main__':
    assert os.name == 'nt', 'Run on native Windows'
    unittest.main(verbosity=2)
