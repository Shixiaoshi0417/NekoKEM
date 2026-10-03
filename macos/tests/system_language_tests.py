#!/usr/bin/env python3
"""Compare the native macOS language source with actual CLI behavior."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

BINARY = Path(sys.argv.pop(1)).resolve()
LABELS = {'en': 'Usage:', 'zh-CN': '用法：', 'zh-TW': '用法：',
          'ja': '使用方法：', 'ko': '사용법:'}

def supported_language(tag):
    parts = tag.lower().replace('_', '-').split('-')
    if parts[0] == 'zh':
        if parts[-1] in {'tw', 'hk', 'mo'}: return 'zh-TW'
        if parts[-1] in {'cn', 'sg'}: return 'zh-CN'
        return 'zh-TW' if 'hant' in parts else 'zh-CN'
    return parts[0] if parts[0] in {'en', 'ja', 'ko'} else 'en'

class NativeLanguageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.env = {k: v for k, v in os.environ.items()
                    if not k.startswith('LC_') and k != 'LANG'}
        self.env['XDG_CONFIG_HOME'] = self.temp.name
        script = "ObjC.import('Foundation'); JSON.stringify(ObjC.deepUnwrap($.NSLocale.preferredLanguages))"
        native = subprocess.check_output(['osascript', '-l', 'JavaScript', '-e', script],
            env=self.env, encoding='utf-8', timeout=15)
        languages = json.loads(native)
        self.native_language = supported_language(languages[0]) if languages else 'en'

    def tearDown(self):
        self.temp.cleanup()

    def cli(self, *args, **overrides):
        return subprocess.run([str(BINARY), *args], env=dict(self.env, **overrides),
            capture_output=True, encoding='utf-8', timeout=10)

    def assert_help(self, language, *args, **overrides):
        result = self.cli(*args, '--help', **overrides)
        self.assertEqual((0, ''), (result.returncode, result.stderr))
        self.assertIn(LABELS[language], result.stdout)

    def test_finder_environment_matches_native_preferred_language(self):
        self.assert_help(self.native_language)

    def test_explicit_locale_overrides_native_display_language(self):
        self.assert_help('ja', LC_ALL='ja_JP.UTF-8', LC_MESSAGES='ko_KR.UTF-8')

    def test_explicit_ascii_locale_remains_ascii(self):
        result = self.cli('--lang', 'zh-CN', '--help', LANG='C')
        self.assertEqual(0, result.returncode)
        self.assertTrue(result.stdout.isascii())
        self.assertIn('Usage:', result.stdout)

    def test_saved_preference_and_explicit_system_override(self):
        self.assertEqual(0, self.cli('--set-lang', 'ko').returncode)
        self.assert_help('ko')
        self.assert_help(self.native_language, '--lang', 'system')
        self.assertEqual(0, self.cli('--set-lang', 'system').returncode)
        self.assert_help(self.native_language)

if __name__ == '__main__':
    if sys.platform != 'darwin': raise SystemExit('Native macOS is required')
    unittest.main(verbosity=2)
