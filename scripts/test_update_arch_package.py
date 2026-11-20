#!/usr/bin/env python3
"""The Arch Linux package follows one release, and its .SRCINFO follows the PKGBUILD."""
from pathlib import Path
import shutil
import tempfile
import unittest

import update_arch_package as arch

ROOT = Path(__file__).resolve().parents[1]
NAMES = ('NekoKEM-linux-x86_64.tar.gz', 'NekoKEM-linux-x86_64-GUI.tar.gz',
         'NekoKEM-linux-aarch64.tar.gz', 'NekoKEM-linux-aarch64-GUI.tar.gz')


def sums(digits):
    return ''.join(f'{digit * 64}  {name}\n' for digit, name in zip(digits, NAMES)) + \
        f'{"f" * 64}  app-release.apk\n'


class ArchPackage(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.dir = Path(temp.name)
        for name in ('PKGBUILD', 'nekokem.desktop', '.SRCINFO'):
            shutil.copyfile(ROOT / 'linux/arch' / name, self.dir / name)

    def test_repository_package_is_consistent(self):
        arch.check(ROOT / 'linux/arch')

    def test_update_sets_version_and_every_checksum(self):
        arch.update(self.dir, '9.8.7', sums('1234'), 2)
        self.assertEqual(arch.check(self.dir), '9.8.7')
        values = arch.parse((self.dir / 'PKGBUILD').read_text())
        self.assertEqual(values['pkgrel'], '2')
        self.assertEqual(values['sha256sums_x86_64'], ['1' * 64, '2' * 64])
        self.assertEqual(values['sha256sums_aarch64'], ['3' * 64, '4' * 64])
        self.assertEqual(values['sha256sums'], [arch.sha256(self.dir / 'nekokem.desktop')])
        self.assertTrue(all('/releases/download/v9.8.7/' in item for item in values['source_x86_64']))
        srcinfo = (self.dir / '.SRCINFO').read_text()
        self.assertIn('\tpkgver = 9.8.7\n', srcinfo)
        self.assertIn(f'\tsha256sums_aarch64 = {"4" * 64}\n', srcinfo)

    def test_update_needs_every_archive_in_the_release(self):
        with self.assertRaisesRegex(arch.PackageError, 'aarch64-GUI.tar.gz is not in the release'):
            arch.update(self.dir, '9.8.7', sums('123'), 1)

    def test_check_finds_a_stale_srcinfo(self):
        pkgbuild = self.dir / 'PKGBUILD'
        pkgbuild.write_text(pkgbuild.read_text().replace("provides=('nekokem')", "provides=('nekokem' 'x')"))
        with self.assertRaisesRegex(arch.PackageError, '.SRCINFO does not match'):
            arch.check(self.dir)

    def test_check_finds_a_changed_desktop_entry(self):
        desktop = self.dir / 'nekokem.desktop'
        desktop.write_text(desktop.read_text() + 'Keywords=encryption;\n')
        with self.assertRaisesRegex(arch.PackageError, 'nekokem.desktop is out of date'):
            arch.check(self.dir)

    def test_check_finds_a_download_from_another_release(self):
        pkgbuild = self.dir / 'PKGBUILD'
        text = pkgbuild.read_text()
        pkgbuild.write_text(text.replace('_release="${url}/releases/download/v${pkgver}"',
                                         '_release="${url}/releases/download/v1.0.0"'))
        with self.assertRaisesRegex(arch.PackageError, 'does not download release'):
            arch.check(self.dir)

    def test_update_of_one_architecture_keeps_the_other(self):
        before = arch.parse((self.dir / 'PKGBUILD').read_text())['sha256sums_aarch64']
        arch.update(self.dir, '9.8.7', sums('12'), 1, ('x86_64',))
        values = arch.parse((self.dir / 'PKGBUILD').read_text())
        self.assertEqual(values['sha256sums_x86_64'], ['1' * 64, '2' * 64])
        self.assertEqual(values['sha256sums_aarch64'], before)
        arch.check(self.dir)

    def test_invalid_version_is_refused(self):
        with self.assertRaisesRegex(arch.PackageError, 'Invalid version'):
            arch.update(self.dir, 'v5.2.0', sums('1234'), 1)


if __name__ == '__main__':
    unittest.main()
