# NekoKEM Desktop GUI

Rust + Tauri 2 + Vue 3 + TypeScript desktop frontend, released as v3.3.1 for
Windows 10/11 x64. It requires the Microsoft Edge WebView2 Runtime. Open
`nekokem-gui.exe` to
use the app; it opens a native window without a terminal. Keep the included
Microsoft `WebView2Loader.dll` beside the EXE. The portable package
contains no Android signing material and is not Authenticode signed.

v3.3.1 also provides native Apple Silicon `aarch64-apple-darwin`
`NekoKEM.app` ZIP and DMG packages using macOS system WKWebView. macOS 11.0 is
the deployment target; native CI runs on macOS 15. Intel Macs are not supported.
本版 Release 提供 M 系列 Mac 原生应用 ZIP 与 DMG，部署目标为 macOS 11.0，
实际验证基线为 macOS 15。

Linux GUI targets native x86_64 and ARM64 with system GTK3, WebKitGTK 4.1 and
glibc 2.39 or newer. Ubuntu 24.04 is the build/test baseline; native Fedora 44
containers install and launch the RPM in CI. v3.3.1 provides `.deb`, `.rpm` and
portable `.tar.gz` packages for each architecture. Linux 原生 GUI 提供两种架构
的 DEB、RPM 和便携包；实际验证基线为 Ubuntu 24.04 与 Fedora 44 的 X11 会话。

## Features / 功能

- Generate an encrypted NKPR private key and public key; encrypt/decrypt NKEM v3
  files; display public-key fingerprints. 密钥生成、文件加解密、公钥指纹。
- Choose paths using native file dialogs or paste both PEM key blocks.
  支持系统文件选择器及粘贴 PEM 密钥。
- Display progress and cancel file operations safely. Show errors and preserve
  existing output on failure/cancellation through the same Core checks as the CLI.
- Automatically detect Windows/macOS display language or Linux locale and share the CLI's private saved
  language preference. English, simplified/traditional Chinese, Japanese and Korean.
- Both CLI and GUI retain the square Android artwork; the outer white area of
  the Windows EXE icons is transparent. Android keeps its existing square white
  background; README uses a separate rounded display image.
  macOS ICNS contains the same transparent square PNG artwork without editing pixels.
  Linux uses the existing transparent square PNGs directly.
  CLI/GUI 图案保持方形，EXE、macOS 与 Linux 图标外部白色区域透明；README 圆角只用于展示。
- Short entry transitions, navigation/button feedback and animated progress follow
  the system's reduced-motion preference. Windows high-contrast mode is supported.
  页面过渡、按钮反馈与进度动画遵循系统减少动态效果设置，支持 Windows 高对比度。
- Navigate operations with Up/Down, Home/End; validation focuses the affected field.
  Native file dialogs lock editing until they return. 支持键盘导航与错误字段定位。

Production cryptography stays in the shared C17 Core. Rust validates requests,
serializes Core calls, owns zeroizing secrets, bridges progress/cancellation and
creates protected temporary pasted-key files through the platform backend. The Vue
frontend has no filesystem, shell, arbitrary process or remote-content access.

## Windows build

Use Node 24, Rust with `x86_64-pc-windows-gnu` target, MSYS2 UCRT64 GCC and the
pinned static OpenSSL 4.0.3 prefix created by `windows/scripts/build-windows-cli.sh`.
The GNU target intentionally matches the existing tested C17 Windows ABI/toolchain.

```powershell
rustup toolchain install stable-x86_64-pc-windows-gnu
rustup default stable-x86_64-pc-windows-gnu
$env:Path = 'C:\msys64\ucrt64\bin;' + $env:Path
$env:CC_x86_64_pc_windows_gnu = 'C:\msys64\ucrt64\bin\gcc.exe'
$env:NEKOKEM_OPENSSL_PREFIX = 'C:\path\to\pinned-windows-openssl'
cd desktop
npm ci --ignore-scripts
npm test
npm run build
npm run tauri -- build --target x86_64-pc-windows-gnu --no-bundle
```

Checked-in lockfiles are used with npm ci and Cargo --locked. The GNU host
toolchain also makes Tauri's resource compiler produce GNU-compatible COFF icons
and the application manifest. CI verifies the loader DLL against the checksum-locked
WebView2 SDK crate and its Microsoft Authenticode signature before packaging.

## Apple Silicon macOS build

Build on an arm64 Mac with Xcode Command Line Tools, Node 24 and Rust stable.
The macOS helper builds the pinned static OpenSSL 4.0.3 prefix and checks its
official source checksum, compiler/SDK manifest, runtime and Argon2 thread support.
GUI builds reuse that prefix. OpenSSL and project licenses enter the app before
its resources are sealed and signed.

```sh
bash macos/scripts/build-openssl.sh /absolute/path/to/pinned-macos-openssl
export NEKOKEM_OPENSSL_PREFIX=/absolute/path/to/pinned-macos-openssl
export MACOSX_DEPLOYMENT_TARGET=11.0
rustup target add aarch64-apple-darwin
cd desktop
npm ci --ignore-scripts
npm test
npm run build
TAURI_CONFIG="$(cat src-tauri/tauri.macos.conf.json)" cargo test --locked --target aarch64-apple-darwin --manifest-path src-tauri/Cargo.toml --bin nekokem-gui -- --test-threads=1
npm run tauri -- build --target aarch64-apple-darwin --config src-tauri/tauri.macos.conf.json --bundles app,dmg -- --locked
cd ..
python3 desktop/scripts/package-macos-gui.py \
  --bundle-dir desktop/src-tauri/target/aarch64-apple-darwin/release/bundle \
  --output macos/dist \
  --version 3.3.1 --source-sha "$(git rev-parse HEAD)"
```

`NekoKEM-macos-arm64-GUI.zip` contains `NekoKEM.app`, documentation, licenses,
source/build metadata and checksums. `NekoKEM-macos-arm64-GUI.dmg` contains the
same app. Packaging verifies thin arm64 Mach-O, PIE, minimum OS, system-only
dynamic imports, embedded ICNS and licenses, then mounts the DMG read-only to
confirm the same sealed app is present. CI runs real Rust/Core roundtrips,
wrong-password, pasted-key and cancellation tests, POSIX permission/link checks,
and visible native window startup with local WKWebView page-load and real
settings IPC evidence. Windows retains its original GNU/UCRT64 toolchain,
loader and PE checks.

The macOS app has an **ad-hoc signature with hardened runtime**. This is not an
Apple Developer ID signature, and the application is not notarized. The signature
checks sealed resources without establishing a trusted developer identity.
Gatekeeper may block downloaded packages. This project does not disable or bypass
macOS security protections. Mac 应用使用 ad-hoc 签名与 hardened runtime，未经
Developer ID 签名或 Apple 公证，系统可能阻止启动；这不是可信开发者身份认证。

## Linux installation and build / Linux 安装与构建

Use the package matching `uname -m`: `x86_64` (Debian `amd64`) or `aarch64`
(Debian `arm64`). On Ubuntu 24.04 install the `.deb` with apt, then open **NekoKEM**
from the application menu; no terminal is required. Its installed desktop entry
uses the existing square app icon.

```sh
sudo apt install ./NekoKEM-linux-x86_64-GUI.deb
```

Fedora 44 uses the native `.rpm`; DNF resolves GTK3/WebKitGTK 4.1 and the
versioned glibc requirement from declared library capabilities. Change the
architecture to `aarch64` for ARM64. Fedora 可安装 RPM，并从应用菜单启动。

```sh
sudo dnf install ./NekoKEM-linux-x86_64-GUI.rpm
```

The RPM is built directly by Tauri, with the same application, desktop entry,
icons and licenses as the DEB; only Tauri's three-byte bundle-format marker
differs in the executable. It is not developer GPG signed. Verify package
SHA-256 hashes; these and RPM digests check integrity, not developer identity.
Only generated public resource copies have their permissions normalized to
`0644`; private keys and configuration retain `0600`/`0700`.

For the portable archive, install system dependencies, extract it and run the
launcher. Change `x86_64` to `aarch64` for ARM64. A graphical session is required;
the archive does not bundle GTK, WebKit or glibc; the supported baseline requires
glibc 2.39 or newer.
Keep the package tree together. Linux 便携包需要系统图形运行库；安装包可从系统应用菜单打开。

```sh
sudo apt install libgtk-3-0t64 libwebkit2gtk-4.1-0 libayatana-appindicator3-1
tar -xzf NekoKEM-linux-x86_64-GUI.tar.gz
./NekoKEM-linux-x86_64-GUI/NekoKEM-GUI.sh
```

Automatic language detection uses the CLI's saved preference, then `LC_ALL`,
`LC_MESSAGES`, `LANG`, falling back to English. Five languages and **Follow system**
are available. CLI and GUI share `$XDG_CONFIG_HOME/nekokem/language`, or
`$HOME/.config/nekokem/language` when XDG is unset/relative, with mode `0600`.
The GUI renders UTF-8 even when launched with a `C/POSIX` terminal locale; the
CLI's ASCII/non-UTF-8 terminal fallback continues to apply to terminal output.
GUI 在 C/POSIX 环境下仍可手动选择五种语言。

Build on the native Ubuntu 24.04 architecture with Node 24 and Rust stable:

```sh
sudo apt install build-essential curl perl pkg-config libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev patchelf rpm
architecture=$(uname -m)
bash desktop/scripts/build-linux-openssl.sh "$architecture" /absolute/path/to/pinned-linux-gui-openssl
export NEKOKEM_OPENSSL_PREFIX=/absolute/path/to/pinned-linux-gui-openssl
rustup target add "$architecture-unknown-linux-gnu"
cd desktop
npm ci --ignore-scripts
npm test
npm run build
TAURI_CONFIG="$(cat src-tauri/tauri.linux.conf.json)" cargo test --locked --target "$architecture-unknown-linux-gnu" --manifest-path src-tauri/Cargo.toml --bin nekokem-gui -- --test-threads=1
bash scripts/build-linux-gui.sh "$architecture"
cd ..
python3 desktop/scripts/package-linux-gui.py \
  --bundle-dir "desktop/src-tauri/target/$architecture-unknown-linux-gnu/release/bundle" \
  --output linux/gui-dist --arch "$architecture" \
  --openssl-prefix "$NEKOKEM_OPENSSL_PREFIX" --source-sha "$(git rev-parse HEAD)"
```

The helper verifies the official OpenSSL 4.0.3 source checksum and builds native
static PIC libcrypto with assembly/threads, Fortify and stack protection. Core and
OpenSSL symbols stay hidden from WebKit's dynamic TLS libraries. Packaging checks
ELF PIE/full RELRO/nonexecutable stack, dependencies, licenses, the unchanged PNG
icon, desktop entry and hashes of all three packages. RPM validation checks
native architecture, digests, every ELF dependency, installed file permissions,
absence of installation scripts and matching DEB resources. CI installs the
`.deb` on Ubuntu 24.04 and the `.rpm` through DNF in native Fedora 44 containers,
then runs actual X11 WebKit startup/normal-close checks as ordinary users on both
architectures with sandboxing retained. Rust/Core and private-file tests remain.
Wayland and distributions other than this Ubuntu/Fedora baseline have not
received equivalent native testing.
The build wrapper adds AArch64's dynamic-loader capability only to ARM64 RPMs.
See [Linux GUI security boundaries](LINUX-SECURITY.md).

## Security boundary / 安全边界

On Windows, the same local fixed-NTFS, owner/ACL, ancestor pinning, regular-file, hard-link,
reparse-point, device/pipe/ADS and atomic-output checks apply. The frontend cannot
relax them. Existing outputs must already satisfy the CLI's private output policy.
Passwords retain the CLI's 1024 UTF-8 byte limit. Private PEM text is obscured in the paste field. Pasted keys are capped at 1 MiB
and 16384 UTF-8 bytes per line, matching the CLI, and remain subject to Core format/component/tail validation.

On macOS, operations use Core's POSIX regular-file, ownership, private mode,
link/output and atomic-commit checks. Private files/directories reject extended
allow ACLs even when their mode is 0600/0700. Regular-file commits and pasted-key
staging require successful fsync and F_FULLFSYNC; failure is reported. Pasted
keys use a new 0700 directory, an exclusive 0600 no-follow file and descriptor-relative
operations. Cleanup rejects symlinks, extra hard links and unsafe permissions.
See the [macOS filesystem design](../macos/SECURITY-DESIGN.md) for platform limits.

On Linux, the same Core POSIX file checks and descriptor-relative pasted-key
staging apply, requiring private `0700` directories, `0600` regular files, current
ownership, one hard link, no symlinks and successful `fsync`. System WebKitGTK
keeps its sandbox enabled and uses a temporary data store. See the
[Linux GUI design](LINUX-SECURITY.md) for limits and the native test baseline.

Only bundled local content runs with incognito enabled in the main WebView
(Windows InPrivate/macOS nonpersistent WKWebView/Linux ephemeral WebKitGTK storage). CSP blocks remote scripts,
frames and network requests. Only native open/save dialogs and the listed Rust
commands are exposed. Developer tools are unavailable in the production release.
Passwords/key text are not stored in browser storage, configuration or logs. Both
form references and live secret input values clear before native invocation,
operation switches and unmounting. Switching away from pasted keys discards their
text. Entry animations do not retain outgoing forms. Rust uses Zeroizing<String> and Core borrows
password bytes only for the call. JavaScript/IPC can make temporary string copies;
reliable zeroization of the WebView heap is not guaranteed. This extra UI/IPC
boundary has not received an independent professional audit.

Closing the window, or quitting the macOS application from its menu or ⌘Q,
during an operation requests cancellation and waits for Core
cleanup; key generation finishes safely before closing. C17 algorithms, KDF
parameters/domains, NKEM/NKPR formats, fingerprints, AAD, GCM limits and 64 KiB
streaming stay unchanged. Progress UI updates are throttled to about 10 per second,
while cancellation is checked on every Core callback.

Windows power-loss equivalence to POSIX directory fsync is unverified. Pair commits
are not cross-file crash atomic. Process death may leave protected plaintext/key
staging files; deletion is not physical erasure. See the
[Windows filesystem design](../windows/SECURITY-DESIGN.md) for the complete backend
boundary. The v3.3.1 Windows application is not Authenticode signed. Verifying the bundled
Microsoft SDK loader does not sign the application. This experimental project has
not undergone an independent professional security audit and must not be used to
protect important or sensitive data.

Download `NekoKEM-Windows-GUI.zip` from the [v3.3.1 Release](https://github.com/Shixiaoshi0417/NekoKEM/releases/tag/v3.3.1)
and verify it against the release's top-level `SHA256SUMS.txt`. See the complete
[bilingual release notes](../release/v3.3.1.md). v3.3.1 提供便携 GUI 压缩包；下载后先校验
SHA-256，再解压并保留 EXE 旁的 Microsoft SDK loader。
