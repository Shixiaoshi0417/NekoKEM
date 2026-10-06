# NekoKEM Core Library

`libnekokem_core.a` 是 NekoKEM 的可复用 C17 Core。CLI、Android JNI 和
未来的桌面 UI 可以包含 `include/nekokem.h` 并链接该静态库；Core 不依赖
`main.c`，也不读取终端或显示交互菜单。

```sh
make core
```

Core 使用 OpenSSL EVP、NKEM v3（单接收方）、NKEM v4（多接收方）和 NKPR；固定依赖为 OpenSSL 4.0.3。NKEM v3 使用独立的
32-byte HKDF salt；NKPR 作为独立私钥容器继续维护。

## 公共 API

原有函数成功返回 `1`、失败返回 `0`。路径和输出缓冲区由调用者拥有；
口令只在调用期间借用，Core 不会保存或释放调用者的口令缓冲区。

- `nekokem_generate_keypair()`：生成 X448 + ML-KEM-1024 Hybrid 公钥和
  受口令保护的 NKPR 私钥。
- `nekokem_encrypt_file()`：默认生成 NKEM v3 Hybrid 文件。
- `nekokem_decrypt_file()`：接受 NKEM v3 与 v4，其他版本一律拒绝；v4 尝试全部接收方记录
  （匹配后也不提前停止），验证头部 MAC 后才解密数据。Hybrid 路径同时兼容 NKPR
  私钥和旧式明文 Hybrid PEM 私钥。
  加密 PKCS#8 PEM（包括 NKPR 内嵌的加密 PEM）会非交互地拒绝；Core
  不调用 OpenSSL 的默认终端密码输入回调。
- `nekokem_encrypt_file_with_progress()`：与默认 v3 加密协议完全相同，
  额外报告 `processed_bytes` 和 `total_bytes`。
- `nekokem_encrypt_file_multi_with_progress()`：为 2 至 `NEKOKEM_MAX_RECIPIENTS`（64）
  个公钥写出一个 NKEM v4 多接收方文件（见 [`docs/NKEM-v4.md`](../docs/NKEM-v4.md)），只有 1 个公钥时写出 v3。
  在创建输出前加载并校验全部公钥；空列表、超过上限、重复公钥或共用任一组件的公钥都会失败，
  不会跳过或替换任何接收方。
  进度、取消和原子提交与 v3 相同。
- `nekokem_decrypt_file_with_progress()`：解密 v3 时按数据块报告进度。回调在调用线程同步执行，返回 `0` 请求取消。
- `nekokem_public_key_fingerprint()`：返回大写、冒号分隔的 SHA-256
  公钥指纹。调用者至少提供 `NEKOKEM_FINGERPRINT_STRING_SIZE` 字节。
- `nekokem_private_key_exists()`：只接受权限为 `0600`、结构有效的 NKPR
  常规文件；仅解析容器结构，不运行 Argon2id 或 AES-GCM。
- `nekokem_check_private_key_password()`：一次性解开并验证 NKPR 私钥，
  随后立即释放 X448/ML-KEM `EVP_PKEY`；不建立解锁会话、不保存口令。
- `nekokem_export_public_key()`：由 Core 验证 X448 与 ML-KEM-1024 公钥，
  再原子地重新序列化到目标文件。
- `nekokem_delete_private_key()`：只删除指定的常规文件；目标不存在时也
  视为成功，便于 UI 实现幂等删除。
- `nekokem_private_key_requires_password()`：供 UI 在调用解密 API 前决定
  是否显示口令输入框。

进度 API 使用 `NEKOKEM_OPERATION_SUCCESS`、`NEKOKEM_OPERATION_ERROR` 和
`NEKOKEM_OPERATION_CANCELLED`。v3 文件数据以 64 KiB 流式处理；错误或取消
会中止并删除 Core 原子输出，不会提交部分密文或未认证明文。原有无回调 API
继续保持 `1/0` 返回约定。

Core 内部保留原子输出、GCM 认证后提交、共享秘密和 AES 密钥清零、
NKPR 明文 PEM 仅驻留内存、以及统一资源清理路径。v3 的 32-byte salt、
12-byte nonce、头部、X448 临时公钥和 ML-KEM 密文全部进入 GCM AAD。

## Android JNI 接入

Android CMake 直接编译 Core facade 和内部密码/文件模块。JNI 提供原有同步
入口和 `nativeEncryptFileWithProgress()`、`nativeDecryptFileWithProgress()`；
后两者只转发 Core 回调和取消状态，不实现密码或解析逻辑。

JNI 层只负责 Java/Kotlin 字符串、路径、口令临时缓冲区和返回值转换。
口令在同步 Core 调用结束后立即以 `OPENSSL_clear_free()` 清零释放。
