#!/usr/bin/env python3
"""Run one bounded AFL++ session and fail closed on findings/incomplete runs."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys


class FuzzFailure(RuntimeError):
    pass


def check_results(corpus, log, seconds):
    stats_files = list(corpus.rglob("fuzzer_stats"))
    if len(stats_files) != 1:
        raise FuzzFailure("Expected exactly one completed fuzzer_stats file")
    stats = {}
    for line in stats_files[0].read_text().splitlines():
        key, separator, value = line.partition(":")
        if separator:
            if key.strip() in stats:
                raise FuzzFailure("Duplicate AFL statistic")
            stats[key.strip()] = value.strip()
    try:
        values = {key: int(stats[key]) for key in
                  ("execs_done", "run_time", "saved_crashes", "saved_hangs")}
    except (KeyError, ValueError) as error:
        raise FuzzFailure("Missing/invalid AFL statistics") from error
    if (values["execs_done"] <= 0 or values["run_time"] < seconds or
            values["saved_crashes"] != 0 or values["saved_hangs"] != 0):
        raise FuzzFailure(f"Incomplete run or fuzz findings: {values}")
    for name in ("crashes", "hangs"):
        directory = stats_files[0].parent / name
        if not directory.is_dir():
            raise FuzzFailure(f"Missing AFL {name} directory")
        # AFL creates a README.txt alongside crash inputs. Everything else is evidence.
        if any(path.name != "README.txt" for path in directory.iterdir()):
            raise FuzzFailure(f"AFL saved {name}; preserve and inspect the samples")
    if "Time limit was reached" not in log.read_text(errors="replace"):
        raise FuzzFailure("AFL did not confirm completion of its requested -V interval")
    return values


def stop_process_group(process):
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass
    # Also kill descendants if the parent exited before its children.
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait(timeout=5)


def run_fuzz(afl, seeds, target, output, seconds=15, wall_timeout=120):
    if seconds <= 0 or wall_timeout <= seconds:
        raise ValueError("Require 0 < fuzz seconds < wall timeout")
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)  # Never accept stale success evidence.
    corpus = output / "corpus"
    log = output / "afl.log"
    command = [str(afl), "-V", str(seconds), "-t", "1000", "-i", str(seeds),
               "-o", str(corpus), "--", str(target), "@@"]
    environment = os.environ.copy()
    environment.update(AFL_NO_UI="1", AFL_SKIP_CPUFREQ="1",
                       AFL_EXIT_ON_SEED_ISSUES="1",
                       UBSAN_OPTIONS="halt_on_error=1:abort_on_error=1:print_stacktrace=1")
    result = {"command": command, "status": "failed"}
    try:
        with log.open("wb") as stream:
            process = subprocess.Popen(command, env=environment, stdout=stream,
                                       stderr=subprocess.STDOUT, start_new_session=True)
            try:
                result["exit_code"] = process.wait(timeout=wall_timeout)
            except subprocess.TimeoutExpired as error:
                result["wall_timeout"] = True
                raise FuzzFailure("AFL exceeded the wall-clock deadline") from error
            finally:
                stop_process_group(process)
        if result["exit_code"] != 0:
            raise FuzzFailure(f"AFL failed/terminated: exit {result['exit_code']}")
        result["statistics"] = check_results(corpus, log, seconds)
        result["status"] = "passed"
    except BaseException as error:
        result["error"] = str(error)
        raise
    finally:
        (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--afl", default="afl-fuzz")
    parser.add_argument("--seconds", type=int, default=15)
    parser.add_argument("--wall-timeout", type=int, default=120)
    parser.add_argument("--seeds", type=Path, required=True)
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    def interrupted(signum, _frame):
        raise InterruptedError(f"Fuzz gate interrupted by signal {signum}")

    signal.signal(signal.SIGTERM, interrupted)
    try:
        result = run_fuzz(args.afl, args.seeds, args.target, args.output,
                          args.seconds, args.wall_timeout)
    except (FuzzFailure, OSError, ValueError, KeyboardInterrupt) as error:
        print(f"Fuzz smoke FAILED: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
