# MetalMC — development handoff

Updated 2026-10-04 after the fifth user-run ABI 16 full-suite failure at
**01:14:33**, and the user's request to save this summary and stop for today.
**STOP FOR TODAY. No test/build/code correction is requested in this turn.**
On a future explicit resume, use the diagnosis below before suggesting a rerun.
Do not launch tests automatically. The previous pending smoke command has been
run; do not repeat the unchanged build.

## Latest failure and likely fix for the next session

Full log read: `build/reports/native-smoke.log`; preserved unchanged as
`build/reports/timestamp-query-abi16-stats-assertion-failure.log`.
Apple M5, macOS Version 27.2 (Build 26B5091g), Metal API validation/JNI checks.
Every suite before TimestampQueryTest passed again; only the known installed
LWJGL Unsafe deprecation warning appears.

This is **a different failure from the earlier slot 3=0 counter failures**:

```text
Timestamp clock: nanoseconds; GPU interval=130041 ns, JVM calibration offset=-84 ns
java.lang.AssertionError: Recorded query markers/resolution snapshots retired too early
    at TimestampQueryTest.verifyAsyncRetirementAndShutdown(TimestampQueryTest.java:172)
```

Execution reached the first recorded async-retirement batch (i=0), before its
submit. Therefore `verifyGpuClockAndTransfers()` and
`verifyCancellationAndOwnership()` both completed: the original transfer sample,
clock/period/calibration checks, transfers before the first marker, multiple
interleaved markers, preserved cached slots, actual replacement bytes, factory
query, cancellation/reuse/ownership and markers around a render pass passed in
this run. The event-boundary correction has real full-suite evidence for those
cases. The remaining 32-batch async retirement/shutdown and native boundary/cap
checks were not completed; the aggregate timestamp PASS/final PASS did not occur.
Latest complete acceptance remains ABI 15, not ABI 16. Completion stays
**44.1% ≈44%**; no frontend/Tracy/game activation or FPS claim.

**Concrete source-inspection finding:** the failing test expects
`recorded[4] == 1040 && recorded[5] == 520`, treating index 5 as staging bytes.
But `NativeMetal.commandStats` in `src/main/native/metal_bridge.mm` returns:

| Index | Meaning |
| --- | --- |
| 0 | Submitted batches |
| 1 | Completed batches |
| 2 | In-flight batches |
| 3 | Peak in-flight batches |
| 4 | bufferBytes + stagingBytes |
| 5 | textureBytes |

This fresh context creates query pools and no textures, so index 5 should be 0,
not 520. The previous `queryPoolCount()==2` assertion already passed. No actual
stats array is printed in the failure log, so index 4's observed value remains
unknown. The error message alone does **not** prove premature native retirement.
The strongest explanation is a mistaken test expectation for the stats layout.

Suggested next fixes/checks (not applied tonight):

1. Correct the recorded stats assertion in TimestampQueryTest to the established
   stats contract: combined resource bytes 1040 and texture bytes 0. Explain the
   calculation: 65 total slots × 8 marker bytes = 520 buffer bytes; 65 × 8 snapshot
   bytes = 520 staging bytes; combined index 4 = 1040. Include actual stats values
   in failure messages, so any genuine accounting discrepancy is actionable.
   Do not repurpose index 5 or loosen lifetime checks to hide a real failure.
2. If independent staging verification is required, inspect existing diagnostic
   APIs or add an explicitly named staging statistic while preserving the
   established commandStats layout and updating ABI/contracts as appropriate.
   This is optional; the minimal correction can keep the combined-byte check.
3. Review remaining TimestampQueryTest accounting assertions against that same
   layout. After draining, the 64-slot pool should retain 512 marker bytes and
   no textures; temporary pools/snapshots must retire. If index 4 actually differs,
   inspect QueryPool accountedBytes/destructor, allocateStaging, Batch snapshot/
   pool retention and cancellation/completion cleanup before changing expectations.
   The native-boundary test also labels `commandStats()[5] == 0` as a staging-leak
   check, although it checks texture bytes. Correct that misleading message/check;
   its existing index 4 expectation already verifies the combined retained bytes.
4. Retain the working per-marker GPU event ordering. Do not revert to empty/fill
   markers or assume the earlier hardware problem persists because exit status
   is still 1. If counter zeros return in later async cases, diagnose that new
   sequence with printed slot/value, pool/batch/event value and retained resources.
   Compute markers are an evidence-backed alternative from the strategy probe,
   but no sampling-strategy replacement is justified by this assertion failure.
5. On a later explicit resume, make the targeted correction, strictly build/sign/
   compile tests using `python3 tools/build_offline.py`, then give the user
   `python3 tools/smoke_test.py` for their separate Terminal. Do not run tests
   automatically. Inspect and preserve the entire resulting log; ABI 16 only
   earns validated credit after its real timestamp PASS and final overall PASS.

## Preserved counter diagnosis and current implementation

Four earlier full-suite failures preceded this assertion failure: first positive/
order check at 23:39:34, slot 3=0 with a real fill at 23:44:47, slot 3=0 with private
counters/GPU resolution at 00:26:45, and slot 3=0 with per-slot copies at 00:44:16.
Their preserved logs are `timestamp-query-abi16-first-failure.log`,
`timestamp-query-abi16-marker-failure.log`,
`timestamp-query-abi16-gpu-resolve-failure.log` and
`timestamp-query-abi16-copy-failure.log` in build/reports.
The original matrix at 00:39:46 validated all 12 configurations; the sequence
probe at 00:56:38 reproduced slot 3=0 only after unsampled transfers in both
8/32-slot pools, with correct bytes and identical callback/post-wait snapshots.
The strategy probe at 01:03:53 returned invalid control/explicit descriptors but
valid compute/event boundaries. Evidence:
`build/reports/timestamp-interleaved-strategies-event-compute-valid.log`.

Production now signals/waits on a batch-owned GPU event before every timestamp
marker and before resolution, using strictly increasing event values. Existing
per-slot blit copies/private counters/shared snapshots remain budgeted/retained.
There is no added CPU wait. Strict Java/native build, signing, packaging and all
test compilation passed before the fifth run. No code was changed after that run.
Source/build/loader/JNI headers/native remain ABI 16. No probe/window repeat needed.
Latest complete GPU PASS: ABI 15, preserved as
`build/reports/scissored-clear-smoke-pass.log`. Other docs may still describe the
fifth run as pending; this latest handoff supersedes that stale status.

Workspace: `/Users/thiago/Desktop/MetalMC`. No Git repository exists.

## Objective and authorization

Continue the Fabric mod rendering **Minecraft Java 26.3 directly through Metal
on Apple Silicon**. Communicate any step requiring user intervention. Historical
stop instructions in CONVERSATION_SUMMARY.md are superseded. Do not switch to
1.21.x, copy OpenGL frames, or claim gameplay rendering exists. No terrain,
entities, GUI or effects currently render using this adapter; vanilla remains
active. No FPS/performance improvement is established.

Read README.md, docs/IMPLEMENTATION_PLAN.md, docs/PROJECT_STATUS.md and the
validation/coverage records in docs/research: presentation-validation.md,
backend-coverage.md, resource-validation.md, command-validation.md,
texture-command-validation.md, transient-memory-validation.md,
render-pass-validation.md, shader-translation-validation.md,
pipeline-draw-validation.md, shader-resource-validation.md,
indexed-draw-validation.md, indirect-draw-validation.md,
vertex-format-validation.md, device-info-validation.md and
scissored-clear-validation.md and timestamp-query-validation.md. Architecture HTML describes intended final
components, rather than current game integration.

## Persistent user instructions

- **Whenever a test is needed, supply commands and let the user run it in a
  separate Terminal. Do not launch tests automatically.** Builds, implementation,
  fixes, read-only bytecode/log inspection and evidence updates are authorized.
- Report overall completion after **every completed step**, relative to full
  Minecraft rendering through Metal. Current validated work estimate is
  **44% (44.1% weighted)**. It is not FPS/time or proportion of game rendering.
  Backend integration 40% (4.8 points), resources 85% (15.3 points),
  shaders/pipelines/passes 50% (11.0 points); other weights unchanged.
  ABI 16 earns no validated credit until its manual GPU gate passes.
- Work only inside this project/isolated game dirs. Preserve user settings,
  mods/worlds; no silent permission changes or unnecessary tool installations.
- Read session permissions on resume. Do not spawn agents without explicit user
  authorization or applicable AGENTS.md/skill instruction. No new skill applies.

## Earlier completed step — ABI 14 GPU validated

The user reported all tests passed; the agent read the entire 22:33:26–39 log on
Apple M5 with Metal API validation and JNI checks. DeviceInfoTest and every prior
suite passed, with only the known installed LWJGL Unsafe deprecation warning.
Evidence preserved as build/reports/device-info-smoke-pass.log. No repeat needed
for this exact increment or the independent window. No frontend/game activation.

MetalDeviceProfile caches actual device name/macOS text, maxBufferLength,
recommendedMaxWorkingSetSize, unified memory and Apple7-family support. Snapshot
JNI arrays are owned and reject stale IDs/signed overflow. Context creation
requires unified memory and Apple7. Cached getters retain owner/open checks.
Reading properties records no commands or new resources. Native facts observed:
Apple M5, macOS Version 27.2 (Build 26B5091g), maxBufferLength=9534832640,
recommendedMaxWorkingSetSize=12713115648, unifiedMemory=true, supportsApple7=true.
The recommended working set is advisory; budgets remain unchanged.

DeviceInfo exposes anisotropy 16, uniform offset alignment 4, max texture axis
8192, allocation min(256 MiB, actual Metal maxBufferLength), 1024 CPU/indirect
records and eight color attachments (32 combined color bytes/pixel still applies).
CPU multi, indirect and nonzero-firstInstance flags are true based on prior GPU
gates. shaderDrawParameters, persistentMapping and wireframe remain false;
no invented extensions/workarounds. Integrated Apple type, zero-to-one depth.
At ABI14 timestampPeriod=0 was an unavailable sentinel and queries/calibration
threw. ABI16 changes this only for actual timestamp counter/stage support, with
period1 ns and its new manual gate pending; render-pass queries still throw.
DeviceInfoTest verified snapshots, policy, max-width/max-height R8 GPU bytes,
limits, ownership/closed/stale handles and prior unsupported APIs.

The inspected FrontendGpuDevice may initialize TracyGpuProfiler whenever Tracy
is available. **Do not construct/activate it until query/profiler, pipeline worker,
game surface and other essential contracts are complete.**

## Latest completed step — ABI 15 GPU validated

The user reported it passed. The agent read the complete 23:08:32–45 log:
new scissored-clear PASS and every prior suite passed on Apple M5 with Metal/JNI
validation. Only the known LWJGL Unsafe warning appeared. Preserved as
build/reports/scissored-clear-smoke-pass.log. Resources now85% (15.3 points),
validated total44.1% ~=44%. No repeat needed for this exact increment/window.

MetalCommandEncoder implements the overloaded clearColorAndDepthTextures with
x/y/width/height/mip. Read-only installed FrontendCommandEncoder and VulkanCommandEncoder
bytecode confirms one rectangle at layer zero of the selected mip. Inspection
files are in build/reports/inspect-frontend-FrontendCommandEncoder.txt and
inspect-backend-vulkan-VulkanCommandEncoder.txt. Equal mip counts, single-layer
color/D32 and matching selected-mip dimensions are required. Positive in-bounds
regions only; arrays/cubemaps/stencil/MSAA/empty rectangles reject.

Native encodeScissoredClear opens/closes an internal render encoder in the same
ordered batch. An internal fullscreen triangle writes constant float color and
fragment depth, with scissor, Load/Store, all color channels, no blending/culling,
Always depth comparison and writes. Seven color formats supported. It does not
change a user render pass or add GPU waits. Per-context internal MSL library,
depth state and at most seven format PSOs are cached; their bytes and objects are
separate from existing frontend pipeline accounting. Batches retain the context.

Each clear snapshots 32 bytes into native shared staging counted in existing
64 MiB staging / 1 GiB resource budgets; batches retain both texture allocations
and parameters through cancellation/completion, including Java close-before-submit.
Each costs one of 4096 batch operations; all fixed checks/cap preflight before
encoding. GPU data remains undefined outside clears unless initialized first.
Java/JNI reject invalid finite values/depth range, usages/aspects/mips/ranges,
wrong devices, stale resources, active render passes or non-recording batches.

For this completed increment, ABI declarations/header/loader/native were **15**.
Full strict offline build, arm64 signing, Fabric JAR and all test compilation
passed. No ABI 15 GPU/shader test was launched by the agent; the user ran the
complete passing suite. The old TextureCommandTest assertion expecting an unsupported
scissored-clear call was updated to invalid-texture rejection before the build.

ScissoredClearTest follows DeviceInfoTest: actual seven-format color/D32 pixels,
asymmetric/edge/overlapping regions, mutable-vector snapshots, depth 0/0.5/1,
exact external/adjacent-mip preservation, load-only pass after clear, full smallest
mip, Java/JNI/range/device/thread/usage/batch state checks, cancellation,
32-byte staging counters, 16 asynchronous retained submissions, and 4096
recorded clears cancelled after testing the operation cap. Details:
docs/research/scissored-clear-validation.md.

Pre-increment source/docs/tools backup: `/tmp/metalmc-abi14-source`.
The previous handoff/history is also preserved there.

User command:

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/smoke_test.py
```

The user ran that command and passed the complete ABI15 suite. No further test
is needed for that exact increment; ABI16 below adds a separate new gate.

## Current increment — ABI 16 compiled; manual timestamp gate pending

MetalGpuQueryPool implements exact size/getValue/getValues OptionalLong contracts.
Native discovery checks the actual timestamp set/counter and stage sampling.
Pools contain1..4096 slots, capped at16 live/retained pools/context and32/process.
Opaque counter memory/CPU metadata are separately bounded and outside the partial
buffer/texture/staging byte budget. Driver allocation failures remain real errors.

CommandEncoderBackend.writeTimestamp records an end-of-blit sample after copying
one immutable source word into a distinct private per-slot marker word, costing one
of4096 operations, with no new CPU waits. Marker bytes are included in the existing
1 GiB resource budget and retired with the pool: shared source/private destination,
four bytes each per logical slot (eight bytes/slot total, max32KiB/pool).
Source words are initialized once to index+1 and never mutated. No per-write
allocation. It only
works outside render passes. RenderPassBackend.writeTimestamp still throws;
encoder timestamp calls during an open pass reject. No render state is silently
split or replaced. Native stage markers do not establish full Vulkan bottom-of-
pipeline barrier semantics or arbitrary per-draw timing.

Slots track Empty/Recorded/Submitted/Available/Failed. Pending reuse rejects;
new recordings invalidate old values. Cancelled slots become empty/reusable.
Counter sample buffers are now private. On first use per pool/batch, recording
allocates a shared eight-byte-per-slot resolution snapshot (max32KiB/pool,
512KiB/batch) in existing staging/resource budgets, plus one shared event/batch.
Before every marker, event signal/wait orders preceding GPU work. At submit,
a further signal/wait orders generation before a full zero-based GPU
resolveCounters blit into each snapshot. Every boundary uses a new increasing
value on this batch's event, including the first marker after prior transfers. Completion publishes only written slots
from the completed owned snapshots, checking zero/sentinels/signed range.
Snapshots/event retire with batch resources on completion/cancel. The internal
resolve stage adds no user operation or CPU wait. Query getters poll cached
values without waits/commands/consumption; arrays are owned. GPU/resolve failures
surface through existing submission/fence/drain and failed-slot reads. Batches
retain closed query pools; shutdown cancels pending samples/drains/closes pools.

DeviceInfo reports timestampPeriod1 ns only when actual counter/stage support is
present, otherwise0. The fifth run passed the period/clock checks; complete ABI16
acceptance still awaits the remaining async/boundary checks. Calibration
brackets five native sampleTimestamps calls with System.nanoTime and uses the
shortest JNI window's midpoint minus actual GPU time; no allocation/GPU wait.
Apple's counter guidance establishes this Apple Silicon nanosecond domain; the
prepared GPU test must prove the actual values/units/offset on this machine.

**Do not construct/activate FrontendGpuDevice/Tracy** until render-pass timestamps,
full profiling semantics, worker compilation/debug/game surface contracts are
complete. This is a query foundation, not a full profiler implementation.

The first user-run ABI16 gate failed its positive/order assertion, which lacked
actual values; zero versus reversed samples is not established by that log.
The original timestamp pass was empty. Dawn's primary Metal source documents
missing counter writes on empty blit encoders; the correction adds the real
marker operation and samples its end. Native zero/sentinel/range failures and
Java order/calibration/unit failures now print actual values. The new suite
checks retained marker bytes and cleanup. The second user run still failed:
slot2 passed native validity checks, then slot3 resolved as zero. The marker alone
was insufficient. GPU-private counters, explicit event ordering and GPU-resolved
snapshots now replace CPU single-slot resolveCounterRange. Actual driver cause
(writing versus resolution) is unproven; the new fix still needs a user rerun.
Async tests now expect marker+snapshot staging bytes during recording and exact
cleanup after completion/cancel. No zero/mock result is accepted as a PASS.

All headers/native/loader remain ABI16. Final strict Java/native build, signing,
JAR packaging and all test compilation pass. The first build's test-only builder
overload error was fixed to Optional<Vector4fc>; no lint disabled. DeviceInfoTest
now expects conditional timestampPeriod and invalid pool-size rejection rather
than the obsolete universal unsupported query/calibration expectations. No ABI16
tests were launched by the agent; all prior suites still precede the new one.

TimestampQueryTest follows ScissoredClearTest: real GPU samples/bytes, positive
ordered blit-boundary counters, JVM clock correlation and20ms diagnostic gap,
partial optional availability, snapshots/reuse/pending rejection/cancellation,
ownership/closed/stale/JNI/bounds, render-pass exclusion,32 async retained batches,
shutdown,4096 cancelled markers and16-pool cap. No mock/skip PASS if target support
is absent. See docs/research/timestamp-query-validation.md.
Backup: /tmp/metalmc-abi15-source. Validation inspection files query-contract,
backend-vulkan-VulkanQueryPool and frontend-TracyGpuProfiler live in build/reports;
the first inspection's misspelled Vulkan class was corrected separately.

The third run again produced slot3=0 despite private counters, GPU event ordering
and GPU resolution. All earlier suites passed. Production sampling cause is
still unknown. A standalone native tools/timestamp_probe.mm compares 12 actual
configurations: nonempty blit copies versus one-thread compute writes, start-only,
end-only and paired boundaries, shared/private counters, full GPU resolution,
plus full and single-slot CPU resolution for shared storage. Six distinct GPU
output words are checked independently of the counters; all 32 raw slots are
printed to expose shifted/omitted writes. It prints native calibration, mach
timebase and macOS26+ GPU timestamp frequency. It uses bounded diagnostic waits
only when explicitly run. The executable was strictly compiled and signed via
python3 tools/timestamp_probe.py --build-only; NO probe was launched by the agent.

Completed probe: full saved log inspected; all12 cases show positive ordered
timestamps and exact real workload bytes. Shared CPU-full/single results agree
with GPU results, including odd slots. Calibration CPU=GPU=54129839774250,
mach-timebase125/3, queryTimestampFrequency=24000000 ticks/sec. This does not
replace the Java clock/period acceptance checks. Counter/storage/CPU resolve
are functional for the probe; production used repeated zero fills at one offset.
Production now copies immutable distinct per-slot words, matching the working
probe workload. Async tests expect1040 resource bytes/520 staging bytes while
recording the first64+1-slot batch,512 resource bytes after drain, and exact
eight-bytes/slot marker allocation/cleanup at the pool cap. Full strict build,
signing, packaging and all test compilation pass; no tests launched by the agent.
Do not rerun the original probe unless new evidence justifies it.

Historical probe command (already completed):

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/timestamp_probe.py
```

The original raw probe log and preserved copy are complete; diagnostic VALID is
evidence, not ABI16 acceptance. Do not substitute CPU times or accept zeros.
Validated completion remains 44.1%. The user completed the strategy probe at
01:03:53: control/explicit descriptors INVALID; compute/event boundary VALID.
The selected event strategy is implemented before every marker with increasing
batch values, and before resolution with the next value. Maximum normal timeline
is 4096 markers plus resolution; cancellation releases the batch-owned event.
Strict compilation/signing/test compilation pass. Full acceptance remains manual.
Historical strategy command (already completed; no repeat needed):

```sh
python3 tools/timestamp_probe.py --interleaved-strategies
```

The completed production-sequence probe used four private-counter/end-
sample configurations compare8 versus32 slots, with/without the exact64KiB
unsampled upload and copy encoders between markers at slots2,3. Marker source/
destination sizes match the pool; source words are immutable index+1. Each encoder
has a separate autorelease pool, matching JNI call lifetimes. All result bytes
are compared inside a native completion handler versus after waitUntilCompleted;
distinct marker words and actual transfer payloads must match. The original
probe used32 samples with adjacent sampled passes and no callback comparison.
The loader was read: fresh temp extraction per process; no stable cached dylib.
The later strategy probe supports the event-boundary correction above; neither
probe grants complete acceptance credit.

Historical fifth acceptance command (already run; failed the stats assertion above):

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/smoke_test.py
```

After the later targeted stats correction, the full suite must show
`PASS: real GPU timestamp pools`, all prior suites and final overall PASS.
Inspect complete log/fix real failures/supply rerun commands. Preserve ABI16
evidence only after an actual complete PASS. Estimated completion remains44%.
No independent window repeat or Minecraft launch needed.

## Previously implemented/validated foundation

- Client-only Fabric initializer, opt-in `-Dmetalmc.probe=true`; otherwise no
  native loading. Platform-gated packaged extraction, owned MetalContext, opaque
  registry IDs, Objective-C++ ARC/autorelease pools and diagnostics. Offscreen
  triangle/readback waits are diagnostic-only. No backend selection mixin.
- Standalone MetalSurface retains a live SDL CAMetalLayer on macOS main thread,
  BGRA8 sRGB rendering, physical Retina sizing, max three frames in flight,
  skips inactive/zero-size/busy/missing drawables, propagates GPU errors and drains
  before view/window destruction. It is not a GpuSurfaceBackend adapter.
- MetalGpuBuffer: shared native buffers, UTF-16 labels, initial/partial uploads,
  detached Java staging mappings, readonly checks, bounds/usages/thread/lifetimes.
  256 MiB allocation, 1 GiB combined resource budget; single map/no texel buffers.
- Private textures: R8/RG8/RGBA8 UNORM, R32/RG32/RGBA32 FLOAT, RGBA16 FLOAT,
  D32 FLOAT; 2D/array/mips, native mip views and immutable samplers. Axis 8192,
  layers 256, cap 256 MiB/texture. Cubemaps/3D/remaining formats unsupported.
  Samplers preserve independent min/mag, linear mip interpolation, U/V repeat/clamp,
  max LOD and anisotropy 1..16. Contents require upload/clear before use.
- Async MetalCommandEncoder batches retain resources/staging after Java close.
  Three in-flight submissions; saturation backpressure. Buffer blits need distinct
  allocations/four-byte ranges. 4096 operations, staging 64 MiB, 1024 batch/fence
  IDs/context. CPU access rejects unsubmitted uses/drains submitted work.
- GPU fences track current submission including later commands; submit before wait.
  Nanosecond timeouts, early fence close, cancellation/shutdown drain work.
  Texture transfers handle regions/mips/layers and pixel-size row alignment;
  readback/copy contracts select layer zero. Full clears cover all mips.
  Owner-thread callbacks dispatch on submit/fence wait/pollCallbacks/shutdown.
- Transient arenas: aligned staging/GPU/mapped bump arenas, native completion-gated
  reuse, detached snapshots/generation tokens, default 1 MiB blocks, 64 MiB native
  pool and snapshots/encoder, 1024 allocations/submit; no escaped native pointers.
- RenderPassBackend: real full-area single-layer attachment passes, eight slots
  with holes, <=32 color bytes/pixel, D32, base mip, load/clear/store, debug groups
  up to64, bounded positive scissor and resource retirement. No nested passes.
- SPIR-V -> MSL 2.3 via installed LWJGL SPIRV-Cross: owned immutable reflection,
  fixed UBOs, set-zero combined 2D/array sampled shaders, push constants.
  Per-stage remaps UBO16–29, constants30, textures/samplers0–15; checks layouts.
  No storage/texel buffers, descriptor arrays, depth/MS/cubemap shaders, DrawIndex,
  complex interfaces or specialization. Coordinate options are explicit.
- Eager owner-thread MetalRenderPipeline compilation, retained D32/no-depth PSOs,
  one-use Pending, layout/state checks, no cache/worker/reload parity. Backend cap
  256 live/retained pipelines, two PSOs each, outside resource byte accounting.
  Per-stage uniforms/textures/samplers/constants retain snapshots/allocations.
  Feedback with current attachment storage rejects. Missing bindings reject draws.
- Direct/indexed/all four CPU multi layouts/indirect draws are GPU validated.
  UInt16/UInt32, signed baseVertex, instances/firstInstance, offset/transient slices.
  CPU multi parameters snapshot/preflight whole calls; max1024 rows. Indirect
  arguments use packed16/20byte GPU records with static preflight, no CPU reads.
  GPU-derived index/vertex/instance addresses remain caller invariants.
- All sixteen real vanilla vertex formats passed synthetic shader/pixel checks:
  float1/2/3/4, RGBA8_UNORM, RG16_SINT, RGBA8_SNORM(vec3/vec4), RGB32_SINT.
  Byte/short alignment/extents/strides/lifetimes checked. Topology/state/shader
  parity still incomplete (thick lines/fan/quads pending).

Historical complete PASS evidence is preserved in build/reports:
transient-memory (ABI6), render-pass (ABI7), shader-translation (ABI8),
pipeline-draw (ABI9), shader-resource (ABI10), indexed-draw (ABI11),
indirect-draw (ABI12), vertex-formats (ABI13), device-info (ABI14) and
scissored-clear (ABI15) smoke logs.
Two ABI13 missing-runtime/jemalloc failures and earlier setup/device-unavailable
failures remain preserved separately. The full installed 67-artifact macOS arm64
runtime resolver passed ABI13 and ABI14; no dependencies are bundled/downloaded.

## Independent presentation gate already passed

User ran python3 tools/presentation_test.py --exercise, supplied a triangle
screenshot and confirmed "funciono". Full log PASS: 862 submitted/completed,
273 skipped, peak2, zero pending; 2x Retina; resize/hide/minimize/fullscreen/
restore/lifetime checks. Last human-verified window used ABI2; resource-only
increments do not require a repeat. Evidence: docs/research/presentation-validation.md.
Raw sdl-presentation.log/json are last-run files. JSON visual_verification remains
pending (automated-only); separate human confirmation is recorded in docs.

Agent GPU/device availability has varied; SDL tools fail before surface work with
"The video driver did not add any displays". No established cause. Do not blame
sandbox/flags/initialization order as facts. User Terminal successes establish
standalone GPU gates, not in-game rendering.

## Next implementation work

1. Validate ABI16 in the user's Terminal and preserve complete evidence.
2. Expand remaining shader/state/resources/query contracts: render-pass timestamps/profiler,
   cubemaps/extra formats, extra copy cases, total memory accounting, vanilla
   pipeline/topology/coordinate parity, safe frontend worker compilation/debug.
3. Implement real GpuSurfaceBackend using the game's existing SDL/main-thread
   ownership; no second SDL runtime. FrontendGpuDevice currently cannot activate.
4. Add GpuBackend/opt-in exact-version selection mixin only when essential
   contracts are complete. Fallback only before backend-owned resources exist;
   never mix APIs. PreferredGraphicsApi.getBackendsToTry is audited selection;
   Window.handle is SDL, not GLFW; shader modules already supply SPIR-V.
5. Full vanilla gameplay/capture/parity/stress/benchmark gates. Defer Sodium/Iris/
   shader packs/MetalFX until parity. Original code; audit licenses before reuse.

tools/audit_backend.py generates checksum-verified signatures/coverage; update
its status strings with implementations/evidence so regeneration stays honest.
Do not copy synchronous diagnostic waits into gameplay. Existing coarse CPU
drain may need optimization; current budget excludes pipelines, drawables,
Java/diagnostic staging and is not total renderer memory.

## Environment/build limitations

Apple M5 MacBook Air,16GB,macOS27.2; arm64 Oracle JDK25.0.4.1, CLT,
Minecraft26.3, Fabric0.19.5, installed LWJGL3.4.3 SDL/shaderc/SPVC/natives.
Default game dir: /Users/thiago/Library/Application Support/minecraft.
Build/test/audit support --minecraft-dir. python3 tools/build_offline.py builds
without tests/downloads/profile changes, compiling exact unobfuscated26.3 APIs.

Loom1.17.4/Gradle9.6.0 configured but never validated; no wrapper/cache/downloads
available previously (DNS failure). Review Loom packaging before mixin/game
selection. Prior isolated Fabric launch recognized the initializer, then probe
found no device/vanilla SDL no displays; title screen never initialized. Normal
worlds/mods untouched. This does not negate later standalone Terminal passes.
