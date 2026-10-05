# MetalMC: feasibility, architecture, and implementation plan

Prepared 2026-10-03. Target: **Minecraft Java 26.3, Fabric Loader 0.19.5,
Java 25, macOS 14+ on arm64**. This request starts development; the older
`CONVERSATION_SUMMARY.md` remains a historical record.

Current state: latest complete user-run GPU PASS is **ABI 15**, Apple M5 at
23:08:32–45 on 2026-10-03. Source/build is **ABI 16**, strictly compiled with
timestamp queries and an event-boundary correction awaiting the full manual gate.
After four failures, user-run probes isolated unsampled transfer interleaving;
the strategy probe at 01:03:53 validated compute markers and GPU event ordering.
Production applies increasing batch event values before every blit marker and
resolution. Existing per-slot copies/private counters/snapshots remain bounded.
Validated completion is
**44% (44.1% weighted)**; Minecraft rendering integration remains incomplete.

## Verdict

**A Fabric mod rendering Minecraft directly through Metal is technically
feasible.** A complete, reliable renderer is a substantial project. A Metal
triangle, a Metal presentation layer, or copying an OpenGL frame into Metal does
not demonstrate that the game itself renders natively. Acceptance requires
terrain, entities, particles, GUI, effects, and presentation to use Metal.

Fabric provides loading and mixins; it does not provide a turnkey Metal backend.
Existing experimental projects demonstrate the approach, but their performance
and compatibility have not been reproduced here. No FPS improvement is assumed.

## Evidence and version-specific discoveries

1. Fabric's [26.2 announcement](https://fabricmc.net/2026/06/15/262.html)
   describes selectable OpenGL/Vulkan backends. That supports adding a graphics
   backend rather than rebuilding all game renderers.
2. Fabric's [26.3 announcement](https://www.fabricmc.net/2026/09/15/263.html)
   establishes the release/toolchain and **SDL replacing GLFW**. It recommends
   Loom 1.17, Gradle 9.6.0, and Loader 0.19.5. Java 25 is confirmed by the locally
   installed game's version metadata.
3. Direct `javap` inspection of the installed **26.3.jar**, rather than assuming
   older mappings, confirms the following concrete extension points:
   - `com.mojang.renderpearl.api.device.GpuBackend`: library lifecycle, SDL window
     creation, and `createDevice(GpuDebugOptions)`.
   - `com.mojang.renderpearl.backend.api.GpuDeviceBackend`: textures, buffers,
     samplers, encoders, surfaces, pipeline compilation, queries, and device info.
   - `FrontendGpuDevice(GpuDeviceBackend)`: reusable frontend validation and
     shader compilation.
   - `BackendRenderPipeline.CreateInfo`: compiled shader modules and render state.
   - `SpvModule.spv()`: **SPIR-V already produced by the frontend**.
   - `net.minecraft.client.PreferredGraphicsApi.getBackendsToTry()`: candidate
     backends selected before window/device creation.
   - `com.mojang.blaze3d.platform.Window.handle()`: SDL window handle.
   The names are verified for this exact release; signatures must be re-audited
   before porting. Preserve generated signatures in `docs/research/`.
4. SDL's [Metal view API](https://wiki.libsdl.org/SDL3/SDL_Metal_CreateView)
   creates a view backed by `CAMetalLayer`; device assignment is our responsibility.
   Its [layer access API](https://wiki.libsdl.org/SDL3/SDL_Metal_GetLayer) exposes
   the layer. Both require the main thread. Reuse Minecraft's loaded SDL instance;
   do not ship a second SDL runtime.
5. [SPIRV-Cross](https://github.com/KhronosGroup/SPIRV-Cross) supports translation
   into MSL. The installed game includes LWJGL `shaderc` and `spvc` 3.4.3 bindings
   and arm64 native libraries. Reuse those initially, then verify resource binding
   remapping and shader semantics with actual vanilla pipelines.
6. Reference projects: [MetalCraft](https://github.com/Im-Fran/MetalCraft)
   (GPL-3.0, older 1.21.11 backend), [Metallum](https://github.com/kokodio/metallum)
   (MIT, experimental), and [VulkanMod](https://github.com/xCollateral/VulkanMod)
   (alternate API renderer). These are engineering references, not dependencies
   or locally verified compatible implementations. Start with original code;
   any future source reuse requires tracking its license and attribution.

## Scope

### Functional requirements

- Load as a client-only Fabric mod; leave servers and game simulation unchanged.
- Render vanilla 26.3 gameplay and menus directly using Metal, including
  inventories, text, terrain, entities, particles, sky, weather, water, and effects.
- Preserve resource reloads, screenshots, resize, Retina scale, fullscreen,
  minimize/restore, and orderly shutdown.
- Provide an explicit development switch and readable initialization errors.
- Preserve a startup path to the vanilla backend while the new backend is being
  developed. Fall back only before resources belong to the Metal backend.

### Constraints and measurable targets

- First validation machine: locally recorded M5 MacBook Air, 16 GB unified RAM;
  actual runtime/device support must be probed. Test M1–M5 before broad support.
- Correctness first. Later performance targets: stable 60 FPS (16.67 ms frame
  budget) at a fixed 1080p scene/render distance 12; evaluate 120 FPS (8.33 ms) as
  a separate target. These are goals, not current results or guarantees.
- Use at most three frames in flight, never overwrite GPU-owned upload memory,
  and avoid synchronous readback or per-frame `waitUntilCompleted` in gameplay.
- Initial resource budget: account for and keep renderer-owned GPU allocations
  below 1 GiB at the benchmark settings; measure atlas/mesh/transient peaks.
- At 2560×1664, one RGBA8 or D32 target is 16.25 MiB; three color targets plus
  one depth target are 65 MiB before atlases, meshes, and effects. Unified memory
  is shared with the JVM and OS and does not eliminate allocation or sync costs.
- Unsupported formats/features fail explicitly; no silent dropped draws.

### Deferred until vanilla parity

- Sodium/Iris/OptiFine shader packs and arbitrary mods making raw OpenGL calls.
- Custom chunk meshing, compute culling, indirect command buffers, MetalFX,
  advanced Metal 4 features, Intel Macs, and a general OpenGL translation layer.
- These are separate later workstreams; full vanilla rendering remains required.

## Recommended architecture

Implement **RenderPearl's backend interfaces**, retaining its existing frontend
and vanilla rendering logic. Use **Java → JNI → Objective-C++ → Metal**. SDL owns
the game window and Metal view; the surface uses its `CAMetalLayer` and drawables.
See [architecture.html](architecture.html) for the intended final architecture.

The backend adapter should construct `FrontendGpuDevice` around our
`MetalGpuDeviceBackend`. Translate the incoming `SpvModule` through existing
LWJGL SPIRV-Cross bindings into MSL, create `MTLLibrary` functions, and build
Metal pipeline state. This removes the need for a second GLSL compiler. Preserve
the frontend's reflected bindings and remap them explicitly to Metal indices.

| Choice | Benefit | Cost / reason to reconsider |
| --- | --- | --- |
| Implement backend interfaces | Retains vanilla scene extraction and rendering behavior | Exact-release internal API; requires deliberate ports |
| Thin JNI + Objective-C++ | Works with installed Java 25; uses Apple's native API directly | Must manage lifetime, thread affinity, and JNI errors carefully |
| Reuse frontend SPIR-V + SPIRV-Cross | Retains includes, defines, and shader validation | Binding remapping, coordinate conventions, and storage features need tests |
| Use SDL Metal view | Fits 26.3 window/input ownership | Main-thread lifetime and fullscreen/resize must be verified |
| Fork an existing backend | Could accelerate older-version implementation | 26.3 API/SDL changes, license and maintenance obligations; audit before choosing |
| Vulkan + MoltenVK | Useful comparison and possibly quicker Vulkan reuse | Adds a translation layer; does not meet the direct Metal backend objective |
| Rewrite terrain/entities/GUI separately | Freedom to redesign rendering | Much larger scope and breaks vanilla behavior; defer optimization |

### Native boundary and lifetime rules

- Native objects use opaque registry IDs; Java must not dereference native
  pointers. Each owner is `AutoCloseable`; close is idempotent and use-after-close
  raises a Java exception. Native lookups reject stale handles too.
- Keep command encoding on the render thread; SDL/AppKit setup and disposal on
  the macOS main thread. Establish the actual game thread relationship before
  injecting a surface; do not synchronously dispatch onto a blocked main thread.
- Use ARC and scoped autorelease pools. JNI reports initialization, compilation,
  allocation, and command-buffer errors as Java exceptions with useful messages.
- Use private storage for render targets and static GPU-only resources, shared
  storage for staged/upload data when appropriate. Coherent unified memory still
  requires completion tracking before CPU writes or reads.
- Frame completion retires resource references. Closing Java resources does not
  release memory still used by an in-flight command buffer.
- Package the arm64 dylib under `/natives/macos-arm64/`; extract and load it from
  a unique temporary directory. Load only on supported platforms.
- The first probe intentionally waits for GPU completion for test readback.
  This synchronous probe is separate from the eventual frame submission path.

### Failure behavior

- Missing dylib/unsupported architecture/no Metal device: report clearly; the
  initial diagnostic mod leaves the vanilla backend active.
- Pipeline compile or surface creation failure before backend activation: close
  partial resources and try the vanilla candidate only if the experiment permits
  it. Never claim Metal is active after fallback.
- Failures after activation: report/fail the session instead of mixing resources
  from different APIs; a restart can select vanilla.
- Minimized/zero-size surface: skip acquisition and presenting; resume on resize.
- Drawable unavailable: skip the frame with bounded waiting and diagnostics.
- Device loss/GPU error: preserve useful command labels and logs; require restart
  unless a complete resource rebuild is implemented and tested.

## Step-by-step delivery plan

Every step has an exit criterion. Keep the renderer opt-in until the gameplay
and lifetime gates pass. A native test image is milestone 1, not a finished mod.

### 0. Audit and pin the target

- [x] Verify release/toolchain and installed development tools.
- [x] Inspect actual 26.3 backend interfaces and SDL change.
- [x] Save reproducible signature evidence and game JAR checksum.
- [x] Pin Loom 1.17.4 / Gradle 9.6.0 / Loader 0.19.5 / Java 25 (wrapper generation
  configured; wrapper distribution not downloaded).
- [x] Record build/runtime environment and dependency access limitations.

Exit: target and adapter contract are concrete; avoid older-version GLFW hooks.

### 1. Build the native foundation (first coding increment)

- [x] Create Fabric metadata and a client initializer.
- [x] Implement a platform-gated native loader and owned Java Metal context.
- [x] Build arm64 Objective-C++ JNI code using Apple Command Line Tools.
- [x] Create a real `MTLDevice`, queue, and MSL triangle pipeline.
- [x] Render to an offscreen Metal texture, copy to a shared staging buffer,
  wait for completion, and verify actual pixels through JNI.
- [x] Exercise invalid dimensions, stale handles, close, and repeated contexts.
- [x] Package the dylib in a diagnostic Fabric JAR; probe only with
  `-Dmetalmc.probe=true`. Add an offline build using installed Loader libraries.

Exit: compiled/packaged native rendering foundation with GPU execution evidence
where the environment permits it. No backend replacement is enabled yet.

### 2. Prove SDL presentation independently

- [x] Implement and compile a separate Metal test window using the installed
  game's LWJGL SDL bindings (`tools/presentation_test.py`). Runtime visibility
  is not yet verified.
- [x] Implement `SDL_Metal_GetLayer` association, BGRA8 sRGB pipeline and native
  drawable rendering/presentation; main-thread checks and explicit ownership.
- [x] Exercise Retina sizing, resize, hide/show, minimize, fullscreen, and close
  in the user's normal Terminal; visual confirmation and final PASS recorded.
- [x] Implement a three-frame bound with completion handlers; skip zero-size,
  inactive and missing-drawable frames, report GPU errors and drain on close.
- [x] Validate drawable rendering, the frame bound and surface cleanup on a real
  visible window with Metal/JNI validation enabled.

Gate passed: the user's normal Terminal execution exits with PASS on Apple M5,
862 frames submitted/completed, peak 2 in flight, 0 pending at shutdown, Retina
2× and all exercise transitions recorded. The user supplied a screenshot and
confirmed it worked. See [validation record](research/presentation-validation.md).
Agent-environment SDL failure remains reproducible; its cause is not established.
Current JNI ABI is 16; its timestamp-query increment compiled strictly and awaits
the new manual GPU gate. Latest complete GPU PASS is ABI 15 on Apple M5 at
23:08:32–45. The mod still leaves Minecraft's
backend active; independent presentation is not an in-game result.

Exit: repeated presentation under Metal API validation without leaking views or
interfering with the running game's surface. Then integrate the game window.

### 3. Establish the RenderPearl adapter

- [ ] Implement `GpuBackend`, `GpuDeviceBackend`, `GpuSurfaceBackend`,
  `CommandEncoderBackend`, and resource wrappers against the verified interfaces.
- [x] Start `MetalGpuDeviceBackend` with native shared buffer factories and
  `MetalGpuBuffer` implementing the game's `GpuBuffer`. No backend registered.
- [x] Add initial async buffer commands/fences with completion-based retention,
  bounded submissions and CPU hazard checks. ABI 6 adds validated texture commands
  and full clears. Transient arenas also passed GPU validation; rendering remains pending.
- [x] Add initial private texture factories, native mip views and samplers
  against exact 26.3 contracts; real GPU resource tests pass on Apple M5.
- [x] Generate an exact-interface [method coverage table](research/backend-coverage.md)
  using `tools/audit_backend.py`; shared buffer/cleanup coverage marked partial
  or implemented and all remaining operations explicitly pending.
- [x] Report bounded native identity/limits/features in `DeviceInfo` (ABI 14 full GPU PASS).
- [ ] Reuse `FrontendGpuDevice` after completing its query/profiler and worker contracts.
- [ ] Add an opt-in, exact-version mixin around
  `PreferredGraphicsApi.getBackendsToTry()` to supply Metal before vanilla.
- [ ] Ensure candidate window creation uses `SDL_WINDOW_METAL` and never creates
  an OpenGL context for the Metal candidate. Audit fallback destruction/ownership.

Exit: a selectable backend creates its own device/surface and clears the game
window. Unimplemented operations prevent activation instead of silently no-oping.

### 4. Complete buffers, textures, and synchronization

- [x] Initial shared buffers: staged mapping/read/write, initial uploads,
  diagnostic GPU blits, usage/range checks and owner-thread cleanup verified.
  Current subset: 256 MiB per allocation, one mapped view, no texel-buffer usage.
- [x] Initial async buffer uploads/copies, submission fences and lifetime retirement.
- [x] Validate ABI 6 async texture region uploads/copies/readbacks, full clears and
  owner-thread callbacks in the user's Terminal; complete GPU suite passes.
- [x] Implement and validate initial reusable transient arenas, mapped snapshots and submission lifetimes.
- [ ] Complete remaining transfers and timestamps.
- [ ] Texture formats, mip levels, arrays, views, render targets, samplers.
- [x] Initial resource subset: eight formats, 2D/arrays/mips, native views,
  samplers, full-mip diagnostic transfers and color/depth render clears.
  Cubemaps and remaining formats are pending; ABI 6 texture transfers/full clears
  passed user-run GPU validation.
- [x] Initial format-correct region copies/readback for the supported eight formats.
- [x] Implement and strictly compile single-mip scissored color/D32 clears (ABI 15).
- [x] Validate ABI 15 scissored-clear pixels and retirement in the user's Terminal.
- [x] Implement/strictly compile bounded outside-pass timestamp pools and calibration (ABI 16).
- [ ] Validate ABI 16 actual counters, clock units, lifetimes and boundaries in the user's Terminal.
- [ ] Implement queries inside render passes and complete Tracy/profiler compatibility.
- [ ] Additional copy/readback cases.
- [x] Bound async buffer submissions to three; defer buffer/staging destruction
  through GPU completion and synchronize CPU shared-memory access.
- [x] Validate deferred texture destruction and retained texture accounting (ABI 6).
- [ ] Reusable upload arenas and deferred destruction for all remaining resources.
- [ ] Test bounds/alignment, CPU/GPU visibility, depth, and lifetime under load.

Exit: resource and copy semantics match the frontend contract under validation.

### 5. Implement shader and pipeline parity

- [x] Validate initial ABI 7 attachment-only passes: full-area color/depth,
  base-mip load/clear/store, debug/scissor state and completion-based retention.
  The complete user-run GPU suite passed on Apple M5 at 16:46:15–16 under
  Metal/JNI validation. No draws or shader parity are implemented yet.
- [x] Initial bounded SPIR-V -> MSL translation and native function compilation,
  user-run ABI 8 PASS; full vanilla shader/resource parity still pending.
- [x] Validate initial retained resource-free PSOs, vertex layouts/bindings and
  direct instanced draws (ABI 9 complete user-run GPU PASS at 18:36:06–09).
- [x] Implement/strictly compile initial per-stage set-zero uniform buffers, combined
  texture views/samplers and push snapshots in ABI 10; complete user-run GPU PASS.
- [x] Validate ABI 10 shader-resource pixels and retained lifetimes in the user's Terminal.
- [x] Implement/strictly compile normal index bindings, indexed instancing and all
  four CPU multi formats with atomic preflight in ABI 11; complete user-run GPU PASS.
- [x] Validate ABI 11 indexed/multi pixels, boundaries and retirement in the user's Terminal.
- [x] Implement/strictly compile direct/indexed GPU argument slices in ABI 12.
- [x] Validate ABI 12 indirect pixels, ordered transfers, static preflight and retirement.
- [x] Validate all sixteen vanilla vertex formats (ABI 13 full user-run GPU PASS).
- [ ] Translate all incoming SPIR-V with SPIRV-Cross to MSL; reflect/remap buffers,
  samplers, textures, vertex attributes, push constants, and storage bindings.
- [ ] Match depth range/Y orientation/front-face winding; prevent double flips.
- [ ] Implement blend, cull, depth/stencil, write masks, primitive/index formats,
  instancing, direct/multi/indirect draws and queries used by vanilla.
- [ ] Validate 26.3 order-independent transparency and its resource requirements.
- [ ] Compile all vanilla pipelines; cache by full shader/state/device/version key.
- [ ] Resource reload invalidates cache ownership safely; test malformed shaders.

Exit: GUI/text/texture primitives and depth-tested textured geometry match
reference images. No regex-based GLSL conversion or hardcoded vanilla-only shaders.

### 6. Reach full vanilla gameplay

- [ ] Menus/loading screen, fonts, HUD, inventory and held items.
- [ ] Opaque/cutout/translucent terrain, atlases, lightmaps, biome tint and fog.
- [ ] Entities/block entities, particles, sky/clouds/weather and overlays.
- [ ] Water/transparency, outlines, portals and post-processing.
- [ ] Screenshots, resource packs/reload, Nether/End and world transitions.
- [ ] Verify GPU capture: all game rendering and presenting are Metal; no GL frame
  transfer, GL calls, or hidden fallback in the native backend run.

Exit: a deterministic world suite works with vanilla visual parity; screenshot
differences are explained and tracked. The mod can now claim native game rendering.

### 7. Harden, benchmark, and distribute

- [ ] Long sessions, stress reloads, fullscreen/resize loops, allocation counters.
- [ ] Test M1–M5 and supported macOS versions; package/license/signing audit.
- [ ] Compare vanilla OpenGL, vanilla Vulkan where available, and direct Metal at
  identical resolution, world, view path, packs, settings and warm-up.
- [ ] Report CPU/GPU frame times, median/p95/p99, 1% lows, allocations, memory and
  sustained thermal behavior. Measure uncapped and fixed-FPS modes separately.
- [ ] Release a clearly experimental client JAR with known incompatibilities and
  reproducible instructions. Keep shader packs/Sodium separate until audited.

Exit: repeatable evidence supports stability and any performance claims.

## Effort and current uncertainty

Planning estimates for one experienced graphics developer: native/presentation
spikes take days; complete resource/pipeline integration takes weeks; vanilla
parity and hardware hardening can take months. API issues and shader semantics
may dominate. These are rough engineering estimates, not delivery commitments.

The weakest dimension is **unproven 26.3 backend parity**, especially OIT,
transient memory, and surface lifetime. The next improvements are real Metal
execution, independent SDL presentation, and an exhaustive method/format coverage
table. Existing mod advertisements do not remove those gates.

## Validation record

Attachment-render-pass continuation on 2026-10-03 (GPU validation complete):

- Native ABI 7 opens/ends real Metal render encoders inside existing async
  batches. Initial full-area/single-layer color/depth attachments, base-mip
  selection, optional clears/load preservation, store actions, debug/scissor
  state, cancellation and retained texture lifetimes are implemented.
- Strict Java/native compilation, generated JNI headers, signing, packaging
  and the new `RenderPassTest` compilation succeed. The user-run full smoke
  suite at 16:46:15–16 passed on Apple M5 with Metal/JNI validation, including
  attachment pixels and every previous suite. The agent read the full saved log.
- Overall completion is now approximately **32%** (32.1% weighted).
  See [pass validation](research/render-pass-validation.md). No game activation,
  shader translation, pipeline compilation or draw implementation is claimed.

Transient-memory continuation on 2026-10-03 (validation complete):

- Java `MetalTransientMemory` implements the exact installed contract with
  separate staging/GPU/mapped pools, aligned suballocations, partial tails,
  generation tokens and completion-gated native block reuse. JNI remains ABI 6.
- User-run `python3 tools/smoke_test.py` at 16:12:43 **PASS** on Apple M5 under
  Metal/JNI validation, including strict compilation and all previous suites.
  New checks include real GPU bytes, 64 sequential reuse submissions and 64
  asynchronous submissions, budget enforcement, invalid references and closure.
- Overall completion estimated at **31 %**; gameplay backend still inactive.
  See [transient validation](research/transient-memory-validation.md) and
  [weighted estimate](PROJECT_STATUS.md). Next: shaders/pipelines/render passes.

Texture-command continuation on 2026-10-03 (validation complete):

- JNI ABI 6: asynchronous texture region uploads, strided buffer/image copies,
  tight readback regions with owner-thread callbacks, same-format texture copies,
  single-layer full color/depth clears over all mips, retained texture accounting.
- Native and strict Java compilation succeeded. The user's normal Terminal run
  at 15:48:48 **PASS** on Apple M5 with Metal/JNI validation; complete raw log read.
  Agent launches
  at 15:43:11 and 15:43:35 report no Metal device before any test. Session policy
  rejected an escalated test attempt. The cause of unavailable GPU access is unknown.
- User requested all subsequent tests be provided as commands for their separate
  Terminal. `python3 tools/smoke_test.py` was provided and successfully executed.
- Semantics, test coverage and status:
  [texture command record](research/texture-command-validation.md).

Async buffer-command continuation on 2026-10-03:

- JNI ABI 5: exact-26.3 buffer encoder and submission fences, retained native
  resources/staging through completion, three submissions in flight, CPU hazard
  checks and cancellation/close draining. No frontend/backend activation.
- Final `python3 tools/smoke_test.py` at 15:18:59 local **PASS** on Apple M5 with
  Metal/JNI validation: 100 completed submissions, peak three, no pending work or
  retired buffer/staging allocation. Previous diagnostics also pass.
- First attempt at 15:15:27 found no Metal device before tests; retained log.
  A 15:16:32 run passed before final fence edge-case tests were added.
- Coverage, semantics, limits and evidence:
  [command validation](research/command-validation.md).

Texture/sampler continuation on 2026-10-03:

- JNI ABI 4: `MetalGpuTexture`, `MetalGpuTextureView`, `MetalGpuSampler` implement
  the installed 26.3 interfaces; device factories are wired. No game activation.
- Complete `python3 tools/smoke_test.py` **PASS** on Apple M5 at 14:51:43 local
  time with Metal/JNI validation and warnings treated as errors. Eight formats
  roundtrip through private textures, padded staging, three mip levels and three
  layers; render clears preserve adjacent subresources. Sampling checks actual
  native views, repeat/clamp, pixel/mip interpolation and max LOD.
- Attempt at 14:51:28 failed before tests with no Metal device; fresh run passed.
  Environment/device availability remains inconsistent and its cause unresolved.
- Resource accounting now combines buffer lengths and texture allocated sizes
  under a 1 GiB cap per context. Views do not duplicate allocations. This remains
  partial renderer accounting; staging/drawables/pipelines are outside the cap.
- Limits, semantics and evidence: [resource validation](research/resource-validation.md).

Later results in the same continuation:

- User's normal Terminal SDL exercise: **PASS**, visual confirmation and screenshot,
  862 submitted/completed frames, peak 2 in flight and correct shutdown. Retina,
  resize, hide/show, minimize/restore and fullscreen events observed in logs.
  See [presentation record](research/presentation-validation.md).
- JNI ABI 3 introduces native shared buffers and a development-only exact-26.3
  device adapter. `python3 tools/smoke_test.py`: **PASS** on Apple M5, Metal/JNI
  validation, real partial GPU buffer copies and byte verification, staged
  read/write mapping, permissions, source position preservation, overflow and
  stale-ID checks, owner-thread access and cleanup. Read-only native destinations
  are rejected; Unicode debug labels supported. Buffer allocation budget is
  separate from texture/staging allocations and is not total renderer accounting.
- Full frontend construction is not enabled: inspected bytecode requires a real
  command encoder immediately in its constructor. Pending adapter operations
  throw; there is no backend selection mixin or game activation.
- Final buffer verification after preserving untouched bytes in write-only mapped
  views: first attempt at 14:14 local time found no Metal device before any GPU
  test; a fresh attempt at 14:16 **passed the complete suite** on Apple M5. The
  agent-launched device availability is inconsistent; its cause is unresolved.
  This failure was not converted into a mock/software passing result.

Continuation on 2026-10-03:

- Extended native ABI to 2 with SDL layer surface ownership and async presentation.
  Added a standalone main-thread SDL test, automated window action requests,
  frame statistics, GPU draining, detach/reattach and boundary/lifetime checks.
- Offline Java/native compilation passes with warnings treated as errors;
  `python3 tools/smoke_test.py` passes on Apple M5 with Metal validation and
  `-Xcheck:jni`. It also checks that surface JNI calls reject non-main threads
  before dereferencing a layer pointer. Actual surface runtime checks remain pending.
- `python3 tools/presentation_test.py --exercise`: blocked at SDL initialization
  (`The video driver did not add any displays`), before any surface exists. Logs:
  `build/reports/sdl-presentation.log` and `sdl-presentation.json`.
- Exact backend coverage table generated from the checksum-verified 26.3 JAR.
  Gradle's separate `presentationTest` task is configured but not executed;
  Gradle/Loom availability is unchanged.

Validated locally on 2026-10-03:

- `python3 tools/audit_backend.py`: generated
  [26.3 backend signatures](research/26.3-backend-signatures.txt); installed JAR
  SHA-1 matches its version metadata; SHA-256 recorded too.
- `python3 tools/smoke_test.py`: **PASS** on **Apple M5**, with Metal API
  validation enabled and `-Xcheck:jni`. Java and Objective-C++ compiled with
  warnings treated as errors (intentional JNI loading warning locally suppressed).
- The test loaded the native library from the packaged JAR and verified triangle
  coverage, RGBA colors/alpha/orientation, padded rows, repeated GPU rendering,
  dimensions, stale native IDs, idempotent Java close, thread ownership, and
  repeated contexts. PNG visually inspected: correct colored triangle and clear.
- `file` confirms arm64 Mach-O; `codesign --verify --strict` succeeds. JAR
  inspection confirms expanded metadata, bundled dylib, and excluded test classes.
- Isolated actual Fabric launch: Loader 0.19.5 recognizes `metalmc 0.1.0-dev` and
  invokes the initializer. **No successful in-game GPU result**: the restricted
  first-thread game process reports `No Metal device available to this process`,
  followed by SDL `The video driver did not add any displays`. A standalone
  first-thread test also cannot obtain a default Metal device here. The precise
  graphical-session/access cause is not conclusively diagnosed; do not infer
  in-game success from the passing standalone test. The normal game directory
  and worlds were not modified; test logs are under `build/fabric-smoke/`.
- The Gradle/Loom workflow is configured but **not executed**: no local Gradle
  installation/cache and shell dependency downloads cannot resolve network hosts.
  The current no-mixin diagnostic has a verified offline build against the
  installed Fabric Loader JAR. Revisit that packaging path for backend integration.

Outputs: `build/libs/metalmc-0.1.0-dev.jar`,
`build/reports/metal-triangle.png`, source under `src/main/`, and repeatable tools
under `tools/`. Independent visible-window validation is complete on this Mac.
The game renderer remains incomplete; milestones 3–7 are in progress/pending.

Design self-assessment (0–5, adapted to graphics rather than service scaling):
requirements 4, evidence/interfaces 4, trade-offs 4, frame/memory budgets 3,
failure handling 4, validation 2; **21/30**. Improve requirements with a specific
reference world, evidence with SDL surface tests, trade-offs with measured
translation/build costs, budgets with allocation/frame traces, failure handling
with real device/surface stress tests, and validation with full vanilla pipeline
coverage. Validation is the weakest dimension; this is a working design and
tested first increment, not a completed backend.


Pipeline/direct-draw continuation (ABI 9, GPU validated): strict Java/native build,
generated headers, arm64 signing, packaging and test compilation succeed. Initial
retained resource-free PSOs with D32/no-depth variants, layouts/state, normal/transient
vertex bindings and direct instanced draws passed the complete user-run GPU suite
on Apple M5 at 18:36:06–09 under Metal/JNI validation. All prior suites also passed;
full log inspected and preserved. Completion is **37% (36.5% weighted)**. No game
activation or full state/vanilla parity claim. See
[pipeline/direct-draw record](research/pipeline-draw-validation.md).


Shader-resource continuation (ABI 10, complete user-run GPU PASS): strict build,
signing, packaging and test compilation succeed. At 19:18:37–43 on Apple M5,
the new per-stage uniforms, combined texture/sampler views and push snapshots
passed pixel and lifetime checks, seven color formats, mip/array selection,
transient uniforms, cancellation/rebinding/JNI boundaries and 32 asynchronous
resource submissions. All prior suites passed too. Full log inspected/preserved;
no agent-launched tests or game activation. Estimate now **39% (38.7% weighted)**.
See [shader-resource record](research/shader-resource-validation.md).


Indexed/CPU multi continuation (ABI 11, complete user-run GPU PASS): normal UInt16/UInt32
index buffers, signed base vertex/first index/instances and all four exact CPU
parameter layouts compile with snapshot ownership and complete-call preflight.
Index contents remain a caller invariant; no synchronous GPU/CPU inspection or
input robustness claim. DrawIndex shaders reject explicitly. The validated
suite includes GPU pixels, offsets, instancing with uniforms/constants, preflight
atomicity/operation caps, cancellation and 16 asynchronous submissions.
The complete user-run suite passed at 19:41:23–31 on Apple M5; full evidence is
preserved as `build/reports/indexed-draw-smoke-pass.log`. No tests launched by
the agent; validated estimate is **40% (39.8% weighted)**.
See [indexed/multi gate](research/indexed-draw-validation.md).

Indirect continuation (ABI 12, new user-run GPU gate pending): native indirect
methods read packed 16/20-byte arguments at GPU execution with no new CPU
inspection or waits. Normal/transient slices require INDIRECT_PARAMETERS usage,
four-byte offsets and full record ranges; fixed resources and whole operation
cost preflight before encoding. Each of 0..1024 records counts as one operation,
including records whose GPU count/instances are zero. Actual GPU-provided
vertex/index/instance ranges remain caller invariants. Strict build and complete
test compilation pass. The prepared suite covers ordered upload/copy snapshots,
U16/U32 signed bases, firstInstance/transient resources, cancellation, JNI errors,
operation caps and 16 asynchronous retained submissions. No tests launched by
the agent, no game activation; estimate stays **40% (39.8% weighted)**.
See [indirect gate](research/indirect-draw-validation.md).

Indirect continuation (ABI 12, complete user-run GPU PASS): the full suite
passed on Apple M5 at 20:18:57–20:19:07 under Metal/JNI validation. Indirect
pixels, ordered transfers, transient resources, static preflight, cancellation
and sixteen asynchronous submissions passed with every prior suite. Full log
inspected/preserved as `build/reports/indirect-draw-smoke-pass.log`. Validated
estimate is **41% (40.9% weighted)**; no gameplay/parity credit or agent-run tests.
Next increment ABI 13 adds vanilla vertex formats and a new manual GPU gate;
see [vertex formats](research/vertex-format-validation.md).

Vanilla vertex-format continuation (ABI 13, complete user-run GPU PASS): the
full suite passed on Apple M5 at 21:53:40–54 under Metal/JNI validation. All
sixteen installed vanilla layouts, signed short coordinates, SNORM normals
and integer chunk positions passed with every prior suite. Both earlier runtime
classpath failures are preserved; the corrected complete game runtime is now
validated. Full PASS preserved as `build/reports/vertex-formats-smoke-pass.log`.
Estimate is **42% (42.0% weighted)**; no gameplay/parity credit. ABI 14 subsequently passed native device facts and bounded DeviceInfo in the
complete user-run suite at 22:33:26–39, with full log preserved. Validated
estimate is now **43% (43.2% weighted)**. See [device reporting](research/device-info-validation.md).


Scissored-clear continuation (ABI 15, strictly compiled; manual GPU gate pending):
combined single-layer color/D32 clears address one positive rectangle at one mip.
An internal fullscreen-triangle PSO loads/stores attachments, with owned budgeted
parameters and existing completion retention. Seven supported color formats;
up to seven cached internal PSOs/context, no frontend module mutation or new
GPU waits. ScissoredClearTest is compiled and included after DeviceInfoTest;
no tests launched by the agent. Estimate stays **43% (43.2% weighted)**.
See [scissored-clear gate](research/scissored-clear-validation.md).


Scissored-clear continuation (ABI 15, complete user-run GPU PASS): the suite
passed on Apple M5 at 23:08:32–45 with Metal/JNI validation. Entire log inspected
and preserved as build/reports/scissored-clear-smoke-pass.log. All prior suites
also pass; estimate is now **44% (44.1% weighted)**, with resources at 85%.
No repeat needed for this exact increment or the independent window.

Timestamp-query continuation (ABI 16, strictly compiled; manual GPU gate pending):
real Metal timestamp sample buffers, stage-boundary blit markers between encoder
operations, nonblocking optional availability, completion-based pool retention,
slot reuse/cancellation and bracketed JVM clock calibration. Size 1..4096,
16 live/retained pools per context and 32 per process. Counter storage is opaque,
separately bounded and excluded from the partial buffer/texture/staging budget.
Queries inside render passes still throw; arbitrary draw-boundary sampling on
Apple Silicon needs further work before frontend/Tracy activation. No tests
launched by the agent; estimate stays **44% (44.1% weighted)**.
See [timestamp query gate](research/timestamp-query-validation.md).

Timestamp-query correction (ABI 16, first user-run failure at 23:39:34): every
earlier suite passed, but the new timestamp positive/order assertion failed
without printing the values. The empty sampling blit encoder is replaced by
a retained private four-byte GPU fill and an end-boundary sample, addressing
the empty-encoder issue documented by Dawn. Marker bytes are budgeted and
retired with the query pool; test checks cover their retained lifetime/cleanup.
Native invalid samples and Java order/clock failures now report actual values.
The corrected strict build and test compilation pass; GPU rerun remains manual
and pending. Validated estimate remains **44% (44.1% weighted)**.

Timestamp resolution correction (ABI 16, second user-run failure at 23:44:47):
the real GPU marker was insufficient; slot 3 resolved as zero after slot 2
passed native validity checks. Every earlier suite passed again. Counter storage
is now private, with one budgeted shared resolution snapshot per pool/batch and
GPU shared-event ordering before full zero-based blit resolution at submission.
Completion copies only recorded slots from the owned completed snapshots.
This removes CPU single-slot resolution and isolates result generations across
batches; it adds no CPU wait. Snapshot staging caps, retention and cancellation
are covered by the updated manual suite. The exact driver cause remains
unproven. Build/signing/test compilation pass; the GPU rerun is pending and
validated completion remains **44% (44.1% weighted)**.

Timestamp diagnosis (2026-10-04, third ABI16 failure at 00:26:45): slot3=0 remains
with GPU resolution and event ordering. All earlier suites pass again. The
production driver/API cause remains unknown; no further source correction is
claimed. A standalone native probe compares 12 nonempty blit/compute, start/end/
paired and shared/private configurations, GPU and CPU resolution paths, actual
workload bytes, all raw slots and native clock/frequency facts. Strict probe
compilation/signing pass; user execution of python3 tools/timestamp_probe.py is
pending. The agent launched no tests. Completion remains **44% (44.1% weighted)**.

Timestamp workload correction (2026-10-04, user-run probe at00:39:46): all12
configurations produced positive ordered GPU counters and exact output bytes;
shared CPU-full/single-slot results matched GPU resolution, including odd slots.
Evidence preserved as timestamp-probe-12-configurations-valid.log. Production
sampling now copies immutable index+1 words from shared storage into distinct
private per-slot destinations, matching the successful probe workload rather
than filling the same four bytes repeatedly. Both buffers remain retained and
budgeted at eight bytes per slot (max32KiB/pool); the existing GPU resolution
snapshots/event remain isolated per batch. Async accounting/cleanup checks now
include the actual source/destination sizes. Strict build/signing/test compilation
pass; the corrected full gate is pending in the user's Terminal. Exact fill
driver behavior remains an inference. Completion stays **44% (44.1% weighted)**.

Timestamp sequence diagnosis (fourth full run,00:44:16): the copy-based build
still failed slot3=0; all earlier suites passed. The standalone12-case probe
used32-slot pools and adjacent sampled passes, while acceptance uses8 slots and
two unsampled64KiB blit encoders between markers. A new four-case probe varies
those two conditions and compares native completion-handler snapshots with
post-wait bytes, matching per-encoder autorelease lifetimes and buffer sizes.
The loader freshly extracts each process's dylib; no stable extraction cache.
Strict probe compilation/signing pass. User command is
python3 tools/timestamp_probe.py --production-sequence; inspect its separate log
before choosing another production change. No tests launched by the agent.
Completion remains **44% (44.1% weighted)**.

Timestamp interleaving diagnosis (user-run sequence probe,00:56:38): both8/32-slot
pools work with adjacent sampled encoders and fail slot3=0 with two unsampled
transfer encoders between markers. Real GPU bytes remain correct and completion
callback/post-wait snapshots agree. This reproduces the issue without Java/JNI
and isolates the transfer interleaving trigger. A strictly compiled/signed probe
now compares the failing control against explicit transfer descriptors, compute
markers and a GPU event boundary before the second marker. User command:
python3 tools/timestamp_probe.py --interleaved-strategies. No strategy is selected
without actual GPU evidence; no tests were launched by the agent. **44% (44.1%)**.

Timestamp event-boundary correction (user-run strategy probe, 01:03:53): the
control and explicit transfer descriptors still fail slot 3=0; compute markers
and a GPU event boundary produce positive ordered samples. All GPU bytes are
correct and callback/post-wait snapshots identical. Evidence preserved as
`timestamp-interleaved-strategies-event-compute-valid.log`. Production now orders
every blit marker, including the first after transfers, through the batch-owned
event with a new increasing value; resolution uses the next value. No CPU wait
or new per-marker buffer is added. Tests cover preceding transfers, several
interleaved markers, cached-slot preservation and markers around a render pass.
Strict build/signing/all test compilation pass. The user must run
`python3 tools/smoke_test.py`; the agent launched no tests. **44% (44.1%)** remains
validated until complete acceptance passes. In-pass queries/Tracy stay pending.
