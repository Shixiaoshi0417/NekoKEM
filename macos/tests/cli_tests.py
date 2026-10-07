#!/usr/bin/env python3
"""POSIX CLI safety, menu, Unicode and controlling-terminal regressions."""
import errno
import fcntl
import os
from pathlib import Path
import pty
import re
import select
import signal
import stat
import subprocess
import sys
import tempfile
import termios
import time
import unittest

EXE = str(Path(sys.argv.pop(1)).resolve())
PASSWORD = b'macos-cli-public-test-password\n'


class Tests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='nekokem-macos-cli-')
        self.root = Path(self.temp.name)
        self.env = dict(os.environ, HOME=str(self.root),
                        XDG_CONFIG_HOME=str(self.root/'config'), LC_ALL='C.UTF-8')

    def tearDown(self):
        self.temp.cleanup()

    def run_cli(self, *args, password=b'', ok=True):
        result = subprocess.run([EXE, '--lang', 'en', *map(str, args)],
                                cwd=self.root, env=self.env, input=password,
                                capture_output=True, timeout=30)
        self.assertEqual(result.returncode == 0, ok,
                         (args, result.returncode, result.stdout, result.stderr))
        return result

    def generate(self, password=PASSWORD, replace=False):
        replacing = ('--replace',) if replace else ()
        self.run_cli('keygen', 'hybrid', *replacing, password=password*2)
        key = self.root/'keys/private.key.enc'
        self.assertEqual(stat.S_IMODE(key.stat().st_mode), 0o600)
        self.assertEqual(key.stat().st_nlink, 1)
        self.assertEqual(key.read_bytes()[:4], b'NKPR')
        self.assertNotIn(b'-----BEGIN PRIVATE KEY-----', key.read_bytes())
        self.assertFalse((self.root/'keys/private.key').exists())

    def valid_cipher(self):
        self.generate()
        (self.root/'plain').write_bytes(b'original\x00\r\n\x1a')
        self.run_cli('encrypt', 'hybrid', 'plain', 'valid.nkem', 'keys/public.key')

    def assert_no_transaction_artifacts(self):
        self.assertFalse(any('.tmp.' in path.name or '.bak.' in path.name
                             for path in self.root.rglob('*')))

    def test_version_and_unicode_paths(self):
        self.assertEqual(self.run_cli('--version').stdout, b'NekoKEM 4.1.0\n')
        self.generate()
        plain = self.root/'中文-日本語-한국어-😀.bin'
        plain.write_bytes(bytes(range(256))*4096)
        encrypted, output = self.root/'加密😀.nkem', self.root/'解密😀.bin'
        self.run_cli('encrypt', 'hybrid', plain, encrypted, 'keys/public.key')
        self.run_cli('decrypt', 'hybrid', encrypted, output, 'keys/private.key.enc', password=PASSWORD)
        self.assertEqual(plain.read_bytes(), output.read_bytes())

    def test_all_five_menu_actions_and_pasted_public_key(self):
        data = bytes(range(256))+b'\x00\r\nmenu'
        (self.root/'plain.bin').write_bytes(data)
        commands = (b'1\n'+PASSWORD*2+
                    b'2\n1\nkeys/public.key\nplain.bin\n'+
                    b'3\n1\nkeys/private.key.enc\nencrypted/plain.bin.nkem\n'+PASSWORD+
                    b'4\n1\nkeys/public.key\n5\n')
        result = self.run_cli(password=commands)
        self.assertEqual(result.stderr, b'')
        self.assertEqual((self.root/'plaintext/plain.bin').read_bytes(), data)
        for directory in ['keys', 'encrypted', 'plaintext']:
            self.assertEqual(stat.S_IMODE((self.root/directory).stat().st_mode), 0o700)
        public = (self.root/'keys/public.key').read_bytes()
        by_path = self.run_cli(password=b'4\n1\nkeys/public.key\n5\n')
        pasted = self.run_cli(password=b'4\n2\n'+public+b'5\n')
        pattern = rb'(?:[0-9A-F]{2}:){31}[0-9A-F]{2}'
        self.assertEqual(re.findall(pattern, pasted.stdout), re.findall(pattern, by_path.stdout))
        self.assertEqual(pasted.stderr, b'')

    def test_wrong_password_tamper_versions_and_preserved_output(self):
        self.valid_cipher()
        sentinel = b'authenticated-output-sentinel'
        output = self.root/'output'
        output.write_bytes(sentinel)
        self.run_cli('decrypt', 'hybrid', 'valid.nkem', 'output', 'keys/private.key.enc', password=b'wrong\n', ok=False)
        self.assertEqual(output.read_bytes(), sentinel)
        valid = (self.root/'valid.nkem').read_bytes()
        invalid = []
        for offset, value in [(4, 1), (4, 2), (5, 4), (10, 1), (12, 1), (24, 0), (25, 0), (26, 0), (-1, valid[-1]^1)]:
            data = bytearray(valid)
            data[offset] = value
            invalid.append(data)
        zero_peer, bad_kem = bytearray(valid), bytearray(valid)
        zero_peer[32:88] = bytes(56)
        bad_kem[88] ^= 1
        invalid.extend([valid[:-1], valid+b'X', zero_peer, bad_kem])
        for data in invalid:
            (self.root/'bad.nkem').write_bytes(data)
            self.run_cli('decrypt', 'hybrid', 'bad.nkem', 'output', 'keys/private.key.enc', password=PASSWORD, ok=False)
            self.assertEqual(output.read_bytes(), sentinel)
        self.assert_no_transaction_artifacts()

    def test_private_permissions_symlinks_and_hardlinks(self):
        self.valid_cipher()
        key = self.root/'keys/private.key.enc'
        for mode in [0o644, 0o660, 0o777]:
            key.chmod(mode)
            self.run_cli('decrypt', 'hybrid', 'valid.nkem', 'output', key, password=PASSWORD, ok=False)
            self.assertFalse((self.root/'output').exists())
        key.chmod(0o600)
        (self.root/'symlink.enc').symlink_to(key)
        self.run_cli('decrypt', 'hybrid', 'valid.nkem', 'output', 'symlink.enc', password=PASSWORD, ok=False)
        os.link(key, self.root/'hardlink.enc')
        for name in ['keys/private.key.enc', 'hardlink.enc']:
            self.run_cli('decrypt', 'hybrid', 'valid.nkem', 'output', name, password=PASSWORD, ok=False)
        self.assertFalse((self.root/'output').exists())

    def test_darwin_extended_private_acl_is_rejected(self):
        if sys.platform != 'darwin':
            self.skipTest('Darwin extended ACL regression runs on the native macOS CI runner')
        self.valid_cipher()
        key = self.root/'keys/private.key.enc'
        subprocess.run(['/bin/chmod', '+a', 'everyone allow read', str(key)], check=True, capture_output=True)
        before = key.read_bytes()
        self.run_cli('decrypt', 'hybrid', 'valid.nkem', 'output', key, password=PASSWORD, ok=False)
        self.assertFalse((self.root/'output').exists())
        self.assertEqual(key.read_bytes(), before)

    def test_nonblocking_fifo_and_device_rejection(self):
        self.valid_cipher()
        os.mkfifo(self.root/'fifo', 0o600)
        operations = [
            ('encrypt', 'hybrid', 'fifo', 'output', 'keys/public.key'),
            ('encrypt', 'hybrid', 'plain', 'output', 'fifo'),
            ('decrypt', 'hybrid', 'fifo', 'output', 'keys/private.key.enc'),
            ('decrypt', 'hybrid', 'valid.nkem', 'output', 'fifo'),
            ('encrypt', 'hybrid', '/dev/null', 'output', 'keys/public.key'),
        ]
        for args in operations:
            started = time.monotonic()
            self.run_cli(*args, password=PASSWORD, ok=False)
            self.assertLess(time.monotonic()-started, 5)
            self.assertFalse((self.root/'output').exists())

    def test_unsafe_private_directory_is_rejected(self):
        for mode in [0o755, 0o777]:
            with self.subTest(mode=mode):
                directory = self.root/'keys'
                directory.mkdir(mode=mode)
                directory.chmod(mode)
                self.run_cli('keygen', password=PASSWORD*2, ok=False)
                self.assertFalse((directory/'private.key.enc').exists())
                directory.rmdir()
        (self.root/'actual').mkdir(mode=0o700)
        (self.root/'keys').symlink_to(self.root/'actual', target_is_directory=True)
        self.run_cli('keygen', password=PASSWORD*2, ok=False)
        self.assertFalse((self.root/'actual/private.key.enc').exists())

    def test_public_size_and_tail_limits(self):
        self.generate()
        (self.root/'plain').write_bytes(b'data')
        public = self.root/'keys/public.key'
        data = public.read_bytes()
        public.write_bytes(data+b'X')
        self.run_cli('encrypt', 'hybrid', 'plain', 'output', public, ok=False)
        public.write_bytes(data+b' '*(1048576-len(data)))
        self.run_cli('encrypt', 'hybrid', 'plain', 'output', public)
        public.write_bytes(public.read_bytes()+b' ')
        self.run_cli('encrypt', 'hybrid', 'plain', 'extra-output', public, ok=False)
        self.assertFalse((self.root/'extra-output').exists())

    def test_utf8_crlf_and_exact_password_limit(self):
        passwords = [('中文-日本語-한국어-😀'+'a'*16).encode('utf-8')+b'\r\n', b'a'*1024+b'\n']
        for index, password in enumerate(passwords):
            # keygen never replaces keys unless asked to.
            self.generate(password=password, replace=index > 0)
            (self.root/'plain').write_bytes(b'data')
            self.run_cli('encrypt', 'hybrid', 'plain', 'cipher.nkem', 'keys/public.key')
            self.run_cli('decrypt', 'hybrid', 'cipher.nkem', 'output', 'keys/private.key.enc', password=password)
            self.assertEqual((self.root/'output').read_bytes(), b'data')

    def test_password_and_prompt_bounds(self):
        result = self.run_cli('keygen', password=b'\n\n', ok=False)
        self.assertIn(b'Password must not be empty', result.stderr)
        result = self.run_cli('keygen', password=b'a'*1025+b'\n'+b'a'*1025+b'\n', ok=False)
        self.assertIn(b'Password exceeds 1024 bytes', result.stderr)
        result = self.run_cli(password=b'a'*5000+b'\n5\n')
        self.assertIn(b'Input exceeds 4096 bytes', result.stderr)
        result = self.run_cli(password=b'2\n2\n'+b'a'*17000+b'\n5\n')
        self.assertIn(b'Pasted-key line exceeds 16384 bytes', result.stderr)
        self.assertFalse((self.root/'keys/private.key.enc').exists())

    def terminal_run(self, arguments, rounds):
        master, slave = pty.openpty()
        transcript = bytearray()

        def attach_terminal():
            os.setsid()
            fcntl.ioctl(0, termios.TIOCSCTTY, 0)

        process = subprocess.Popen([EXE, '--lang', 'en', *arguments], cwd=self.root,
                                   env=self.env, stdin=slave, stdout=slave,
                                   stderr=slave, preexec_fn=attach_terminal)
        os.close(slave)
        deadline = time.monotonic()+20

        def receive():
            if not select.select([master], [], [], 0.1)[0]:
                return b''
            try:
                data = os.read(master, 4096)
            except OSError as error:
                if error.errno == errno.EIO:
                    return b''
                raise
            transcript.extend(data)
            return data

        try:
            cursor = 0
            for prompt, data, hidden in rounds:
                while prompt not in transcript[cursor:]:
                    receive()
                    self.assertIsNone(process.poll(), bytes(transcript))
                    self.assertLess(time.monotonic(), deadline, bytes(transcript))
                cursor = len(transcript)
                if hidden:
                    # The paste notice is flushed immediately before echo is disabled.
                    while termios.tcgetattr(master)[3] & termios.ECHO:
                        self.assertIsNone(process.poll(), bytes(transcript))
                        self.assertLess(time.monotonic(), deadline,
                                        'Sensitive input never disabled terminal echo')
                        receive()
                os.write(master, data)
            while process.poll() is None:
                receive()
                self.assertLess(time.monotonic(), deadline, bytes(transcript))
            while receive():
                pass
            self.assertEqual(process.returncode, 0, bytes(transcript))
            return bytes(transcript)
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)
            os.close(master)

    def test_password_and_pasted_private_input_disable_terminal_echo(self):
        password = b'PASSWORD-ECHO-SENTINEL'
        transcript = self.terminal_run(['keygen'], [
            (b'password:', password+b'\n', True),
            (b'password:', password+b'\n', True),
        ])
        self.assertNotIn(password, transcript)
        # Choose the private-key paste action, supply invalid bounded PEM and exit.
        private = b'PRIVATE-ECHO-SENTINEL'
        transcript = self.terminal_run([], [
            (b'Select [1-5]:', b'3\n', False),
            (b'Select [1/2]:', b'2\n', False),
            (b'input will not be echoed.', private+b'\n-----END PRIVATE KEY-----\n-----END PRIVATE KEY-----\n', True),
            (b'.nkem file path:', b'missing.nkem\n', False),
            (b'Select [1-5]:', b'5\n', False),
        ])
        self.assertNotIn(private, transcript)
        self.assert_no_transaction_artifacts()


if __name__ == '__main__':
    assert os.name == 'posix', 'Run with a native POSIX executable'
    unittest.main(verbosity=2)
