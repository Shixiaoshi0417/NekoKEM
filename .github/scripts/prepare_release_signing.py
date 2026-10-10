#!/usr/bin/env python3
"""Materialize the Android signing keystore for one signing step.

The credentials come from that step's environment and stay inside its process
tree: nothing is written to GITHUB_ENV, the repository or artifacts, and the
private directory is removed by the caller before the step ends. Password
files exist only for a rotated key, whose recovery envelope must carry them.
"""
import base64
import os
import secrets
import subprocess

NAMES = ('STORE_PASSWORD', 'KEY_PASSWORD', 'KEY_ALIAS')


def prepare(root, environ):
    """Return (keystore, child environment, values, rotated)."""
    rotated = environ.get('ROTATE_SIGNING') == 'true'
    if rotated:
        password = secrets.token_urlsafe(48)
        values = {'STORE_PASSWORD': password, 'KEY_PASSWORD': password,
                  'KEY_ALIAS': 'nekokem-release'}
    else:
        values = {key: environ.get('NEKOKEM_RELEASE_' + key, '') for key in NAMES}
        if not all(values.values()) or not environ.get('NEKOKEM_RELEASE_KEYSTORE_BASE64'):
            raise SystemExit('Release signing secrets are required; key rotation is never automatic.')
    for value in values.values():
        if '\n' in value or '\r' in value:
            raise SystemExit('Invalid signing configuration')
        print('::add-mask::' + value)
    keystore = None
    if not rotated:
        try:
            keystore = base64.b64decode(environ['NEKOKEM_RELEASE_KEYSTORE_BASE64'], validate=True)
        except ValueError:
            raise SystemExit('Invalid signing keystore encoding') from None

    # Children see the two passwords by name only; no other release secret.
    child = {key: value for key, value in environ.items()
             if not key.startswith('NEKOKEM_RELEASE_')}
    child['NEKOKEM_RELEASE_STORE_PASSWORD'] = values['STORE_PASSWORD']
    child['NEKOKEM_RELEASE_KEY_PASSWORD'] = values['KEY_PASSWORD']

    previous = os.umask(0o077)
    try:
        root.mkdir(mode=0o700)
        store = root / 'release.p12'
        if rotated:
            subprocess.run(['keytool', '-genkeypair', '-storetype', 'PKCS12',
                            '-keystore', str(store), '-alias', values['KEY_ALIAS'],
                            '-keyalg', 'RSA', '-keysize', '4096', '-sigalg', 'SHA256withRSA',
                            '-validity', '10000', '-dname', 'CN=NekoKEM release signing',
                            '-storepass:env', 'NEKOKEM_RELEASE_STORE_PASSWORD',
                            '-keypass:env', 'NEKOKEM_RELEASE_KEY_PASSWORD'],
                           env=child, check=True)
            for key, value in values.items():
                (root / (key.lower() + '.txt')).write_text(value)
        else:
            store.write_bytes(keystore)
        subprocess.run(['keytool', '-list', '-keystore', str(store),
                        '-alias', values['KEY_ALIAS'],
                        '-storepass:env', 'NEKOKEM_RELEASE_STORE_PASSWORD'],
                       env=child, check=True, stdout=subprocess.DEVNULL)
    finally:
        os.umask(previous)
    return store, child, values, rotated
