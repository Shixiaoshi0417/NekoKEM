# Changelog

## Unreleased

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
  `Unreleased` 开发版本已移除这两条兼容路径，NKPR 格式不变。
