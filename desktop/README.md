# NekoKEM Windows GUI

Rust + Tauri 2 + Vue 3 + TypeScript desktop frontend. This is a Windows x64 test
build and requires the Microsoft Edge WebView2 Runtime. Open `nekokem-gui.exe` to
use the app; it opens a native window without a terminal. The portable package
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
- Use the original Android launcher icon for both CLI and GUI. The source PNG is
  copied byte-for-byte; Windows sizes are converted without changing the artwork.

Production cryptography stays in the shared C17 Core. Rust validates requests,
serializes Core calls, owns zeroizing secrets, bridges progress/cancellation and
creates protected temporary pasted-key files through the Windows backend. The Vue
frontend has no filesystem, shell, arbitrary process or remote-content access.

## Build

Use Node 24, Rust with `x86_64-pc-windows-gnu` target, MSYS2 UCRT64 GCC and the
pinned static OpenSSL 3.5.6 prefix created by `windows/scripts/build-windows-cli.sh`.
The GNU target intentionally matches the existing tested C17 Windows ABI/toolchain.

```powershell
rustup target add x86_64-pc-windows-gnu
$env:Path = 'C:\msys64\ucrt64\bin;' + $env:Path
$env:CC_x86_64_pc_windows_gnu = 'C:\msys64\ucrt64\bin\gcc.exe'
$env:NEKOKEM_OPENSSL_PREFIX = 'C:\path\to\pinned-windows-openssl'
cd desktop
npm ci --ignore-scripts
npm test
npm run build
npm run tauri -- build --target x86_64-pc-windows-gnu --no-bundle
```

The CI bootstrap resolves lockfiles once and archives them with the test build.
The checked-in final lockfiles are used with npm ci and Cargo --locked.

## Security boundary / 安全边界

The same local fixed-NTFS, owner/ACL, ancestor pinning, regular-file, hard-link,
reparse-point, device/pipe/ADS and atomic-output checks apply. The frontend cannot
relax them. Existing outputs must already satisfy the CLI's private output policy.
Passwords retain the CLI's 1024 UTF-8 byte limit. Pasted keys are capped at 1 MiB
and remain subject to Core format/component/tail validation.

Only bundled local content runs in an InPrivate main WebView. CSP blocks remote scripts,
frames and network requests. Only native open/save dialogs and the listed Rust
commands are exposed. Developer tools are unavailable in the production release.
Passwords/key text are not stored in browser storage, configuration or logs. Form
references clear at operation start; Rust uses Zeroizing<String> and Core borrows
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
staging files; deletion is not physical erasure. See Windows SECURITY-DESIGN.md for
the complete backend boundary. This is an unsigned test build, not an audited
security-equivalent release.
