# NekoKEM v4.3.1

## 简体中文

### 本版更新

本版是安全与稳定性维护版本，修复 2026-10-22 外部审计报告中的问题，并完成 Android 发布构建与目标版本升级。Android 与各平台 CLI/GUI 统一为 **4.3.1**，Core 仍为 **4.0**。NKEM v3/v4、NKPR v1、密码参数和公钥指纹算法不变。

- **Android 删除私钥一并删除中断副本**：永久删除私钥或密钥对时，同时删除导入中断留下的私钥候选与备份副本；无法安全删除的副本会报告为删除失败，不再静默保留。
- **拒绝空口令加密的 PEM 私钥**：Core 现在拒绝用空口令加密的 PKCS#8 私钥组件；带旧式加密头的公钥文件也会被直接拒绝，不再在终端提示输入 PEM 口令并等待。
- **不误删正在生成的密钥暂存文件**：删除私钥时只清理超过 10 分钟的 `.tmp.` 暂存文件，不再删除另一个进程刚写入完整 NKPR 头的暂存文件；Linux、macOS 与 Windows 行为一致。
- **CLI 接受 CRLF 结尾的 1024 字节口令**：Linux/macOS CLI 从重定向输入读取口令时，先去掉行尾 CR 再检查 1024 字节上限，与 Windows 一致。
- **跨进程加锁**：Windows 上成对发布与删除密钥现在与 POSIX 一样持有密钥目录中的 `.nekokem-pair.lock`（`LockFileEx`），多个进程同时生成或替换同一密钥对不再交错；桌面 GUI 通讯录的导入、修改备注与删除持有跨进程锁，多个应用进程同时导入不会超过 500 条，超出上限的记录计为无法读取，列表仍可打开并删除。
- **Android 构建**：发布版启用 R8，CI 对压缩后的构建重复 JNI 与回调设备测试；`targetSdk` 升级为 36（Android 16），API 36 模拟器加入设备测试。
- **构建与测试**：桌面前端 Vite 升级到 7.3.7（GHSA-9jrq-w75r-8gcw 等公告）；桌面 GUI 增量构建在 Core 头文件变化时重新编译；模糊测试种子覆盖 NKEM v4 的接收方数量、数据与截断情形。

### 兼容性与升级


- Android **4.3.1 / versionCode 13**，最低 API 26、目标 API 36，仅 arm64-v8a。沿用 v3.2.0/v3.3.0 签名，可直接覆盖升级；建议先备份公钥、加密 NKPR 私钥并验证密码。仅旧签名 v3.1.x 安装需要验证备份后卸载重装，卸载会删除应用内部密钥。
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

This is a security and stability maintenance release. It fixes the issues in the 2026-10-22 external audit report and completes the Android release-build and target-version upgrades. Android and every CLI/GUI application are **4.3.1**; Core remains **4.0**. NKEM v3/v4, NKPR v1, cryptographic parameters and public-key fingerprint algorithms are unchanged.

- **Android deletes interrupted copies with the private key**: permanently deleting the private key or key pair now also deletes the private-key candidates and backups an interrupted import left behind. A copy that cannot be removed safely is reported as a failed deletion instead of being kept silently.
- **Empty-password PEM private keys are rejected**: Core now rejects PKCS#8 private-key components encrypted with an empty password. A public-key file with a legacy encryption header is rejected directly instead of prompting for a PEM pass phrase on the terminal and waiting.
- **Key staging files being generated are kept**: deleting a private key only removes `.tmp.` staging files older than ten minutes and no longer deletes another process's staging file that already has a complete NKPR header. Linux, macOS and Windows behave the same.
- **The CLI accepts a 1024-byte password ending in CRLF**: when reading a password from redirected input, the Linux/macOS CLI strips a trailing CR before applying the 1024-byte limit, as Windows does.
- **Cross-process locking**: on Windows, pair publication and key deletion now hold `.nekokem-pair.lock` in the key directory (`LockFileEx`), as on POSIX, so processes generating or replacing the same pair no longer interleave. Desktop GUI contact imports, note edits and deletes hold a cross-process lock, so app processes importing at once cannot exceed 500 contacts; records past the limit count as unreadable and the list still opens for deletion.
- **Android build**: release builds enable R8, and CI repeats the JNI and callback device tests on the minified build. `targetSdk` moves to 36 (Android 16), and an API 36 emulator joins the device tests.
- **Build and tests**: the desktop frontend moves to Vite 7.3.7 (GHSA-9jrq-w75r-8gcw and related advisories). Incremental desktop GUI builds recompile when Core headers change. Fuzzing seeds cover NKEM v4 recipient counts, data and truncation.

### Compatibility and upgrading


- Android **4.3.1 / versionCode 13**, minimum API 26, target API 36, arm64-v8a only. The v3.2.0/v3.3.0 signer is retained for in-place updates. Back up public and encrypted NKPR private keys and verify the password first. Only old-signer v3.1.x installations need verified backups followed by uninstall/reinstall; uninstalling deletes app-private keys.
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
