"""Exercise SDK download failures without network or a real Android installation."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('install_android_emulator_image.sh')

class ImageInstallTests(unittest.TestCase):
    def run_install(self, plan, api='35'):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manager = root/'cmdline-tools/latest/bin/sdkmanager'
            manager.parent.mkdir(parents=True)
            manager.write_text('#!' + sys.executable + '\n' + '''
import json, os, pathlib, sys
root = pathlib.Path(next(a.split('=',1)[1] for a in sys.argv if a.startswith('--sdk_root=')))
counter = root/'attempts'
attempt = int(counter.read_text())+1 if counter.exists() else 1
counter.write_text(str(attempt))
result = json.loads(os.environ['FAKE_SDK_PLAN'])[attempt-1]
if result == 'download-error': sys.exit(1)
package = sys.argv[sys.argv.index('--install')+1]
image = root.joinpath(*package.split(';'))
image.mkdir(parents=True, exist_ok=True)
(image/'package.xml').write_text('<repository><localPackage path="'+package+'"/></repository>')
if result == 'complete': (image/'system.img').write_bytes(b'fixture')
elif result == 'corrupt-metadata': (image/'package.xml').write_text('not XML')
''')
            manager.chmod(0o755)
            commands = root/'commands'
            commands.mkdir()
            (commands/'sleep').write_text('#!/bin/sh\nexit 0\n')
            (commands/'sleep').chmod(0o755)
            env = dict(os.environ, FAKE_SDK_PLAN=json.dumps(plan),
                       PATH=str(commands)+os.pathsep+os.environ['PATH'])
            result = subprocess.run(['bash',str(SCRIPT),str(root),api],
                                    capture_output=True,text=True,env=env,timeout=10)
            attempts = int((root/'attempts').read_text()) if (root/'attempts').exists() else 0
            return result, attempts

    def test_transient_download_failure_then_valid_image(self):
        result, attempts = self.run_install(['download-error','complete'])
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(attempts,2)

    def test_sdk_success_with_partial_image_is_not_success(self):
        result, attempts = self.run_install(['partial','corrupt-metadata','complete'])
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(attempts,3)
        self.assertIn('Incomplete emulator image',result.stderr)

    def test_exhausted_download_failures_remain_failed(self):
        result, attempts = self.run_install(['download-error']*3)
        self.assertNotEqual(result.returncode,0)
        self.assertEqual(attempts,3)
        self.assertIn('exhausted retries',result.stderr)

    def test_unsupported_api_does_not_install(self):
        result, attempts = self.run_install(['complete'],api='34')
        self.assertNotEqual(result.returncode,0)
        self.assertEqual(attempts,0)

if __name__ == '__main__':
    unittest.main(verbosity=2)
