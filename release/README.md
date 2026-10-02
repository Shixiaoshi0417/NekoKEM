# Build workflows and Android signing

GitHub Actions has two workflow definitions:

- `ci.yml` (`CI`) builds and tests pull requests targeting `main`, pushes to
  `main`, and manual runs. It contains the Linux Core/analyzer/sanitizer/fuzz
  suite, x86_64/aarch64 release-package and installer tests, Android JVM and
  API 26/35 device tests, native Windows CLI security/interoperability/performance
  checks, and Windows GUI frontend/Rust/Core/EXE-launch checks.
- `release.yml` (`Release`) builds artifacts on `v*` tags and manual runs. It
  produces Linux x86_64/aarch64 archives, a signed Android arm64 APK, and Windows
  CLI/GUI packages. Regression suites run in CI. Source checksums, dependency
  versions, compiler hardening, binary imports/protections, SDK loader signature,
  icons, APK identity/signature and package checksums remain build requirements.

The shared Linux/Windows build scripts default to `NEKOKEM_BUILD_TESTS=1`.
Release sets it to `0`, which omits the Linux cryptographic smoke test and Windows
test executables while retaining the required dependency and artifact checks.
CI verifies that both modes produce identical CLI binaries/packages.

Run Release from the Actions page or with `gh workflow run release.yml --ref main`.
Build artifacts are available on that run; this workflow only builds and uploads
artifacts to Actions. Publishing is a separate maintainer operation after checking
the same source's successful CI and artifact/signing provenance.

The Android job uses the GitHub Environment named `NekoKEM` and requires:

- `NEKOKEM_RELEASE_KEYSTORE_BASE64`: base64 of the PKCS12 signing keystore.
- `NEKOKEM_RELEASE_STORE_PASSWORD`
- `NEKOKEM_RELEASE_KEY_ALIAS`
- `NEKOKEM_RELEASE_KEY_PASSWORD`

Normal Release builds always use the existing signing identity. The APK signer
must match the certificate published with v3.2.0; missing or mismatched credentials
fail the build. Key rotation requires a separate, explicitly authorized maintainer
operation and a migration warning because a new key cannot update existing APKs.
The job retains the Environment's approval and branch protection rules.

The Android artifact contains the signed APK, public build metadata and a CMS
signing recovery envelope encrypted with AES-256-GCM and RSA-OAEP/SHA-256 to the
pinned public recovery recipient. The recipient's private key stays outside the
repository. Plaintext signing keys/passwords and the recovery private key must
never be uploaded. Plaintext signing material is removed even when the job fails.

The v3.3.0 public release assets are:

- `app-release.apk`
- `NekoKEM-linux-x86_64.tar.gz`
- `NekoKEM-linux-aarch64.tar.gz`
- `NekoKEM-windows-x86_64.zip`
- `NekoKEM-Windows-GUI.zip`
- `SHA256SUMS.txt`

Publish these packages from the successful Release run for the exact v3.3.0 source,
after that source passes CI. Generate the top-level `SHA256SUMS.txt` from the five
published packages. Record the source commit, CI/Release run links, Android signer
and public asset hashes in the [Chinese-then-English v3.3.0 notes](v3.3.0.md).
The encrypted signing recovery envelope remains a protected Actions artifact;
**do not attach it to the public GitHub Release**. Windows application EXEs are
unsigned; verification of the Microsoft SDK loader's signature is separate.

v3.3.0 keeps the v3.2.0 Android signing identity and supports an in-place update.
Only old-signer v3.1.x installations need backup verification and uninstall/reinstall.
Keep the existing square artwork for Android, use transparent outer white areas
for Windows EXE icons, and a separate rounded image for README display.

The v3.2.0 bootstrap key rotation and publication records remain historical audit
data. Their one-time workflows and hard-coded publication automation have been
retired in favor of these two entry points.
