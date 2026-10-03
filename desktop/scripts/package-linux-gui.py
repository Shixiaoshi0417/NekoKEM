"""Check a native Debian package and archive its exact installed application."""
from pathlib import Path
import argparse
import gzip
import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('check_gui_linux', ROOT/'desktop/tests/check_gui_linux.py')
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def package(bundle, output, arch, prefix, source):
    assert len(source) == 40 and all(char in '0123456789abcdef' for char in source)
    candidates = list((bundle/'deb').glob('*.deb'))
    assert len(candidates) == 1, 'Use a clean bundle directory with one Debian package'
    deb = candidates[0]
    expected_arch = 'arm64' if arch == 'aarch64' else 'amd64'
    field = lambda name: subprocess.check_output(['dpkg-deb', '-f', str(deb), name], text=True).strip()
    assert field('Architecture') == expected_arch and field('Version') == '3.3.0'
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
        (output/'gui-build-metadata.json').write_text(json.dumps(metadata, indent=2)+'\n')
        (output/'gui-SHA256SUMS.txt').write_text(f'{digest(archive)}  {archive.name}\n{digest(public_deb)}  {public_deb.name}\n')
        print(f'Verified native {arch} Debian package, desktop entry, square icon, licenses and portable archive')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--arch', choices=['x86_64','aarch64'], required=True)
    parser.add_argument('--openssl-prefix', type=Path, required=True)
    parser.add_argument('--source-sha', required=True)
    args = parser.parse_args()
    package(args.bundle_dir.resolve(),args.output.resolve(),args.arch,args.openssl_prefix.resolve(),args.source_sha)
