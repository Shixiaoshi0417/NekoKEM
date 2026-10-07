"""Verify native DEB/RPM packages and archive the exact Debian application tree."""
from pathlib import Path
import argparse
import gzip
import hashlib
import importlib.util
import json
import os
import shutil
import stat
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('check_gui_linux', ROOT/'desktop/tests/check_gui_linux.py')
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_rpm(rpm, deb_stage, arch, prefix):
    def query(format):
        return subprocess.check_output(['rpm', '-qp', '--queryformat', format, str(rpm)], text=True)
    name, version, release, machine, license = query('%{NAME}\n%{VERSION}\n%{RELEASE}\n%{ARCH}\n%{LICENSE}\n').splitlines()
    assert (name, version, release, machine, license) == ('neko-kem', '4.0.1', '1', arch, 'Apache-2.0')
    subprocess.run(['rpm', '--checksig', '--nosignature', str(rpm)], check=True)
    assert all(value == '(none)' for value in query('%{PREIN}\n%{POSTIN}\n%{PREUN}\n%{POSTUN}\n').splitlines())
    signatures = query('%{RSAHEADER:pgpsig}\n%{DSAHEADER:pgpsig}\n%{SIGPGP:pgpsig}\n%{SIGGPG:pgpsig}\n').splitlines()
    assert all(value == '(none)' for value in signatures), 'RPM signing must be separately configured and verified'
    requirements = set(subprocess.check_output(['rpm', '-qp', '--requires', str(rpm)], text=True).splitlines())
    assert 'libc.so.6(GLIBC_2.39)(64bit)' in requirements
    files = {}
    for line in query('[%{FILENAMES}\t%{FILEMODES:octal}\n]').splitlines():
        filename, mode = line.split('\t')
        path = Path(filename)
        mode = int(mode, 8)
        assert path.is_absolute() and path.parts[1] == 'usr' and '..' not in path.parts
        assert (stat.S_ISREG(mode) or stat.S_ISDIR(mode)) and not mode & 0o6000
        if stat.S_ISREG(mode):
            files[path.relative_to('/')] = mode & 0o777
    deb_files = {path.relative_to(deb_stage): path for path in (deb_stage/'usr').rglob('*') if path.is_file()}
    assert set(files) == set(deb_files), 'DEB/RPM must contain the same application, icons and licenses'
    assert all(mode == (0o755 if path == Path('usr/bin/nekokem-gui') else 0o644)
               for path, mode in files.items()), 'Installed executables and public resources must be usable by normal users'
    with tempfile.TemporaryDirectory(prefix='nekokem-linux-gui-rpm-') as directory:
        # Ubuntu's older rpm2cpio requires an optional archive-size tag that
        # Tauri's RPM builder omits. rpm2archive reads each file's actual size.
        with rpm.open('rb') as source, tempfile.TemporaryFile() as payload:
            subprocess.run(['rpm2archive', '--nocompression', '-'],
                           stdin=source, stdout=payload, check=True)
            payload.seek(0)
            with tarfile.open(fileobj=payload, mode='r:') as archive:
                extracted_files = set()
                for member in archive.getmembers():
                    path = Path(member.name)
                    assert not path.is_absolute() and path.parts[0] == 'usr' and '..' not in path.parts
                    assert (member.isfile() or member.isdir()) and not member.mode & 0o6000
                    if member.isfile():
                        extracted_files.add(path)
                assert extracted_files == set(files)
                archive.extractall(directory, filter='data')
        stage = Path(directory)
        binary = stage/'usr/bin/nekokem-gui'
        metadata = checker.verify(binary, arch, prefix)
        missing_dependencies = {f'{name}()(64bit)' for name in metadata['elf_dependencies']} - requirements
        assert not missing_dependencies, f'RPM lacks ELF dependencies: {sorted(missing_dependencies)}'
        deb_binary = (deb_stage/'usr/bin/nekokem-gui').read_bytes()
        rpm_binary = binary.read_bytes()
        assert len(deb_binary) == len(rpm_binary)
        differences = [index for index, (deb, rpm) in enumerate(zip(deb_binary, rpm_binary)) if deb != rpm]
        assert len(differences) == 3 and differences == list(range(differences[0], differences[0]+3))
        offset = differences[0]
        marker = b'__TAURI_BUNDLE_TYPE_VAR_'
        assert deb_binary[offset-len(marker):offset+3] == marker+b'DEB'
        assert rpm_binary[offset-len(marker):offset+3] == marker+b'RPM'
        for path, deb_file in deb_files.items():
            installed = stage/path
            assert installed.is_file() and not installed.is_symlink()
            assert stat.S_IMODE(installed.stat().st_mode) == files[path]
            if path != Path('usr/bin/nekokem-gui'):
                assert installed.read_bytes() == deb_file.read_bytes(), path
        # The bundle format marker is the only allowed binary difference.
        return {'package': name, 'architecture': machine, 'release': release,
                'signed': False, 'exe_sha256': metadata['exe_sha256'],
                'bundle_marker_only_difference': True,
                'elf_dependencies': metadata['elf_dependencies'],
                'requires': sorted(requirements)}


def package(bundle, output, arch, prefix, source):
    assert len(source) == 40 and all(char in '0123456789abcdef' for char in source)
    candidates = list((bundle/'deb').glob('*.deb'))
    assert len(candidates) == 1, 'Use a clean bundle directory with one Debian package'
    deb = candidates[0]
    rpm_candidates = list((bundle/'rpm').glob('*.rpm'))
    assert len(rpm_candidates) == 1, 'Use a clean bundle directory with one RPM package'
    rpm = rpm_candidates[0]
    expected_arch = 'arm64' if arch == 'aarch64' else 'amd64'
    field = lambda name: subprocess.check_output(['dpkg-deb', '-f', str(deb), name], text=True).strip()
    assert field('Architecture') == expected_arch and field('Version') == '4.0.1'
    dependencies = {name.strip() for name in field('Depends').split(',')}
    assert {'libc6 (>= 2.39)', 'libgcc-s1', 'libdbus-1-3', 'libgtk-3-0t64',
            'libwebkit2gtk-4.1-0', 'libayatana-appindicator3-1'} <= dependencies
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='nekokem-linux-gui-package-') as temporary:
        stage = Path(temporary)/f'NekoKEM-linux-{arch}-GUI'
        stage.mkdir()
        subprocess.run(['dpkg-deb', '-x', str(deb), str(stage)], check=True)
        binary = stage/'usr/bin/nekokem-gui'
        metadata = checker.verify(binary, arch, prefix)
        metadata.update(source_sha=source, run_id=os.environ.get('GITHUB_RUN_ID'),
                        package=field('Package'), package_architecture=expected_arch)
        licenses = list(stage.glob('usr/**/licenses/OpenSSL-LICENSE.txt'))
        assert len(licenses) == 1 and licenses[0].read_bytes() == (prefix/'LICENSE.txt').read_bytes()
        assert (licenses[0].parent/'NekoKEM-LICENSE.txt').read_bytes() == (ROOT/'LICENSE').read_bytes()
        entries = list(stage.glob('usr/share/applications/*.desktop'))
        assert len(entries) == 1
        desktop = entries[0].read_text()
        assert 'Exec=nekokem-gui' in desktop and 'Terminal=false' in desktop
        # Tauri preserves the @2x source's HiDPI designation in the hicolor
        # directory name. Verify every installed icon against the source bytes.
        icon_root = stage/'usr/share/icons/hicolor'
        expected_icons = {'32x32': '32x32.png', '128x128': '128x128.png',
                          '256x256@2': '128x128@2x.png'}
        installed_icons = set(icon_root.glob('*/apps/*.png'))
        assert installed_icons == {icon_root/size/'apps/nekokem-gui.png' for size in expected_icons}
        metadata['icon_hashes'] = {}
        for size, source_name in expected_icons.items():
            icon = icon_root/size/'apps/nekokem-gui.png'
            assert icon.read_bytes() == (ROOT/'windows/icons'/source_name).read_bytes()
            metadata['icon_hashes'][size] = digest(icon)
        metadata['rpm'] = verify_rpm(rpm, stage, arch, prefix)
        for name, origin in [('README.md',ROOT/'desktop/README.md'),
                             ('LINUX-SECURITY.md',ROOT/'desktop/LINUX-SECURITY.md'),
                             ('LICENSE',ROOT/'LICENSE'),('OPENSSL-LICENSE.txt',prefix/'LICENSE.txt'),
                             ('Cargo.lock',ROOT/'desktop/src-tauri/Cargo.lock'),
                             ('package-lock.json',ROOT/'desktop/package-lock.json')]:
            shutil.copy2(origin, stage/name)
        launcher = stage/'NekoKEM-GUI.sh'
        launcher.write_text('#!/bin/sh\nset -eu\nroot=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)\nexec "$root/usr/bin/nekokem-gui" "$@"\n')
        launcher.chmod(0o755)
        (stage/'build-metadata.json').write_text(json.dumps(metadata, indent=2)+'\n')
        sums = ''.join(f'{digest(path)}  {path.relative_to(stage).as_posix()}\n'
                       for path in sorted(stage.rglob('*')) if path.is_file() and not path.is_symlink())
        (stage/'SHA256SUMS.txt').write_text(sums)
        archive = output/f'NekoKEM-linux-{arch}-GUI.tar.gz'
        epoch = int(subprocess.check_output(['git','-C',str(ROOT),'log','-1','--format=%ct'],text=True).strip())
        def normalized(member):
            member.uid = member.gid = 0
            member.uname = member.gname = ''
            member.mtime = epoch
            assert member.isdir() or member.isfile(), 'Public archives must not contain links or special files'
            member.mode = 0o755 if member.isdir() or member.name in {
                stage.name+'/NekoKEM-GUI.sh', stage.name+'/usr/bin/nekokem-gui'} else 0o644
            return member
        with archive.open('wb') as stream, gzip.GzipFile(filename='',mode='wb',fileobj=stream,mtime=0) as compressed:
            with tarfile.open(mode='w',fileobj=compressed,format=tarfile.GNU_FORMAT) as tar:
                tar.add(stage,arcname=stage.name,filter=normalized)
        with tarfile.open(archive,'r:gz') as tar:
            for line in sums.splitlines():
                expected,name = line.split('  ',1)
                assert hashlib.sha256(tar.extractfile(stage.name+'/'+name).read()).hexdigest() == expected
            assert tar.getmember(stage.name+'/NekoKEM-GUI.sh').mode == 0o755
            assert tar.getmember(stage.name+'/usr/bin/nekokem-gui').mode == 0o755
        public_deb = output/f'NekoKEM-linux-{arch}-GUI.deb'
        shutil.copy2(deb, public_deb)
        public_rpm = output/f'NekoKEM-linux-{arch}-GUI.rpm'
        shutil.copy2(rpm, public_rpm)
        (output/'gui-build-metadata.json').write_text(json.dumps(metadata, indent=2)+'\n')
        (output/'gui-SHA256SUMS.txt').write_text(''.join(f'{digest(path)}  {path.name}\n' for path in [archive, public_deb, public_rpm]))
        print(f'Verified native {arch} DEB/RPM, ELF protections, runtime dependencies, desktop entry, square icons, licenses and portable archive')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--arch', choices=['x86_64','aarch64'], required=True)
    parser.add_argument('--openssl-prefix', type=Path, required=True)
    parser.add_argument('--source-sha', required=True)
    args = parser.parse_args()
    package(args.bundle_dir.resolve(),args.output.resolve(),args.arch,args.openssl_prefix.resolve(),args.source_sha)
