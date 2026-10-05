#!/usr/bin/env python3
"""Build and launch an independent SDL/Metal window with the installed game runtime."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
from datetime import datetime, timezone

from build_offline import ROOT, build
from installed_lwjgl import sdl_jars, joml_jar


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft-dir", type=Path,
                        default=Path.home() / "Library/Application Support/minecraft")
    parser.add_argument("--seconds", type=int, default=None, help="Auto-close after N seconds; 0 waits for window close")
    parser.add_argument("--exercise", action="store_true", help="Request resize, hide/show, minimize/restore and fullscreen transitions")
    parser.add_argument("--build-only", action="store_true")
    args = parser.parse_args()
    seconds = args.seconds if args.seconds is not None else (18 if args.exercise else 0)
    if seconds < 0 or (args.exercise and seconds != 0 and seconds < 16):
        parser.error("Seconds must be nonnegative; --exercise needs at least 16 seconds (or 0)")
    jar, tests, loader = build(args.minecraft_dir)
    game = args.minecraft_dir / "versions/26.3/26.3.jar"
    cp = os.pathsep.join(map(str, (jar, tests, loader, game, joml_jar(args.minecraft_dir), *sdl_jars(args.minecraft_dir, include_natives=True))))
    if args.build_only:
        print("SDL presentation runner compiled. No graphical test was executed.")
        return
    reports = ROOT / "build/reports"
    reports.mkdir(parents=True, exist_ok=True)
    log = reports / "sdl-presentation.log"
    result_path = reports / "sdl-presentation.json"
    env = os.environ.copy()
    env["MTL_DEBUG_LAYER"] = "1"
    command = ["java", "-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED", "-Xcheck:jni",
               "-cp", cp, "dev.metalmc.metal.SDLPresentationTest", str(seconds), str(args.exercise).lower()]
    result = {"started_utc": datetime.now(timezone.utc).isoformat(), "exercise_requested": args.exercise,
              "seconds": seconds, "visual_verification": "pending", "log": str(log)}
    gpu_started = False
    with log.open("w") as output:
        process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, bufsize=1)
        def timeout():
            result["timed_out"] = True
            process.kill()

        watchdog = threading.Timer(seconds + 45, timeout) if seconds else None
        if watchdog:
            watchdog.daemon = True
            watchdog.start()
        try:
            for line in process.stdout:
                if line.startswith("Metal device:"):
                    gpu_started = True
                print(line, end="", flush=True)
                output.write(line)
                output.flush()
            code = process.wait()
        except KeyboardInterrupt:
            process.terminate()
            try:
                code = process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                code = process.wait()
            result["interrupted"] = True
        finally:
            if watchdog:
                watchdog.cancel()
            process.stdout.close()
    result["exit_code"] = code
    result["gpu_test"] = "passed" if code == 0 else ("failed" if gpu_started else "not_run")
    result_path.write_text(json.dumps(result, indent=2) + "\n")
    print(f"Log: {log}\nResult: {result_path}")
    if code:
        print("Presentation test failed. A normal macOS Terminal launch may expose different display access; the cause is not established.", file=sys.stderr)
    raise SystemExit(0 if code == 0 else 1)


if __name__ == "__main__":
    main()
