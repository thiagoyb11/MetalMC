"""Resolve existing game libraries for offline builds/runners; never download dependencies."""
import json


def game_runtime_jars(minecraft_dir):
    """Use installed game Java libraries together with their macOS arm64 natives.

    LWJGL detects optional modules such as jemalloc on the classpath, so their
    native artifacts must accompany the Java modules even before vanilla loads.
    Exclude foreign-OS and x86 native classifiers. Never download or bundle JARs.
    """
    metadata = json.loads((minecraft_dir / "versions/26.3/26.3.json").read_text())
    result = {}
    for library in metadata["libraries"]:
        parts = library["name"].split(":")
        if len(parts) != 3 and not (len(parts) == 4 and parts[3] in ("natives-macos-arm64", "osx-aarch_64")):
            continue
        rules = library.get("rules", [])
        allowed = not rules
        for rule in rules:
            os_rule = rule.get("os", {})
            # Feature-/version-/architecture-specific rules are not enabled by this
            # standalone runner. Current installed Java artifacts only use OS names.
            if rule.get("features") or set(os_rule) - {"name"}:
                raise SystemExit(f"Unsupported game runtime rule: {library['name']}")
            if os_rule.get("name", "osx") == "osx":
                allowed = rule["action"] == "allow"
        if not allowed:
            continue
        artifact = library.get("downloads", {}).get("artifact")
        if artifact is None:
            raise SystemExit(f"Minecraft runtime artifact missing from metadata: {library['name']}")
        path = minecraft_dir / "libraries" / artifact["path"]
        if not path.is_file():
            raise SystemExit(f"Installed Minecraft runtime dependency missing: {path}")
        result[library["name"]] = path
    return list(result.values())


def sdl_jars(minecraft_dir, include_natives=False):
    metadata = minecraft_dir / "versions/26.3/26.3.json"
    if not metadata.is_file():
        raise SystemExit(f"Minecraft 26.3 version metadata missing: {metadata}")
    libraries = json.loads(metadata.read_text())["libraries"]
    wanted = {"org.lwjgl:lwjgl", "org.lwjgl:lwjgl-sdl"}
    result = {}
    for library in libraries:
        parts = library["name"].split(":")
        module = ":".join(parts[:2])
        classifier = parts[3] if len(parts) == 4 else ""
        if module not in wanted or classifier not in ("", "natives-macos-arm64"):
            continue
        if classifier and not include_natives:
            continue
        artifact = library["downloads"]["artifact"]
        path = minecraft_dir / "libraries" / artifact["path"]
        if not path.is_file():
            raise SystemExit(f"Installed LWJGL dependency missing: {path}")
        result[(module, classifier)] = path
    expected = {(module, classifier) for module in wanted
                for classifier in (("", "natives-macos-arm64") if include_natives else ("",))}
    if set(result) != expected:
        raise SystemExit("Minecraft 26.3 metadata does not contain the required LWJGL SDL artifacts")
    return [result[key] for key in sorted(result)]


def joml_jar(minecraft_dir):
    """Resolve the exact game's JOML API used by render backend signatures."""
    metadata = json.loads((minecraft_dir / "versions/26.3/26.3.json").read_text())
    for library in metadata["libraries"]:
        if library["name"].startswith("org.joml:joml:"):
            path = minecraft_dir / "libraries" / library["downloads"]["artifact"]["path"]
            if path.is_file():
                return path
            raise SystemExit(f"Installed JOML dependency missing: {path}")
    raise SystemExit("Minecraft 26.3 metadata does not contain JOML")


def fastutil_jar(minecraft_dir):
    """Resolve the game's FastUtil used inside RenderPassDescriptor's constructor."""
    metadata = json.loads((minecraft_dir / "versions/26.3/26.3.json").read_text())
    for library in metadata["libraries"]:
        if library["name"].startswith("it.unimi.dsi:fastutil:"):
            path = minecraft_dir / "libraries" / library["downloads"]["artifact"]["path"]
            if path.is_file():
                return path
            raise SystemExit(f"Installed FastUtil dependency missing: {path}")
    raise SystemExit("Minecraft 26.3 metadata does not contain FastUtil")


def shader_jars(minecraft_dir, include_natives=False, include_shaderc=False):
    """Resolve SPIRV-Cross (production) and optionally shaderc (test inputs) plus core."""
    metadata = json.loads((minecraft_dir / "versions/26.3/26.3.json").read_text())
    wanted = {"org.lwjgl:lwjgl", "org.lwjgl:lwjgl-spvc"}
    if include_shaderc:
        wanted.add("org.lwjgl:lwjgl-shaderc")
    classifiers = ("", "natives-macos-arm64") if include_natives else ("",)
    result = {}
    for library in metadata["libraries"]:
        parts = library["name"].split(":")
        module, classifier = ":".join(parts[:2]), parts[3] if len(parts) == 4 else ""
        if module not in wanted or classifier not in classifiers:
            continue
        path = minecraft_dir / "libraries" / library["downloads"]["artifact"]["path"]
        if not path.is_file():
            raise SystemExit(f"Installed shader dependency missing: {path}")
        result[(module, classifier)] = path
    if set(result) != {(module, classifier) for module in wanted for classifier in classifiers}:
        raise SystemExit("Minecraft 26.3 metadata lacks required shader libraries")
    return [result[key] for key in sorted(result)]
