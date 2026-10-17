# NekoKEM v4.3.0

## 简体中文

### 本版更新

本版把 Android 界面升级为 Material 3 Expressive，并随之升级 Android 构建工具链。Android 与各平台 CLI/GUI 统一为 **4.3.0**，Core 仍为 **4.0**。Windows、macOS 和 Linux 的 CLI/GUI 功能与 v4.2.0 相同，仅更新版本号。NKEM v3/v4、NKPR v1、密码参数和公钥指纹算法不变。

- **Material 3 Expressive 界面**：表现力主题与弹簧动效；支持动态取色的设备沿用系统配色，其他设备使用表现力浅色配色。按钮按下时圆角形变；加密与解密、公私钥导入与导出、删除公钥与私钥组成按钮组，标签在长译文或大字体下放不下一行时自动改为整宽纵向排列。删除操作使用错误色，密钥卡片带有各自的操作，主卡片与标题使用更大的圆角和强调字体。
- **进度与反馈**：加解密进度对话框显示加载指示器与波浪进度条；选中的通讯录接收方卡片圆角加大并加描边；切换页面时新页面淡入，离开的页面立即移除，预测性返回保持原有行为；关于页用表现力形状展示 App 图标。
- **操作与数据不变**：文案、操作流程、密钥管理、SAF 文件处理、口令处理与 JNI/Core 调用均不变；无障碍文本与可点击元素保持一致。
- **Android 工具链**：`material3` 1.5.0-beta01、Compose 1.12.1、Android Gradle Plugin 9.4.1、Gradle 9.6.1、Kotlin 2.4.21（AGP 内置 Kotlin）、`compileSdk 37` 与 Build Tools 36.0.0。`targetSdk` 仍为 35、`minSdk` 仍为 26，运行时行为不随编译 SDK 改变；NDK r28c、CMake 与 OpenSSL 4.0.3 不变。
- **回归验证**：API 26/29/35 设备测试在深色模式与 1.5 倍字体下覆盖 JNI、SAF、语言切换、预测性返回和公钥通讯录界面；发布构建仍以固定的 Build Tools 35.0.0 校验 APK 签名与 16 KB 页对齐。

### 兼容性与升级

- Android **4.3.0 / versionCode 12**，最低 API 26、目标 API 35，仅 arm64-v8a。沿用 v3.2.0/v3.3.0 签名，可直接覆盖升级；建议先备份公钥、加密 NKPR 私钥并验证密码。仅旧签名 v3.1.x 安装需要验证备份后卸载重装，卸载会删除应用内部密钥。
- NKEM v3/v4 与 NKPR v1 保持兼容；NKEM v4 需要 v4.0.0 或更新版本。NKEM v1/v2 不再支持。OpenSSL 固定为 4.0.3，Argon2id 保持 64 MiB、3 次迭代、并行度 4。
- 默认不覆盖已有文件；密钥替换仍需明确确认。替换或删除旧私钥前请保留可用备份。POSIX 的 `.nekokem-pair.lock` 可保留，操作运行期间不要删除。
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

This release moves the Android interface to Material 3 Expressive and upgrades the Android build toolchain with it. Android and every CLI/GUI application are **4.3.0**; Core remains **4.0**. The Windows, macOS and Linux CLIs and GUIs behave as in v4.2.0; only their version changes. NKEM v3/v4, NKPR v1, cryptographic parameters and public-key fingerprint algorithms are unchanged.

- **Material 3 Expressive interface**: an expressive theme with spring motion, using the system's dynamic colors where supported and the expressive light scheme elsewhere. Buttons morph their corners when pressed. Encrypt and decrypt, public/private key import and export, and public/private key deletion form button groups that stack at full width when a label would not fit on one line, as with long translations or large font scales. Deletions use the error colors, key cards carry their own actions, and leading cards and titles use larger shapes and emphasized type.
- **Progress and feedback**: the encryption and decryption progress dialog shows a loading indicator and a wavy progress bar. A chosen contact's card rounds further and gains an outline. A newly opened page fades in while the page being left is removed at once, so predictive back behaves as before. The About page frames the App icon in an expressive shape.
- **Operations and data unchanged**: strings, flows, key management, SAF file handling, password handling and JNI/Core calls are unchanged, and so are the accessible labels and clickable elements.
- **Android toolchain**: `material3` 1.5.0-beta01, Compose 1.12.1, Android Gradle Plugin 9.4.1, Gradle 9.6.1, Kotlin 2.4.21 (AGP's built-in Kotlin), `compileSdk 37` and Build Tools 36.0.0. `targetSdk` stays 35 and `minSdk` 26, so runtime behavior does not change with the compile SDK; NDK r28c, CMake and OpenSSL 4.0.3 are unchanged.
- **Regression coverage**: API 26/29/35 device tests, run in dark mode at a 1.5× font scale, cover JNI, SAF, language switching, predictive back and the public-key contacts interface. Release builds still verify the APK signature and 16 KB page alignment with the pinned Build Tools 35.0.0.

### Compatibility and upgrading

- Android **4.3.0 / versionCode 12**, minimum API 26, target API 35, arm64-v8a only. The v3.2.0/v3.3.0 signer is retained for in-place updates. Back up public and encrypted NKPR private keys and verify the password first. Only old-signer v3.1.x installations need verified backups followed by uninstall/reinstall; uninstalling deletes app-private keys.
- NKEM v3/v4 and NKPR v1 remain compatible; NKEM v4 needs v4.0.0 or newer. NKEM v1/v2 are unsupported. OpenSSL stays pinned to 4.0.3 and Argon2id remains 64 MiB, three iterations, parallelism four.
- Existing files are not overwritten by default; key replacement still needs explicit confirmation. Retain usable backups before replacing or deleting old keys. POSIX `.nekokem-pair.lock` files can remain; do not delete them during operations.
- NKEM does not authenticate senders. A v4 recipient can identify its own records and participation across files. AES-GCM provides no key commitment. Paired file commits do not promise cross-file power-loss atomicity; external concurrent mutation, SAF provider behavior and physical erasure retain their documented limits.

### Platform and validation boundaries

- Windows 10/11 x64: CLI/GUI EXEs are not Authenticode signed; GUI needs WebView2 Runtime. Local fixed NTFS, private ACL and path checks remain.
- macOS Apple Silicon arm64: deployment target 11.0, native CI baseline macOS 15, no Intel/Rosetta builds. Ad-hoc hardened-runtime signatures are used, without Developer ID or Apple notarization.
- Linux GUI: built on Ubuntu 24.04, RPM tested in native Fedora 44 containers; requires glibc 2.39+, GTK3 and WebKitGTK 4.1. RPMs are not developer-GPG-signed. X11 launch tests do not establish every distribution or physical Wayland session. Static CLI packages remain available.

This remains an experimental tool without an independent professional security audit; it is not production-grade software and should not protect important or sensitive data.

### Downloads and verification

The platform table above lists all **14 packages**, accompanied by `SHA256SUMS.txt`. Verify downloaded archives and then their internal files. Every asset carries this Release run's provenance; use `gh attestation verify <file> --repo Shixiaoshi0417/NekoKEM`. Normal releases generate no signing recovery envelope and publish no signing keys. Publish fills the verified source, CI/build links, signer and hashes below.

<!-- RELEASE_EN_METADATA -->
