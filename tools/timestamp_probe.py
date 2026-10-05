#!/usr/bin/env python3
"""Build/run an isolated real-GPU counter matrix; --build-only never launches it."""
import argparse
import os
from pathlib import Path
import platform
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-only", action="store_true")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--production-sequence", action="store_true",
                        help="Compare 8/32 slots and interleaved transfers, reading completion-handler snapshots.")
    mode.add_argument("--interleaved-strategies", action="store_true",
                      help="Compare the failing control with explicit blit descriptors, compute markers, and event boundaries.")
    args = parser.parse_args()
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        raise SystemExit("Counter diagnosis requires an Apple Silicon Mac.")
    executable = ROOT / "build/native/timestamp-probe"
    executable.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["xcrun", "clang++", "-std=c++17", "-fobjc-arc", "-arch", "arm64",
                    "-mmacosx-version-min=14.0", "-Wall", "-Wextra", "-Werror",
                    str(ROOT / "tools/timestamp_probe.mm"), "-framework", "Foundation",
                    "-framework", "Metal", "-o", str(executable)], cwd=ROOT, check=True)
    subprocess.run(["codesign", "--force", "--sign", "-", str(executable)], check=True)
    print(f"Built {executable}", flush=True)
    if args.build_only:
        return
    env = os.environ.copy()
    env["MTL_DEBUG_LAYER"] = "1"
    filename = ("timestamp-interleaved-strategies.log" if args.interleaved_strategies else
                "timestamp-production-sequence.log" if args.production_sequence else "timestamp-probe.log")
    log = ROOT / "build/reports" / filename
    command = [str(executable)]
    if args.production_sequence:
        command.append("--production-sequence")
    elif args.interleaved_strategies:
        command.append("--interleaved-strategies")
    log.parent.mkdir(parents=True, exist_ok=True)
    try:
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, timeout=90)
    except subprocess.TimeoutExpired as error:
        output = error.stdout or ""
        if isinstance(output, bytes):
            output = output.decode("utf-8", errors="replace")
        log.write_text(output + "\nDIAGNOSTIC TIMEOUT after 90 seconds\n")
        print(output, end="", flush=True)
        raise SystemExit(f"Counter diagnosis timed out. Partial output saved to {log}") from error
    log.write_text(result.stdout)
    print(result.stdout, end="", flush=True)
    print(f"Counter diagnosis log: {log}", flush=True)
    result.check_returncode()


if __name__ == "__main__":
    main()
