#!/usr/bin/env python3
"""Regression checks for preserving completed performance reports."""

import errno
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import profile_large_files as profiler


class AtomicReportTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="nekokem-report-tests-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.path = self.root / "profile.json"
        self.previous = b'{"complete": false, "sizes": [1]}\n'
        self.path.write_bytes(self.previous)
        self.following = {"complete": True, "sizes": [1, 2]}

    def assert_previous_preserved(self):
        self.assertEqual(self.path.read_bytes(), self.previous)
        self.assertEqual(json.loads(self.path.read_text()), {"complete": False, "sizes": [1]})
        self.assertEqual(list(self.root.iterdir()), [self.path])

    def test_successful_first_save_and_replacement(self):
        for first_save in (False, True):
            with self.subTest(first_save=first_save):
                if first_save:
                    self.path.unlink()
                profiler.write_report_atomic(self.path, self.following)
                self.assertEqual(json.loads(self.path.read_text()), self.following)
                self.assertTrue(self.path.read_bytes().endswith(b"\n"))
                self.assertEqual(list(self.root.iterdir()), [self.path])

    @unittest.skipUnless(sys.platform == "linux", "requires Linux file-size limits")
    def test_real_write_failure_preserves_previous_report(self):
        # The OS rejects a large JSON write, without mocked serializers/files.
        # The old truncate-and-write implementation corrupts the previous file
        # under this same limit; the replacement must leave its bytes intact.
        worker = """
import errno
from pathlib import Path
import resource
import signal
import sys
from profile_large_files import write_report_atomic
signal.signal(signal.SIGXFSZ, signal.SIG_IGN)
resource.setrlimit(resource.RLIMIT_FSIZE, (64, 64))
try:
    write_report_atomic(Path(sys.argv[1]), {"padding": "x" * 65536})
except OSError as error:
    if error.errno != errno.EFBIG:
        raise
else:
    raise AssertionError("Expected an actual file-size-limit write failure")
"""
        result = subprocess.run(
            [sys.executable, "-c", worker, str(self.path)],
            cwd=Path(__file__).resolve().parent,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))
        self.assert_previous_preserved()

    def test_fsync_failure_preserves_previous_report(self):
        with patch.object(profiler.os, "fsync", side_effect=OSError(errno.ENOSPC, "full")):
            with self.assertRaises(OSError):
                profiler.write_report_atomic(self.path, self.following)
        self.assert_previous_preserved()

    def test_replace_failure_preserves_previous_report(self):
        with patch.object(profiler.os, "replace", side_effect=OSError(errno.EACCES, "denied")):
            with self.assertRaises(OSError):
                profiler.write_report_atomic(self.path, self.following)
        self.assert_previous_preserved()

    def test_interruption_after_partial_write_preserves_previous_report(self):
        def interrupted_dump(report, output, **kwargs):
            output.write('{"complete":')
            output.flush()
            raise KeyboardInterrupt

        with patch.object(profiler.json, "dump", side_effect=interrupted_dump):
            with self.assertRaises(KeyboardInterrupt):
                profiler.write_report_atomic(self.path, self.following)
        self.assert_previous_preserved()

    def test_serialization_failure_preserves_previous_report(self):
        with self.assertRaises(TypeError):
            profiler.write_report_atomic(self.path, {"sizes": [1, object()]})
        self.assert_previous_preserved()


if __name__ == "__main__":
    unittest.main()
