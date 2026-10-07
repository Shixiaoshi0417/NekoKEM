#!/usr/bin/env python3
"""Verify release identity/signature/ABI and seal the recovery bundle."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import zipfile

os.umask(0o077)
output = Path('release-output')
output.mkdir()
apk = output / 'app-release.apk'
shutil.copyfile('android/app/build/outputs/apk/release/app-release.apk', apk)
tools = Path(os.environ['ANDROID_SDK_ROOT']) / 'build-tools/35.0.0'
def signer(path):
    text = subprocess.check_output([str(tools/'apksigner'), 'verify', '--verbose', '--print-certs', str(path)], text=True)
    hashes = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', text, re.M)
    if len(hashes) != 1 or len(hashes[0]) != 64:
        raise SystemExit('Expected exactly one APK signer')
    return hashes[0].lower()
new_signer = signer(apk)
root = Path(os.environ['RUNNER_TEMP']) / 'nekokem-release-signing'
rotated = (root/'rotation.txt').read_text() == 'True'
# Preserve the identity check from the retired one-time Secrets workflow.
if not rotated and new_signer != '5a091b86b1cb339f081c1afa2aa71b986c07597343ce336f22d866b05c39fb50':
    raise SystemExit('Release signer does not match the existing v3.2.0 identity')
badging = subprocess.check_output([str(tools/'aapt'), 'dump', 'badging', str(apk)], text=True)
if not re.search(r"package: name='com.shixiaoshi0417.nekokem' versionCode='9' versionName='4.0.1'", badging):
    raise SystemExit('Unexpected application identity/version')
if "sdkVersion:'26'" not in badging:
    raise SystemExit('Unexpected minimum SDK')
with zipfile.ZipFile(apk) as archive:
    abis = {name.split('/')[1] for name in archive.namelist() if name.startswith('lib/')}
    if abis != {'arm64-v8a'}:
        raise SystemExit('Release APK must contain only arm64-v8a native code')
previous = root/'previous.apk'
subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error', '--proto', '=https',
                '--tlsv1.2', '--retry', '3', '-o', str(previous),
                'https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v3.2.0/app-release.apk'], check=True)
if hashlib.sha256(previous.read_bytes()).hexdigest() != '427b0716cc1ad2773e4a2b6d3dee55a9e5260b806fb280c60d13aa2a140aa511':
    raise SystemExit('Previous APK checksum mismatch')
old_signer = signer(previous)
if not rotated and old_signer != new_signer:
    raise SystemExit('Release signer does not match the previous published APK')
if rotated and old_signer == new_signer:
    raise SystemExit('Expected signing-key rotation')
recipient = Path('release/signing-recovery-recipient.pem')
der = subprocess.check_output(['openssl', 'x509', '-in', str(recipient), '-outform', 'DER'])
if hashlib.sha256(der).hexdigest() != '5620deff70c932f040d022543f07f29c9eb20b1831463dada297a4ade580c95a':
    raise SystemExit('Unexpected signing recovery recipient')
bundle = root/'recovery.tar.gz'
with tarfile.open(bundle, 'w:gz') as archive:
    for name in ['release.p12','store_password.txt','key_password.txt','key_alias.txt']:
        archive.add(root/name, arcname=name)
subprocess.run(['openssl', 'cms', '-encrypt', '-aes-256-gcm', '-binary', '-outform', 'DER',
                '-in', str(bundle), '-out', str(output/'signing-recovery.p7m'),
                '-recip', str(recipient), '-keyopt', 'rsa_padding_mode:oaep',
                '-keyopt', 'rsa_oaep_md:sha256'], check=True)
metadata = {'source_sha': os.environ['GITHUB_SHA'], 'run_id': int(os.environ['GITHUB_RUN_ID']),
            'version': '4.0.1', 'version_code': 9, 'core_version': '4.0',
            'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
            'signer_sha256': new_signer, 'previous_signer_sha256': old_signer,
            'signing_key_rotated': rotated,
            'recovery_recipient_sha256': hashlib.sha256(der).hexdigest()}
(output/'build-metadata.json').write_text(json.dumps(metadata, indent=2)+'\n')
print(json.dumps(metadata, indent=2))
