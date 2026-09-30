#!/usr/bin/env python3
"""Reject encrypted PEM components without consulting a real controlling TTY."""
import fcntl
import os
from pathlib import Path
import pty
import select
import signal
import subprocess
import sys
import tempfile
import termios
import unittest


DRIVER = str(Path(sys.argv.pop(1)).resolve())


class PemInteractionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="nekokem-pem-tests-")
        try:
            subprocess.run([DRIVER, "fixtures", cls.directory.name], check=True,
                           timeout=30)
            for path in Path(cls.directory.name).iterdir():
                path.chmod(0o600)
        except BaseException:
            cls.directory.cleanup()
            raise

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def assert_noninteractive_rejection(self, kind, component):
        suffix = "pem" if kind == "raw" else "enc"
        path = Path(self.directory.name) / f"{kind}-{component}.{suffix}"
        master, slave = pty.openpty()

        def attach_terminal():
            os.setsid()
            fcntl.ioctl(0, termios.TIOCSCTTY, 0)

        process = None

        def terminal_output():
            output = b""
            while select.select([master], [], [], 0)[0]:
                chunk = os.read(master, 4096)
                if not chunk:
                    break
                output += chunk
            return output

        try:
            process = subprocess.Popen([DRIVER, kind, str(path)], stdin=slave,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                       preexec_fn=attach_terminal)
            try:
                stdout, stderr = process.communicate(timeout=3)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                stdout, stderr = process.communicate(timeout=5)
                self.fail(f"Core waited for terminal input: "
                          f"{stdout + stderr + terminal_output()!r}")
            evidence = stdout + stderr + terminal_output()
            self.assertEqual(process.returncode, 0, evidence)
            self.assertNotIn(b"Enter PEM pass phrase", evidence)
        finally:
            if process is not None and process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate(timeout=5)
            os.close(slave)
            os.close(master)

    def test_raw_first_component(self):
        self.assert_noninteractive_rejection("raw", 0)

    def test_raw_second_component(self):
        self.assert_noninteractive_rejection("raw", 1)

    def test_nkpr_first_component(self):
        self.assert_noninteractive_rejection("protected", 0)

    def test_nkpr_second_component(self):
        self.assert_noninteractive_rejection("protected", 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
