#!/usr/bin/env python3
"""Version drift must fail before release artifacts are built."""
from pathlib import Path
import shutil
import tempfile
import unittest

import check_versions


ROOT = Path(__file__).resolve().parents[1]


class VersionChecks(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        for relative in (
            'desktop/package.json', 'desktop/package-lock.json',
            'desktop/src-tauri/tauri.conf.json', 'desktop/src-tauri/Cargo.toml',
            'desktop/src-tauri/Cargo.lock', 'linux/src/main.c', 'linux/packaging/README',
            'linux/install.sh',
            'linux/scripts/build-linux-release.sh', 'macos/scripts/build-macos-cli.sh',
            'windows/scripts/build-windows-cli.sh', 'desktop/scripts/package-linux-gui.py',
            '.github/workflows/ci.yml', '.github/workflows/release.yml',
            'android/app/build.gradle.kts', '.github/scripts/verify_android_release.py',
            *(f'android/app/src/main/res/{directory}/strings.xml' for directory in
              ('values', 'values-zh-rCN', 'values-zh-rTW', 'values-ja', 'values-ko')),
        ):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)

    def test_repository_versions_agree(self):
        self.assertEqual(check_versions.check_versions(self.root)[1], [])

    def test_application_signing_package_and_workflow_drift_is_detected(self):
        version = check_versions.check_versions(self.root)[0]
        for relative in ('desktop/src-tauri/Cargo.toml', 'linux/src/main.c', 'linux/install.sh',
                         '.github/scripts/verify_android_release.py',
                         '.github/workflows/release.yml',
                         'android/app/src/main/res/values-ko/strings.xml'):
            with self.subTest(relative=relative):
                path = self.root / relative
                original = path.read_text()
                path.write_text(original.replace(version, '99.99.99'))
                errors = check_versions.check_versions(self.root)[1]
                self.assertTrue(any(relative in error for error in errors), errors)
                path.write_text(original)

    def test_windows_cli_metadata_cannot_hardcode_or_ignore_executable_version(self):
        path = self.root / 'windows/scripts/build-windows-cli.sh'
        original = path.read_text()
        for mutated in (original.replace("'version':version.group(1)", "'version':'99.99.99'"),
                        original.replace("'--version'", "'--help'"),
                        original.replace('check=True', 'check=False')):
            with self.subTest(mutated=mutated != original):
                self.assertNotEqual(mutated, original)
                path.write_text(mutated)
                errors = check_versions.check_versions(self.root)[1]
                self.assertTrue(any('windows/scripts/build-windows-cli.sh' in error for error in errors))
        path.write_text(original)

    def test_windows_gui_metadata_cannot_override_tauri_version(self):
        path = self.root / '.github/workflows/release.yml'
        path.write_text(path.read_text().replace('version=$version; exe_sha256',
                                                "version='99.99.99'; exe_sha256"))
        self.assertTrue(any('.github/workflows/release.yml' in error
                            for error in check_versions.check_versions(self.root)[1]))

    def test_version_code_drift_is_detected(self):
        path = self.root / '.github/scripts/verify_android_release.py'
        path.write_text(path.read_text().replace('VERSION_CODE = 10', 'VERSION_CODE = 11'))
        self.assertTrue(check_versions.check_versions(self.root)[1])

    def test_missing_signing_declaration_is_detected(self):
        path = self.root / '.github/scripts/verify_android_release.py'
        path.write_text(path.read_text().replace('VERSION_CODE =', 'OLD_VERSION_CODE ='))
        self.assertTrue(check_versions.check_versions(self.root)[1])


if __name__ == '__main__':
    unittest.main()
