# Build workflows and Android signing

GitHub Actions has two workflow definitions:

- `ci.yml` (`CI`) builds and tests pull requests targeting `main`, pushes to
  `main`, and manual runs. It contains the Linux Core/analyzer/sanitizer/fuzz
  suite, x86_64/aarch64 release-package and installer tests, Android JVM and
  API 26/35 device tests, native Windows CLI security/interoperability/performance
  checks, Windows GUI frontend/Rust/Core/EXE-launch checks, and native macOS
  arm64 CLI/Core/sanitizer/filesystem/interoperability and GUI/app/package checks,
  plus Linux x86_64/ARM64 GUI Rust/Core, private staging, ELF/package and actual
  WebKit window/normal-close checks.
- `release.yml` (`Release`) builds artifacts on `v*` tags and manual runs. It
  produces Linux x86_64/aarch64 archives, a signed Android arm64 APK, and Windows
  CLI/GUI packages, macOS arm64 CLI/GUI packages and Linux x86_64/ARM64 GUI
  `.deb`/`.rpm`/portable archives. Regression suites run in CI. Source checksums, dependency
  versions, compiler hardening, binary imports/protections, SDK loader signature,
  icons, APK identity/signature and package checksums remain build requirements.

The shared Linux/Windows/macOS CLI build scripts default to `NEKOKEM_BUILD_TESTS=1`.
Release sets it to `0`, which omits the Linux cryptographic smoke test and Windows
test executables and macOS regression suites while retaining the required dependency and artifact checks.
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

The v4.0.1 public release contains 14 packages:

| Platform | Public assets |
|---|---|
| Android ARM64 | `app-release.apk` |
| Windows x64 | `NekoKEM-windows-x86_64.zip`, `NekoKEM-Windows-GUI.zip` |
| macOS Apple Silicon | `NekoKEM-macos-arm64.tar.gz`, `NekoKEM-macos-arm64-GUI.zip`, `NekoKEM-macos-arm64-GUI.dmg` |
| Linux x86_64 | `NekoKEM-linux-x86_64.tar.gz`, `NekoKEM-linux-x86_64-GUI.deb`, `NekoKEM-linux-x86_64-GUI.rpm`, `NekoKEM-linux-x86_64-GUI.tar.gz` |
| Linux ARM64 | `NekoKEM-linux-aarch64.tar.gz`, `NekoKEM-linux-aarch64-GUI.deb`, `NekoKEM-linux-aarch64-GUI.rpm`, `NekoKEM-linux-aarch64-GUI.tar.gz` |

Publish only packages from the successful Release run for the exact CI-validated
v4.0.1 source. Generate the fifteenth asset, top-level `SHA256SUMS.txt`, from all
14 packages. Record the source commit, CI/Release run links, Android signer and
public asset hashes in the [Chinese-then-English v4.0.1 notes](v4.0.1.md).
The encrypted signing recovery envelope remains a protected Actions artifact;
**do not attach it to the public GitHub Release**. Windows application EXEs are
unsigned; verification of the Microsoft SDK loader's signature is separate.

Android v4.0.1 uses versionCode `9`, keeps the v3.2.0/v3.3.0 signing identity and
supports an in-place update. Only old-signer v3.1.x installations need backup
verification and uninstall/reinstall. Keep the existing square artwork for Android,
use transparent outer white areas for Windows EXE icons, reuse those PNGs for
macOS/Linux, and use a separate rounded image for README display.

The v3.2.0 bootstrap key rotation and publication records remain historical audit
data. Their one-time workflows and hard-coded publication automation have been
retired in favor of these two entry points.

## macOS Apple Silicon build artifacts

v4.0.1 includes the following native macOS packages in CI and Release:

- `NekoKEM-macos-arm64.tar.gz`: native CLI and Terminal menu launcher.
- `NekoKEM-macos-arm64-GUI.zip`: `NekoKEM.app`, documentation, licenses,
  checksums and build metadata.
- `NekoKEM-macos-arm64-GUI.dmg`: Tauri DMG checked and exported under a stable
  package name; its initial Tauri filename is `NekoKEM_4.0.1_aarch64.dmg`.

Builds use native Apple Silicon and set a macOS 11.0 deployment target. The actual
native CI runner is `macos-15`; the target does not establish compatibility on
macOS 11.0 hardware or every M-series generation. OpenSSL 4.0.3 is statically
linked with arm64 assembly acceleration; macOS system libraries/frameworks remain
runtime dependencies. The GUI uses system WKWebView without a WebView2 loader.

CLI and GUI use ad-hoc hardened-runtime signatures, with no Developer ID or Apple
notarization. The build verifies their structure and integrity; this does not
authenticate the publisher or guarantee Gatekeeper acceptance. Android signing
material is not used. Native architecture, PIE, deployment target, allowable
system dylibs, signatures, icons, metadata and package checksums remain build
requirements in Release mode. Regression suites stay in CI.

See [macOS usage](../macos/README.md), [filesystem boundaries](../macos/SECURITY-DESIGN.md)
and [GUI build instructions](../desktop/README.md). Every published macOS package
is included in top-level `SHA256SUMS.txt`; historical v3.3.0 assets remain unchanged.

## Linux GUI build artifacts

Linux GUI v4.0.1 uses native Ubuntu 24.04 x86_64 and ARM64 build/test runners.
CI and Release produce, for each `x86_64`/`aarch64` architecture:

- `NekoKEM-linux-<architecture>-GUI.deb`: native application, desktop entry,
  existing transparent square PNG icons and project/OpenSSL licenses.
- `NekoKEM-linux-<architecture>-GUI.rpm`: native RPM with the same application
  resources, release `1` and shared-library capability dependencies, including
  glibc 2.39 or newer. It is not developer GPG signed.
- `NekoKEM-linux-<architecture>-GUI.tar.gz`: the exact installed application
  tree, executable `NekoKEM-GUI.sh`, documentation, licenses, dependency lockfiles,
  build metadata and inner checksums.
- `gui-SHA256SUMS.txt`: hashes of all three distributable packages.

The GUI uses system GTK3, WebKitGTK 4.1 and glibc 2.39 or newer; the portable archive
does not bundle these runtimes. This differs from the existing fully static CLI
archives, whose build path is retained. Core uses pinned static PIC OpenSSL 4.0.3
with assembly/threads and hidden symbols to isolate it from system WebKit TLS.
Native ELF architecture, PIE/full RELRO/nonexecutable stack, no RPATH/TEXTREL or
OpenSSL dynamic exports, licenses, unchanged icons and hashes remain Release
build requirements. RPM validation additionally checks declared ELF dependencies,
digests, public file permissions, absence of installation scripts and byte-identical
DEB resources; only Tauri's bundle-format marker differs in the executable.
Actual `.deb` installation on Ubuntu 24.04, `.rpm` installation through DNF in
native Fedora 44 containers, Rust/Core/private-file and sandboxed WebKit X11
startup/normal-close tests run only in CI. Other distributions and native Wayland
are not established by these tests. Android signing material is not used.

See [Linux GUI installation and building](../desktop/README.md#linux-installation-and-build--linux-安装与构建)
and [security boundaries](../desktop/LINUX-SECURITY.md). Every published Linux GUI
package is included in top-level release checksums. Historical v3.3.0 assets remain
unchanged.
