#!/usr/bin/env python3
"""Build the current diagnostic mod using the installed Fabric Loader, without downloads."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
from installed_lwjgl import sdl_jars, shader_jars, joml_jar, fastutil_jar

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)


def build(minecraft_dir):
    properties = {}
    for line in (ROOT / "gradle.properties").read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    loader_version = properties["loader_version"]
    loader = minecraft_dir / "libraries/net/fabricmc/fabric-loader" / loader_version / f"fabric-loader-{loader_version}.jar"
    if not loader.is_file():
        raise SystemExit(f"Installed Fabric Loader JAR missing: {loader}\nInstall Fabric {loader_version} or use the Gradle build.")
    game = minecraft_dir / "versions/26.3/26.3.jar"
    if not game.is_file():
        raise SystemExit(f"Installed Minecraft 26.3 JAR missing: {game}")
    game_libraries = tuple(dict.fromkeys((joml_jar(minecraft_dir), fastutil_jar(minecraft_dir),
                                         *sdl_jars(minecraft_dir), *shader_jars(minecraft_dir))))
    test_libraries = tuple(dict.fromkeys((*game_libraries, *shader_jars(minecraft_dir, include_shaderc=True))))
    # Exact unobfuscated 26.3 interfaces are used, but there are no mixins or
    # backend-selection transforms. Loom remains the integration build workflow.
    classes = ROOT / "build/offline/classes"
    resources = ROOT / "build/offline/resources"
    tests = ROOT / "build/offline/test-classes"
    for directory in (classes, resources, tests):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir(parents=True)
    headers = ROOT / "build/native/headers"
    headers.mkdir(parents=True, exist_ok=True)
    run("javac", "--release", "25", "-encoding", "UTF-8", "-Xlint:all", "-Werror",
        "-h", headers, "-cp", os.pathsep.join(map(str, (loader, game, *game_libraries))), "-d", classes,
        *sorted((ROOT / "src/main/java").rglob("*.java")))
    run("bash", ROOT / "tools/build_native.sh")
    shutil.copytree(ROOT / "src/main/resources", resources, dirs_exist_ok=True)
    metadata_path = resources / "fabric.mod.json"
    metadata_path.write_text(metadata_path.read_text().replace("${version}", properties["mod_version"]))
    json.loads(metadata_path.read_text())
    shutil.copytree(ROOT / "build/generated-resources", resources, dirs_exist_ok=True)
    jar = ROOT / "build/libs" / f"metalmc-{properties['mod_version']}.jar"
    jar.parent.mkdir(parents=True, exist_ok=True)
    run("jar", "--create", "--file", jar, "-C", classes, ".", "-C", resources, ".")
    run("javac", "--release", "25", "-encoding", "UTF-8", "-Xlint:all", "-Werror",
        "-cp", os.pathsep.join(map(str, (jar, loader, game, *test_libraries))), "-d", tests,
        *sorted((ROOT / "src/test/java").rglob("*.java")))
    print(f"Built {jar}", flush=True)
    return jar, tests, loader


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft-dir", type=Path,
                        default=Path.home() / "Library/Application Support/minecraft")
    args = parser.parse_args()
    build(args.minecraft_dir)


if __name__ == "__main__":
    main()
