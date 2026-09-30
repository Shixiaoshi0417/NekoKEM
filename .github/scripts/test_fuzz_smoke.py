"""Fault injection for the actual subprocess/result gate; no AFL dependency."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "run_fuzz_smoke", Path(__file__).with_name("run_fuzz_smoke.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

FAKE_AFL = r'''#!/usr/bin/env python3
import os, pathlib, signal, sys, time
out = pathlib.Path(sys.argv[sys.argv.index('-o')+1]) / 'default'
out.mkdir(parents=True)
case = os.environ.get('FUZZ_TEST_CASE', 'clean')
for name in ('crashes', 'hangs'):
    if case != 'missing_directory': (out/name).mkdir()
if case in ('crash', 'hang'):
    (out/('crashes' if case == 'crash' else 'hangs')/'id:000000').write_bytes(b'evidence')
if case == 'readme': (out/'crashes'/'README.txt').write_text('AFL crash documentation')
stats = dict(execs_done=123, run_time=15, saved_crashes=0, saved_hangs=0)
if case == 'counter_crash': stats['saved_crashes'] = 1
if case == 'counter_hang': stats['saved_hangs'] = 1
if case == 'zero_execs': stats['execs_done'] = 0
if case == 'early': stats['run_time'] = 0
if case == 'malformed': stats['saved_crashes'] = 'garbage'
if case != 'missing_stats':
    (out/'fuzzer_stats').write_text(''.join(f'{k} : {v}\n' for k,v in stats.items()))
if case == 'timeout': time.sleep(60)
if case == 'signal': os.kill(os.getpid(), signal.SIGTERM)
if case != 'interrupted_zero': print('Time limit was reached')
sys.exit(2 if case == 'nonzero' else 0)
'''


class FuzzGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.afl = self.root / 'afl-fuzz'
        self.afl.write_text(FAKE_AFL)
        self.afl.chmod(0o700)

    def run_case(self, case):
        output = self.root / case
        with patch.dict(os.environ, FUZZ_TEST_CASE=case):
            result = gate.run_fuzz(self.afl, 'seeds', 'target', output,
                                   seconds=1, wall_timeout=2)
        return result

    def test_clean_and_readme_succeed(self):
        for case in ('clean', 'readme'):
            with self.subTest(case=case):
                self.assertEqual('passed', self.run_case(case)['status'])

    def test_failures_rejected_and_evidence_retained(self):
        for case in ('crash', 'hang', 'counter_crash', 'counter_hang',
                     'zero_execs', 'early', 'malformed', 'missing_stats',
                     'missing_directory', 'nonzero', 'signal', 'interrupted_zero',
                     'timeout'):
            with self.subTest(case=case):
                with self.assertRaises(gate.FuzzFailure): self.run_case(case)
                output = self.root / case
                self.assertTrue((output/'afl.log').exists())
                evidence = json.loads((output/'result.json').read_text())
                self.assertEqual('failed', evidence['status'])
                if case == 'timeout': self.assertTrue(evidence['wall_timeout'])
                if case == 'signal': self.assertLess(evidence['exit_code'], 0)
                if case == 'nonzero': self.assertEqual(2, evidence['exit_code'])
                if case in ('crash', 'hang'):
                    self.assertEqual(1, len(list(output.rglob('id:*'))))

    def test_stale_results_cannot_pass(self):
        self.run_case('clean')
        with self.assertRaises(FileExistsError): self.run_case('clean')

    def test_old_exit_only_gate_accepts_crash_and_hang(self):
        # This is the pre-fix CI command's decision: shell exit code alone.
        for case in ('crash', 'hang'):
            with patch.dict(os.environ, FUZZ_TEST_CASE=case):
                result = subprocess.run([str(self.afl), '-V', '15', '-o',
                                         str(self.root/('legacy-'+case))],
                                        capture_output=True, check=False)
            self.assertEqual(0, result.returncode)
            # The same child result is rejected by the production gate.
            with self.assertRaises(gate.FuzzFailure): self.run_case(case)


if __name__ == '__main__':
    unittest.main()
