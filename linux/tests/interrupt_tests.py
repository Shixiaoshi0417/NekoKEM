#!/usr/bin/env python3
"""Interrupt the CLI where it holds plaintext or hides input.

A termination signal must remove partial decrypted output and a pasted key
and restore terminal echo before the process dies of that signal; Ctrl+Z and
fg at a password prompt must leave input hidden again.
"""
import glob
import os
from pathlib import Path
import pty
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import termios
import time
import unittest

CLI = str(Path(sys.argv.pop(1)).resolve())
PASSWORD = b'interrupt-test-password\n'


def echo_on(descriptor):
    return bool(termios.tcgetattr(descriptor)[3] & termios.ECHO)


def drain(descriptor, seconds):
    data = b''
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        ready, _, _ = select.select([descriptor], [], [], 0.05)
        if ready:
            try:
                data += os.read(descriptor, 4096)
            except OSError:
                break
    return data


class InterruptTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.mkdtemp(prefix='nekokem-interrupt-tests-')
        cls.environment = dict(os.environ, LANG='C', LC_ALL='C')
        cls.environment.pop('LANGUAGE', None)
        cls.runtime = os.path.join(cls.directory, 'runtime')
        os.mkdir(cls.runtime, 0o700)
        cls.environment['XDG_RUNTIME_DIR'] = cls.runtime
        cls.paste_pattern = os.path.join(cls.runtime, 'nekokem-paste.*')
        cls.run_cli(['keygen'], PASSWORD * 2)
        with open(os.path.join(cls.directory, 'big.bin'), 'wb') as stream:
            stream.write(os.urandom(64 * 1024 * 1024))
        cls.run_cli(['encrypt', 'hybrid', 'big.bin', 'big.nkem', 'keys/public.key'], b'')

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.directory)

    @classmethod
    def run_cli(cls, arguments, data):
        subprocess.run([CLI, *arguments], input=data, cwd=cls.directory, env=cls.environment,
                       capture_output=True, check=True, timeout=120)

    def output_directory(self, name):
        path = os.path.join(self.directory, name)
        os.mkdir(path)
        self.addCleanup(shutil.rmtree, path)
        return path

    def test_interrupted_decryption_leaves_no_partial_plaintext(self):
        output = self.output_directory('interrupted')
        process = subprocess.Popen(
            [CLI, 'decrypt', 'hybrid', 'big.nkem', os.path.join(output, 'big.bin'),
             'keys/private.key.enc'],
            cwd=self.directory, env=self.environment, stdin=subprocess.PIPE,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        process.stdin.write(PASSWORD)
        process.stdin.flush()
        end = time.monotonic() + 60
        while time.monotonic() < end and not any(
                os.path.getsize(path) > 0 for path in glob.glob(os.path.join(output, '*'))):
            time.sleep(0.002)
        self.assertTrue(glob.glob(os.path.join(output, '*.tmp.*')), 'decryption never started')
        process.send_signal(signal.SIGINT)
        self.assertEqual(process.wait(timeout=30), -signal.SIGINT)
        process.stdin.close()
        self.assertEqual(os.listdir(output), [])

    def test_interrupted_paste_removes_the_key_and_restores_echo(self):
        before = set(glob.glob(self.paste_pattern))
        pid, master = pty.fork()
        if pid == 0:
            os.chdir(self.directory)
            os.execve(CLI, [CLI], self.environment)
        try:
            drain(master, 1.0)
            os.write(master, b'3\n')
            drain(master, 0.5)
            os.write(master, b'2\n')
            drain(master, 0.5)
            os.write(master, b'-----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2VuBCIEIA==\n'
                             b'-----END PRIVATE KEY-----\n')
            drain(master, 0.5)
            self.assertEqual(len(set(glob.glob(self.paste_pattern)) - before), 1)
            self.assertFalse(echo_on(master))
            os.kill(pid, signal.SIGINT)
            _, status = os.waitpid(pid, 0)
            pid = 0
            self.assertTrue(os.WIFSIGNALED(status))
            self.assertEqual(os.WTERMSIG(status), signal.SIGINT)
            self.assertTrue(echo_on(master))
            self.assertEqual(set(glob.glob(self.paste_pattern)) - before, set())
        finally:
            if pid:
                os.kill(pid, signal.SIGKILL)
                os.waitpid(pid, 0)
            os.close(master)

    def test_suspended_password_prompt_hides_input_again(self):
        output = self.output_directory('suspended')
        pid, master = pty.fork()
        if pid == 0:
            os.chdir(self.directory)
            os.execve('/bin/bash', ['bash', '--norc', '--noprofile', '-i'], self.environment)
        try:
            drain(master, 1.0)
            command = (f"'{CLI}' decrypt hybrid big.nkem '{output}/big.bin' "
                       f"keys/private.key.enc\n")
            os.write(master, command.encode())
            drain(master, 1.5)
            self.assertFalse(echo_on(master))
            os.write(master, b'\x1a')
            self.assertIn(b'Stopped', drain(master, 1.0))
            os.write(master, b'fg\n')
            drain(master, 1.0)
            self.assertFalse(echo_on(master))
            os.write(master, PASSWORD)
            end = time.monotonic() + 60
            target = os.path.join(output, 'big.bin')
            while time.monotonic() < end and not os.path.exists(target):
                drain(master, 0.2)
            self.assertEqual(os.path.getsize(target),
                             os.path.getsize(os.path.join(self.directory, 'big.bin')))
            os.write(master, b'exit\n')
            drain(master, 0.5)
            os.waitpid(pid, 0)
            pid = 0
        finally:
            if pid:
                os.kill(pid, signal.SIGKILL)
                os.waitpid(pid, 0)
            os.close(master)


if __name__ == '__main__':
    unittest.main()
