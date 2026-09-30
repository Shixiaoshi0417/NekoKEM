# Release signing and publication

The signed Android preparation workflow builds a production arm64 APK and verifies
its application ID, version, minimum SDK, ABI and signature. It requires these
secrets in the GitHub Environment named `NekoKEM` for normal future builds:

- `NEKOKEM_RELEASE_KEYSTORE_BASE64`: base64 of the PKCS12 signing keystore.
- `NEKOKEM_RELEASE_STORE_PASSWORD`
- `NEKOKEM_RELEASE_KEY_ALIAS`
- `NEKOKEM_RELEASE_KEY_PASSWORD`

The v3.2.0 bootstrap explicitly rotated the signing key with the maintainer's
authorization. Subsequent builds run manually and default to using existing secrets
from the `NekoKEM` Environment; automatic bootstrap rotation on branch pushes has
been removed. Select `rotate_signing` only for an explicitly authorized key change
with a migration warning. A new key cannot update APKs signed with the previous key.
The jobs retain the Environment's configured approval and branch protection rules.

The workflow uploads the signed APK, public build metadata and a CMS signing
recovery envelope encrypted with AES-256-GCM and RSA-OAEP/SHA-256 to the pinned
public recovery recipient. The recipient's private key is held outside the
repository. Neither plaintext signing keys/passwords nor the recovery private key
may be uploaded to Actions or public releases. Recover and verify the signing
keystore before publishing, then retain it securely and configure the secrets for
future updates with the same signing identity.

Publication uses `release/publish-request.json` on `release/publish-v3.2.0` after
the source is merged. It requires exact source SHAs, successful complete CI,
native x86_64/aarch64 package tests, a verified signed APK and locally verified
signing recovery. It downloads those immutable artifacts, creates a draft release,
uploads only the APK, two Linux archives and `SHA256SUMS.txt`, downloads and checks
all uploaded hashes, then publishes the release. Published releases/tags are never
overwritten. The signing-recovery envelope is never a public release asset.
