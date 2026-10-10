#!/usr/bin/env python3
"""Verify the signed release APK's identity, signer, ABI and contents."""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tarfile
import zipfile

VERSION = '4.1.0'
VERSION_CODE = 10
CORE_VERSION = '4.0'
# The v3.2.0 release signer; normal releases must keep it.
SIGNER = '5a091b86b1cb339f081c1afa2aa71b986c07597343ce336f22d866b05c39fb50'
PREVIOUS_APK = 'https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v3.2.0/app-release.apk'
PREVIOUS_SHA256 = '427b0716cc1ad2773e4a2b6d3dee55a9e5260b806fb280c60d13aa2a140aa511'
RECIPIENT = Path('release/signing-recovery-recipient.pem')
RECIPIENT_SHA256 = '5620deff70c932f040d022543f07f29c9eb20b1831463dada297a4ade580c95a'


def build_tools():
    return Path(os.environ['ANDROID_SDK_ROOT']) / 'build-tools/35.0.0'


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def signer(path):
    text = subprocess.check_output([str(build_tools() / 'apksigner'), 'verify', '--verbose',
                                    '--print-certs', str(path)], text=True)
    hashes = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', text, re.M)
    if len(hashes) != 1 or len(hashes[0]) != 64:
        raise SystemExit('Expected exactly one APK signer')
    if not re.search(r'^Verified using v2 scheme \(APK Signature Scheme v2\): true$', text, re.M):
        raise SystemExit('Expected an APK Signature Scheme v2 signature')
    return hashes[0].lower()


def entries(path):
    with zipfile.ZipFile(path) as archive:
        return [(item.filename, item.CRC, item.file_size, item.compress_type)
                for item in archive.infolist()]


def verify(unsigned, apk, rotated, work):
    """Check the signed APK and return its public build metadata."""
    # Signing adds only the v2 signature block; every entry stays as built.
    if entries(apk) != entries(unsigned):
        raise SystemExit('Signing changed the APK contents')
    subprocess.run([str(build_tools() / 'zipalign'), '-c', '-P', '16', '4', str(apk)],
                   check=True, stdout=subprocess.DEVNULL)
    new_signer = signer(apk)
    if not rotated and new_signer != SIGNER:
        raise SystemExit('Release signer does not match the existing v3.2.0 identity')
    badging = subprocess.check_output([str(build_tools() / 'aapt'), 'dump', 'badging', str(apk)],
                                      text=True)
    identity = (f"package: name='com.shixiaoshi0417.nekokem' versionCode='{VERSION_CODE}' "
                f"versionName='{VERSION}'")
    if identity not in badging:
        raise SystemExit('Unexpected application identity/version')
    if "sdkVersion:'26'" not in badging:
        raise SystemExit('Unexpected minimum SDK')
    with zipfile.ZipFile(apk) as archive:
        abis = {name.split('/')[1] for name in archive.namelist() if name.startswith('lib/')}
    if abis != {'arm64-v8a'}:
        raise SystemExit('Release APK must contain only arm64-v8a native code')
    previous = Path(work) / 'previous.apk'
    subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error', '--proto', '=https',
                    '--tlsv1.2', '--retry', '3', '-o', str(previous), PREVIOUS_APK], check=True)
    if sha256(previous) != PREVIOUS_SHA256:
        raise SystemExit('Previous APK checksum mismatch')
    old_signer = signer(previous)
    if not rotated and old_signer != new_signer:
        raise SystemExit('Release signer does not match the previous published APK')
    if rotated and old_signer == new_signer:
        raise SystemExit('Expected signing-key rotation')
    return {'source_sha': os.environ['GITHUB_SHA'], 'run_id': int(os.environ['GITHUB_RUN_ID']),
            'version': VERSION, 'version_code': VERSION_CODE, 'core_version': CORE_VERSION,
            'apk_sha256': sha256(apk), 'signer_sha256': new_signer,
            'previous_signer_sha256': old_signer, 'signing_key_rotated': rotated}


def seal(root, output):
    """Encrypt a rotated key and its passwords to the pinned recovery recipient."""
    der = subprocess.check_output(['openssl', 'x509', '-in', str(RECIPIENT), '-outform', 'DER'])
    if hashlib.sha256(der).hexdigest() != RECIPIENT_SHA256:
        raise SystemExit('Unexpected signing recovery recipient')
    bundle = Path(root) / 'recovery.tar.gz'
    with tarfile.open(bundle, 'w:gz') as archive:
        for name in ['release.p12', 'store_password.txt', 'key_password.txt', 'key_alias.txt']:
            archive.add(Path(root) / name, arcname=name)
    subprocess.run(['openssl', 'cms', '-encrypt', '-aes-256-gcm', '-binary', '-outform', 'DER',
                    '-in', str(bundle), '-out', str(Path(output) / 'signing-recovery.p7m'),
                    '-recip', str(RECIPIENT), '-keyopt', 'rsa_padding_mode:oaep',
                    '-keyopt', 'rsa_oaep_md:sha256'], check=True)
    return RECIPIENT_SHA256
