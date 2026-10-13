#!/usr/bin/env python3
"""Exercise real signing setup, its isolation and fail-closed configuration."""
import base64
import contextlib
import io
import os
from pathlib import Path
import tempfile
import unittest

from prepare_release_signing import prepare


class SigningTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = {k: v for k, v in os.environ.items() if not k.startswith('NEKOKEM_RELEASE_')}
        self.env.update(GITHUB_ENV=str(self.root / 'env'), ROTATE_SIGNING='false')

    def invoke(self, directory):
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            result = prepare(directory, self.env)
        return result, stdout.getvalue()

    def refused(self, directory):
        with self.assertRaises(SystemExit), contextlib.redirect_stdout(io.StringIO()):
            prepare(directory, self.env)
        self.assertFalse(directory.exists())
        self.assertFalse((self.root / 'env').exists())

    def test_missing_secrets_never_rotates(self):
        self.refused(self.root / 'signing')

    def test_authorized_rotation_and_future_same_identity(self):
        self.env['ROTATE_SIGNING'] = 'true'
        protected = self.root / 'signing'
        (store, child, values, rotated), stdout = self.invoke(protected)
        self.assertTrue(rotated)
        self.assertEqual(protected.stat().st_mode & 0o777, 0o700)
        for path in protected.iterdir():
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        password = (protected / 'store_password.txt').read_text()
        self.assertGreaterEqual(len(password), 48)
        self.assertIn('::add-mask::' + password, stdout)
        self.assertEqual(child['NEKOKEM_RELEASE_STORE_PASSWORD'], password)
        self.assertFalse((self.root / 'env').exists())

        self.env.update(ROTATE_SIGNING='false',
                        NEKOKEM_RELEASE_KEYSTORE_BASE64=base64.b64encode(store.read_bytes()).decode())
        self.env.update({'NEKOKEM_RELEASE_' + key: value for key, value in values.items()})
        again = self.root / 'again'
        (copy, child, _, rotated), _ = self.invoke(again)
        self.assertFalse(rotated)
        self.assertEqual(copy.read_bytes(), store.read_bytes())
        # An existing key needs no recovery bundle, so no password files.
        self.assertEqual(sorted(path.name for path in again.iterdir()), ['release.p12'])
        self.assertNotIn('NEKOKEM_RELEASE_KEYSTORE_BASE64', child)
        self.assertNotIn('NEKOKEM_RELEASE_KEY_ALIAS', child)
        self.assertFalse((self.root / 'env').exists())

    def test_malformed_keystore_fails_without_exporting_environment(self):
        self.env.update(NEKOKEM_RELEASE_KEYSTORE_BASE64='not base64!',
                        NEKOKEM_RELEASE_STORE_PASSWORD='example',
                        NEKOKEM_RELEASE_KEY_PASSWORD='example',
                        NEKOKEM_RELEASE_KEY_ALIAS='example')
        self.refused(self.root / 'signing')

    def test_newline_configuration_rejected(self):
        self.env.update(NEKOKEM_RELEASE_KEYSTORE_BASE64=base64.b64encode(b'bad').decode(),
                        NEKOKEM_RELEASE_STORE_PASSWORD='example\nINJECTED=value',
                        NEKOKEM_RELEASE_KEY_PASSWORD='example',
                        NEKOKEM_RELEASE_KEY_ALIAS='example')
        self.refused(self.root / 'signing')


if __name__ == '__main__':
    unittest.main()
