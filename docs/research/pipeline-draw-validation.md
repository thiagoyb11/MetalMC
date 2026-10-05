# Initial backend pipelines and direct draws — 2026-10-03

**JNI ABI 9: complete user-run GPU suite PASS on Apple M5 at 18:36:06–09.**
The user confirmed all tests passed; the agent inspected the complete saved log.
Metal API validation and JNI checks were enabled. Strict build, signing,
packaging and test compilation also succeeded. The complete log is preserved
as `build/reports/pipeline-draw-smoke-pass.log`. The agent did not launch tests.
Minecraft's backend is unchanged.

## Contract and ownership

`MetalRenderPipeline implements BackendRenderPipeline`; the development device's
`compilePipeline(CreateInfo)` now delegates to its owned context. Compilation is
eager and synchronous on the context's creating thread. The returned `Pending`
can be consumed once on that thread. The context owns unconsumed pipelines too.
Incoming real `SpvModule` bytes are translated immediately; modules can close
when compilation returns, without invalidating the native PSOs. Their reflection
cache is not changed. This owner-thread development contract is insufficient
for the eventual frontend worker compilation path; no game activation is enabled.

Each native object owns real Metal PSOs with and without a D32 depth target,
plus depth state for each case. `setPipeline` selects the matching variant,
requires exact color formats/holes and the same context, and records depth,
bias, cull, front winding and fill state. No-depth passes disable depth testing
and writes. Blend operations/factors and independent RGBA write masks are mapped
explicitly. MSL 2.3 and disabled fast math match the translator diagnostic.

`setVertexBuffer` validates normal or same-encoder transient slices, usage,
alignment, device, bounds, liveness and mapping state. Native batches retain
pipelines and buffers until cancellation or completed submission, even when Java
objects close after binding. Switching state keeps earlier objects alive too.
Closing a bound pipeline/buffer prevents rebinding it; already bound state can
draw using retained storage. CPU buffer access rejects unsubmitted bindings.
Ending a pass clears its current bindings; the next pass must bind again.

`draw(vertexCount, instanceCount, firstVertex, firstInstance)` uses exactly that
26.3 argument order, inspected in the installed Vulkan backend bytecode. Native
validation checks every required vertex/instance slice with 64-bit arithmetic,
including the first element and last attribute extent. Zero counts record no
draw; negative arguments fail. No new synchronous wait enters the draw path.
Existing async submission/backpressure, retirement and fences remain in use.

Pipeline accounting counts live and batch-retained objects (two PSOs per object),
with a context cap of 256. It does not count GPU pipeline bytes in the 1 GiB
buffer/texture/staging budget. Operation limits remain 4096 per batch.

The audit regenerates exact contract signatures from the checksum-verified game.
Apple describes the corresponding native [pipeline descriptor](https://developer.apple.com/documentation/metal/mtlrenderpipelinedescriptor),
[vertex layout step function](https://developer.apple.com/documentation/metal/mtlvertexbufferlayoutdescriptor/stepfunction)
and [direct instanced draw API](https://developer.apple.com/documentation/metal/mtlrendercommandencoder/drawprimitives(type:vertexstart:vertexcount:instancecount:baseinstance:)).

## Explicit initial limits

- Exactly one vertex and one fragment shader; no shader resources, uniform
  declarations or push constants. Existing translation of those resources remains
  available independently; shader bindings are the next increment.
- Vertex buffer slots/attribute locations 0..15, stride 4..2048 (multiple of four),
  step rate 0 (vertex) or 1 (instance). Float scalar/2/3/4 and normalized RGBA8
  attributes only; reflected active inputs must match formats and components.
- Up to eight color slots including holes, eight supported texture formats
  excluding D32 for color, combined color size <=32 bytes/pixel. Blending on
  32-bit float targets and constant blend factors fail explicitly.
- Fixed CCW front winding, optional back-face culling, fill/wireframe, finite
  depth bias and all eight comparisons are mapped. The passing pixel checks
  exercise depth comparison/writes and no-depth behavior, ordinary alpha blend,
  masks and scissor. They do not establish all blend/compare/cull/wireframe
  combinations or vanilla coordinate parity. Depth/Y translation options remain
  explicit; development defaults are Vulkan depth/no Y flip.
- Points, debug lines/line strip, triangles/triangle strip are mapped. Thick
  `LINES`, fan and quads need frontend expansion or further implementation.
- Full-area, single-layer attachment passes remain required. Indexed, multi,
  indirect draws, uniforms, sampled textures, push constants and timestamps
  still fail explicitly. No pipeline cache, reload parity or frontend activation.

## Completed user-run GPU validation

`PipelineDrawTest` now runs after `ShaderTranslationTest` in the complete smoke
suite. It compiles real shaderc fixtures into exact frontend modules, closes
modules before drawing, and passed these actual GPU checks:

- Full-target RGBA8 pixels from float3 position and normalized RGBA8 color,
  nonzero buffer slice offset and `firstVertex`, including slice bounds.
- Closing PSOs and buffers before submit; retained counts/bytes must return to
  zero after fences. Sixteen asynchronous draw submissions verify the final
  pattern and retirement without per-submit waits.
- Alpha blending with independent write masks, asymmetric scissor and disable.
- D32 less-than comparison/write and readback of every depth value; the same
  wrapper drawn without depth must select the other PSO and disable depth state.
- Per-instance float4 data, nonzero `firstInstance`, multiple instances and
  transient vertex slices; stale generations must fail on the next submission.
- Cancellation preserves initialized target bytes. Wrong device/usage, missing
  bindings, overflow, stale IDs, foreign thread and malformed native arrays fail.
- Resource-bearing shaders reject before creating pipelines; failed creation
  cannot leak registered native state. Device factory and unconsumed Pending
  cleanup are exercised.

Command executed in the user's separate normal Terminal (no repeat needed for this exact increment):

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/smoke_test.py
```

It builds first and runs the complete real-GPU suite with Metal validation and
`-Xcheck:jni`, loading the dylib from the packaged JAR. The new line
`PASS: retained Metal pipelines` and the overall final PASS were present; the
user reported all tests passed. Every prior suite also passed. Output is saved
to `build/reports/native-smoke.log`. The ABI 8 result remains preserved in
`build/reports/shader-translation-smoke-pass.log`.

The user-run result establishes initial pipeline/direct-draw GPU pixels and
lifetimes in addition to strict build success. Installed LWJGL printed its
recorded Unsafe deprecation warning; the suite continued and passed.
Completion is now approximately **37%** (36.5% weighted, shader/pipeline/pass
stage 15% ->25%), without game parity credit. No backend activation, vanilla
shader parity, full state/topology coverage or performance claim follows.

Next: per-stage shader resources (uniform buffers, textures/samplers, push
constants), indexed/multi/indirect draws and wider vanilla pipeline compatibility.
No further test is needed for this exact validated increment.
