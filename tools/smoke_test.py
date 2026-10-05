#!/usr/bin/env python3
"""Compile/package the foundation and test real Metal readback through the packaged JNI library."""
import argparse
import os
from pathlib import Path
import subprocess

from build_offline import ROOT, build
from installed_lwjgl import game_runtime_jars


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft-dir", type=Path,
                        default=Path.home() / "Library/Application Support/minecraft")
    args = parser.parse_args()
    jar, tests, loader = build(args.minecraft_dir)
    game = args.minecraft_dir / "versions/26.3/26.3.jar"
    dependencies = dict.fromkeys((jar, tests, loader, game, *game_runtime_jars(args.minecraft_dir)))
    cp = os.pathsep.join(map(str, dependencies))
    report = ROOT / "build/reports/metal-triangle.png"
    env = os.environ.copy()
    env["MTL_DEBUG_LAYER"] = "1"
    # A genuine failure remains a failure. No mock device or software fallback.
    result = subprocess.run(["java", "--enable-native-access=ALL-UNNAMED", "-Xcheck:jni",
                    "-cp", cp, "dev.metalmc.metal.NativeSmokeTest", str(report)],
                   cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                   text=True, timeout=120)
    log = report.with_name("native-smoke.log")
    log.write_text(result.stdout)
    print(result.stdout, end="", flush=True)
    print(f"Smoke log: {log}", flush=True)
    result.check_returncode()


if __name__ == "__main__":
    main()
