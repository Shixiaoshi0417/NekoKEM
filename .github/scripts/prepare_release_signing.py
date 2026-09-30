#!/usr/bin/env python3
"""Prepare signing credentials; never write them to the repository or artifacts."""
import base64
import os
from pathlib import Path
import secrets
import subprocess

os.umask(0o077)
root = Path(os.environ['RUNNER_TEMP']) / 'nekokem-release-signing'
root.mkdir(mode=0o700)
store = root / 'release.p12'
rotate = os.environ.get('ROTATE_SIGNING') == 'true'
if rotate:
    password = secrets.token_urlsafe(48)
    values = {'STORE_PASSWORD': password, 'KEY_PASSWORD': password,
              'KEY_ALIAS': 'nekokem-release'}
else:
    values = {key: os.environ.get('NEKOKEM_RELEASE_' + key, '')
              for key in ['STORE_PASSWORD', 'KEY_PASSWORD', 'KEY_ALIAS']}
    if not all(values.values()) or not os.environ.get('NEKOKEM_RELEASE_KEYSTORE_BASE64'):
        raise SystemExit('Release signing secrets are required; key rotation is never automatic.')
for key, value in values.items():
    if '\n' in value or '\r' in value:
        raise SystemExit('Invalid signing configuration')
    print('::add-mask::' + value)
    os.environ['NEKOKEM_RELEASE_' + key] = value
    (root / (key.lower() + '.txt')).write_text(value)
if not rotate:
    store.write_bytes(base64.b64decode(os.environ['NEKOKEM_RELEASE_KEYSTORE_BASE64'], validate=True))
if rotate:
    subprocess.run(['keytool', '-genkeypair', '-storetype', 'PKCS12',
                    '-keystore', str(store), '-alias', values['KEY_ALIAS'],
                    '-keyalg', 'RSA', '-keysize', '4096', '-sigalg', 'SHA256withRSA',
                    '-validity', '10000', '-dname', 'CN=NekoKEM release signing',
                    '-storepass:env', 'NEKOKEM_RELEASE_STORE_PASSWORD',
                    '-keypass:env', 'NEKOKEM_RELEASE_KEY_PASSWORD'], check=True)
subprocess.run(['keytool', '-list', '-keystore', str(store),
                '-alias', values['KEY_ALIAS'],
                '-storepass:env', 'NEKOKEM_RELEASE_STORE_PASSWORD'],
               check=True, stdout=subprocess.DEVNULL)
with open(os.environ['GITHUB_ENV'], 'a') as env:
    env.write('NEKOKEM_RELEASE_STORE_FILE=' + str(store) + '\n')
    for key, value in values.items():
        env.write('NEKOKEM_RELEASE_' + key + '=' + value + '\n')
(root / 'rotation.txt').write_text(str(rotate))
