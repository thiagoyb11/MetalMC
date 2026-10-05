# MetalMC

A native Metal rendering backend project for **Minecraft Java 26.3 + Fabric on
Apple Silicon**. An arm64 JNI library executes a real offscreen Metal render pass
and returns the triangle's pixels. Independent SDL/CAMetalLayer presentation is
visually verified on this Mac. A first RenderPearl adapter creates native shared
buffers and private textures, with tested GPU transfers, mip views and samplers. Ordered buffer commands now
submit asynchronously with fences and completion-based resource retirement.
The texture encoder adds region transfers, full clears and owner-thread readback
callbacks, verified on Apple M5 in the user's Terminal.
Submission-scoped transient arenas reuse completion-gated native blocks and
protect escaped mappings with detached snapshots; their GPU suite passed on
Apple M5 in the user's Terminal at 16:12:43. Overall project completion is
approximately **44%** ([estimate](docs/PROJECT_STATUS.md)). See the
[transient memory record](docs/research/transient-memory-validation.md).

The backend also implements attachment-only render passes in **JNI ABI 7**:
full-area color/depth attachments, base mip selection, load/clear/store, debug
groups, bounded scissor state and retained lifetime. Strict Java/native and test
compilation succeed; **the complete GPU suite passed on Apple M5 at 16:46:15–16**.
That ABI 7 increment did not implement backend pipelines or draw calls. See the
[render-pass record](docs/research/render-pass-validation.md).

**JNI ABI 8** adds a bounded SPIR-V → MSL translator using the game's installed
LWJGL SPIRV-Cross, with reflected block layouts, explicit per-stage resource
remaps and coordinate options. A native diagnostic compiles translated Metal
functions. Strict Java/native build and test compilation succeed; **the complete
user-run ABI 8 suite passed on Apple M5 at 17:28:25–27**. That ABI 8 increment did not implement backend
pipelines or draws. See the [shader translation record](docs/research/shader-translation-validation.md).

**Latest complete GPU PASS: JNI ABI 15**, Apple M5 at **23:08:32–45** on
2026-10-03 with Metal API validation and JNI checks. Scissored color/D32 clears,
seven color formats, rectangle/mip preservation, snapshots, cancellation,
retirement and every prior suite passed. Evidence:
`build/reports/scissored-clear-smoke-pass.log`.

**Current build: JNI ABI 16**, adding real timestamp query pools, stage-boundary
sampling between encoder operations, nonblocking optional results, completion
retention and clock calibration. Strict Java/native build, signing, packaging
and all test compilation pass. After four full-suite failures, the probes isolated
missing samples after unsampled transfers. The strategy probe at 01:03:53 showed
compute markers and a GPU event boundary work; explicit transfer descriptors fail.
Production now applies an event boundary before every blit marker and resolution,
with increasing batch event values and no CPU wait. Expanded regression checks
are compiled. Complete GPU acceptance is pending: run `python3 tools/smoke_test.py`
in the user's Terminal. Queries inside render passes remain unsupported;
frontend/Tracy activation is pending.
See [timestamps](docs/research/timestamp-query-validation.md),
[scissored clears](docs/research/scissored-clear-validation.md) and
[device reporting](docs/research/device-info-validation.md).

**Current status: native foundation, not a complete Minecraft renderer.** The
Fabric client module can run an offscreen Metal diagnostic. Minecraft continues
to use its selected vanilla backend. No terrain, GUI, or game rendering has been
ported yet, and there is no performance claim.

- [Feasibility and step-by-step implementation plan](docs/IMPLEMENTATION_PLAN.md)
- [Intended final architecture](docs/architecture.html)
- [Verified 26.3 backend signatures](docs/research/26.3-backend-signatures.txt)
- [Required backend method coverage](docs/research/backend-coverage.md)
- [Texture/sampler GPU validation and limits](docs/research/resource-validation.md)
- [Prueba de ventana: instrucciones en español](docs/PRESENTATION_TEST.md)

## Build and verify now

Requirements: Apple Silicon Mac, macOS 14+, ARM64 JDK 25, Python 3, Apple Command
Line Tools, an installed Minecraft 26.3 (including its LWJGL SDL, SPIRV-Cross and shaderc libraries), and
Fabric Loader 0.19.5 JAR. This development machine
already has these. The current diagnostic does not require Fabric API.

```sh
python3 tools/smoke_test.py
```

This builds the Java classes, generates JNI headers, compiles and signs the arm64
native library, packages it into a Fabric JAR, and runs real GPU tests with Metal
API validation and JNI checks. It writes:

- `build/libs/metalmc-0.1.0-dev.jar`
- `build/reports/metal-triangle.png`
- `build/reports/native-smoke.log` (last GPU smoke result)
- `build/reports/shaders/` (translated MSL and binding reports after the shader suite succeeds)

The test covers actual pixel colors/coverage/orientation, non-aligned readback
rows, repeatability, small targets, invalid sizes, native stale handles,
thread ownership, and repeated resource creation/closure. It loads the dylib
**from the packaged JAR**. It also directly exercises the client initializer;
that alone is not an in-game Fabric launch test.

To only build, run `python3 tools/build_offline.py`. If your Minecraft directory
differs, either command accepts `--minecraft-dir /path/to/minecraft`. The offline
builder reads the existing Loader JAR and writes only project build outputs; it
does not install the mod or modify your game profile.

The offline builder compiles against the exact installed, unobfuscated 26.3 game
interfaces. No backend-selection mixin or transform is present. Use/review the
Loom workflow before selection/in-game integration; it owns development launches
and integration-specific transforms.

## Standard development build

`build.gradle` pins Fabric Loom 1.17.4 and targets Java 25/26.3. Install **Gradle
9.6.0** and allow access to Fabric/Mojang Maven repositories, then run:

```sh
gradle build
gradle runClient
```

The Gradle build runs the same native smoke test through `check`. A Gradle wrapper
has not been downloaded/generated in this restricted environment, and the Loom
build is not yet validated. The offline build above has been validated.

An isolated launch with the installed 26.3 game and actual Fabric Loader did
recognize `metalmc 0.1.0-dev` and invoke its client initializer. In this restricted
execution environment, the first-thread game process then reported no Metal
device and SDL reported no displays. It did not reach the title screen. Full
in-game GPU validation is pending; the passing standalone test does not establish
that result.

## Optional Fabric diagnostic

In an isolated Minecraft Java 26.3 / Fabric Loader 0.19.5 instance, put the built
JAR in `mods` and add these JVM arguments:

```text
--enable-native-access=ALL-UNNAMED -Dmetalmc.probe=true
```

For development Metal validation, launch with `MTL_DEBUG_LAYER=1` in the process
environment. Expected log: `Metal GPU probe passed on Apple M5` (device name
varies). Without `metalmc.probe`, the initializer only logs project status and
does not load the native library. Probe failure is logged and leaves the vanilla
backend active. The image is an offscreen diagnostic, so it does not appear over
the game window.

## Independent window test

Run the new independent window test from a normal macOS Terminal:

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/presentation_test.py --exercise
```

It builds first, reuses the installed game's LWJGL SDL runtime, and launches on
the macOS main thread with Metal/JNI validation. Over 18 seconds it requests two
resizes, hide/show, minimize/restore, fullscreen transitions, and restores the
initial size. Observe the colored triangle throughout the visible phases. It
does not open Minecraft or modify its profile/worlds. A `PASS` proves GPU command
completion and boundary checks; visual/OS transition correctness needs observation.
Logs and a machine-readable result go to `build/reports/sdl-presentation.log`
and `build/reports/sdl-presentation.json` (last run). See the Spanish instructions
above for interactive controls and the visual checklist.

Implemented: retained surface ownership, BGRA8 sRGB drawable pipeline, real pixel
dimensions, asynchronous command submission, a maximum of three frames in flight,
skipping inactive/zero-size/missing-drawable frames, completion error reporting,
and shutdown drain before SDL view/window destruction. Current JNI ABI is **16**; its timestamp-query GPU gate is pending. Latest complete user-run GPU PASS is ABI 15 at 23:08:32–45 on Apple M5.

**Verified in the user's normal Terminal:** the 18-second exercise exits with
`PASS` on Apple M5 under Metal/JNI validation. 862 frames submitted/completed,
peak 2 in flight, zero pending at shutdown; 2× Retina pixel density and resize,
hide/show, minimize/restore and fullscreen events recorded. The user provided a
screenshot and confirmed it worked. See the
[presentation validation record](docs/research/presentation-validation.md).
Agent-tool SDL initialization still finds no displays; the exact cause remains
unresolved. The successful normal Terminal result does not imply an in-game test.

`MetalGpuDeviceBackend` now implements both buffer factories and cleanup against
the exact 26.3 interface, with `MetalGpuBuffer` implementing `GpuBuffer`.
`python3 tools/smoke_test.py` verifies initial data, staged mapping, read-only
protection, partial GPU blits and untouched bytes, dimensions/overflow/usage
checks, device/thread ownership, stale native IDs and cleanup. Initial limits:
256 MiB per shared buffer, 1 GiB combined buffer/texture/encoder-staging budget
per context, one mapping per buffer; texel-buffer usage unsupported. Diagnostic
copies remain synchronous; the initial game buffer encoder submits asynchronously.
Scissored color/D32 clears passed the complete ABI 15 user-run GPU suite. Device
reporting passed the complete ABI 14 user-run GPU suite; reported features describe
the bounded implemented backend, not every hardware capability. Indexed and CPU multi-draw commands compile in
ABI 11 and passed the complete user-run GPU suite. GPU indirect arguments passed the complete ABI 12 user-run GPU suite. Shader-resource bindings compile
in ABI 10 and passed the complete user-run GPU suite.
Initial resource-free pipelines and direct draws passed real GPU validation in
ABI 9, including normalized vertex pixels, depth, blending and scissor. Initial attachment-only
render passes passed the complete ABI 7 GPU suite in the user's Terminal. Texture commands and
full clears have been added in ABI 6 and passed real GPU validation. The adapter is not
registered as a game candidate and is not constructed by the mod initializer.

The adapter also creates private 2D/2D-array textures, restricted native mip views
and samplers. Supported formats: `R8_UNORM`, `RG8_UNORM`, `RGBA8_UNORM`,
`R32_FLOAT`, `RG32_FLOAT`, `RGBA16_FLOAT`, `RGBA32_FLOAT`, `D32_FLOAT`.
Initial limits: 8192×8192 pixels, 256 array layers, 256 MiB per texture including
mips. Cubemaps and other formats fail explicitly. Samplers preserve the game's
linear mip interpolation, nearest/linear pixel filtering, repeat/clamp, max LOD
and anisotropy 1..16. The resource suite passes on Apple M5 with ABI 6: byte roundtrips,
layer/mip isolation, format-correct render clears, depth readback, view-relative
sampling, filtering and cleanup. All resource diagnostics wait for completion;
they are not the future asynchronous gameplay path. Texture contents require an
upload or clear before reading/sampling. The resource budget includes encoder upload staging and retains the cost of
Java-closed buffers and textures through GPU completion. It excludes Java staging, synchronous
diagnostic staging, pipelines, diagnostic targets and presentation drawables. See the
[validation record](docs/research/resource-validation.md).

`MetalCommandEncoder` implements the installed 26.3 `CommandEncoderBackend`
subset for `writeToBuffer`, `copyToBuffer`, `submit` and `createFence`. Uploads
snapshot the caller's bytes at recording time; GPU blits preserve command order.
Native buffer storage survives Java resource close until completion. Three submissions
may be in flight; only saturation imposes submission backpressure. Direct CPU
mapping/uploads reject recorded, unsubmitted buffer uses and drain submitted
work before shared-memory access. This conservative drain is correct but remains
a potential performance cost.

Initial buffer encoder limits: four-byte aligned ranges, distinct source/destination
buffers for copies, 4096 operations per batch, 64 MiB total encoder staging and
1024 command/fence IDs per context. Fences track the current submission, including
commands recorded after their creation, matching the inspected Vulkan backend.
Submit before waiting; zero timeout polls, positive values are nanoseconds and
`GpuFence.NO_TIMEOUT` waits indefinitely on submitted work. Encoder close cancels
unsubmitted work; device close drains GPU work and releases fences/resources.

The GPU suite verifies ordering, staging snapshots, untouched bytes, CPU/GPU
hazards, close-before-submit, close-in-flight, cancellation, native bounds and
100 completed submissions with peak three in flight. See the
[command validation record](docs/research/command-validation.md).

ABI 6 extends the same batches with `writeToTexture`, strided
`copyBufferToTexture`, tightly packed `copyTextureToBuffer`, same-format
`copyTextureToTexture`, and full color/depth clears across all mips of a
single-layer texture. Uploads address individual array layers; the readback and
texture-to-texture contracts address layer zero. Transfer offsets align to the
pixel size; texture row strides do not inherit the buffer-copy four-byte limit.
Positive regions, source/destination bounds, usages, ownership and format checks
run before encoding. Texture storage remains retained and accounted after Java
close until GPU retirement; diagnostics reject unsubmitted texture uses.

Readback callbacks run exactly once on the creating Java thread after successful
GPU completion. `submit`, successful fence waits, explicit
`MetalContext.pollCallbacks()` and context shutdown dispatch completed callbacks.
Cancellation discards callbacks; failures propagate without skipping later
callbacks in the batch. Context shutdown dispatches completed readbacks before
closing their destination buffers. The complete real-GPU suite passed in the user's normal Terminal at 15:48:48 on
Apple M5, including texture commands, callbacks, clears and resource lifetimes. See the
[texture command record](docs/research/texture-command-validation.md).

The project status and weighted completion estimate are recorded in
[PROJECT_STATUS.md](docs/PROJECT_STATUS.md).

Next, complete RenderPearl's backend
interfaces behind `FrontendGpuDevice`. Validate ABI 16 timestamp queries in the user's Terminal, then expand vanilla
shader/state compatibility and remaining resource/query APIs. The plan defines the resource,
pipeline, gameplay, and validation gates before native game rendering can be
claimed.
