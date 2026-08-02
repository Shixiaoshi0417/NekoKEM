# NekoKEM v3.1.1 Release Notes

NekoKEM v3.1.1 是 Linux distribution / installer 补丁版本，同时将 Android
App 版本同步为 3.1.1。NekoKEM Core 版本保持 3.1。

- Package/application ID：`com.shixiaoshi0417.nekokem`
- ABI：`arm64-v8a`
- 最低 Android 版本：Android 8.0（API 26）
- 密码后端：App 自带的 Android arm64 OpenSSL 3.5.6 与 NekoKEM Core
- 默认文件格式：NKEM v3（协议版本独立于 App 版本）
- 向后兼容：Core 保留 NKEM v1/v2 解密路径，NKPR 私钥容器格式不变

本次发布增加 Linux x86_64/aarch64 静态 CLI、`install.sh`、Linux README、
GitHub Actions 自动构建和 CLI `--version`。不修改密码算法、协议参数、
NKEM/NKPR 格式或 Core API；协议仍为 NKEM v3，而不是“NKEM v3.1.1”。
