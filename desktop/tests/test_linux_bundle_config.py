"""Exercise the native bundle wrapper and its direct TLS loader dependency."""
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class LinuxBundleConfigTests(unittest.TestCase):
    def wrapper_config(self, architecture):
        with tempfile.TemporaryDirectory(prefix='nekokem-bundle-config-') as directory:
            tools = Path(directory)
            uname = tools / 'uname'
            uname.write_text('#!/bin/sh\ncase "$1" in\n'
                             '-s) printf "Linux\\n" ;;\n'
                             '-m) printf "%s\\n" "$NEKOKEM_TEST_ARCH" ;;\n'
                             '*) exit 2 ;;\nesac\n')
            npm = tools / 'npm'
            npm.write_text(f'#!{sys.executable}\nimport json, sys\n'
                           'print(json.dumps(sys.argv[1:]))\n')
            uname.chmod(0o755)
            npm.chmod(0o755)
            environment = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ['PATH'],
                               NEKOKEM_TEST_ARCH=architecture)
            arguments = json.loads(subprocess.check_output(
                ['bash', str(ROOT / 'desktop/scripts/build-linux-gui.sh'), architecture],
                env=environment, text=True))
        self.assertEqual(arguments[:4], ['run', 'tauri', '--', 'build'])
        self.assertEqual(arguments[arguments.index('--target') + 1],
                         architecture + '-unknown-linux-gnu')
        self.assertEqual(arguments[-4:], ['--bundles', 'deb,rpm', '--', '--locked'])
        return json.loads(arguments[arguments.index('--config') + 1])

    def test_each_target_declares_only_its_native_loader(self):
        loaders = {'x86_64': 'ld-linux-x86-64.so.2()(64bit)',
                   'aarch64': 'ld-linux-aarch64.so.1()(64bit)'}
        for architecture, loader in loaders.items():
            with self.subTest(architecture=architecture):
                requirements = self.wrapper_config(architecture)['bundle']['linux']['rpm']['depends']
                self.assertEqual(requirements.count(loader), 1)
                self.assertNotIn(loaders['aarch64' if architecture == 'x86_64' else 'x86_64'],
                                 requirements)
                self.assertIn('libc.so.6(GLIBC_2.39)(64bit)', requirements)

    def test_native_tls_elf_dependencies_are_covered(self):
        # Exercise the same PIC/thread-local object linkage as Core. A linker
        # may relax TLS accesses, so verify actual DT_NEEDED entries rather than
        # requiring a loader entry on every local compiler/libc combination.
        architecture = platform.machine()
        self.assertIn(architecture, ('x86_64', 'aarch64'))
        with tempfile.TemporaryDirectory(prefix='nekokem-bundle-tls-') as directory:
            stage = Path(directory)
            source = stage / 'tls.c'
            source.write_text('static _Thread_local volatile int value;\n'
                              'int access_tls(int input) { value = input; return value; }\n')
            main = stage / 'main.c'
            main.write_text('int access_tls(int);\n'
                            'int main(void) { return access_tls(0); }\n')
            object_file, binary = stage / 'tls.o', stage / 'tls'
            subprocess.run(['cc', '-O2', '-fPIC', '-c', str(source), '-o', str(object_file)], check=True)
            symbols = subprocess.check_output(['readelf', '-sW', str(object_file)], text=True)
            self.assertRegex(symbols, r'\bTLS\s+LOCAL\s+DEFAULT\s+\S+\s+value\b')
            subprocess.run(['cc', '-pie', str(main), str(object_file), '-o', str(binary)], check=True)
            dynamic = subprocess.check_output(['readelf', '-dW', str(binary)], text=True)
            dependencies = set(re.findall(r'\(NEEDED\).*?\[([^\]]+)\]', dynamic))
            subprocess.run([str(binary)], check=True)
        self.assertTrue(dependencies)
        requirements = set(self.wrapper_config(architecture)['bundle']['linux']['rpm']['depends'])
        self.assertFalse({name + '()(64bit)' for name in dependencies} - requirements)


if __name__ == '__main__':
    unittest.main()
