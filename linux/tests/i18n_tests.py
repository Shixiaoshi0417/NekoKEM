#!/usr/bin/env python3
"""Black-box CLI regressions with isolated preferences and bounded subprocesses."""
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

BINARY = Path(sys.argv.pop(1)).resolve() if len(sys.argv) > 1 else Path('linux/nekokem').resolve()
LABELS = {'en': 'Usage:', 'zh-CN': '用法：', 'zh-TW': '用法：', 'ja': '使用方法：', 'ko': '사용법:'}

class LanguageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.env = {k: v for k, v in os.environ.items() if not k.startswith('LC_')}
        self.env.update(HOME=str(self.root), XDG_CONFIG_HOME=str(self.root / 'config'), LANG='C.UTF-8')
    def tearDown(self):
        self.temp.cleanup()
    def cli(self, *args, input='', **env):
        return subprocess.run([str(BINARY), *args], input=input, encoding='utf-8', timeout=5,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=dict(self.env, **env), cwd=self.root)
    def help(self, language, *args, **env):
        r = self.cli(*args, '--help', **env)
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual('', r.stderr)
        self.assertIn(LABELS[language], r.stdout)
        for text in ['--version', '--lang', '--set-lang', 'keygen hybrid', 'encrypt hybrid', 'LC_ALL', 'LC_MESSAGES', 'LANG']:
            self.assertIn(text, r.stdout)
        if language == 'zh-CN': self.assertIn('显示帮助', r.stdout)
        if language == 'zh-TW': self.assertIn('顯示說明', r.stdout)
        return r.stdout
    def test_five_languages_help_menu_errors_and_invariant_version(self):
        errors = {'en': 'Invalid selection', 'zh-CN': '无效选择', 'zh-TW': '無效選擇', 'ja': '選択が無効', 'ko': '잘못된 선택'}
        for tag in LABELS:
            with self.subTest(language=tag):
                self.help(tag, '--lang', tag)
                r = self.cli('--lang', tag, input='9\n5\n')
                self.assertEqual(0, r.returncode); self.assertIn(errors[tag], r.stderr)
                self.assertNotIn(errors[tag], r.stdout)
                r = self.cli('--lang', tag, '--version')
                self.assertEqual((0, 'NekoKEM 4.0.0\n', ''), (r.returncode, r.stdout, r.stderr))
                r = self.cli('--lang', tag, 'unknown')
                self.assertEqual(1, r.returncode); self.assertIn(LABELS[tag], r.stderr); self.assertEqual('', r.stdout)
    def test_posix_regions_and_unsupported_fallback(self):
        cases = {'zh_CN.UTF-8':'zh-CN', 'zh_SG.UTF-8':'zh-CN', 'zh_TW.UTF-8':'zh-TW',
            'zh_HK.UTF-8':'zh-TW', 'zh_MO.UTF-8':'zh-TW', 'zh-Hant.UTF-8':'zh-TW', 'zh-Hans-HK.UTF-8':'zh-TW', 'zh-Hant-SG.UTF-8':'zh-CN',
            'en_US.UTF-8':'en', 'ja_JP.UTF-8':'ja', 'ko_KR.UTF-8':'ko', 'fr_FR.UTF-8':'en', 'bad.UTF-8':'en', '':'en'}
        for value, tag in cases.items():
            with self.subTest(locale=value): self.help(tag, LANG=value)
    def test_environment_priority(self):
        self.help('ja', LANG='ko_KR.UTF-8', LC_MESSAGES='ja_JP.UTF-8')
        self.help('zh-TW', LANG='ko_KR.UTF-8', LC_MESSAGES='ja_JP.UTF-8', LC_ALL='zh_HK.UTF-8')
        self.help('ko', LANG='ko_KR.UTF-8', LC_MESSAGES='', LC_ALL='')
    def test_persistence_override_system_restore_and_changes(self):
        self.assertEqual(0, self.cli('--set-lang', 'ja').returncode)
        path = self.root / 'config/nekokem/language'
        self.assertEqual('ja\n', path.read_text()); self.assertEqual(0o600, path.stat().st_mode & 0o777)
        self.help('ja', LANG='ko_KR.UTF-8'); self.help('ko', '--lang=ko')
        self.assertEqual('ja\n', path.read_text())
        self.help('zh-TW', '--lang', 'system', LANG='zh_TW.UTF-8')
        self.assertEqual(0, self.cli('--set-lang', 'system').returncode)
        self.help('ja', LANG='ja_JP.UTF-8'); self.help('ko', LANG='ko_KR.UTF-8'); self.help('en', LANG='es_ES.UTF-8')
    def test_each_persisted_language(self):
        for tag in LABELS:
            self.assertEqual(0, self.cli('--set-lang', tag).returncode); self.help(tag)
    def test_corrupt_and_unsafe_configuration_does_not_block(self):
        directory = self.root / 'config/nekokem'; directory.mkdir(parents=True, mode=0o700)
        path = directory / 'language'
        for content in [b'fr\n', b'ja\x00ko', b'\xff', b'x'*10000, b'ja\nko\n', b'']:
            path.write_bytes(content); path.chmod(0o600); self.help('en')
        path.write_text('ja\n'); path.chmod(0o644); self.help('en')
        path.unlink(); os.mkfifo(path, 0o600); self.help('en')
        path.unlink(); path.symlink_to(self.root / 'missing'); self.help('en')
        for args in [('--lang','bad'), ('--lang',), ('--set-lang','bad')]:
            self.assertEqual(1, self.cli(*args).returncode)
    def test_xdg_home_fallback(self):
        for xdg in ['', 'relative']:
            self.assertEqual(0, self.cli('--set-lang','ko', XDG_CONFIG_HOME=xdg).returncode)
            self.help('ko', XDG_CONFIG_HOME=xdg)
        self.assertTrue((self.root / '.config/nekokem/language').exists())
    def test_ascii_and_noninteractive_help(self):
        r = self.cli('--lang','ja','--help', LANG='C')
        self.assertEqual(0, r.returncode); self.assertTrue(r.stdout.isascii()); self.assertIn('Usage:', r.stdout)
        for tag in LABELS: self.help(tag, '--lang', tag)
    def test_core_errors_localized_and_failure_leaves_no_output(self):
        plaintext = self.root / 'empty'; plaintext.touch()
        messages = {'en':'Cannot open regular input file', 'zh-CN':'无法打开普通输入文件',
            'zh-TW':'一般輸入檔案開啟失敗', 'ja':'通常の入力ファイルを開くことができません', 'ko':'일반 입력 파일 열기에 실패했습니다'}
        for tag, text in messages.items():
            r = self.cli('--lang',tag,'encrypt','hybrid',str(plaintext),'output.nkem','missing.key')
            self.assertEqual(1,r.returncode); self.assertIn(text,r.stderr)
            self.assertFalse((self.root/'output.nkem').exists())

    def test_language_switches_preserve_container_headers_fingerprints_and_round_trips(self):
        password = 'test-only-i18n-password'
        generated = self.cli('--lang', 'ja', 'keygen', input=password+'\n'+password+'\n')
        self.assertEqual(0, generated.returncode, generated.stderr)
        protected = (self.root/'keys/private.key.enc').read_bytes()
        self.assertEqual(b'NKPR', protected[:4])
        self.assertEqual(bytes([1,1,1,0]), protected[4:8])
        self.assertEqual((65536).to_bytes(4,'big'), protected[8:12])
        self.assertEqual(bytes([12,16]), protected[26:28])
        payload = bytes(range(256)) * 17
        (self.root/'input').write_bytes(payload)
        fingerprints = []
        for tag in LABELS:
            fingerprint = self.cli('--lang',tag,input='4\n1\nkeys/public.key\n5\n')
            self.assertEqual(0,fingerprint.returncode)
            matched = re.findall(r'(?:[0-9A-F]{2}:){31}[0-9A-F]{2}',fingerprint.stdout)
            self.assertEqual(1,len(matched)); fingerprints.append(matched[0])
            encrypted = self.cli('--lang',tag,'encrypt','hybrid','input',tag+'.nkem','keys/public.key')
            self.assertEqual(0,encrypted.returncode,encrypted.stderr)
            header=(self.root/(tag+'.nkem')).read_bytes()
            self.assertEqual(b'NKEM',header[:4]); self.assertEqual(3,header[4])
            decrypted = self.cli('--lang',tag,'decrypt','hybrid',tag+'.nkem',tag+'.out','keys/private.key.enc',input=password+'\n')
            self.assertEqual(0,decrypted.returncode,decrypted.stderr)
            self.assertEqual(payload,(self.root/(tag+'.out')).read_bytes())
            wrong = self.cli('--lang',tag,'decrypt','hybrid',tag+'.nkem',tag+'.wrong','keys/private.key.enc',input='incorrect\n')
            self.assertEqual(1,wrong.returncode); self.assertFalse((self.root/(tag+'.wrong')).exists())
        self.assertEqual(1,len(set(fingerprints)))
        (self.root/'--lang').write_bytes(payload)
        encrypted=self.cli('--lang','ja','encrypt','hybrid','--lang','--','keys/public.key')
        self.assertEqual(0,encrypted.returncode,encrypted.stderr)
        decrypted=self.cli('--lang','ko','decrypt','hybrid','--','positional.out','keys/private.key.enc',input=password+'\n')
        self.assertEqual(0,decrypted.returncode,decrypted.stderr)
        self.assertEqual(payload,(self.root/'positional.out').read_bytes())


if __name__ == '__main__': unittest.main(verbosity=2)
