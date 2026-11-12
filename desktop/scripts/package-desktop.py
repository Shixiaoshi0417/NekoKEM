#!/usr/bin/env python3
"""Check a built desktop app and write its public packages.

CI and the Release workflow run the same commands, so what is published is
what CI installed and started.

  package-desktop.py resources --library LIB --openssl-license FILE --target DIR
  package-desktop.py linux --arch ARCH --app DIR --deb FILE --rpm FILE --output DIR --source-sha SHA
  package-desktop.py windows --app DIR --msi FILE --output DIR --source-sha SHA
  package-desktop.py macos --app NekoKEM.app --output DIR --source-sha SHA

The version is the bridge's (desktop/bridge/Cargo.toml); the run id comes from
GITHUB_RUN_ID when a workflow runs this.
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
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[2]
LICENSES = ('LICENSE', 'OpenSSL-LICENSE.txt')


class PackageError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise PackageError(message)


def version():
    found = tomllib.loads((ROOT / 'desktop/bridge/Cargo.toml').read_text())['package']['version']
    require(re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', found) is not None, 'Invalid bridge version')
    return found


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def output_of(*command):
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout


def metadata(platform, source, extra):
    require(re.fullmatch(r'[0-9a-f]{40}', source) is not None, 'Invalid source commit')
    record = {'source_sha': source, 'version': version(), 'platform': platform}
    run = os.environ.get('GITHUB_RUN_ID')
    if run:
        record['run_id'] = run
    record.update(extra)
    return json.dumps(record, indent=1, sort_keys=True) + '\n'


def write_sums(output, names):
    text = ''.join(f'{sha256(output / name)}  {name}\n' for name in sorted(names))
    (output / 'gui-SHA256SUMS.txt').write_text(text, encoding='ascii')


def new_directory(path):
    path = Path(path)
    require(not path.exists() or not any(path.iterdir()), f'{path} is not empty')
    path.mkdir(parents=True, exist_ok=True)
    return path.resolve()


def check_licenses(resources):
    for name in LICENSES:
        require((resources / name).is_file() and (resources / name).stat().st_size > 0,
                f'{name} is not in the package')


def resources(arguments):
    """The files the app carries beside its bridge library."""
    target = Path(arguments.target)
    target.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(arguments.library, target / Path(arguments.library).name)
    shutil.copyfile(ROOT / 'LICENSE', target / 'LICENSE')
    shutil.copyfile(arguments.openssl_license, target / 'OpenSSL-LICENSE.txt')


def check_elf(library):
    """OpenSSL is linked in, and the library keeps the hardening it was built with."""
    dynamic = output_of('readelf', '-d', str(library))
    require(re.search(r'NEEDED.*(libcrypto|libssl)', dynamic) is None, 'The bridge needs a system OpenSSL')
    require('BIND_NOW' in dynamic, 'The bridge is not bound at load time')
    headers = output_of('readelf', '-lW', str(library))
    require('GNU_RELRO' in headers, 'The bridge has no read-only relocations')
    stack = [line for line in headers.splitlines() if 'GNU_STACK' in line]
    require(len(stack) == 1 and 'RWE' not in stack[0], 'The bridge has an executable stack')


def linux(arguments):
    arch = arguments.arch
    require(arch in ('x86_64', 'aarch64'), 'Unsupported architecture')
    app = Path(arguments.app)
    bundled = app / 'lib/app/resources'
    library = bundled / 'libnekokem_native.so'
    require(library.is_file(), 'The bridge is not in the application')
    check_elf(library)
    check_licenses(bundled)
    require((app / 'bin/NekoKEM').is_file(), 'The launcher is missing')

    current = version()
    deb_arch = {'x86_64': 'amd64', 'aarch64': 'arm64'}[arch]
    require(output_of('dpkg-deb', '-f', arguments.deb, 'Version').strip().split('-')[0] == current,
            f'The Debian package is not version {current}')
    require(output_of('dpkg-deb', '-f', arguments.deb, 'Architecture').strip() == deb_arch,
            'The Debian package is for another architecture')
    require('fonts-noto-cjk' in output_of('dpkg-deb', '-f', arguments.deb, 'Depends'),
            'The Debian package does not depend on the CJK fonts')
    require(output_of('rpm', '-qp', '--queryformat', '%{VERSION} %{ARCH}', arguments.rpm) == f'{current} {arch}',
            f'The RPM package is not version {current} for {arch}')
    require('noto-sans-cjk' in output_of('rpm', '-qp', '--requires', arguments.rpm),
            'The RPM package does not depend on the CJK fonts')

    output = new_directory(arguments.output)
    base = f'NekoKEM-linux-{arch}-GUI'
    shutil.copyfile(arguments.deb, output / f'{base}.deb')
    shutil.copyfile(arguments.rpm, output / f'{base}.rpm')
    record = metadata(f'linux-{arch}', arguments.source_sha, {'bridge_sha256': sha256(library)})
    (output / 'gui-build-metadata.json').write_text(record)
    # The application folder as it is installed, for systems without dpkg or rpm.
    with tarfile.open(output / f'{base}.tar.gz', 'w:gz') as archive:
        archive.add(app, arcname=base)
        archive.add(output / 'gui-build-metadata.json', arcname=f'{base}/build-metadata.json')
    write_sums(output, [f'{base}.deb', f'{base}.rpm', f'{base}.tar.gz'])


def windows(arguments):
    app = Path(arguments.app)
    bundled = app / 'app/resources'
    library = bundled / 'nekokem_native.dll'
    require(library.is_file(), 'The bridge is not in the application')
    check_licenses(bundled)
    require((app / 'NekoKEM.exe').is_file(), 'The launcher is missing')
    require(os.environ.get('GITHUB_RUN_ID'), 'The Windows package records the run that built it')

    output = new_directory(arguments.output)
    shutil.copyfile(arguments.msi, output / 'NekoKEM-Windows-GUI.msi')
    record = metadata('windows-x86_64', arguments.source_sha, {
        'bridge_sha256': sha256(library), 'exe_sha256': sha256(app / 'NekoKEM.exe'),
        'authenticode_signed': False, 'apk_signing_material_used': False})
    # The application folder the installer installs, to run without installing.
    with zipfile.ZipFile(output / 'NekoKEM-Windows-GUI.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('build-metadata.json', record)
        for path in sorted(app.rglob('*')):
            if path.is_file():
                archive.write(path, (Path('NekoKEM') / path.relative_to(app)).as_posix())
    write_sums(output, ['NekoKEM-Windows-GUI.msi', 'NekoKEM-Windows-GUI.zip'])


def macos(arguments):
    app = Path(arguments.app)
    require(app.name == 'NekoKEM.app' and (app / 'Contents/MacOS/NekoKEM').is_file(), 'Not the NekoKEM application')
    info = plistlib.loads((app / 'Contents/Info.plist').read_bytes())
    current = version()
    require(info.get('CFBundleShortVersionString') == current, f'The application is not version {current}')
    for name in LICENSES:
        require((app / 'Contents/Resources' / name).is_file(), f'{name} is not in the application')
    subprocess.run(['codesign', '--verify', '--deep', '--strict', str(app)], check=True)
    architectures = output_of('lipo', '-archs', str(app / 'Contents/MacOS/NekoKEM')).split()
    require(architectures == ['arm64'], 'The application is not arm64 only')

    output = new_directory(arguments.output)
    base = 'NekoKEM-macos-arm64-GUI'
    folder = output / base
    folder.mkdir()
    # ditto keeps the signature's extended attributes and symbolic links.
    subprocess.run(['ditto', str(app), str(folder / 'NekoKEM.app')], check=True)
    (folder / 'build-metadata.json').write_text(metadata('macos-arm64', arguments.source_sha, {
        'executable_sha256': sha256(app / 'Contents/MacOS/NekoKEM'), 'signature': 'ad-hoc'}))
    subprocess.run(['codesign', '--verify', '--deep', '--strict', str(folder / 'NekoKEM.app')], check=True)
    subprocess.run(['ditto', '-c', '-k', '--keepParent', str(folder), str(output / f'{base}.zip')], check=True)
    subprocess.run(['hdiutil', 'create', '-volname', 'NekoKEM', '-srcfolder', str(folder), '-ov',
                    '-format', 'UDZO', str(output / f'{base}.dmg')], check=True)
    shutil.rmtree(folder)
    write_sums(output, [f'{base}.zip', f'{base}.dmg'])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    command = commands.add_parser('resources')
    command.add_argument('--library', required=True)
    command.add_argument('--openssl-license', required=True)
    command.add_argument('--target', required=True)
    command = commands.add_parser('linux')
    command.add_argument('--arch', required=True)
    command.add_argument('--deb', required=True)
    command.add_argument('--rpm', required=True)
    for name in ('linux', 'windows', 'macos'):
        command = commands.choices.get(name) or commands.add_parser(name)
        command.add_argument('--app', required=True)
        command.add_argument('--output', required=True)
        command.add_argument('--source-sha', required=True)
    commands.choices['windows'].add_argument('--msi', required=True)
    arguments = parser.parse_args()
    try:
        {'resources': resources, 'linux': linux, 'windows': windows, 'macos': macos}[arguments.command](arguments)
    except (PackageError, OSError, KeyError, ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(f'Desktop package rejected: {error}')


if __name__ == '__main__':
    main()
