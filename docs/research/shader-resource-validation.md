# Shader resources and push constants — JNI ABI 10

Implemented 2026-10-03. **Complete user-run ABI 10 GPU suite PASS on Apple M5
at 19:18:37–43, with Metal API validation and JNI checks.** The user reported
exit code 0; the agent inspected the complete saved log, including the new
shader-resource PASS and every prior suite. Preserved as
`build/reports/shader-resource-smoke-pass.log`; this is historical ABI 10
evidence. The current raw `build/reports/native-smoke.log` is the newer ABI 11
complete PASS, also including this suite. Strict offline
Java/native build, JNI generation, arm64 signing, packaging and all test
compilation also succeed. No tests were launched by the agent.
Minecraft's backend remains unchanged.

## Exact frontend contract

Read-only `javap -p -c` inspection of the checksum-verified installed 26.3 JAR
established that `frontend.shaders.PipelineBuilder` rewrites shader descriptors
to set zero and the index of the `CreateInfo.uniforms()` declaration.
`FrontendRenderPass` forwards those indices with either `GpuBufferSlice` or
`TextureViewAndSampler`. Vulkan's pass clears its uniform state on every
`setPipeline`, including rebinding the same pipeline. Frontend push constants
require at least the declared size in the caller's remaining byte range.
Signatures for `BindGroupLayout.UniformDescription`, `UniformType` and
`TextureViewAndSampler` are now included in the generated interface audit.

`MetalRenderPipeline` checks each active descriptor against that exact set-zero
index and declaration type. A descriptor may be used by either or both stages;
its Metal slots come from each independent translation table. Blocks shared
between stages can have different slots and sizes; a bound slice must fit the
larger active block. Incompatible image dimensions between stages fail at
pipeline creation. Incoming module/reflection ownership stays unchanged.

## Encoding and lifetime

- `setUniform(index, GpuBufferSlice)` requires `USAGE_UNIFORM`, a live same-device
  unmapped buffer, a positive in-bounds slice, offset divisible by four, and
  enough bytes for every active block at that index. Same-encoder transient
  slices use the existing generation/forgery checks. Metal uniform buffer slots
  remain 16–29 per stage, separate from vertex slots 0–15.
- `setUniform(index, TextureViewAndSampler)` binds the native mip-restricted view
  and sampler, requiring live same-device resources, `TEXTURE_BINDING` usage,
  color aspect, and a matching 2D/2D-array shader dimension. Texture/sampler
  slots remain 0–15 in each independent stage. Sampling any storage attached
  to the current pass is conservatively rejected, even at a different mip.
- `setUniform(index, null)` clears the binding. Active missing uniforms reject
  a positive draw before GPU encoding; zero-count draws remain no-ops.
  Setting a pipeline clears all shader binding flags and constants state.
  Vertex binding state retains its existing pass-scoped behavior. Each new
  pass must bind its own state. Inactive declarations may be bound/cleared;
  only active descriptors are required by native draws.
- `pushConstants(ByteBuffer)` snapshots exactly the declared prefix of the
  remaining range without changing the caller's position/limit. Heap, direct
  and read-only source buffers are accepted through Java-owned direct copies.
  Native shared staging holds an independent snapshot and binds slot 30 in each
  stage that uses a push block. Subsequent source mutation, pushes or draws
  cannot rewrite earlier snapshots. A positive declared size must be pushed
  before a positive draw, even if the shader optimized out its block.
- Batches explicitly retain uniform buffers, parent texture allocations, native
  views, samplers, pipelines and push staging through completion. Closing Java
  wrappers prevents rebinding but preserves already recorded/bound draws.
  Rebinding keeps earlier resources alive for earlier commands. Parent storage
  remains budgeted and CPU diagnostics reject recorded, unsubmitted uses.
  Cancellation discards commands, clears retained references and releases staging.
- No new diagnostic wait enters these binding/draw methods. The existing three
  submissions, saturation backpressure, completion fences and conservative
  CPU buffer drain remain in use. The async stress fixture uses ordered uploads,
  avoiding CPU buffer initialization that would drain earlier submissions.

Apple's [constant-buffer alignment tables](https://developer.apple.com/metal/Metal-Feature-Set-Tables.pdf)
list four-byte constant offsets for Apple GPU families. This arm64-only initial
subset requires four-byte offsets; eventual DeviceInfo must report measured
frontend limits. Binding uses Apple's
[vertex buffer API](https://developer.apple.com/documentation/metal/mtlrendercommandencoder/setvertexbuffer(_:offset:at:)),
[fragment texture API](https://developer.apple.com/documentation/metal/mtlrendercommandencoder/setfragmenttexture(_:at:))
and the corresponding per-stage sampler/buffer methods.

## Limits and remaining work

At most 64 uniform declarations, 14 uniform blocks per stage (64 KiB each),
16 combined sampled images/samplers per stage, and one push block per stage.
The shared push declaration is 4..4096 bytes, divisible by four; every reflected
push block must fit it. Native layout arrays validate slot namespace bounds,
stage/kind/index/type, duplicates, block sizes and image dimensions too.
Existing batch limits are unchanged: 4096 operations, aggregate context staging
64 MiB and accounted buffer/texture/staging storage 1 GiB. Push snapshots count
against both staging and resource budgets; views/samplers/pipeline object bytes
remain outside that GPU allocation total. Native pipeline cap remains 256.

Sampled shaders require float-component, non-depth/non-MS 2D or 2D-array images.
Supported sampled color formats are the seven existing non-depth formats.
Standalone translation still supports separate image/sampler descriptors and
arbitrary sets, but backend pipelines reject them because this frontend binding
contract supplies combined descriptors at set zero. Texel buffers, storage,
descriptor arrays, depth comparison/cubemaps and other unsupported translator
features still fail explicitly. Indexed/multi/indirect draws, timestamps, full
vanilla parity, frontend worker compilation and game selection remain pending.

## Completed user-run GPU gate

`ShaderResourceTest` ran after `PipelineDrawTest` in the complete smoke suite
and passed these checks:

- Real vertex and fragment resources with different slots for the same uniform
  and image index, uniform slice offset, two simultaneous sampled mip views,
  ordered texture uploads before the pass and asymmetric scissor pixel checks.
- Push snapshots from read-only sources with nonzero positions, mutation after
  recording, per-stage gains and multiple pushes/UBO rebindings in one pass.
- Seven sampled color formats, array layer one at view-relative mip zero with
  adjacent mips/layers deliberately different, and format-default components.
- Resource close before submit/draw, allocation counters and completion
  retirement; 16 asynchronous UBO/push and 16 sampled texture/view/sampler
  submissions with close-before-submit and final GPU pixel checks.
- Same-encoder transient uniforms and expired generations, missing/cleared
  resources, pipeline rebinding, wrong indices/types/sizes/alignment/usage,
  mapping/device/thread ownership, feedback/dimension/aspect rejection,
  cancellation preserving initialized target bytes, and ended-pass use.
- Invalid declarations (set/type/size/duplicates/texel/separate resources), JNI
  metadata/direct-buffer/stale-ID boundaries and no registered pipeline leaks.

The user ran in their separate normal macOS Terminal (no repeat needed for this exact increment):

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/smoke_test.py
```

The runner rebuilt first and executed the complete suite with Metal API validation
and `-Xcheck:jni`, loading the native library from the packaged JAR. The new
`PASS: per-stage uniform/texture/sampler bindings` line and the overall final
PASS are present, together with every prior suite. No Metal/JNI error was logged.
The previously recorded LWJGL Unsafe deprecation warning appeared without
preventing the PASS. This verifies the bounded resource subset, not full vanilla
shader parity, game integration or performance. The ABI 9 PASS remains preserved
separately. No independent visible-window repeat or Minecraft launch is needed.

Project estimate now **38.7% ~=39%**, with shaders/pipelines/passes credited at
35% (up from 25%); gameplay and parity credit remain zero. Next: indexed,
multi and indirect draws and broader frontend compatibility.
