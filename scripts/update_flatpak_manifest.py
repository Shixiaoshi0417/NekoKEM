#!/usr/bin/env python3
"""Point the Flatpak manifest (linux/flatpak) at one release, or at local archives.

  update_flatpak_manifest.py 5.3.0              checksums from that release's SHA256SUMS.txt
  update_flatpak_manifest.py 5.3.0 --sums FILE  checksums from a local SHA256SUMS file
  update_flatpak_manifest.py --check            manifest and AppStream release agree
  update_flatpak_manifest.py --local OUT --arch x86_64 --version 5.3.0 --cli CLI.tar.gz --gui GUI.tar.gz
                                                a build directory for this run's own archives

Like the Arch Linux package, the manifest repackages the published release
archives, so its checksums can only be known once a release is out. A new
version also gets a <release> entry in the AppStream metadata. --local is
what CI and the Release workflow build the .flatpak bundle from: the
manifest with that architecture's two archives taken from local files.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / 'linux/flatpak'
APP_ID = 'io.github.shixiaoshi0417.NekoKEM'
REPOSITORY = 'https://github.com/Shixiaoshi0417/NekoKEM'
ARCHES = ('x86_64', 'aarch64')
# Each architecture's two archives, by where the manifest unpacks them.
ARCHIVES = {'cli': 'NekoKEM-linux-{arch}.tar.gz', 'gui': 'NekoKEM-linux-{arch}-GUI.tar.gz'}
VERSION = re.compile(r'[0-9]+\.[0-9]+\.[0-9]+')


class ManifestError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise ManifestError(message)


def files(directory):
    return (directory / f'{APP_ID}.json', directory / f'{APP_ID}.metainfo.xml',
            directory / f'{APP_ID}.desktop')


def load(directory):
    manifest_path = files(directory)[0]
    manifest = json.loads(manifest_path.read_text())
    require(manifest.get('id') == APP_ID, f'The manifest id must be {APP_ID}')
    modules = manifest.get('modules', [])
    require(len(modules) == 1 and modules[0].get('name') == 'nekokem',
            'The manifest must have exactly the nekokem module')
    return manifest


def archive_sources(manifest):
    """{(arch, dest): source} for the release archives, each exactly once."""
    found = {}
    for source in manifest['modules'][0]['sources']:
        if source.get('type') != 'archive':
            continue
        arches = source.get('only-arches', [])
        require(len(arches) == 1 and arches[0] in ARCHES, 'Each archive names one architecture')
        key = (arches[0], source.get('dest'))
        require(source.get('dest') in ARCHIVES and key not in found, f'Unexpected archive {key}')
        found[key] = source
    require(sorted(found) == sorted((arch, dest) for arch in ARCHES for dest in ARCHIVES),
            'The manifest needs the CLI and GUI archives of both architectures')
    return found


def release_address(version, arch, dest):
    return f'{REPOSITORY}/releases/download/v{version}/{ARCHIVES[dest].format(arch=arch)}'


def release_sums(text):
    sums = {}
    for line in text.splitlines():
        match = re.fullmatch(r'([0-9a-f]{64}) [ *]?(\S+)', line.strip())
        if match:
            sums[match.group(2)] = match.group(1)
    return sums


def releases(metainfo_text):
    return re.findall(r'<release version="([^"]+)" date="([0-9]{4}-[0-9]{2}-[0-9]{2})"', metainfo_text)


def add_release(metainfo_text, version, date):
    """The newest release goes first, as AppStream expects."""
    if any(existing == version for existing, _ in releases(metainfo_text)):
        return metainfo_text
    entry = (f'    <release version="{version}" date="{date}">\n'
             f'      <url type="details">{REPOSITORY}/releases/tag/v{version}</url>\n'
             f'    </release>\n')
    updated, count = re.subn(r'(?m)^(  <releases>\n)', lambda match: match.group(1) + entry, metainfo_text)
    require(count == 1, 'The metainfo must have one <releases> element')
    return updated


def write_manifest(path, manifest):
    path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n')


def update(directory, version, sums_text, date):
    require(VERSION.fullmatch(version) is not None, f'Invalid version {version!r}')
    manifest_path, metainfo_path, _ = files(directory)
    manifest = load(directory)
    sums = release_sums(sums_text)
    for (arch, dest), source in archive_sources(manifest).items():
        name = ARCHIVES[dest].format(arch=arch)
        require(name in sums, f'{name} is not in the release checksums')
        source['url'] = release_address(version, arch, dest)
        source['sha256'] = sums[name]
    write_manifest(manifest_path, manifest)
    metainfo_path.write_text(add_release(metainfo_path.read_text(), version, date))


def check(directory):
    manifest_path, metainfo_path, desktop_path = files(directory)
    manifest = load(directory)
    versions = set()
    for (arch, dest), source in archive_sources(manifest).items():
        match = re.fullmatch(re.escape(f'{REPOSITORY}/releases/download/v') + r'([0-9.]+)/(.+)',
                             source.get('url', ''))
        require(match is not None and match.group(2) == ARCHIVES[dest].format(arch=arch),
                f'The {arch} {dest} archive is not a release download')
        require(re.fullmatch(r'[0-9a-f]{64}', source.get('sha256', '')) is not None,
                f'The {arch} {dest} archive has no SHA-256')
        versions.add(match.group(1))
    require(len(versions) == 1, 'The archives come from different releases')
    version = versions.pop()
    listed = releases(metainfo_path.read_text())
    require(listed and listed[0][0] == version,
            f'The newest AppStream release must be {version}; run scripts/update_flatpak_manifest.py')
    for source in manifest['modules'][0]['sources']:
        if source.get('type') == 'file':
            require((directory / source['path']).is_file(), f'{source["path"]} is missing')
    require(f'Icon={APP_ID}\n' in desktop_path.read_text(), f'The desktop entry icon must be {APP_ID}')
    require(manifest.get('command') == 'nekokem-gui', 'The manifest must start nekokem-gui')
    return version


def local(output, arch, version, cli, gui, date):
    """A build directory whose manifest unpacks the given archives for one architecture."""
    require(VERSION.fullmatch(version) is not None, f'Invalid version {version!r}')
    require(arch in ARCHES, f'Unknown architecture {arch}')
    output.mkdir(parents=True, exist_ok=False)
    manifest = load(PACKAGE)
    archives = {'cli': Path(cli).resolve(), 'gui': Path(gui).resolve()}
    sources = []
    for source in manifest['modules'][0]['sources']:
        if source.get('type') == 'archive':
            if source['only-arches'] != [arch]:
                continue
            path = archives[source['dest']]
            require(path.is_file(), f'{path} is missing')
            source = {'type': 'archive', 'only-arches': [arch], 'path': str(path),
                      'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'dest': source['dest']}
        sources.append(source)
    manifest['modules'][0]['sources'] = sources
    manifest_path, metainfo_path, desktop_path = files(output)
    write_manifest(manifest_path, manifest)
    shutil.copyfile(files(PACKAGE)[2], desktop_path)
    # A release build carries its own version before the release is published.
    metainfo_path.write_text(add_release(files(PACKAGE)[1].read_text(), version, date))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('version', nargs='?')
    parser.add_argument('--sums', type=Path, help='a local SHA256SUMS file instead of the release download')
    parser.add_argument('--date', default=datetime.datetime.now(datetime.timezone.utc).date().isoformat(),
                        help='the AppStream release date of a new version (default today, UTC)')
    parser.add_argument('--dir', type=Path, default=PACKAGE)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--local', type=Path, metavar='OUT')
    parser.add_argument('--arch', choices=ARCHES)
    parser.add_argument('--cli', type=Path)
    parser.add_argument('--gui', type=Path)
    arguments = parser.parse_args()
    try:
        require(re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}', arguments.date) is not None, 'Invalid --date')
        if arguments.check:
            require(arguments.version is None and arguments.local is None, '--check takes no version')
            print(f'The Flatpak manifest for release {check(arguments.dir)} is consistent')
            return
        require(arguments.version is not None, 'Name the release version')
        if arguments.local is not None:
            require(None not in (arguments.arch, arguments.cli, arguments.gui),
                    '--local needs --arch, --cli and --gui')
            local(arguments.local, arguments.arch, arguments.version, arguments.cli, arguments.gui,
                  arguments.date)
            print(f'Flatpak build directory for {arguments.version} {arguments.arch}: {arguments.local}')
            return
        if arguments.sums is not None:
            sums_text = arguments.sums.read_text()
        else:
            address = f'{REPOSITORY}/releases/download/v{arguments.version}/SHA256SUMS.txt'
            with urllib.request.urlopen(address, timeout=60) as response:
                sums_text = response.read(1 << 20).decode()
        update(arguments.dir, arguments.version, sums_text, arguments.date)
        check(arguments.dir)
        print(f'The Flatpak manifest now repackages release {arguments.version}')
    except (OSError, ValueError, KeyError, ManifestError) as error:
        sys.exit(f'Flatpak manifest: {error}')


if __name__ == '__main__':
    main()
