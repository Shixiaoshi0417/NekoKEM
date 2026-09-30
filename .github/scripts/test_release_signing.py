#!/usr/bin/env python3
"""Exercise real signing setup, persistence and fail-closed configuration."""
import base64
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('prepare_release_signing.py')

class SigningTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = {k:v for k,v in os.environ.items() if not k.startswith('NEKOKEM_RELEASE_')}
        self.env.update(RUNNER_TEMP=str(self.root), GITHUB_ENV=str(self.root/'env'), ROTATE_SIGNING='false')

    def invoke(self):
        return subprocess.run(['python3', str(SCRIPT)], env=self.env, capture_output=True, text=True)

    def test_missing_secrets_never_rotates(self):
        result = self.invoke()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root/'nekokem-release-signing/release.p12').exists())
        self.assertFalse((self.root/'env').exists())

    def test_authorized_rotation_and_future_same_identity(self):
        self.env['ROTATE_SIGNING'] = 'true'
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        protected = self.root/'nekokem-release-signing'
        self.assertEqual(protected.stat().st_mode & 0o777, 0o700)
        original = (protected/'release.p12').read_bytes()
        for path in protected.iterdir():
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        password = (protected/'store_password.txt').read_text()
        self.assertGreaterEqual(len(password), 48)
        self.assertIn('::add-mask::'+password, result.stdout)
        credentials = {name:(protected/(name.lower()+'.txt')).read_text()
                       for name in ['STORE_PASSWORD','KEY_PASSWORD','KEY_ALIAS']}
        with tempfile.TemporaryDirectory() as other:
            self.env.update(RUNNER_TEMP=other, GITHUB_ENV=other+'/env', ROTATE_SIGNING='false',
                            NEKOKEM_RELEASE_KEYSTORE_BASE64=base64.b64encode(original).decode())
            self.env.update({'NEKOKEM_RELEASE_'+key:value for key,value in credentials.items()})
            result = self.invoke()
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((Path(other)/'nekokem-release-signing/release.p12').read_bytes(), original)

    def test_malformed_keystore_fails_without_exporting_environment(self):
        self.env.update(NEKOKEM_RELEASE_KEYSTORE_BASE64='not base64!',
                        NEKOKEM_RELEASE_STORE_PASSWORD='example',
                        NEKOKEM_RELEASE_KEY_PASSWORD='example',
                        NEKOKEM_RELEASE_KEY_ALIAS='example')
        self.assertNotEqual(self.invoke().returncode, 0)
        self.assertFalse((self.root/'env').exists())

    def test_newline_configuration_rejected(self):
        self.env.update(NEKOKEM_RELEASE_KEYSTORE_BASE64=base64.b64encode(b'bad').decode(),
                        NEKOKEM_RELEASE_STORE_PASSWORD='example\nINJECTED=value',
                        NEKOKEM_RELEASE_KEY_PASSWORD='example',
                        NEKOKEM_RELEASE_KEY_ALIAS='example')
        self.assertNotEqual(self.invoke().returncode, 0)
        self.assertFalse((self.root/'env').exists())

if __name__ == '__main__':
    unittest.main()
