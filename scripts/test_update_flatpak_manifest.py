#!/usr/bin/env python3
"""The Flatpak manifest follows one release, and its AppStream data lists that release."""
import json
from pathlib import Path
import shutil
import tempfile
import unittest

import update_flatpak_manifest as flatpak

ROOT = Path(__file__).resolve().parents[1]
NAMES = ('NekoKEM-linux-x86_64.tar.gz', 'NekoKEM-linux-x86_64-GUI.tar.gz',
         'NekoKEM-linux-aarch64.tar.gz', 'NekoKEM-linux-aarch64-GUI.tar.gz')


def sums(digits):
    return ''.join(f'{digit * 64}  {name}\n' for digit, name in zip(digits, NAMES)) + \
        f'{"f" * 64}  app-release.apk\n'


class FlatpakManifest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.dir = self.root / 'flatpak'
        shutil.copytree(flatpak.PACKAGE, self.dir)

    def manifest(self, directory=None):
        return json.loads(flatpak.files(directory or self.dir)[0].read_text())

    def test_repository_manifest_is_consistent(self):
        flatpak.check(flatpak.PACKAGE)

    def test_update_sets_every_archive_and_adds_the_release_first(self):
        flatpak.update(self.dir, '9.8.7', sums('1234'), '2030-01-02')
        self.assertEqual(flatpak.check(self.dir), '9.8.7')
        archives = flatpak.archive_sources(self.manifest())
        self.assertEqual(archives[('x86_64', 'cli')]['sha256'], '1' * 64)
        self.assertEqual(archives[('x86_64', 'gui')]['sha256'], '2' * 64)
        self.assertEqual(archives[('aarch64', 'cli')]['sha256'], '3' * 64)
        self.assertEqual(archives[('aarch64', 'gui')]['sha256'], '4' * 64)
        self.assertTrue(all('/releases/download/v9.8.7/' in source['url'] for source in archives.values()))
        releases = flatpak.releases(flatpak.files(self.dir)[1].read_text())
        self.assertEqual(releases[0], ('9.8.7', '2030-01-02'))
        self.assertIn(('5.2.0', '2026-11-15'), releases)

    def test_updating_the_same_release_again_keeps_one_entry(self):
        flatpak.update(self.dir, '9.8.7', sums('1234'), '2030-01-02')
        flatpak.update(self.dir, '9.8.7', sums('5678'), '2030-01-03')
        releases = flatpak.releases(flatpak.files(self.dir)[1].read_text())
        self.assertEqual([version for version, _ in releases].count('9.8.7'), 1)
        self.assertEqual(flatpak.archive_sources(self.manifest())[('x86_64', 'cli')]['sha256'], '5' * 64)

    def test_missing_checksum_or_bad_version_is_refused(self):
        with self.assertRaises(flatpak.ManifestError):
            flatpak.update(self.dir, '9.8.7', sums('123'), '2030-01-02')
        with self.assertRaises(flatpak.ManifestError):
            flatpak.update(self.dir, 'v9.8', sums('1234'), '2030-01-02')

    def test_check_refuses_mixed_releases_and_a_stale_appstream_release(self):
        current = f"/v{flatpak.releases(flatpak.files(self.dir)[1].read_text())[0][0]}/"
        manifest = self.manifest()
        self.assertIn(current, manifest['modules'][0]['sources'][0]['url'])
        manifest['modules'][0]['sources'][0]['url'] = manifest['modules'][0]['sources'][0]['url'].replace(
            current, '/v5.1.0/')
        flatpak.write_manifest(flatpak.files(self.dir)[0], manifest)
        with self.assertRaises(flatpak.ManifestError):
            flatpak.check(self.dir)
        shutil.rmtree(self.dir)
        shutil.copytree(flatpak.PACKAGE, self.dir)
        manifest = self.manifest()
        for source in manifest['modules'][0]['sources']:
            if 'url' in source:
                source['url'] = source['url'].replace(current, '/v9.9.9/')
        flatpak.write_manifest(flatpak.files(self.dir)[0], manifest)
        with self.assertRaises(flatpak.ManifestError):
            flatpak.check(self.dir)

    def test_local_build_uses_only_the_given_archives(self):
        cli = self.root / 'cli.tar.gz'
        gui = self.root / 'gui.tar.gz'
        cli.write_bytes(b'cli')
        gui.write_bytes(b'gui')
        output = self.root / 'local'
        flatpak.local(output, 'aarch64', '9.8.7', cli, gui, '2030-01-02')
        sources = self.manifest(output)['modules'][0]['sources']
        archives = [source for source in sources if source['type'] == 'archive']
        self.assertEqual([(source['dest'], source['path']) for source in archives],
                         [('cli', str(cli)), ('gui', str(gui))])
        self.assertTrue(all(source['only-arches'] == ['aarch64'] and 'url' not in source for source in archives))
        self.assertEqual(archives[0]['sha256'], flatpak.hashlib.sha256(b'cli').hexdigest())
        metainfo, desktop = flatpak.files(output)[1:]
        self.assertEqual(flatpak.releases(metainfo.read_text())[0], ('9.8.7', '2030-01-02'))
        self.assertTrue(desktop.is_file())
        # The repository's own files are untouched.
        flatpak.check(flatpak.PACKAGE)


if __name__ == '__main__':
    unittest.main()
