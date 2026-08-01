# NekoKEM Android v3.1 Release Notes

NekoKEM Android v3.1 是与 NekoKEM Core 3.1 对齐的正式 Android 版本。

- Package/application ID：`com.shixiaoshi0417.nekokem`
- ABI：`arm64-v8a`
- 最低 Android 版本：Android 8.0（API 26）
- 密码后端：App 自带的 Android arm64 OpenSSL 3.5.6 与 NekoKEM Core
- 默认文件格式：NKEM v3（协议版本独立于 App 版本）
- 向后兼容：Core 保留 NKEM v1/v2 解密路径，NKPR 私钥容器格式不变

本次发布只统一 Android 包名、版本标识和相关工程引用，不修改密码算法、
协议参数、NKEM/NKPR 格式或 Core API。
