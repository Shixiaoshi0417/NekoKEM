# 大文件性能基线 / Large-file performance baseline

## 简体中文

### 测量对象与环境

2026-10-04 测量 **v3.3.1 正式发布的 Linux x86_64 CLI**，源代码为
[`57c13828b163808fd45f30e99cbd3efa99088c6b`](https://github.com/Shixiaoshi0417/NekoKEM/commit/57c13828b163808fd45f30e99cbd3efa99088c6b)。
使用 [v3.3.1 发布包](https://github.com/Shixiaoshi0417/NekoKEM/releases/tag/v3.3.1)，
未重新编译测量对象。

- `NekoKEM-linux-x86_64.tar.gz` SHA-256：`38604b564f9144547d8c8335a16a21d9ce1553e43fb92144f008febeafce5862`。
- 包内 `nekokem` SHA-256：`8b3c285f45beb4db15b218c2d68015356c9365e7f725041357eaadf768c3677d`。
- AMD EPYC 9V74；容器可见 3 个逻辑 CPU，CPU 配额为 2 核（`cpu.max=200000 100000`）。
- Debian 13、Linux 6.18.44、Python 3.12.14；内存配额 8 GiB，工作目录位于 overlay 文件系统。
- 固定 OpenSSL 4.0.3、X448 + ML-KEM-1024、AES-256-GCM、NKEM v3、NKPR v1。

### 结果

每个大小先运行 1 轮预热，再记录 5 轮加密/解密。每轮 SHA-256 校验通过，
完整原始样本见 [JSON 数据](v3.3.1-linux-x86_64.json)。以下内存为执行 CLI 后
采样的 `VmHWM` 最大值，包含私钥解锁所需内存。

| 文件大小 | 操作 | 耗时中位数 | 吞吐中位数 | CPU 时间中位数 | 观察到的峰值 RSS |
|---|---|---:|---:|---:|---:|
| 1 GiB | 加密 | 0.636 s | 1,610 MiB/s | 0.362 s | 5.76 MiB |
| 1 GiB | 解密 | 0.706 s | 1,450 MiB/s | 0.425 s | 68.66 MiB |
| 8 GiB | 加密 | 6.184 s | 1,325 MiB/s | 3.183 s | 5.76 MiB |
| 8 GiB | 解密 | 5.544 s | 1,478 MiB/s | 2.957 s | 68.70 MiB |

8 GiB 加密的五个耗时样本为 `6.184, 12.241, 5.299, 5.560, 8.930` 秒，
全部纳入中位数。文件大小增加后，CLI 内存占用保持近似稳定。

### 瓶颈与后续优化方向

8 GiB 加密的用户态 CPU 中位数约 0.88 秒，系统态约 2.36 秒；总耗时
中位数约 6.18 秒。解密也存在明显的系统态 CPU 和等待时间。
I/O、缓存、文件同步及调度应作为后续优化的优先调查方向。

独立 [strace 诊断](v3.3.1-linux-x86_64-syscalls.json)中，64 MiB 操作约有
1,024 次数据读、写以及少量元数据/私钥操作，符合现有 64 KiB 流式处理。
1 GiB 操作的文件 `fsync` 分别耗时约 0.271 / 0.267 秒，随后还有目录
`fsync`。这些跟踪独立于吞吐样本，跟踪会改变总耗时。

后续应在目标硬件上比较读取、写入及写回策略的效果，并保留认证后提交、
文件与目录同步、权限/路径校验、失败清理和每 64 KiB 的取消检查。
解密保持 Argon2id 64 MiB、3 次迭代、并行度 4；安全参数沿用原值。
本次提交提供基线和测量工具，应用 Core、协议与依赖保持原样。

### 复现

```sh
python3 scripts/profile_large_files.py \
  --executable /absolute/path/to/nekokem \
  --work-dir /absolute/path/on/filesystem \
  --json /absolute/path/to/profile.json
```

默认测 1 GiB / 8 GiB。工作目录必须已存在，最大文件的两倍加 256 MiB
为最低空闲空间要求：默认至少 16.25 GiB。工具完整写入确定性输入，
在临时私有目录生成一次性受口令保护的 NKPR 密钥，每轮加密成功后移除
一次性输入，再用认证解密重建并验证 SHA-256。最多同时保留两份大文件。
每个大小结束后保存 JSON，`complete` 字段标记所有请求的大小是否完成。

工具仅支持 Linux。跨版本和 Windows 吞吐比较继续使用
[`benchmark_throughput.py`](../scripts/benchmark_throughput.py)，该工具交替执行
两版程序并验证双向互操作，峰值磁盘需求约为最大输入的四倍。

### 统计边界

- 耗时包含完整 CLI 启动、Hybrid 运算、解密私钥时的 Argon2id、原子提交和正常 `fsync`；输入生成、密钥生成、预热和 SHA-256 校验不计入测量。
- OS 缓存未清空或控制。1 GiB 样本的块读取计数为零；8 GiB 样本约为 8 GiB。内存配额、缓存压力和调度会影响结果，不能据此量化每一种等待。
- Linux `wait4.ru_maxrss` 可能包含执行 CLI 前继承的 Python 启动内存，因此原始数据同时保存 `wait4` 值和执行 CLI 后的 `/proc` `VmHWM`。后者按 10 ms 采样，短进程或临退出时的分配可能漏采；无法观察时记录 `null`。
- 轮询会增加退出检测延迟，目标间隔为 10 ms，实际间隔受调度影响。`VmHWM`、`wait4` 是内核提供的统计值；RSS 不包含内核页缓存或 GUI/WebView。
- 块 I/O 是 Linux 以 512-byte 单位记录的进程 I/O 计数，和 `read/write` 的字节数、物理设备带宽含义不同。
- 这些结果仅代表该环境下的 Linux CLI。Windows、macOS、Android 和 GUI 总内存/吞吐需要原生测量；结果未用作 CI 性能阈值。

## English

### Executable and environment

The **released v3.3.1 Linux x86_64 CLI** was profiled on 2026-10-04, from source
[`57c13828b163808fd45f30e99cbd3efa99088c6b`](https://github.com/Shixiaoshi0417/NekoKEM/commit/57c13828b163808fd45f30e99cbd3efa99088c6b).
The executable came from the [v3.3.1 release](https://github.com/Shixiaoshi0417/NekoKEM/releases/tag/v3.3.1).

- Archive SHA-256: `38604b564f9144547d8c8335a16a21d9ce1553e43fb92144f008febeafce5862`.
- Executable SHA-256: `8b3c285f45beb4db15b218c2d68015356c9365e7f725041357eaadf768c3677d`.
- AMD EPYC 9V74; 3 visible logical CPUs, a 2-core quota (`cpu.max=200000 100000`).
- Debian 13, Linux 6.18.44, Python 3.12.14; an 8 GiB memory limit and an overlay filesystem.
- Existing OpenSSL 4.0.3, X448 + ML-KEM-1024, AES-256-GCM, NKEM v3 and NKPR v1.

### Results

Each size has one excluded warmup and five measured encryption/decryption
rounds, with a verified plaintext SHA-256 every round. See the
[raw JSON](v3.3.1-linux-x86_64.json). RSS below is the maximum observed
post-exec `VmHWM`, including protected-key unlocking memory.

| File size | Operation | Median elapsed | Median throughput | Median CPU time | Observed peak RSS |
|---|---|---:|---:|---:|---:|
| 1 GiB | Encrypt | 0.636 s | 1,610 MiB/s | 0.362 s | 5.76 MiB |
| 1 GiB | Decrypt | 0.706 s | 1,450 MiB/s | 0.425 s | 68.66 MiB |
| 8 GiB | Encrypt | 6.184 s | 1,325 MiB/s | 3.183 s | 5.76 MiB |
| 8 GiB | Decrypt | 5.544 s | 1,478 MiB/s | 2.957 s | 68.70 MiB |

All five 8 GiB encryption samples (`6.184, 12.241, 5.299, 5.560, 8.930`
seconds) contribute to the median. CLI memory remains approximately constant
as file size increases.

### Findings and optimization priorities

8 GiB encryption has median user CPU time of about 0.88 seconds, system CPU
time of 2.36 seconds, and elapsed time of 6.18 seconds. Decryption also has
substantial system CPU and waiting time. Prioritize investigation of I/O,
caching, synchronization and scheduling on the target hardware.

Separate [strace diagnostics](v3.3.1-linux-x86_64-syscalls.json) show roughly
1,024 data reads/writes for 64 MiB plus metadata/private-key operations,
consistent with 64 KiB streaming. File `fsync` took about 0.271 / 0.267
seconds in the 1 GiB traces, followed by directory `fsync`. Tracing affects
elapsed time; these diagnostics are separate from throughput samples.

Future read/write/writeback changes must retain authenticated publication,
file/directory synchronization, permission/path checks, failure cleanup and
cancellation every 64 KiB. Argon2id retains 64 MiB, 3 iterations and
parallelism 4. This change supplies a baseline and profiling tool; application
Core, protocols, dependencies and security parameters retain their existing values.

### Reproduction

Run the command in the Chinese section using existing absolute paths. Defaults
are 1 GiB / 8 GiB with one warmup and five measured rounds. Free disk space
must cover twice the largest input plus 256 MiB (16.25 GiB for defaults).
The tool fully writes deterministic input, creates disposable NKPR keys in a
private temporary directory, removes the disposable plaintext after encryption,
then reconstructs it with authenticated decryption and verifies SHA-256.
At most two large files coexist. JSON is saved after each size; `complete`
indicates whether every requested size finished.

This resource profiler requires Linux. Use
[`benchmark_throughput.py`](../scripts/benchmark_throughput.py) for alternating
version comparisons on Linux/Windows with bidirectional interoperability
checks; its peak disk requirement is approximately four times the largest input.

### Measurement limits

- Timings include CLI startup, hybrid crypto, protected-key Argon2id, atomic commit and normal `fsync`. Generation, warmups and SHA-256 checks are excluded.
- Caches are uncontrolled. Linux block reads are zero for 1 GiB samples and approximately 8 GiB for large samples. Memory limits, cache pressure and scheduling affect results; the counters cannot isolate every kind of wait.
- `wait4.ru_maxrss` can include inherited pre-exec Python launcher memory. JSON also records post-exec `/proc` `VmHWM` at 10 ms intervals. Short processes or allocations just before exit can be missed; unavailable observations are `null`.
- Polling adds exit detection latency with a target interval of 10 ms; scheduling affects actual intervals. RSS values are kernel accounting, excluding kernel page cache and GUI/WebView memory.
- Block I/O counters use Linux's 512-byte units, with different semantics from syscall byte counts and device bandwidth.
- These are Linux CLI results for this environment. Other operating systems and GUI memory/throughput need native measurement. No CI performance threshold is introduced.
