#!/usr/bin/env python3
"""Compare real CLI throughput using disposable inputs and protected keys.

Example:
    python3 scripts/benchmark_throughput.py --baseline /tmp/old/nekokem \
        --candidate /tmp/new/nekokem --work-dir /path/on/target/filesystem

This is a warm-cache-oriented, end-to-end benchmark, not a performance gate.
The page cache is not dropped or controlled. Timings include process startup,
hybrid cryptography, protected-key Argon2 work for decryption, and the normal
atomic output/fsync path. File generation, key generation, warmups, and streaming
SHA-256 verification are excluded. Run on an otherwise idle system and compare
the raw samples as well as the medians; filesystem and CPU load affect results.
All generated keys and the fixed password are disposable public test fixtures.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys
import tempfile
import time


MIB = 1024 * 1024
PASSWORD = b"nekokem-public-benchmark-password\n"


def positive_integer(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("must be at least 1")
    return number


def sample_count(value):
    number = positive_integer(value)
    if number < 5:
        raise argparse.ArgumentTypeError("use at least 5 measured samples")
    return number


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(MIB), b""):
            digest.update(chunk)
    return digest.hexdigest()


def make_input(path, size_mib):
    # Bounded memory, deterministic content, and a fully written input file.
    chunk = bytes(range(256)) * (MIB // 256)
    digest = hashlib.sha256()
    with path.open("xb") as output:
        for _ in range(size_mib):
            output.write(chunk)
            digest.update(chunk)
        output.flush()
        os.fsync(output.fileno())
    return digest.hexdigest()


def require_output(path, expected_size=None):
    if path.is_symlink() or not path.is_file():
        raise RuntimeError(f"CLI did not create a regular output file: {path.name}")
    size = path.stat().st_size
    if size == 0 or (expected_size is not None and size != expected_size):
        raise RuntimeError(f"Unexpected output size for {path.name}: {size}")


def verify_plaintext(path, expected_size, expected_sha256):
    require_output(path, expected_size)
    actual_sha256 = sha256_file(path)
    if actual_sha256 != expected_sha256:
        raise RuntimeError(f"Plaintext SHA-256 mismatch: {path.name}")
    return actual_sha256


def run_cli(executable, root, environment, timeout, *arguments, password=b""):
    command = [str(executable), "--lang", "en", *map(str, arguments)]
    started = time.perf_counter_ns()
    completed = subprocess.run(
        command, cwd=root, env=environment, input=password,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout,
        check=False,
    )
    elapsed = (time.perf_counter_ns() - started) / 1_000_000_000
    if completed.returncode != 0:
        diagnostic = (completed.stdout + completed.stderr)[-4096:].decode(
            "utf-8", errors="replace"
        )
        raise RuntimeError(
            f"{executable.name} {arguments[0]} exited with "
            f"status {completed.returncode}: {diagnostic.strip()}"
        )
    return elapsed


def summarize(samples, size_mib):
    median = statistics.median(samples)
    return {
        "seconds": samples,
        "median_seconds": median,
        "median_MiB_per_second": size_mib / median,
    }


def benchmark_size(root, executables, environment, args, size_mib, size_index):
    case = root / f"size-{size_mib}-MiB"
    case.mkdir()
    plain = case / "plain.bin"
    expected_sha256 = make_input(plain, size_mib)
    expected_size = size_mib * MIB
    public_key = root / "keys" / "public.key"
    private_key = root / "keys" / "private.key.enc"
    reference_cipher = case / "baseline-reference.nkem"
    compatibility = []

    # Each version encrypts once and the other decrypts it. Both timed decrypt
    # paths then consume exactly the same baseline-produced ciphertext.
    for producer, consumer in (("baseline", "candidate"), ("candidate", "baseline")):
        cipher = reference_cipher if producer == "baseline" else case / "candidate-reference.nkem"
        recovered = case / f"compatibility-{consumer}.bin"
        run_cli(executables[producer], root, environment, args.timeout,
                "encrypt", "hybrid", plain, cipher, public_key)
        require_output(cipher)
        run_cli(executables[consumer], root, environment, args.timeout,
                "decrypt", "hybrid", cipher, recovered, private_key,
                password=PASSWORD)
        compatibility.append({
            "producer": producer,
            "consumer": consumer,
            "plaintext_sha256": verify_plaintext(recovered, expected_size, expected_sha256),
        })
        recovered.unlink()
        if cipher != reference_cipher:
            cipher.unlink()

    timings = {label: {"encrypt": [], "decrypt": []} for label in executables}
    verified_hashes = {label: [] for label in executables}
    sample_orders = []
    for phase, count in (("warmup", args.warmup), ("measured", args.samples)):
        for sample in range(count):
            order = ["baseline", "candidate"]
            if (sample + size_index) % 2:
                order.reverse()
            if phase == "measured":
                sample_orders.append(order)
            for operation in ("encrypt", "decrypt"):
                for label in order:
                    output = case / f"{label}-{operation}.{'nkem' if operation == 'encrypt' else 'bin'}"
                    # Removing the prior result also prevents an exit-zero CLI
                    # that fails to write output from passing this benchmark.
                    output.unlink(missing_ok=True)
                    if operation == "encrypt":
                        elapsed = run_cli(
                            executables[label], root, environment, args.timeout,
                            "encrypt", "hybrid", plain, output, public_key,
                        )
                        require_output(output)
                        if output.stat().st_size <= expected_size:
                            raise RuntimeError(f"Encrypted output is incomplete: {output.name}")
                    else:
                        elapsed = run_cli(
                            executables[label], root, environment, args.timeout,
                            "decrypt", "hybrid", reference_cipher, output,
                            private_key, password=PASSWORD,
                        )
                        digest = verify_plaintext(output, expected_size, expected_sha256)
                        if phase == "measured":
                            verified_hashes[label].append(digest)
                    if phase == "measured":
                        timings[label][operation].append(elapsed)
                    output.unlink()

    result = {
        "size_MiB": size_mib,
        "size_bytes": expected_size,
        "input_sha256": expected_sha256,
        "compatibility": compatibility,
        "sample_order": sample_orders,
        "results": {},
        "candidate_speedup": {},
    }
    for label in executables:
        result["results"][label] = {
            operation: summarize(samples, size_mib)
            for operation, samples in timings[label].items()
        }
        result["results"][label]["verified_plaintext_sha256"] = verified_hashes[label]
    for operation in ("encrypt", "decrypt"):
        result["candidate_speedup"][operation] = (
            result["results"]["baseline"][operation]["median_seconds"]
            / result["results"]["candidate"][operation]["median_seconds"]
        )
    # Do not accumulate multiple large inputs/ciphertexts while testing sizes.
    reference_cipher.unlink()
    plain.unlink()
    case.rmdir()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--baseline", required=True, type=Path, help="baseline CLI executable")
    parser.add_argument("--candidate", required=True, type=Path, help="candidate CLI executable")
    parser.add_argument("--sizes-mib", nargs="+", type=positive_integer, default=[1, 64, 256])
    parser.add_argument("--samples", type=sample_count, default=5, help="measured samples per executable and operation (default: 5)")
    parser.add_argument("--warmup", type=positive_integer, default=1, help="excluded warmup rounds per size (default: 1)")
    parser.add_argument("--timeout", type=positive_integer, default=120, help="per-command timeout in seconds (default: 120)")
    parser.add_argument("--work-dir", type=Path, help="existing parent directory on the filesystem to measure")
    parser.add_argument("--json", type=Path, help="also write the JSON report to this file")
    args = parser.parse_args()
    executables = {label: getattr(args, label).resolve() for label in ("baseline", "candidate")}
    for label, executable in executables.items():
        if not executable.is_file() or not os.access(executable, os.X_OK):
            parser.error(f"{label} is not an executable file: {executable}")
    if len(set(args.sizes_mib)) != len(args.sizes_mib):
        parser.error("--sizes-mib must not repeat sizes")
    if args.work_dir is not None:
        args.work_dir = args.work_dir.resolve()
        if not args.work_dir.is_dir():
            parser.error("--work-dir must be an existing directory")

    report = {
        "schema_version": 1,
        "system": platform.platform(),
        "machine": platform.machine(),
        "processor": platform.processor(),
        "logical_cpus": os.cpu_count(),
        "python": platform.python_version(),
        "executables": {
            label: {"path": str(path), "sha256": sha256_file(path)}
            for label, path in executables.items()
        },
        "method": {
            "measured_samples": args.samples,
            "excluded_warmup_rounds": args.warmup,
            "cache": "warm-cache-oriented; OS page cache is not dropped or controlled",
            "timing": "full CLI process including hybrid crypto, Argon2 for NKPR decryption, atomic output and fsync",
            "excluded": ["input generation", "shared NKPR key generation", "compatibility checks", "warmups", "SHA-256 checks"],
            "order": "paired, alternating baseline/candidate order for each operation",
            "shared_decryption_input": "same baseline ciphertext and same password-protected NKPR key",
            "speedup": "baseline median seconds / candidate median seconds; >1 is faster",
            "locale": "explicit --lang en; isolated XDG_CONFIG_HOME; no saved preference writes",
            "performance_gate": False,
        },
        "sizes": [],
    }
    with tempfile.TemporaryDirectory(prefix="nekokem-throughput-", dir=args.work_dir) as temporary:
        root = Path(temporary).resolve()
        report["filesystem_parent"] = str(root.parent)
        environment = os.environ.copy()
        environment.update(XDG_CONFIG_HOME=str(root / "config"), LC_ALL="C", LANG="C")
        run_cli(executables["baseline"], root, environment, args.timeout,
                "keygen", "hybrid", password=PASSWORD * 2)
        require_output(root / "keys" / "public.key")
        private_key = root / "keys" / "private.key.enc"
        require_output(private_key)
        with private_key.open("rb") as key:
            if key.read(4) != b"NKPR":
                raise RuntimeError("Key generation did not produce an NKPR protected key")
        if (root / "keys" / "private.key").exists():
            raise RuntimeError("Key generation unexpectedly left a plaintext private key")
        for index, size_mib in enumerate(args.sizes_mib):
            print(f"Benchmarking {size_mib} MiB ({args.samples} paired samples)...", file=sys.stderr, flush=True)
            report["sizes"].append(benchmark_size(root, executables, environment, args, size_mib, index))
    serialized = json.dumps(report, indent=2) + "\n"
    if args.json is not None:
        args.json.write_text(serialized, encoding="utf-8")
    print(serialized, end="")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"Benchmark failed: {error}", file=sys.stderr)
        sys.exit(1)
