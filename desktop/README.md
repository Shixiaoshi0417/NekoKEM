# NekoKEM Windows GUI

Rust + Tauri 2 + Vue 3 + TypeScript desktop frontend, released as v3.3.0 for
Windows 10/11 x64. It requires the Microsoft Edge WebView2 Runtime. Open
`nekokem-gui.exe` to
use the app; it opens a native window without a terminal. Keep the included
Microsoft `WebView2Loader.dll` beside the EXE. The portable package
contains no Android signing material and is not Authenticode signed.

## Features / 功能

- Generate an encrypted NKPR private key and public key; encrypt/decrypt NKEM v3
  files; display public-key fingerprints. 密钥生成、文件加解密、公钥指纹。
- Choose paths using native file dialogs or paste both PEM key blocks.
  支持系统文件选择器及粘贴 PEM 密钥。
- Display progress and cancel file operations safely. Show errors and preserve
  existing output on failure/cancellation through the same Core checks as the CLI.
- Automatically detect Windows display language and share the CLI's private saved
  language preference. English, simplified/traditional Chinese, Japanese and Korean.
- Both CLI and GUI retain the square Android artwork; the outer white area of
  the Windows EXE icons is transparent. Android keeps its existing square white
  background; README uses a separate rounded display image.
  CLI/GUI 图案保持方形，EXE 图标外部白色区域透明；README 圆角只用于展示。
- Short entry transitions, navigation/button feedback and animated progress follow
  the system's reduced-motion preference. Windows high-contrast mode is supported.
  页面过渡、按钮反馈与进度动画遵循系统减少动态效果设置，支持 Windows 高对比度。
- Navigate operations with Up/Down, Home/End; validation focuses the affected field.
  Native file dialogs lock editing until they return. 支持键盘导航与错误字段定位。

Production cryptography stays in the shared C17 Core. Rust validates requests,
serializes Core calls, owns zeroizing secrets, bridges progress/cancellation and
creates protected temporary pasted-key files through the Windows backend. The Vue
frontend has no filesystem, shell, arbitrary process or remote-content access.

## Build

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

## Security boundary / 安全边界

The same local fixed-NTFS, owner/ACL, ancestor pinning, regular-file, hard-link,
reparse-point, device/pipe/ADS and atomic-output checks apply. The frontend cannot
relax them. Existing outputs must already satisfy the CLI's private output policy.
Passwords retain the CLI's 1024 UTF-8 byte limit. Private PEM text is obscured in the paste field. Pasted keys are capped at 1 MiB
and 16384 UTF-8 bytes per line, matching the CLI, and remain subject to Core format/component/tail validation.

Only bundled local content runs in an InPrivate main WebView. CSP blocks remote scripts,
frames and network requests. Only native open/save dialogs and the listed Rust
commands are exposed. Developer tools are unavailable in the production release.
Passwords/key text are not stored in browser storage, configuration or logs. Both
form references and live secret input values clear before native invocation,
operation switches and unmounting. Switching away from pasted keys discards their
text. Entry animations do not retain outgoing forms. Rust uses Zeroizing<String> and Core borrows
password bytes only for the call. JavaScript/IPC can make temporary string copies;
reliable zeroization of the WebView heap is not guaranteed. This extra UI/IPC
boundary has not received an independent professional audit.

Closing the window during an operation requests cancellation and waits for Core
cleanup; key generation finishes safely before closing. C17 algorithms, KDF
parameters/domains, NKEM/NKPR formats, fingerprints, AAD, GCM limits and 64 KiB
streaming stay unchanged. Progress UI updates are throttled to about 10 per second,
while cancellation is checked on every Core callback.

Windows power-loss equivalence to POSIX directory fsync is unverified. Pair commits
are not cross-file crash atomic. Process death may leave protected plaintext/key
staging files; deletion is not physical erasure. See the
[Windows filesystem design](../windows/SECURITY-DESIGN.md) for the complete backend
boundary. The v3.3.0 Windows application is not Authenticode signed. Verifying the bundled
Microsoft SDK loader does not sign the application. This experimental project has
not undergone an independent professional security audit and must not be used to
protect important or sensitive data.

Download `NekoKEM-Windows-GUI.zip` from the [v3.3.0 Release](https://github.com/Shixiaoshi0417/NekoKEM/releases/tag/v3.3.0)
and verify it against the release's top-level `SHA256SUMS.txt`. See the complete
[bilingual release notes](../release/v3.3.0.md). v3.3.0 提供便携 GUI 压缩包；下载后先校验
SHA-256，再解压并保留 EXE 旁的 Microsoft SDK loader。
