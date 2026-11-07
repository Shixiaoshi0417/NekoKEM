# NekoKEM v4.4.0

## 简体中文

### 本版更新

本版为 Android 带来全新的 MIUIX 界面，并修复 2026-11-07 源码审计中确认的问题。Android 与各平台 CLI/GUI 统一为 **4.4.0**，Core 仍为 **4.0**。NKEM v3/v4、NKPR v1、密码参数和公钥指纹算法不变。

- **Android MIUIX 界面**：新安装默认使用 MIUIX 风格，采用 MIUIX 原生浅色/深色配色（不随壁纸取色），对话框也是 MIUIX 对话框（手机上从底部弹出），每页带可折叠大标题，底部为悬浮的液态玻璃标签栏，带模糊与透镜折射，选中指示器可拖动；Android 8–12 显示不透明的悬浮底栏。「设置 → 界面风格」可随时切回 Material 3 Expressive，立即生效，已选文件、密钥和收件人不会丢失。
- **Material 3 Expressive 底部导航**：去掉侧边抽屉和菜单按钮，改为底部导航栏，选中标签为实心图标加胶囊指示器；预测性返回的页面预览保留。
- **删除私钥如实报告残留**：密钥生成或替换中断时，可能留下新私钥的暂存文件 `.tmp.*`。未满 10 分钟的暂存可能仍属于其他进程中的操作，删除时会保留；以前删除仍报告成功，现在 Core 返回 `NEKOKEM_DELETE_RESIDUE_RETAINED`（POSIX 与 Windows 一致）。Android 的「删除私钥」和「删除密钥对」会报告“私钥已删除，但保留了暂存副本”，不再显示为完整删除；这类暂存会计入“已保留恢复备份”提示，不会被自动删除。
- **发布门禁**：Publish 只接受 `main` 上由 push 或手动触发的 CI 运行，以及手动触发的 Release 运行；PR 触发的运行测试的是临时合并树，不再被接受。
- **依赖**：桌面 GUI 的 tokio 升级到 1.53.3（RUSTSEC-2026-0386）。glib 0.18.5（RUSTSEC-2024-0429）来自 Tauri 2 的 gtk-rs 0.18 依赖链，暂时无法单独升级；NekoKEM 不直接使用受影响的接口。
- **文档**：新增跨平台 NKEM 加解密往返说明（[中文](../docs/cross-platform-roundtrip.md) / [English](../docs/cross-platform-roundtrip.en.md)）。

### 兼容性与升级


- Android **4.4.0 / versionCode 14**，最低 API 26、目标 API 36，仅 arm64-v8a。沿用 v3.2.0/v3.3.0 签名，可直接覆盖升级；建议先备份公钥、加密 NKPR 私钥并验证密码。仅旧签名 v3.1.x 安装需要验证备份后卸载重装，卸载会删除应用内部密钥。
- NKEM v3/v4 与 NKPR v1 保持兼容；NKEM v4 需要 v4.0.0 或更新版本。NKEM v1/v2 不再支持。OpenSSL 固定为 4.0.3，Argon2id 保持 64 MiB、3 次迭代、并行度 4。
- 默认不覆盖已有文件；密钥替换仍需明确确认。替换或删除旧私钥前请保留可用备份。密钥目录中的 `.nekokem-pair.lock`（POSIX 与 Windows）可保留，操作运行期间不要删除。
- NKEM 不认证发送者；v4 的接收方可识别自己的记录并跨文件确认参与。AES-GCM 不提供密钥承诺性。成对文件提交不宣称跨文件断电原子性；外部并发修改、SAF 提供方行为及物理擦除仍有文档所述限制。

### 平台与验证边界

- Windows 10/11 x64：CLI/GUI EXE 无 Authenticode 签名；GUI 需要 WebView2 Runtime。保留本地固定 NTFS、私有 ACL 与路径检查。
- macOS Apple Silicon arm64：部署目标 11.0，实际原生 CI 基线 macOS 15；不提供 Intel/Rosetta 构建。使用 ad-hoc hardened-runtime 签名，没有 Developer ID 或 Apple 公证。
- Linux GUI：Ubuntu 24.04 构建，Fedora 44 原生容器验证 RPM；需要 glibc 2.39+、GTK3、WebKitGTK 4.1。RPM 无开发者 GPG 签名；X11 启动检查不代表所有发行版或 Wayland 实机。静态 CLI 继续提供。

本项目仍是实验性工具，未经过独立专业安全审计，不应视为生产级软件或用于保护重要、敏感数据。

### 下载与校验

| 平台 | 正式附件 |
|---|---|
| Android ARM64 | `app-release.apk` |
| Windows x64 | `NekoKEM-windows-x86_64.zip`、`NekoKEM-Windows-GUI.zip` |
| macOS Apple Silicon | `NekoKEM-macos-arm64.tar.gz`、`NekoKEM-macos-arm64-GUI.zip`、`NekoKEM-macos-arm64-GUI.dmg` |
| Linux x86_64 | `NekoKEM-linux-x86_64.tar.gz`、`NekoKEM-linux-x86_64-GUI.deb`、`NekoKEM-linux-x86_64-GUI.rpm`、`NekoKEM-linux-x86_64-GUI.tar.gz` |
| Linux ARM64 | `NekoKEM-linux-aarch64.tar.gz`、`NekoKEM-linux-aarch64-GUI.deb`、`NekoKEM-linux-aarch64-GUI.rpm`、`NekoKEM-linux-aarch64-GUI.tar.gz` |

共 14 个包，另附 `SHA256SUMS.txt`。先校验归档，再校验包内文件；每个附件带有本次 Release 构建的来源证明，可用 `gh attestation verify <文件> --repo Shixiaoshi0417/NekoKEM` 验证。正常发布不生成签名恢复信封，也不发布签名密钥。以下记录由 Publish 在核验成功后填写。

<!-- RELEASE_ZH_METADATA -->

---

## English

### What's new

This release brings a new MIUIX interface to Android and fixes the issues confirmed by the 2026-11-07 source audit. Android and every CLI/GUI application are **4.4.0**; Core remains **4.0**. NKEM v3/v4, NKPR v1, cryptographic parameters and public-key fingerprint algorithms are unchanged.

- **Android MIUIX interface**: new installations open in the MIUIX style, drawn in its own light or dark palette rather than wallpaper colors and with MIUIX dialogs (rising from the bottom on phones), with a collapsible large title on every page and a floating liquid glass tab bar that blurs and refracts the page behind it; its selection indicator can be dragged. Android 8–12 shows an opaque floating bar. Settings → Interface style switches back to Material 3 Expressive at once, keeping the selected file, keys and recipients.
- **Material 3 Expressive bottom navigation**: the navigation drawer and menu button give way to a bottom navigation bar whose selected tab shows a filled icon in a pill indicator. Predictive back page previews remain.
- **Private-key deletion reports what it keeps**: an interrupted key generation or replacement can leave a staged new private key as `.tmp.*`. A stage younger than ten minutes may still belong to another process's operation and is kept, yet deletion used to report success. Core now returns `NEKOKEM_DELETE_RESIDUE_RETAINED` (on POSIX and Windows alike). Android's Delete private key and Delete key pair report that the key was deleted but a staged copy was kept, instead of a complete deletion; such stages count towards the recovery backups notice and are never removed automatically.
- **Publication gate**: Publish accepts only CI runs of `main` triggered by a push or by hand, and Release runs triggered by hand. A pull-request run tests a merge tree and is no longer accepted.
- **Dependencies**: the desktop GUI moves to tokio 1.53.3 (RUSTSEC-2026-0386). glib 0.18.5 (RUSTSEC-2024-0429) comes from Tauri 2's gtk-rs 0.18 chain and cannot be upgraded on its own yet; NekoKEM does not use the affected interface directly.
- **Documentation**: a cross-platform NKEM encrypt/decrypt round-trip guide ([中文](../docs/cross-platform-roundtrip.md) / [English](../docs/cross-platform-roundtrip.en.md)).

### Compatibility and upgrading


- Android **4.4.0 / versionCode 14**, minimum API 26, target API 36, arm64-v8a only. The v3.2.0/v3.3.0 signer is retained for in-place updates. Back up public and encrypted NKPR private keys and verify the password first. Only old-signer v3.1.x installations need verified backups followed by uninstall/reinstall; uninstalling deletes app-private keys.
- NKEM v3/v4 and NKPR v1 remain compatible; NKEM v4 needs v4.0.0 or newer. NKEM v1/v2 are unsupported. OpenSSL stays pinned to 4.0.3 and Argon2id remains 64 MiB, three iterations, parallelism four.
- Existing files are not overwritten by default; key replacement still needs explicit confirmation. Retain usable backups before replacing or deleting old keys. `.nekokem-pair.lock` files in key directories (POSIX and Windows) can remain; do not delete them during operations.
- NKEM does not authenticate senders. A v4 recipient can identify its own records and participation across files. AES-GCM provides no key commitment. Paired file commits do not promise cross-file power-loss atomicity; external concurrent mutation, SAF provider behavior and physical erasure retain their documented limits.

### Platform and validation boundaries

- Windows 10/11 x64: CLI/GUI EXEs are not Authenticode signed; GUI needs WebView2 Runtime. Local fixed NTFS, private ACL and path checks remain.
- macOS Apple Silicon arm64: deployment target 11.0, native CI baseline macOS 15, no Intel/Rosetta builds. Ad-hoc hardened-runtime signatures are used, without Developer ID or Apple notarization.
- Linux GUI: built on Ubuntu 24.04, RPM tested in native Fedora 44 containers; requires glibc 2.39+, GTK3 and WebKitGTK 4.1. RPMs are not developer-GPG-signed. X11 launch tests do not establish every distribution or physical Wayland session. Static CLI packages remain available.

This remains an experimental tool without an independent professional security audit; it is not production-grade software and should not protect important or sensitive data.

### Downloads and verification

The platform table above lists all **14 packages**, accompanied by `SHA256SUMS.txt`. Verify downloaded archives and then their internal files. Every asset carries this Release run's provenance; use `gh attestation verify <file> --repo Shixiaoshi0417/NekoKEM`. Normal releases generate no signing recovery envelope and publish no signing keys. Publish fills the verified source, CI/build links, signer and hashes below.

<!-- RELEASE_EN_METADATA -->
