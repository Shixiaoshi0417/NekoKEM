#!/usr/bin/env python3
"""CLI audit regressions with isolated homes, runtime directories and outputs."""
import hashlib
import io
import os
from pathlib import Path
import signal
import select
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest

BINARY = Path(sys.argv.pop(1)).resolve()
INSTALLER = Path(__file__).resolve().parents[1] / 'install.sh'
PASSWORD = b'audit-test-password\n'


class AuditTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='nekokem-cli-audit-')
        self.root = Path(self.temp.name)
        self.runtime = self.root / 'runtime'
        self.runtime.mkdir(mode=0o700)
        self.env = dict(os.environ, HOME=str(self.root), XDG_RUNTIME_DIR=str(self.runtime),
                        XDG_CONFIG_HOME=str(self.root / 'config'), LANG='C.UTF-8', LC_ALL='C.UTF-8')

    def tearDown(self):
        self.temp.cleanup()

    def cli(self, *arguments, data=b''):
        return subprocess.run([str(BINARY), '--lang', 'en', *arguments], input=data,
                              cwd=self.root, env=self.env, capture_output=True, timeout=30)

    def keys(self):
        result = self.cli('keygen', data=PASSWORD * 2)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_encrypted_pkcs8_paste_finishes_immediately_and_cleans_up(self):
        for marker in [b'-----BEGIN ENCRYPTED PRIVATE KEY-----',
                       b'-----END ENCRYPTED PRIVATE KEY-----']:
            result = self.cli(data=b'3\n2\n' + marker + b'\n5\n')
            self.assertEqual(result.returncode, 1)
            self.assertIn(b'Encrypted PKCS#8 PEM cannot be pasted', result.stderr)
            self.assertEqual(list(self.runtime.iterdir()), [])

    def test_last_operation_result_survives_exit_and_eof(self):
        for ending in [b'5\n', b'']:
            result = self.cli(data=b'4\n1\nmissing.key\n' + ending)
            self.assertEqual(result.returncode, 1)
        self.assertEqual(self.cli(data=b'5\n').returncode, 0)
        self.assertEqual(self.cli().returncode, 0)
        self.keys()
        result = self.cli(data=b'4\n1\nmissing.key\n4\n1\nkeys/public.key\n5\n')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_help_and_version_report_output_failure(self):
        with open('/dev/full', 'wb') as output:
            for argument in ['--help', '--version']:
                result = subprocess.run([str(BINARY), argument], cwd=self.root, env=self.env,
                                        stdout=output, stderr=subprocess.PIPE, timeout=5)
                self.assertNotEqual(result.returncode, 0)

    def test_new_password_requires_canonical_utf8_but_unlock_uses_existing_bytes(self):
        for password in [b'\xff', b'\xc0\xaf', b'\xed\xa0\x80', b'\xf4\x90\x80\x80', b'\xe4\xb8']:
            result = self.cli('keygen', data=(password + b'\n') * 2)
            self.assertEqual(result.returncode, 1)
            self.assertIn(b'valid UTF-8', result.stderr)
            self.assertFalse((self.root / 'keys/private.key.enc').exists())
        password = '中文🔐'.encode()
        result = self.cli('keygen', data=(password + b'\n') * 2)
        self.assertEqual(result.returncode, 0, result.stderr)
        (self.root / 'input').write_bytes(b'payload')
        self.assertEqual(self.cli('encrypt', 'hybrid', 'input', 'cipher.nkem', 'keys/public.key').returncode, 0)
        result = self.cli('decrypt', 'hybrid', 'cipher.nkem', 'output', 'keys/private.key.enc', data=b'\xff\n')
        self.assertEqual(result.returncode, 1)
        self.assertNotIn(b'valid UTF-8', result.stderr)
        result = self.cli('decrypt', 'hybrid', 'cipher.nkem', 'output', 'keys/private.key.enc', data=password + b'\n')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_interactive_decryption_preserves_existing_plaintext(self):
        self.keys()
        (self.root / 'input').write_bytes(b'new plaintext')
        self.assertEqual(self.cli('encrypt', 'hybrid', 'input', 'input.nkem', 'keys/public.key').returncode, 0)
        output = self.root / 'plaintext/input'
        output.parent.mkdir(mode=0o700)
        output.write_bytes(b'previous output')
        result = self.cli(data=b'3\n1\nkeys/private.key.enc\ninput.nkem\n5\n')
        self.assertEqual(result.returncode, 1)
        self.assertIn(b'Output already exists', result.stderr)
        self.assertEqual(output.read_bytes(), b'previous output')

    def test_interactive_decryption_refuses_output_created_after_precheck(self):
        self.keys()
        (self.root / 'input').write_bytes(b'new plaintext')
        self.assertEqual(self.cli('encrypt', 'hybrid', 'input', 'input.nkem', 'keys/public.key').returncode, 0)
        process = subprocess.Popen([str(BINARY), '--lang', 'en'], cwd=self.root, env=self.env,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            process.stdin.write(b'3\n1\nkeys/private.key.enc\ninput.nkem\n')
            process.stdin.flush()
            seen = b''
            deadline = time.monotonic() + 5
            while b'Enter the private-key password:' not in seen and time.monotonic() < deadline:
                readable, _, _ = select.select([process.stdout], [], [], 0.1)
                if readable:
                    chunk = os.read(process.stdout.fileno(), 4096)
                    if not chunk:
                        break
                    seen += chunk
            self.assertIn(b'Enter the private-key password:', seen)
            output = self.root / 'plaintext/input'
            output.write_bytes(b'created during password prompt')
            process.stdin.write(PASSWORD + b'5\n')
            process.stdin.flush()
            process.stdin.close()
            process.stdin = None
            process.communicate(timeout=10)
            self.assertEqual(process.returncode, 1)
            self.assertEqual(output.read_bytes(), b'created during password prompt')
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            if process.stdin is not None:
                process.stdin.close()
            process.stdout.close()
            process.stderr.close()

    def test_displayed_paths_filter_terminal_and_unicode_controls(self):
        self.keys()
        filename = 'input\x1b[31m\n\x7f\u0085\u202e\u200b\u2028\u2029'
        (self.root / filename).write_bytes(b'payload')
        result = self.cli('encrypt', 'hybrid', filename, 'cipher.nkem', 'keys/public.key')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(b'input?[31m', result.stdout)
        for control in ['\x1b', '\x7f', '\u0085', '\u202e', '\u200b', '\u2028', '\u2029']:
            self.assertNotIn(control.encode(), result.stdout + result.stderr)
        self.assertEqual(result.stdout.count(b'\n'), 1)

    def test_paste_uses_private_runtime_or_home_and_signal_cleanup(self):
        for secure_runtime in [True, False]:
            self.runtime.chmod(0o700 if secure_runtime else 0o755)
            expected = self.runtime if secure_runtime else self.root / '.nekokem-tmp'
            process = subprocess.Popen([str(BINARY), '--lang', 'en'], cwd=self.root, env=self.env,
                                       stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            try:
                process.stdin.write(b'3\n2\n-----BEGIN PRIVATE KEY-----\nsecret\n')
                process.stdin.flush()
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline and not list(expected.glob('nekokem-paste.*/key.pem')):
                    time.sleep(0.01)
                paths = list(expected.glob('nekokem-paste.*/key.pem'))
                self.assertEqual(len(paths), 1)
                self.assertEqual(paths[0].stat().st_mode & 0o777, 0o600)
                self.assertEqual(paths[0].parent.stat().st_mode & 0o777, 0o700)
                limits = Path(f'/proc/{process.pid}/limits').read_text()
                core_line = next(line for line in limits.splitlines() if line.startswith('Max core file size'))
                self.assertEqual(core_line.split()[4:6], ['0', '0'])
                process.send_signal(signal.SIGTERM)
                self.assertEqual(process.wait(timeout=5), -signal.SIGTERM)
                self.assertEqual(list(expected.glob('nekokem-paste.*')), [])
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()
                process.stdin.close()
                process.stderr.close()

    def test_installer_pins_release_and_requires_requested_attestation(self):
        fixtures = self.root / 'fixtures'
        fixtures.mkdir()
        package = 'NekoKEM-linux-x86_64'
        contents = {'LICENSE': b'license', 'README': b'readme',
                    'nekokem': b'#!/bin/sh\nprintf "NekoKEM 4.1.0\\n"\n'}
        contents['SHA256SUMS'] = ''.join(hashlib.sha256(data).hexdigest() + '  ' + name + '\n'
                                       for name, data in contents.items()).encode()
        archive = fixtures / (package + '.tar.gz')
        with tarfile.open(archive, 'w:gz') as output:
            directory = tarfile.TarInfo(package + '/')
            directory.type = tarfile.DIRTYPE
            directory.mode = 0o755
            output.addfile(directory)
            for name, data in contents.items():
                info = tarfile.TarInfo(package + '/' + name)
                info.size = len(data)
                info.mode = 0o755 if name == 'nekokem' else 0o644
                output.addfile(info, io.BytesIO(data))
        (fixtures / 'SHA256SUMS.txt').write_text(hashlib.sha256(archive.read_bytes()).hexdigest() + '  ' + archive.name + '\n')
        tools = self.root / 'tools'
        tools.mkdir()
        scripts = {
            'uname': '#!/bin/sh\ncase "$1" in -s) echo Linux;; -m) echo x86_64;; esac\n',
            'curl': '#!/bin/sh\nwhile [ "$#" -gt 0 ]; do case "$1" in --output) shift; destination=$1;; https:*) url=$1;; esac; shift; done\nprintf "%s\\n" "$url" >> "$FIXTURES/urls"\ncp "$FIXTURES/${url##*/}" "$destination"\n',
            'gh': '#!/bin/sh\nprintf "%s\\n" "$*" > "$FIXTURES/gh-args"\nexit "${ATTESTATION_STATUS:-0}"\n',
        }
        for name, text in scripts.items():
            (tools / name).write_text(text)
            (tools / name).chmod(0o755)
        environment = dict(self.env, PATH=str(tools) + ':' + os.environ['PATH'], FIXTURES=str(fixtures),
                           NEKOKEM_INSTALL_DIR=str(self.root / 'installed'), NEKOKEM_VERIFY_ATTESTATION='1')
        (self.root / 'installed').mkdir()
        failed = subprocess.run(['sh', str(INSTALLER)], cwd=self.root, env=dict(environment, ATTESTATION_STATUS='1'),
                                capture_output=True, timeout=10)
        self.assertNotEqual(failed.returncode, 0)
        self.assertFalse((self.root / 'installed/nekokem').exists())
        passed = subprocess.run(['sh', str(INSTALLER)], cwd=self.root, env=environment,
                                capture_output=True, timeout=10)
        self.assertEqual(passed.returncode, 0, passed.stderr)
        self.assertIn('attestation verify ', (fixtures / 'gh-args').read_text())
        self.assertIn('--repo Shixiaoshi0417/NekoKEM', (fixtures / 'gh-args').read_text())
        urls = (fixtures / 'urls').read_text().splitlines()
        self.assertTrue(all('/releases/download/v4.1.0/' in url for url in urls))
        self.assertTrue((self.root / 'installed/nekokem').exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
