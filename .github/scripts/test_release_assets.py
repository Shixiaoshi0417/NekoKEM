#!/usr/bin/env python3
"""Mutation regressions for the public asset selection and its checks."""
import hashlib
import io
import json
from pathlib import Path
import plistlib
import shutil
import tarfile
import tempfile
import unittest
from unittest import mock
import zipfile

import publish_release
import release_assets as assets

VERSION = '4.2.0'
SOURCE = 'a' * 40
RUN = '123456'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def write_tar(path, members):
    with tarfile.open(path, 'w:gz') as archive:
        for name, data in members.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))


def write_zip(path, members):
    with zipfile.ZipFile(path, 'w') as archive:
        for name, data in members.items():
            archive.writestr(name, data)


def write_sums(path, files, newline='\n'):
    path.write_bytes(''.join(f'{digest(file.read_bytes())}  {file.name}{newline}'
                             for file in files).encode('ascii'))


def metadata(**extra):
    return json.dumps({'source_sha': SOURCE, 'run_id': RUN, 'version': VERSION, **extra}).encode()


def build_release(root, version=VERSION, app_version=VERSION, windows_run=RUN, rotated=False):
    android = root / 'NekoKEM-android-signed'
    android.mkdir(parents=True)
    apk = android / 'app-release.apk'
    apk.write_bytes(b'signed apk')
    (android / 'signing-recovery.p7m').write_bytes(b'encrypted recovery envelope')
    (android / 'build-metadata.json').write_text(json.dumps({
        'source_sha': SOURCE, 'run_id': int(RUN), 'version': version, 'version_code': 11,
        'core_version': '4.0', 'apk_sha256': digest(apk.read_bytes()),
        'signer_sha256': assets.SIGNER, 'previous_signer_sha256': assets.SIGNER,
        'signing_key_rotated': rotated}))
    for arch in ('x86_64', 'aarch64'):
        cli = root / f'NekoKEM-linux-{arch}'
        cli.mkdir()
        write_tar(cli / f'NekoKEM-linux-{arch}.tar.gz',
                  {f'NekoKEM-linux-{arch}/README': f'NekoKEM Linux CLI {version}\n====\n'.encode()})
        gui = root / f'NekoKEM-linux-{arch}-gui'
        gui.mkdir()
        packages = []
        for suffix in ('deb', 'rpm', 'tar.gz'):
            package = gui / f'NekoKEM-linux-{arch}-GUI.{suffix}'
            package.write_bytes(f'{arch} {suffix}'.encode())
            packages.append(package)
        write_sums(gui / 'gui-SHA256SUMS.txt', packages)
        (gui / 'gui-build-metadata.json').write_bytes(metadata(version=version))
    macos = root / 'NekoKEM-macos-arm64'
    macos.mkdir()
    write_tar(macos / 'NekoKEM-macos-arm64.tar.gz',
              {'NekoKEM-macos-arm64/build-metadata.json': metadata(version=version)})
    write_sums(macos / 'macos-SHA256SUMS.txt', [macos / 'NekoKEM-macos-arm64.tar.gz'])
    macos_gui = root / 'NekoKEM-macos-arm64-gui'
    macos_gui.mkdir()
    info = plistlib.dumps({'CFBundleShortVersionString': app_version})
    write_zip(macos_gui / 'NekoKEM-macos-arm64-GUI.zip', {
        'NekoKEM-macos-arm64-GUI/build-metadata.json': json.dumps(
            {'source_sha': SOURCE, 'version': version}),
        'NekoKEM-macos-arm64-GUI/NekoKEM.app/Contents/Info.plist': info})
    (macos_gui / 'NekoKEM-macos-arm64-GUI.dmg').write_bytes(b'disk image')
    write_sums(macos_gui / 'gui-SHA256SUMS.txt',
               [macos_gui / 'NekoKEM-macos-arm64-GUI.zip', macos_gui / 'NekoKEM-macos-arm64-GUI.dmg'])
    windows = root / 'NekoKEM-windows-x86_64'
    windows.mkdir()
    windows_metadata = json.dumps({'source_sha': SOURCE, 'run_id': windows_run})
    write_zip(windows / 'NekoKEM-windows-x86_64.zip', {'build-metadata.json': windows_metadata})
    write_sums(windows / 'windows-SHA256SUMS.txt', [windows / 'NekoKEM-windows-x86_64.zip'], '\r\n')
    windows_gui = root / 'NekoKEM-windows-gui-x86_64'
    windows_gui.mkdir()
    write_zip(windows_gui / 'NekoKEM-Windows-GUI.zip', {'build-metadata.json': windows_metadata})


class ReleaseAssetTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.artifacts = self.root / 'artifacts'
        self.checked = []

    def apk_check(self, apk, version, version_code):
        self.checked.append((apk.name, version, version_code))

    def verify(self):
        return assets.verify(self.artifacts, VERSION, SOURCE, RUN, apk_check=self.apk_check)

    def test_consistent_release_is_accepted(self):
        build_release(self.artifacts)
        self.assertEqual(self.verify(), 11)
        self.assertEqual(self.checked, [('app-release.apk', VERSION, 11)])

    def test_collect_selects_exactly_the_packages_and_their_sums(self):
        build_release(self.artifacts)
        hashes = assets.collect(self.artifacts, self.root / 'public')
        names = sorted(path.name for path in (self.root / 'public').iterdir())
        self.assertEqual(names, sorted([*assets.PACKAGES, assets.SUMS_NAME]))
        self.assertNotIn('signing-recovery.p7m', names)
        sums = (self.root / 'public' / assets.SUMS_NAME).read_text().splitlines()
        self.assertEqual(sums, [f'{hashes[name]}  {name}' for name in sorted(hashes)])
        for name in assets.PACKAGES:
            self.assertEqual(hashes[name], assets.sha256(self.root / 'public' / name))

    def test_missing_or_duplicate_packages_are_refused(self):
        build_release(self.artifacts)
        (self.artifacts / 'NekoKEM-macos-arm64-gui/NekoKEM-macos-arm64-GUI.dmg').unlink()
        with self.assertRaises(assets.ReleaseError):
            assets.collect(self.artifacts, self.root / 'missing')
        shutil.rmtree(self.artifacts)
        build_release(self.artifacts)
        shutil.copy(self.artifacts / 'NekoKEM-android-signed/app-release.apk',
                    self.artifacts / 'NekoKEM-linux-x86_64/app-release.apk')
        with self.assertRaises(assets.ReleaseError):
            assets.collect(self.artifacts, self.root / 'duplicate')

    def test_mismatches_are_refused(self):
        cases = {
            'macOS app version': dict(app_version='4.1.0'),
            'release version': dict(version='4.1.9'),
            'Windows run': dict(windows_run='999'),
            'rotated signer': dict(rotated=True),
        }
        for label, options in cases.items():
            with self.subTest(label):
                shutil.rmtree(self.artifacts, ignore_errors=True)
                build_release(self.artifacts, **options)
                with self.assertRaises(assets.ReleaseError):
                    self.verify()

    def test_tampered_package_or_metadata_is_refused(self):
        tampering = {
            'internal checksum': lambda: (self.artifacts / 'NekoKEM-linux-x86_64-gui/'
                                          'NekoKEM-linux-x86_64-GUI.deb').write_bytes(b'changed'),
            'APK digest': lambda: (self.artifacts / 'NekoKEM-android-signed/'
                                   'app-release.apk').write_bytes(b'other apk'),
            'GUI source': lambda: (self.artifacts / 'NekoKEM-linux-aarch64-gui/'
                                   'gui-build-metadata.json').write_bytes(
                                       metadata(source_sha='b' * 40)),
        }
        for label, tamper in tampering.items():
            with self.subTest(label):
                shutil.rmtree(self.artifacts, ignore_errors=True)
                build_release(self.artifacts)
                tamper()
                with self.assertRaises(assets.ReleaseError):
                    self.verify()

    def test_notes_receive_build_records_once(self):
        hashes = {name: digest(name.encode()) for name in assets.PACKAGES}
        notes = f'# NekoKEM\n\n{assets.ZH_PLACEHOLDER}\n\n---\n\n{assets.EN_PLACEHOLDER}\n'
        filled = assets.fill_notes(notes, SOURCE, '11', '22', hashes)
        self.assertNotIn('<!--', filled)
        self.assertIn('### 构建与校验记录', filled)
        self.assertIn('### Build and verification records', filled)
        self.assertEqual(filled.count(assets.sums_text(hashes)), 2)
        self.assertIn(f'/actions/runs/22', filled)
        for broken in (notes.replace(assets.EN_PLACEHOLDER, ''), notes + assets.ZH_PLACEHOLDER):
            with self.assertRaises(assets.ReleaseError):
                assets.fill_notes(broken, SOURCE, '11', '22', hashes)

    def test_missing_android_tools_fail_closed(self):
        with mock.patch.dict('os.environ', {'ANDROID_SDK_ROOT': str(self.root / 'none'),
                                            'ANDROID_HOME': ''}):
            with self.assertRaises(assets.ReleaseError):
                assets.android_tool('apksigner')



class DraftResumeTests(unittest.TestCase):
    expected = {'a.zip': 'sha256:' + 'a' * 64, 'b.zip': 'sha256:' + 'b' * 64,
                'SHA256SUMS.txt': 'sha256:' + 'c' * 64}

    def asset(self, identifier, name, digest, state='uploaded'):
        return {'id': identifier, 'name': name, 'digest': digest, 'state': state}

    def test_new_draft_uploads_everything(self):
        self.assertEqual(publish_release.plan_upload([], self.expected),
                         ([], sorted(self.expected)))

    def test_interrupted_upload_resumes(self):
        existing = [self.asset(1, 'a.zip', self.expected['a.zip']),
                    self.asset(2, 'b.zip', None, state='starter'),
                    self.asset(3, 'SHA256SUMS.txt', 'sha256:' + 'd' * 64)]
        self.assertEqual(publish_release.plan_upload(existing, self.expected),
                         ([2, 3], ['SHA256SUMS.txt', 'b.zip']))

    def test_complete_draft_needs_nothing(self):
        existing = [self.asset(index, name, digest)
                    for index, (name, digest) in enumerate(self.expected.items())]
        self.assertEqual(publish_release.plan_upload(existing, self.expected), ([], []))

    def test_foreign_asset_is_refused(self):
        with self.assertRaises(assets.ReleaseError):
            publish_release.plan_upload([self.asset(9, 'other.bin', None)], self.expected)


if __name__ == '__main__':
    unittest.main()
