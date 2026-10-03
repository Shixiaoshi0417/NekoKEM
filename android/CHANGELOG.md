# Changelog

## 3.3.1

- 完整中文在上、英文在下的发布说明见 [v3.3.1](../release/v3.3.1.md)。
- 新增 M 系列 Mac 原生 CLI/GUI ZIP/DMG；Linux x86_64、aarch64 GUI 提供 DEB、RPM 和便携包。
- 全平台应用版本为 3.3.1，Android versionCode 为 6，沿用 v3.2.0/v3.3.0 签名，可覆盖升级。
- Core 3.1、NKEM v3、NKPR v1、Argon2id 成本、64 KiB 流式处理及固定 OpenSSL 4.0.3 不变；保留文件权限、认证后提交和编译加固。
- CI 增加原生 macOS 与 Linux GUI 回归，以及 Ubuntu DEB、Fedora RPM 的实际安装启动检查；Release 只构建，README 更新全部 14 个正式包。

Full Chinese-then-English notes: [v3.3.1](../release/v3.3.1.md). Adds native M-series
Mac CLI/GUI ZIP/DMG and Linux x86_64/aarch64 GUI DEB, RPM and portable packages.
Every app is 3.3.1; Android versionCode is 6 and retains the v3.2.0/v3.3.0 signer
for in-place updates. Core 3.1, NKEM v3, NKPR v1, Argon2id costs, 64 KiB streaming,
pinned OpenSSL 4.0.3, file protection and authenticated commits remain unchanged.
CI covers native macOS/Linux GUI regressions and actual Ubuntu DEB/Fedora RPM
installation and launch; Release builds only. READMEs document all 14 packages.

## 3.3.0

- 完整中文在上、英文在下的发布说明见 [v3.3.0](../release/v3.3.0.md)。
- Android、Linux CLI、Windows CLI 与 Rust/Tauri 2/Vue 3/TypeScript GUI 统一为 3.3.0；Core 保持 3.1，NKEM v3/NKPR v1 和密码参数不变。
- Windows 原生 CLI 共用 Linux 参数与五项菜单，双击直接进入菜单；GUI 提供五语言自动识别、原生文件选择、进度、取消、键盘导航和过渡动画。
- 改进流式性能，集成固定 OpenSSL 4.0.3，保留编译加固、文件安全和完整 CI 回归；工作流收敛为 CI 构建测试和 Release 仅构建。
- Android 继续使用现有方形白底图案与 v3.2.0 签名，可直接覆盖升级；Windows EXE 方形图案外部白色区域透明，README 使用独立圆角展示图。
- 仅旧签名 v3.1.x 用户需要备份公钥、加密 NKPR 私钥并保存密码后卸载重装；Windows 应用未进行 Authenticode 签名。

Full Chinese-then-English notes: [v3.3.0](../release/v3.3.0.md). Android, Linux CLI,
Windows CLI and Rust/Tauri 2/Vue 3/TypeScript GUI are version 3.3.0; Core remains
3.1 and NKEM v3/NKPR v1 and cryptographic parameters are unchanged. Windows adds
the matching CLI/menu and a GUI with five-language detection, file dialogs,
progress, cancellation, keyboard navigation and transitions. Streaming performance,
pinned OpenSSL 4.0.3 and the two CI/Release entry points retain security checks.
Android keeps its square white artwork and v3.2.0 signer for in-place updates;
Windows EXE icons use transparent outer white areas and README has a separate
rounded display image. Only old-signer v3.1.x installations need backup and
uninstall/reinstall. Windows applications are not Authenticode signed.

## 3.2.0

- 完整中英双语发布日志见 [v3.2.0](../release/v3.2.0.md)。
- 五种语言、跟随系统、Android 系统级应用语言与 CLI 持久化语言设置。
- 修复 Core 输入边界、SAF 取消/回滚/资源关闭、暂存 NKPR 导入和 fuzz CI 失败判定。
- Android 发布签名已更换：备份公钥和加密 NKPR 私钥并保存密码后，再卸载旧版重装。
- Full bilingual release notes: [v3.2.0](../release/v3.2.0.md). Five languages, system-language selection, security regressions and a new Android signing identity; export both keys and retain the private-key password before uninstalling/reinstalling.

- 删除 NKEM v1/v2 加密、解密和兼容解析路径；当前仅支持 NKEM v3。
- NKPR 私钥容器格式保持不变。

## 3.1.1

- 发布 Linux x86_64/aarch64 静态 CLI、自动安装脚本、Linux README 和 GitHub Actions 构建流程。
- CLI 新增 `--version`；Android App 版本同步为 3.1.1，Core 版本保持 3.1。
- 此版本发布时未修改 Core API、密码算法、NKEM 或 NKPR 格式。

## 3.1

- 将 Android application ID、namespace、Kotlin/androidTest 包和 JNI 静态绑定统一迁移为 `com.shixiaoshi0417.nekokem`。
- Android App 与 NekoKEM Core 的正式版本统一为 3.1。
- 保留现有 Material 3 UI、本地密钥管理、SAF 导入导出、文件加解密进度与中英文界面。
- 默认容器协议为 NKEM v3；该版本发布时仍兼容解密 NKEM v1/v2。当前
  `3.2.0` 版本已移除这两条兼容路径，NKPR 格式不变。
