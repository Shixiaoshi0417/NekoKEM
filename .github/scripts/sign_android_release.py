#!/usr/bin/env python3
"""Sign the unsigned release APK, then verify it in a step without secrets.

  sign_android_release.py sign UNSIGNED_APK OUTPUT_DIR
  sign_android_release.py verify UNSIGNED_APK OUTPUT_DIR

The Release workflow builds the APK without any signing material and hands it
to this script in a separate job, so neither Gradle, its plugins nor any
cached build output ever run alongside the signing secrets. `sign` runs in the
only step that has them: it signs, seals a rotated key for recovery and
removes its private directory before it exits. `verify` runs in a later step
whose environment never held a secret, so the tools that parse the APK and
curl cannot reach the key or its passwords, not even through a parent
process's environment. OUTPUT_DIR receives the signed APK and its public build
metadata, plus the encrypted recovery envelope only for a rotated key.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from prepare_release_signing import prepare  # noqa: E402
import verify_android_release as release  # noqa: E402

ENVELOPE = 'signing-recovery.p7m'


def sign(unsigned, output):
    root = Path(os.environ['RUNNER_TEMP']) / 'nekokem-release-signing'
    os.umask(0o077)
    output.mkdir()
    try:
        store, child, values, rotated = prepare(root, os.environ)
        # v2 only, as every release since v3.2.0; v1 is unused at minSdk 26.
        subprocess.run([str(release.build_tools() / 'apksigner'), 'sign',
                        '--ks', str(store), '--ks-type', 'PKCS12',
                        '--ks-key-alias', values['KEY_ALIAS'],
                        '--ks-pass', 'env:NEKOKEM_RELEASE_STORE_PASSWORD',
                        '--key-pass', 'env:NEKOKEM_RELEASE_KEY_PASSWORD',
                        '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
                        '--v3-signing-enabled', 'false', '--v4-signing-enabled', 'false',
                        '--out', str(output / 'app-release.apk'), str(unsigned)],
                       env=child, check=True)
        if rotated:
            release.seal(root, output)
    finally:
        shutil.rmtree(root, ignore_errors=True)


def verify(unsigned, output):
    if any(key.startswith('NEKOKEM_RELEASE_') for key in os.environ):
        raise SystemExit('Verify the signed APK in a step without signing secrets')
    rotated = os.environ.get('ROTATE_SIGNING') == 'true'
    with tempfile.TemporaryDirectory() as work:
        metadata = release.verify(unsigned, output / 'app-release.apk', rotated, work)
    envelope = output / ENVELOPE
    if rotated:
        if not envelope.is_file() or envelope.stat().st_size == 0:
            raise SystemExit('A rotated key needs its recovery envelope')
        metadata['recovery_recipient_sha256'] = release.RECIPIENT_SHA256
    elif envelope.exists():
        raise SystemExit('A recovery envelope exists only for a rotated key')
    (output / 'build-metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(json.dumps(metadata, indent=2))


def main():
    if len(sys.argv) != 4 or sys.argv[1] not in ('sign', 'verify'):
        raise SystemExit(__doc__)
    (sign if sys.argv[1] == 'sign' else verify)(Path(sys.argv[2]), Path(sys.argv[3]))


if __name__ == '__main__':
    main()
