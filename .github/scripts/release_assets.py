#!/usr/bin/env python3
"""Select, verify and describe the public packages of one Release run.

The Release workflow attests exactly the files `collect` selects, and the
Publish workflow publishes exactly those files after `verify` and the
attestation checks pass. Nothing else from a Release run, such as the
encrypted signing recovery envelope, is ever selected.

  release_assets.py collect ARTIFACTS OUT
  release_assets.py verify ARTIFACTS --version 4.2.0 --source SHA --run ID
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import tarfile
import zipfile

PACKAGES = (
    'app-release.apk',
    'NekoKEM-Windows-GUI.zip',
    'NekoKEM-windows-x86_64.zip',
    'NekoKEM-macos-arm64.tar.gz',
    'NekoKEM-macos-arm64-GUI.zip',
    'NekoKEM-macos-arm64-GUI.dmg',
    'NekoKEM-linux-x86_64.tar.gz',
    'NekoKEM-linux-x86_64-GUI.deb',
    'NekoKEM-linux-x86_64-GUI.rpm',
    'NekoKEM-linux-x86_64-GUI.tar.gz',
    'NekoKEM-linux-aarch64.tar.gz',
    'NekoKEM-linux-aarch64-GUI.deb',
    'NekoKEM-linux-aarch64-GUI.rpm',
    'NekoKEM-linux-aarch64-GUI.tar.gz',
)
SUMS_NAME = 'SHA256SUMS.txt'
# Checksum files inside the Release artifacts, by artifact name.
INTERNAL_SUMS = (
    'NekoKEM-linux-x86_64-gui/gui-SHA256SUMS.txt',
    'NekoKEM-linux-aarch64-gui/gui-SHA256SUMS.txt',
    'NekoKEM-macos-arm64/macos-SHA256SUMS.txt',
    'NekoKEM-macos-arm64-gui/gui-SHA256SUMS.txt',
    'NekoKEM-windows-x86_64/windows-SHA256SUMS.txt',
)
# The build-tools version publish.yml installs for the APK checks.
BUILD_TOOLS = '35.0.0'
# The v3.2.0 release signer; a rotation must change this deliberately.
SIGNER = '5a091b86b1cb339f081c1afa2aa71b986c07597343ce336f22d866b05c39fb50'
REPOSITORY_URL = 'https://github.com/Shixiaoshi0417/NekoKEM'
ZH_PLACEHOLDER = '<!-- RELEASE_ZH_METADATA -->'
EN_PLACEHOLDER = '<!-- RELEASE_EN_METADATA -->'


class ReleaseError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def locate(root, name):
    matches = [path for path in Path(root).rglob(name) if path.is_file()]
    require(len(matches) == 1, f'Expected exactly one {name}, found {len(matches)}')
    return matches[0]


def sums_text(hashes):
    return ''.join(f'{hashes[name]}  {name}\n' for name in sorted(hashes))


def collect(artifacts, output):
    """Copy the 14 packages into a new directory and add SHA256SUMS.txt."""
    output = Path(output)
    output.mkdir(parents=True)
    hashes = {}
    for name in PACKAGES:
        target = output / name
        shutil.copyfile(locate(artifacts, name), target)
        hashes[name] = sha256(target)
    (output / SUMS_NAME).write_text(sums_text(hashes), encoding='ascii')
    return hashes


def check_internal_sums(artifacts):
    for relative in INTERNAL_SUMS:
        sums = Path(artifacts) / relative
        require(sums.is_file(), f'Missing {relative}')
        lines = [line for line in sums.read_text(encoding='ascii').splitlines() if line.strip()]
        require(lines, f'{relative} is empty')
        for line in lines:
            match = re.fullmatch(r'([0-9a-fA-F]{64}) [ *]?(\S.*)', line.strip())
            require(match is not None, f'Malformed line in {relative}')
            target = sums.parent / match.group(2)
            require(target.is_file() and sha256(target) == match.group(1).lower(),
                    f'{relative} does not match {match.group(2)}')


def check_metadata(metadata, label, source, run, version=None):
    require(metadata.get('source_sha') == source, f'{label} was built from another commit')
    if 'run_id' in metadata or label.startswith('Windows') or label.startswith('Android'):
        require(str(metadata.get('run_id')) == str(run), f'{label} came from another run')
    if version is not None:
        require(metadata.get('version') == version, f'{label} is not version {version}')


def tar_member(path, name):
    with tarfile.open(path, 'r:gz') as archive:
        member = archive.getmember(name)
        require(member.isfile(), f'{name} in {Path(path).name} is not a file')
        return archive.extractfile(member).read()


def zip_member(path, name):
    with zipfile.ZipFile(path) as archive:
        return archive.read(name)


def android_tool(name):
    """The pinned build-tools copy, never a newer one a runner image ships."""
    roots = [os.environ.get(key) for key in ('ANDROID_SDK_ROOT', 'ANDROID_HOME')]
    tools = [Path(root) / 'build-tools' / BUILD_TOOLS / name for root in roots if root]
    tools = [tool for tool in tools if tool.is_file()]
    require(tools, f'Android build-tools {BUILD_TOOLS} {name} is not installed')
    return str(tools[0])


def tool_environment():
    """The tools that parse the APK need no GitHub credential."""
    return {key: value for key, value in os.environ.items()
            if key not in ('GH_TOKEN', 'GITHUB_TOKEN')}


def check_apk(apk, version, version_code):
    """The APK's own signature and identity, independent of its metadata."""
    certificates = subprocess.run(
        [android_tool('apksigner'), 'verify', '--verbose', '--print-certs', str(apk)],
        check=True, capture_output=True, text=True, env=tool_environment()).stdout
    signers = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$',
                         certificates, re.M)
    require([signer.lower() for signer in signers] == [SIGNER],
            'The APK is not signed by exactly the release signer')
    require(re.search(r'^Verified using v2 scheme \(APK Signature Scheme v2\): true$',
                      certificates, re.M) is not None, 'The APK lacks a valid v2 signature')
    badging = subprocess.run([android_tool('aapt2'), 'dump', 'badging', str(apk)],
                             check=True, capture_output=True, text=True,
                             env=tool_environment()).stdout
    expected = (f"package: name='com.shixiaoshi0417.nekokem' versionCode='{version_code}' "
                f"versionName='{version}'")
    require(badging.startswith(expected), 'The APK identity does not match its metadata')


def verify(artifacts, version, source, run, apk_check=check_apk):
    artifacts = Path(artifacts)
    require(re.fullmatch(r'\d+\.\d+\.\d+', version) is not None, 'Invalid version')
    require(re.fullmatch(r'[0-9a-f]{40}', source) is not None, 'Invalid source commit')
    check_internal_sums(artifacts)

    apk = locate(artifacts, 'app-release.apk')
    android = json.loads(locate(artifacts, 'build-metadata.json').read_text())
    check_metadata(android, 'Android', source, run, version)
    require(isinstance(android.get('version_code'), int) and android['version_code'] > 0,
            'Android versionCode is invalid')
    require(android.get('apk_sha256') == sha256(apk), 'APK differs from its metadata')
    require(android.get('signer_sha256') == SIGNER and
            android.get('previous_signer_sha256') == SIGNER and
            android.get('signing_key_rotated') is False,
            'Android signing identity changed')
    apk_check(apk, version, android['version_code'])

    for arch in ('x86_64', 'aarch64'):
        gui = json.loads((artifacts / f'NekoKEM-linux-{arch}-gui/gui-build-metadata.json').read_text())
        check_metadata(gui, f'Linux {arch} GUI', source, run, version)
        readme = tar_member(locate(artifacts, f'NekoKEM-linux-{arch}.tar.gz'),
                            f'NekoKEM-linux-{arch}/README').decode('utf-8')
        require(readme.splitlines()[0] == f'NekoKEM Linux CLI {version}',
                f'Linux {arch} CLI is not version {version}')

    macos = json.loads(tar_member(locate(artifacts, 'NekoKEM-macos-arm64.tar.gz'),
                                  'NekoKEM-macos-arm64/build-metadata.json'))
    check_metadata(macos, 'macOS CLI', source, run, version)
    gui_zip = locate(artifacts, 'NekoKEM-macos-arm64-GUI.zip')
    check_metadata(json.loads(zip_member(gui_zip, 'NekoKEM-macos-arm64-GUI/build-metadata.json')),
                   'macOS GUI', source, run, version)
    info = plistlib.loads(zip_member(gui_zip, 'NekoKEM-macos-arm64-GUI/NekoKEM.app/Contents/Info.plist'))
    require(info.get('CFBundleShortVersionString') == version, f'macOS app is not version {version}')

    for name, label in (('NekoKEM-windows-x86_64.zip', 'Windows CLI'),
                        ('NekoKEM-Windows-GUI.zip', 'Windows GUI')):
        check_metadata(json.loads(zip_member(locate(artifacts, name), 'build-metadata.json')),
                       label, source, run, version)
    return android['version_code']


def build_records(language, source, ci_run, release_run, hashes):
    block = '```text\n' + sums_text(hashes) + '```'
    if language == 'zh':
        return (f'### 构建与校验记录\n\n'
                f'- 源码提交：[`{source}`]({REPOSITORY_URL}/tree/{source})\n'
                f'- CI：[运行 {ci_run}]({REPOSITORY_URL}/actions/runs/{ci_run})\n'
                f'- Release：[运行 {release_run}]({REPOSITORY_URL}/actions/runs/{release_run})\n'
                f'- 同一源码的 CI 与 Release 已全部成功；已核对构建产物来源、包内校验和、'
                f'Android 构建签名记录及每个附件的 GitHub 构建来源证明。Android 沿用原签名证书。\n'
                f'- Android 签名证书 SHA-256：`{SIGNER}`\n\n{block}')
    return (f'### Build and verification records\n\n'
            f'- Source commit: [`{source}`]({REPOSITORY_URL}/tree/{source})\n'
            f'- CI: [run {ci_run}]({REPOSITORY_URL}/actions/runs/{ci_run})\n'
            f'- Release: [run {release_run}]({REPOSITORY_URL}/actions/runs/{release_run})\n'
            f'- CI and Release for the same source commit succeeded. Artifact provenance, '
            f'internal checksums, Android signing build records and the GitHub build '
            f'provenance attestation of every asset were verified. The Android signing '
            f'identity is retained.\n'
            f'- Android signing certificate SHA-256: `{SIGNER}`\n\n{block}')


def fill_notes(notes, source, ci_run, release_run, hashes):
    require(sorted(hashes) == sorted(PACKAGES), 'Release notes must list the 14 packages')
    require(notes.count(ZH_PLACEHOLDER) == 1 and notes.count(EN_PLACEHOLDER) == 1,
            'Release notes need each build-record placeholder exactly once')
    notes = notes.replace(ZH_PLACEHOLDER, build_records('zh', source, ci_run, release_run, hashes))
    notes = notes.replace(EN_PLACEHOLDER, build_records('en', source, ci_run, release_run, hashes))
    return notes if notes.endswith('\n') else notes + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    selected = commands.add_parser('collect')
    selected.add_argument('artifacts')
    selected.add_argument('output')
    checked = commands.add_parser('verify')
    checked.add_argument('artifacts')
    checked.add_argument('--version', required=True)
    checked.add_argument('--source', required=True)
    checked.add_argument('--run', required=True)
    arguments = parser.parse_args()
    try:
        if arguments.command == 'collect':
            collect(arguments.artifacts, arguments.output)
        else:
            verify(arguments.artifacts, arguments.version, arguments.source, arguments.run)
    except (ReleaseError, OSError, KeyError, ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(f'Release assets rejected: {error}')


if __name__ == '__main__':
    main()
