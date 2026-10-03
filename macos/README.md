# macOS Apple Silicon CLI 与 GUI / macOS Apple Silicon CLI and GUI

v3.3.1 面向 M 系列 Mac 的原生 `arm64` 架构，提供 CLI 和 Rust + Tauri 2 + Vue 3 + TypeScript GUI。不提供 Intel/Rosetta 构建。从 [v3.3.1 Release](https://github.com/Shixiaoshi0417/NekoKEM/releases/tag/v3.3.1) 下载 CLI、GUI ZIP 或 DMG，先用顶层 `SHA256SUMS.txt` 校验归档，再校验包内文件。

构建使用 macOS 11.0 deployment target；实际原生 CI 运行于 GitHub `macos-15` arm64 runner。deployment target 不等于 macOS 11.0 实机验证，也不承诺不同代 M 系列芯片具有相同性能。两种应用静态链接固定 OpenSSL 4.0.3，并保留其 arm64 汇编加速；运行不需要 Homebrew OpenSSL dylib，但仍依赖 macOS 系统库与框架。GUI 使用系统 WKWebView，不需要 WebView2 Runtime 或 `WebView2Loader.dll`。

## 构建与产物

在原生 Apple Silicon macOS 上安装 Xcode Command Line Tools，确保 clang、make、Perl、curl、Python 3、tar 和 codesign 可用，再从仓库根目录运行：

```sh
bash macos/scripts/build-macos-cli.sh
```

CLI 输出为 `macos/out/NekoKEM-macos-arm64.tar.gz`。可传入输出目录；`NEKOKEM_OPENSSL_PREFIX` 可指定通过构建清单校验的 OpenSSL 缓存，`NEKOKEM_BUILD_JOBS` 控制其编译并行度。脚本默认执行测试；Release 使用 `NEKOKEM_BUILD_TESTS=0` 只构建，仍强制检查版本、OpenSSL 运行时及 Argon2 线程配置、arm64/PIE、系统动态库、签名和包校验。

归档内的 `NekoKEM-macos-arm64/` 包含 `nekokem`、`NekoKEM.command`、本文、`SECURITY-DESIGN.md`、项目与 OpenSSL 许可、`build-metadata.json` 及 `SHA256SUMS`。解包后校验文件，再在用户自己拥有的目录运行 CLI：

```sh
tar -xzf NekoKEM-macos-arm64.tar.gz
cd NekoKEM-macos-arm64
shasum -a 256 -c SHA256SUMS
./nekokem --version
./nekokem --lang zh-CN keygen
./nekokem encrypt hybrid input.bin output.nkem keys/public.key
./nekokem decrypt hybrid output.nkem restored.bin keys/private.key.enc
./nekokem --set-lang system
```

包内校验文件用于检测文件损坏，不替代可信构建来源或发布者签名。CLI 与 Linux 共用全部参数和五项菜单；无参数运行进入菜单。`NekoKEM.command` 在 Terminal 中启动 CLI，并先将工作目录切换为解包目录，因此交互产生的 `keys/`、`encrypted/` 和 `plaintext/` 位于该目录。密码通过关闭回显的终端或 UTF-8 标准输入读取，不放在命令行参数里。

GUI 构建说明见仓库的 [desktop/README.md](https://github.com/Shixiaoshi0417/NekoKEM/blob/main/desktop/README.md)。GUI 包为 `NekoKEM-macos-arm64-GUI.zip` 和 `NekoKEM-macos-arm64-GUI.dmg`；ZIP 中包含 `NekoKEM.app`、说明、许可、校验和构建元数据。Tauri 原始 DMG 名为 `NekoKEM_3.3.1_aarch64.dmg`，构建检查后导出为上述稳定包名。GUI 提供原生文件选择、进度、取消、五语言以及遵循减少动态效果偏好的界面动画。

## 语言与文件安全

支持简体中文、繁体中文、英语、日语、韩语。CLI 优先级为 `--lang` → 保存的 `--set-lang` 偏好 → `LC_ALL` → `LC_MESSAGES` → `LANG` → CoreFoundation 首选语言；不支持的语言回退英语。`--lang system` 绕过已保存语言，`--set-lang system` 恢复自动检测。POSIX 配置沿用 Linux：绝对 `$XDG_CONFIG_HOME/nekokem/language`，否则为 `$HOME/.config/nekokem/language`，文件权限 `0600`。未设置 Locale 环境变量的 macOS 原生启动按 UTF-8 处理；显式 ASCII 设置仍回退英语。

导入 NKPR 必须是当前用户拥有、权限为 `0600`、只有一个硬链接的普通文件，且没有扩展 ACL 的 allow 条目；不能用宽松权限替代这些检查。私有目录要求当前用户所有、`0700`，扩展 ACL 只允许空或 deny-only。普通文件先 `fsync` 再强制 `F_FULLFSYNC`，父目录使用 `fsync`；失败或不支持时拒绝，不静默降级。文件系统大小写或 Unicode 等价名称导致的密钥输出别名会触发拒绝及进程内回滚。细节、失败边界和原生测试范围见 [SECURITY-DESIGN.md](SECURITY-DESIGN.md)。

Core 3.1、NKEM v3、NKPR v1、密码算法、KDF 参数与 64 KiB 流式分块均保持不变。CLI 和 GUI 使用 ad-hoc hardened-runtime 签名，用于结构完整性检查，未使用 Developer ID，也未进行 Apple 公证；不能证明发布者身份或保证下载后通过 Gatekeeper。本项目仍是未经独立专业安全审计的实验性工具，不应用于保护重要或敏感数据。

---

v3.3.1 targets native `arm64` M-series Macs with a CLI and Rust + Tauri 2 + Vue 3 + TypeScript GUI. Intel/Rosetta builds are not provided. Download the CLI, GUI ZIP or DMG from the [v3.3.1 Release](https://github.com/Shixiaoshi0417/NekoKEM/releases/tag/v3.3.1), verify the archive against the top-level `SHA256SUMS.txt`, then check its internal files.

Builds set a macOS 11.0 deployment target; actual native CI uses GitHub's `macos-15` arm64 runner. The deployment target does not establish physical macOS 11.0 compatibility or identical performance across M-series generations. Both applications statically link pinned OpenSSL 4.0.3 with arm64 assembly acceleration retained. They require no Homebrew OpenSSL dylib but still depend on macOS system libraries/frameworks. The GUI uses system WKWebView, with no WebView2 Runtime or `WebView2Loader.dll` requirement.

## Building and packages

On native Apple Silicon macOS, install Xcode Command Line Tools and make clang, make, Perl, curl, Python 3, tar and codesign available. Run from the repository root:

```sh
bash macos/scripts/build-macos-cli.sh
```

The CLI archive is `macos/out/NekoKEM-macos-arm64.tar.gz`; an optional argument selects the output directory. `NEKOKEM_OPENSSL_PREFIX` selects an OpenSSL cache checked against its build manifest, and `NEKOKEM_BUILD_JOBS` controls dependency build parallelism. The script runs tests by default. Release sets `NEKOKEM_BUILD_TESTS=0` for building only, retaining mandatory version, OpenSSL runtime/Argon2 threading, arm64/PIE, system dylib, signature and package checks.

The archive's `NekoKEM-macos-arm64/` directory contains `nekokem`, `NekoKEM.command`, this README, `SECURITY-DESIGN.md`, project/OpenSSL licenses, `build-metadata.json` and `SHA256SUMS`. Extract and check the files, then run the CLI in a directory owned by your user:

```sh
tar -xzf NekoKEM-macos-arm64.tar.gz
cd NekoKEM-macos-arm64
shasum -a 256 -c SHA256SUMS
./nekokem --version
./nekokem --lang zh-CN keygen
./nekokem encrypt hybrid input.bin output.nkem keys/public.key
./nekokem decrypt hybrid output.nkem restored.bin keys/private.key.enc
./nekokem --set-lang system
```

The internal checksums detect damaged files; they do not replace trusted build provenance or a publisher signature. The CLI shares every Linux argument and the five-option menu; running without arguments enters that menu. `NekoKEM.command` opens the CLI in Terminal after changing the working directory to the extracted package. Interactive `keys/`, `encrypted/` and `plaintext/` directories therefore live there. Passwords are read from a terminal with echo disabled, or UTF-8 stdin, and never belong in command-line arguments.

See the repository's [desktop/README.md](https://github.com/Shixiaoshi0417/NekoKEM/blob/main/desktop/README.md) for GUI builds. GUI packages are `NekoKEM-macos-arm64-GUI.zip` and `NekoKEM-macos-arm64-GUI.dmg`. The ZIP contains `NekoKEM.app`, documentation, licenses, checksums and build metadata. Tauri's original `NekoKEM_3.3.1_aarch64.dmg` is checked before export under the stable package name. The GUI provides native file dialogs, progress, cancellation, five languages and animations that respect reduced-motion preferences.

## Language and file security

Simplified Chinese, Traditional Chinese, English, Japanese and Korean are supported. CLI precedence is `--lang` → saved `--set-lang` preference → `LC_ALL` → `LC_MESSAGES` → `LANG` → CoreFoundation preferred language; unsupported languages fall back to English. `--lang system` bypasses the saved language and `--set-lang system` restores automatic detection. POSIX preferences follow Linux: absolute `$XDG_CONFIG_HOME/nekokem/language`, otherwise `$HOME/.config/nekokem/language`, with mode `0600`. Native macOS launches without locale environment variables use UTF-8; explicit ASCII settings still fall back to English.

An imported NKPR must be a current-user-owned regular file with mode `0600`, one hard link and no allow entries in its extended ACL. Broad permissions cannot replace these checks. Private directories require current-user ownership and mode `0700`; only empty or deny-only extended ACLs are accepted. Regular files require `fsync` followed by `F_FULLFSYNC`; parent directories require `fsync`. Failure or lack of support rejects the operation rather than silently downgrading. Filesystem case/Unicode-equivalent key-output aliases are rejected with process-local rollback. See [SECURITY-DESIGN.md](SECURITY-DESIGN.md) for details, failure boundaries and native test coverage.

Core 3.1, NKEM v3, NKPR v1, algorithms, KDF parameters and 64 KiB streaming chunks are unchanged. CLI and GUI use ad-hoc hardened-runtime signing for structural integrity checks, without Developer ID or Apple notarization. This neither authenticates the publisher nor guarantees Gatekeeper acceptance after downloading. The project remains experimental, without an independent professional security audit, and must not protect important or sensitive data.
