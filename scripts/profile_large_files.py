#!/usr/bin/env python3
"""Profile a Linux CLI with at most two large files on disk.

Timings include process startup, hybrid cryptography, protected-key Argon2id
decryption, and the normal authenticated atomic commit/fsync path. Generation,
key generation, warmups, and SHA-256 checks are excluded. OS caches are not
controlled. All keys and the fixed password are disposable public fixtures.
Only temporary benchmark files are removed; never use production keys/data.
"""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import statistics
import subprocess
import sys
import tempfile
import time

from benchmark_throughput import (
    MIB, PASSWORD, make_input, positive_integer, require_output, run_cli,
    sample_count, sha256_file, verify_plaintext,
)


POLL_SECONDS = 0.01
DISK_MARGIN = 256 * MIB
# SP 800-38D: the existing Core's per-nonce GCM data limit, rounded down to MiB.
MAX_SIZE_MIB = ((1 << 36) - 32) // MIB


def write_report_atomic(path, report):
    """Publish complete JSON while preserving the previous report on failure."""
    temporary_path = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=path.parent,
            prefix=f".{path.name}.", suffix=".tmp", delete=False,
        ) as output:
            temporary_path = Path(output.name)
            json.dump(report, output, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        # The sibling file is on the same filesystem, so readers observe
        # either the previous report or the complete replacement.
        os.replace(temporary_path, path)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def measure_cli(executable, root, environment, timeout, *arguments, password=b""):
    """Collect child usage and sample the CLI's high-water RSS after exec."""
    command = [str(executable), "--lang", "en", *map(str, arguments)]
    with tempfile.TemporaryFile(dir=root) as log:
        started = time.perf_counter_ns()
        process = subprocess.Popen(
            command, cwd=root, env=environment, stdin=subprocess.PIPE,
            stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
        )
        cli_started = False
        peak_rss = None
        rss_observations = 0
        try:
            try:
                process.stdin.write(password)
                process.stdin.close()
            except BrokenPipeError:
                pass
            deadline = time.monotonic() + timeout
            while True:
                try:
                    if not cli_started:
                        cli_started = os.readlink(f"/proc/{process.pid}/exe") == str(executable)
                    if cli_started:
                        status_text = Path(f"/proc/{process.pid}/status").read_text(encoding="ascii")
                        for line in status_text.splitlines():
                            if line.startswith("VmHWM:"):
                                peak_rss = max(peak_rss or 0, int(line.split()[1]))
                                rss_observations += 1
                                break
                except (OSError, ValueError):
                    # Restricted/unavailable procfs must not become a zero RSS.
                    pass
                child, status, usage = os.wait4(process.pid, os.WNOHANG)
                if child:
                    process.returncode = os.waitstatus_to_exitcode(status)
                    break
                if time.monotonic() >= deadline:
                    raise subprocess.TimeoutExpired(command, timeout)
                time.sleep(POLL_SECONDS)
        except BaseException:
            # Kill and reap on timeout/interruption before temporary cleanup.
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            if process.returncode is None:
                _, status, _ = os.wait4(process.pid, 0)
                process.returncode = os.waitstatus_to_exitcode(status)
            raise
        finally:
            process.stdin.close()
        elapsed = (time.perf_counter_ns() - started) / 1_000_000_000
        if process.returncode != 0:
            log.seek(0, os.SEEK_END)
            log.seek(max(0, log.tell() - 4096))
            diagnostic = log.read().decode("utf-8", errors="replace")
            raise RuntimeError(
                f"{arguments[0]} exited with status {process.returncode}: "
                f"{diagnostic.strip()}"
            )
    return {
        "seconds": elapsed,
        "user_cpu_seconds": usage.ru_utime,
        "system_cpu_seconds": usage.ru_stime,
        "wait4_peak_rss_KiB": usage.ru_maxrss,
        "sampled_post_exec_peak_rss_KiB": peak_rss,
        "post_exec_rss_observations": rss_observations,
        # Linux wait4 reports block I/O in units of 512 bytes, not read/write
        # syscall bytes. Cached reads can report zero; this is not device speed.
        "input_block_bytes": usage.ru_inblock * 512,
        "output_block_bytes": usage.ru_oublock * 512,
        "major_page_faults": usage.ru_majflt,
        "voluntary_context_switches": usage.ru_nvcsw,
        "involuntary_context_switches": usage.ru_nivcsw,
    }


def summarize(samples, size_mib):
    median = statistics.median(sample["seconds"] for sample in samples)
    observed_rss = [sample["sampled_post_exec_peak_rss_KiB"] for sample in samples
                    if sample["sampled_post_exec_peak_rss_KiB"] is not None]
    return {
        "samples": samples,
        "median_seconds": median,
        "median_MiB_per_second": size_mib / median,
        "median_cpu_seconds": statistics.median(
            sample["user_cpu_seconds"] + sample["system_cpu_seconds"]
            for sample in samples
        ),
        "maximum_wait4_peak_rss_KiB": max(sample["wait4_peak_rss_KiB"] for sample in samples),
        "maximum_sampled_post_exec_peak_rss_KiB": max(observed_rss, default=None),
    }


def profile_size(root, executable, environment, args, size_mib):
    expected_size = size_mib * MIB
    required = 2 * expected_size + DISK_MARGIN
    available = shutil.disk_usage(root).free
    if available < required:
        raise RuntimeError(
            f"{size_mib} MiB requires at least {required} free bytes; "
            f"only {available} available on {root.parent}"
        )
    case = root / f"size-{size_mib}-MiB"
    case.mkdir()
    plain, cipher = case / "plain.bin", case / "cipher.nkem"
    expected_sha256 = make_input(plain, size_mib)
    public_key = root / "keys" / "public.key"
    private_key = root / "keys" / "private.key.enc"
    samples = {"encrypt": [], "decrypt": []}
    for phase, count in (("warmup", args.warmup), ("measured", args.samples)):
        for index in range(count):
            encrypted = measure_cli(
                executable, root, environment, args.timeout,
                "encrypt", "hybrid", plain, cipher, public_key,
            )
            require_output(cipher)
            if cipher.stat().st_size <= expected_size:
                raise RuntimeError("Encrypted output is incomplete")
            # Reconstruct the disposable input from authenticated ciphertext,
            # avoiding a third large file. Verify its hash before reuse.
            plain.unlink()
            decrypted = measure_cli(
                executable, root, environment, args.timeout,
                "decrypt", "hybrid", cipher, plain, private_key,
                password=PASSWORD,
            )
            verify_plaintext(plain, expected_size, expected_sha256)
            cipher.unlink()
            if phase == "measured":
                samples["encrypt"].append(encrypted)
                samples["decrypt"].append(decrypted)
            print(
                f"{size_mib} MiB {phase} {index + 1}/{count}: "
                f"encrypt {encrypted['seconds']:.3f}s, "
                f"decrypt {decrypted['seconds']:.3f}s; SHA-256 verified",
                file=sys.stderr, flush=True,
            )
    plain.unlink()
    case.rmdir()
    return {
        "size_MiB": size_mib,
        "size_bytes": expected_size,
        "input_sha256": expected_sha256,
        "verified_round_trips": args.warmup + args.samples,
        "results": {name: summarize(values, size_mib) for name, values in samples.items()},
    }


def container_limits():
    result = {}
    for name in ("cpu.max", "memory.max"):
        path = Path("/sys/fs/cgroup") / name
        try:
            result[name] = path.read_text(encoding="ascii").strip()
        except OSError:
            result[name] = None
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--executable", required=True, type=Path)
    parser.add_argument("--sizes-mib", nargs="+", type=positive_integer, default=[1024, 8192])
    parser.add_argument("--samples", type=sample_count, default=5)
    parser.add_argument("--warmup", type=positive_integer, default=1)
    parser.add_argument("--timeout", type=positive_integer, default=600, help="per-command timeout in seconds")
    parser.add_argument("--work-dir", required=True, type=Path, help="existing directory on the filesystem to profile")
    parser.add_argument("--json", required=True, type=Path, help="report output path")
    args = parser.parse_args()
    if sys.platform != "linux" or not hasattr(os, "wait4"):
        parser.error("this profiler requires Linux wait4 resource accounting")
    executable = args.executable.resolve()
    if not executable.is_file() or not os.access(executable, os.X_OK):
        parser.error("--executable must be an executable file")
    parent = args.work_dir.resolve()
    if not parent.is_dir():
        parser.error("--work-dir must be an existing directory")
    if len(set(args.sizes_mib)) != len(args.sizes_mib):
        parser.error("--sizes-mib must not repeat sizes")
    if max(args.sizes_mib) > MAX_SIZE_MIB:
        parser.error(f"--sizes-mib must not exceed {MAX_SIZE_MIB} (GCM data limit)")
    if shutil.disk_usage(parent).free < 2 * max(args.sizes_mib) * MIB + DISK_MARGIN:
        parser.error("insufficient free space: need twice the largest input plus 256 MiB")
    report = {
        "schema_version": 1,
        "started_utc": datetime.now(timezone.utc).isoformat(),
        "requested_sizes_MiB": args.sizes_mib,
        "complete": False,
        "system": platform.platform(),
        "machine": platform.machine(),
        "logical_cpus": os.cpu_count(),
        "container_limits": container_limits(),
        "python": platform.python_version(),
        "filesystem_parent": str(parent),
        "executable": {"path": str(executable), "sha256": sha256_file(executable)},
        "method": {
            "measured_samples": args.samples,
            "excluded_warmup_rounds": args.warmup,
            "order": "encrypt then decrypt; each round uses newly produced ciphertext",
            "input": "fully written deterministic repeating bytes 0..255; not sparse",
            "cache": "OS caches not dropped or controlled; SHA-256 reads precede later encryption",
            "timing": "full CLI including hybrid crypto, protected-key Argon2id decryption, atomic commit and fsync",
            "excluded": ["input generation", "key generation", "SHA-256 verification", "warmups"],
            "resources": "Linux wait4 for each CLI process; peak RSS in KiB; block I/O in 512-byte units",
            "rss_scope": "wait4 may include inherited pre-exec launcher memory; sampled procfs VmHWM is observed only after the requested executable starts; neither includes kernel page cache or GUI",
            "rss_sampling": "VmHWM retains the process high-water mark; 10 ms polling may miss very short processes or allocations immediately before exit; unavailable observations are null",
            "wait_poll_seconds": POLL_SECONDS,
            "disk": "at most two large files; disposable plaintext removed after encryption and reconstructed",
            "locale": "explicit --lang en; isolated XDG_CONFIG_HOME; no saved preference writes",
            "performance_gate": False,
        },
        "sizes": [],
    }
    with tempfile.TemporaryDirectory(prefix="nekokem-profile-", dir=parent) as temporary:
        root = Path(temporary).resolve()
        environment = os.environ.copy()
        environment.update(XDG_CONFIG_HOME=str(root / "config"), LC_ALL="C", LANG="C")
        run_cli(executable, root, environment, args.timeout,
                "keygen", "hybrid", password=PASSWORD * 2)
        require_output(root / "keys" / "public.key")
        private_key = root / "keys" / "private.key.enc"
        require_output(private_key)
        with private_key.open("rb") as source:
            if source.read(4) != b"NKPR":
                raise RuntimeError("Expected an NKPR protected private key")
        if (root / "keys" / "private.key").exists():
            raise RuntimeError("Unexpected plaintext private key")
        for size_mib in args.sizes_mib:
            print(f"Profiling {size_mib} MiB...", file=sys.stderr, flush=True)
            report["sizes"].append(profile_size(root, executable, environment, args, size_mib))
            # Preserve completed sizes if a subsequent size is interrupted.
            report["complete"] = len(report["sizes"]) == len(args.sizes_mib)
            report["recorded_utc"] = datetime.now(timezone.utc).isoformat()
            write_report_atomic(args.json, report)
    print(f"Profile saved to {args.json}")


if __name__ == "__main__":
    main()
