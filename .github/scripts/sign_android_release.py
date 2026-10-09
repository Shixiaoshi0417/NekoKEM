#!/usr/bin/env python3
"""Sign the unsigned release APK in a job that runs no build code.

  sign_android_release.py UNSIGNED_APK OUTPUT_DIR

The Release workflow builds the APK without any signing material and hands it
to this script in a separate job, so neither Gradle, its plugins nor any
cached build output ever run alongside the signing secrets. The secrets live
only in this step's environment and a private directory that is removed
before the script exits. OUTPUT_DIR receives the signed APK and its public
build metadata, plus the encrypted recovery envelope only for a rotated key.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from prepare_release_signing import prepare  # noqa: E402
import verify_android_release as release  # noqa: E402


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    unsigned = Path(sys.argv[1])
    output = Path(sys.argv[2])
    root = Path(os.environ['RUNNER_TEMP']) / 'nekokem-release-signing'
    os.umask(0o077)
    output.mkdir()
    try:
        store, child, values, rotated = prepare(root, os.environ)
        # Verification runs curl, openssl and build tools: none of them gets a secret.
        for key in [key for key in os.environ if key.startswith('NEKOKEM_RELEASE_')]:
            del os.environ[key]
        apk = output / 'app-release.apk'
        # v2 only, as every release since v3.2.0; v1 is unused at minSdk 26.
        subprocess.run([str(release.build_tools() / 'apksigner'), 'sign',
                        '--ks', str(store), '--ks-type', 'PKCS12',
                        '--ks-key-alias', values['KEY_ALIAS'],
                        '--ks-pass', 'env:NEKOKEM_RELEASE_STORE_PASSWORD',
                        '--key-pass', 'env:NEKOKEM_RELEASE_KEY_PASSWORD',
                        '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
                        '--v3-signing-enabled', 'false', '--v4-signing-enabled', 'false',
                        '--out', str(apk), str(unsigned)],
                       env=child, check=True)
        metadata = release.verify(unsigned, apk, rotated, root)
        if rotated:
            metadata['recovery_recipient_sha256'] = release.seal(root, output)
    finally:
        shutil.rmtree(root, ignore_errors=True)
    (output / 'build-metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(json.dumps(metadata, indent=2))


if __name__ == '__main__':
    main()
