#!/usr/bin/env python3
"""Point the Arch Linux package (linux/arch) at one release and write its .SRCINFO.

  update_arch_package.py 5.3.0                  checksums from that release's SHA256SUMS.txt
  update_arch_package.py 5.3.0 --sums FILE      checksums from a local SHA256SUMS file
  update_arch_package.py 5.3.0 --sums FILE --arch x86_64
                                                only that architecture's checksums (CI's own archives)
  update_arch_package.py --check                .SRCINFO and the local file's checksum are current

The PKGBUILD repackages published release archives, so its checksums can only
be known once a release is out. The release's SHA256SUMS.txt lists every
archive; nothing is downloaded but that file.
"""
import argparse
import hashlib
from pathlib import Path
import re
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / 'linux/arch'
REPOSITORY = 'https://github.com/Shixiaoshi0417/NekoKEM'
ARCHES = ('x86_64', 'aarch64')
# makepkg --printsrcinfo order: the package base, then the per-architecture arrays.
FIELDS = ('pkgdesc', 'pkgver', 'pkgrel', 'url', 'arch', 'license', 'depends',
          'provides', 'conflicts', 'options', 'source', 'sha256sums')
ARCH_FIELDS = ('source', 'sha256sums')


class PackageError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise PackageError(message)


def assignments(text):
    """The top-level assignments of a PKGBUILD written in this file's plain style."""
    values = {}
    for match in re.finditer(r"(?ms)^([a-z_0-9]+)=(\((.*?)\)|'[^'\n]*'|\"[^\"\n]*\"|[^\s(]+)", text):
        name, whole, array = match.group(1), match.group(2), match.group(3)
        if array is not None:
            items = re.findall(r"'([^']*)'|\"([^\"]*)\"", re.sub(r'(?m)#.*$', '', array))
            values[name] = [single or double for single, double in items]
        else:
            values[name] = whole.strip('\'"')
    return values


def expand(value, scalars):
    def replace(match):
        name = match.group(1)
        require(name in scalars, f'PKGBUILD uses ${{{name}}} before setting it')
        return scalars[name]
    return re.sub(r'\$\{([a-zA-Z_0-9]+)\}', replace, value)


def parse(text):
    raw = assignments(text)
    scalars = {}
    values = {}
    for name, value in raw.items():
        if isinstance(value, str):
            scalars[name] = expand(value, scalars)
            values[name] = scalars[name]
        else:
            values[name] = [expand(item, scalars) for item in value]
    for name in ('pkgname', 'pkgver', 'pkgrel', 'pkgdesc', 'url'):
        require(isinstance(values.get(name), str), f'PKGBUILD has no {name}')
    require(tuple(values.get('arch', ())) == ARCHES, f'PKGBUILD arch must be {ARCHES}')
    return values


def srcinfo(values):
    lines = [f"pkgbase = {values['pkgname']}"]

    def add(name, key=None):
        value = values.get(key or name)
        for item in [value] if isinstance(value, str) else value or ():
            lines.append(f'\t{name} = {item}')
    for name in FIELDS:
        add(name)
    for arch in ARCHES:
        for name in ARCH_FIELDS:
            add(f'{name}_{arch}')
    lines += ['', f"pkgname = {values['pkgname']}", '']
    return '\n'.join(lines)


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def release_sums(text):
    sums = {}
    for line in text.splitlines():
        match = re.fullmatch(r'([0-9a-f]{64}) [ *]?(\S+)', line.strip())
        if match:
            sums[match.group(2)] = match.group(1)
    return sums


def replace_array(text, name, items):
    indent = ' ' * (len(name) + 2)
    body = ('\n' + indent).join(f"'{item}'" for item in items)
    updated, count = re.subn(rf'(?ms)^{name}=\(.*?\)', lambda _: f'{name}=({body})', text)
    require(count == 1, f'PKGBUILD must set {name} once')
    return updated


def replace_scalar(text, name, value):
    updated, count = re.subn(rf'(?m)^{name}=.*$', lambda _: f'{name}={value}', text)
    require(count == 1, f'PKGBUILD must set {name} once')
    return updated


def update(directory, version, sums_text, pkgrel, arches=ARCHES):
    require(re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version) is not None, f'Invalid version {version!r}')
    pkgbuild = directory / 'PKGBUILD'
    text = pkgbuild.read_text()
    text = replace_scalar(text, 'pkgver', version)
    text = replace_scalar(text, 'pkgrel', str(pkgrel))
    values = parse(text)
    sums = release_sums(sums_text)
    local = []
    for item in values['source']:
        require('::' not in item and '/' not in item, f'{item} must be a file next to the PKGBUILD')
        local.append(sha256(directory / item))
    text = replace_array(text, 'sha256sums', local)
    for arch in arches:
        require(arch in ARCHES, f'Unknown architecture {arch}')
        digests = []
        for item in values[f'source_{arch}']:
            address = item.split('::', 1)[-1]
            require(address.startswith(f'{REPOSITORY}/releases/download/v{version}/'),
                    f'{item} is not a download from release v{version}')
            name = address.rsplit('/', 1)[-1]
            require(name in sums, f'{name} is not in the release checksums')
            digests.append(sums[name])
        text = replace_array(text, f'sha256sums_{arch}', digests)
    pkgbuild.write_text(text)
    (directory / '.SRCINFO').write_text(srcinfo(parse(text)))


def check(directory):
    values = parse((directory / 'PKGBUILD').read_text())
    for name in ['source', *(f'source_{arch}' for arch in ARCHES)]:
        require(len(values.get(name, ())) == len(values.get(name.replace('source', 'sha256sums'), ())),
                f'{name} and its checksums differ in length')
    for item, digest in zip(values['source'], values['sha256sums']):
        require(sha256(directory / item) == digest, f'The checksum of {item} is out of date')
    for arch in ARCHES:
        for item in values[f'source_{arch}']:
            require(item.split('::', 1)[-1].startswith(f"{REPOSITORY}/releases/download/v{values['pkgver']}/"),
                    f'{item} does not download release v{values["pkgver"]}')
    require((directory / '.SRCINFO').read_text() == srcinfo(values),
            '.SRCINFO does not match the PKGBUILD; run scripts/update_arch_package.py')
    return values['pkgver']


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('version', nargs='?')
    parser.add_argument('--sums', type=Path, help='a local SHA256SUMS file instead of the release download')
    parser.add_argument('--pkgrel', type=int, default=1)
    parser.add_argument('--arch', action='append', choices=ARCHES,
                        help='update only these architectures (repeatable; default all)')
    parser.add_argument('--dir', type=Path, default=PACKAGE)
    parser.add_argument('--check', action='store_true')
    arguments = parser.parse_args()
    try:
        if arguments.check:
            require(arguments.version is None and arguments.sums is None, '--check takes no version or sums')
            print(f'The Arch Linux package for release {check(arguments.dir)} is consistent')
            return
        require(arguments.version is not None, 'Name the release version')
        require(arguments.pkgrel >= 1, '--pkgrel must be 1 or more')
        if arguments.sums is not None:
            sums_text = arguments.sums.read_text()
        else:
            address = f'{REPOSITORY}/releases/download/v{arguments.version}/SHA256SUMS.txt'
            with urllib.request.urlopen(address, timeout=60) as response:
                sums_text = response.read(1 << 20).decode()
        update(arguments.dir, arguments.version, sums_text, arguments.pkgrel,
               tuple(arguments.arch or ARCHES))
        check(arguments.dir)
        print(f'The Arch Linux package now repackages release {arguments.version}')
    except (OSError, PackageError) as error:
        sys.exit(f'Arch Linux package: {error}')


if __name__ == '__main__':
    main()
