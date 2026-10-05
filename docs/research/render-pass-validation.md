# Attachment render passes — 2026-10-03

**Native ABI 7: complete GPU smoke PASS in the user's normal Terminal on
Apple M5 at 16:46:15–16, with Metal API validation and JNI checks.** The user
supplied the complete output and the agent read the same saved log. Strict Java,
generated JNI/native arm64 compilation, signing, packaging and all test sources
passed too. No test was launched by the agent.

The PASS includes all prior suites and `RenderPassTest`: actual GPU attachment
pixels for all eight formats, base mips, load/clear/store, multiple attachments,
state/lifetime boundaries, cancellation and retirement. Minecraft's vanilla
backend remains active; no shader translation or drawing is claimed.

The user's first ABI 7 run at **16:35:09** found Apple M5 and passed buffer
allocation/mapping, texture resources and the 100-submission buffer exercise.
It then stopped in `CommandEncoderTest` at an obsolete assertion expecting
`UnsupportedOperationException` from `createRenderPass(null)`. The implemented
pass constructor correctly rejects a missing descriptor with
`NullPointerException`; the assertion has been updated. No new render-pass GPU
checks executed in that run. The failure is preserved in
`build/reports/render-pass-smoke-obsolete-assertion.log`. This failure preceded the successful final run.

The repeat at **16:39:42** passed all previous resource, command and transient
suites, then reached `RenderPassTest` and failed loading FastUtil's
`ReferenceList`. Direct bytecode inspection confirms that the game's real
`RenderPassDescriptor` constructor wraps attachments with `ReferenceArrayList`
and `ReferenceLists.unmodifiable`. The standalone runner now resolves the
installed game's **FastUtil 8.5.18** from its version metadata and includes it
in Java compile/test/runtime classpaths. Gradle's test runtime also declares it.
The installed JAR exists; no download or mod bundling was added. The failure is
preserved in `build/reports/render-pass-smoke-missing-fastutil.log`. The final 16:46 run then passed the complete suite.

## Implemented subset

`MetalCommandEncoder.createRenderPass` returns an initial `RenderPassBackend`
adapter and opens a real Metal render command encoder in its current resource
batch. `submitRenderPass` ends that encoder; it does **not** commit a submission.
`submit` subsequently commits the batch through the existing asynchronous path.
Transfers before and after a pass preserve recording order.

- Up to eight color slots, including unused slots, with a conservative combined
  limit of 32 color bytes per pixel. Optional D32 depth attachment; depth-only
  passes are supported. Attachment views select their parent's base mip; only
  that mip is addressed, even when the view exposes further mips.
- Matching attachment pixel dimensions, single-layer textures and a full-size
  render area starting at (0,0) are required. Only `RENDER_ATTACHMENT` usage is
  required for an attachment, rather than additionally requiring copy usage.
  Existing eight texture formats remain the supported subset.
- Present clear values select `MTLLoadActionClear`; absent values select
  `MTLLoadActionLoad`. Color and depth select `MTLStoreActionStore` explicitly.
  Clear values are captured before native encoding. Textures still require
  initialization before a load pass; allocation does not define their contents.
- Native attachment storage remains retained and budgeted through recording and
  GPU completion even if Java closes the view or texture during the open pass.
  No new resource registry is needed: the pass belongs to the existing batch.
- Native UTF-16 pass/debug labels, balanced debug groups with maximum depth 64,
  positive scissor rectangles within the full render area, and viewport setup.
  Scissor changes do not constrain attachment load clears. No draw/scissor image
  parity or shader coordinate-convention parity is established.
- Nested passes, encoder transfers/clears/fences/submission during an open pass,
  and commands on finished/cancelled passes fail explicitly. Before opening a
  pass all transient mappings must be closed; saved transient allocator objects
  also reject new allocations while the pass is open. Full backend activation
  remains disabled.
- Closing an encoder/context cancels the batch, unwinds native debug groups and
  ends its open native render encoder without committing. Normal pass finish
  requires the caller to pop its groups. Java and native guards validate bounds,
  device/usage/aspect/mip ownership, stale IDs and duplicate color subresources.
- The existing 4096-operation batch cap includes pass starts, debug pushes and
  scissor changes. Group pops and pass finish remain legal at that cap so a pass
  can always be ended before submission. Existing submission caps and resource
  budgets are unchanged.

The installed Minecraft 26.3 JAR was inspected directly with `javap`: descriptor
attachments carry a `GpuTextureView` and optional clear value, color slots may
be null, and `RenderArea.fillsTexture` refers to view-relative mip zero. Audit
signatures now include these descriptor types. Load/store semantics follow
Apple's [load action documentation](https://developer.apple.com/documentation/metal/mtlrenderpassattachmentdescriptor/loadaction)
and [store action documentation](https://developer.apple.com/documentation/metal/mtlrenderpassattachmentdescriptor/storeaction).

Shader translation, pipeline compilation, uniforms/constants, vertex/index
bindings, all direct/multi/indirect draws and timestamps remain explicit
unsupported operations. Partial render areas, arrays and empty scissors are
also unsupported in this initial pass subset. This increment does not implement
shader-based scissored texture clears or activate Minecraft's Metal backend.

## Validated GPU checks

`RenderPassTest` is included in `NativeSmokeTest` and compiled with fatal
warnings. The successful run verified:

- Real GPU load preservation and format-correct clear/store for all eight
  formats, base-mip views spanning two mips and isolation of adjacent mips.
- Repeated passes in one submission, color slots 0/2 with a hole and depth,
  and eight simultaneous RGBA8 color attachments.
- Full attachment clears despite recorded scissor changes, debug groups and
  stale finished-pass rejection. Raster scissor behavior cannot be tested yet.
- State errors, foreign devices/threads, aspect/size/usage errors, arrays,
  empty attachments, nine slots, invalid clear values, outstanding transient
  mappings, saved transient allocator guards and cancellation preserving bytes.
- Closing a texture/view while its pass is open, retained allocation counters,
  ordered pass-to-buffer readback with byte checks, completed resource retirement,
  and context shutdown with an unfinished debug group/pass.
- Direct JNI array shapes, bad mips/sizes/devices/stale IDs, nested passes,
  active-pass submit/transfer rejection, debug balancing and scissor bounds.

`python3 tools/build_offline.py` succeeded for Java, generated JNI headers,
Objective-C++ arm64 compilation, ad hoc signing, JAR packaging and all test
sources. LWJGL core is now a compile-only main dependency for the exact
`PointerBuffer` signature; offline compilation/runtime resolves the installed
game's core JAR. No LWJGL dependency is bundled in the mod.
The game's installed FastUtil is also required at runtime by its descriptor
implementation and is resolved before the offline build begins; no copy is
packaged in the mod.

The user ran in their separate Terminal:

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/smoke_test.py
```

Complete raw evidence: `build/reports/native-smoke.log`, also preserved as
`build/reports/render-pass-smoke-pass.log`. Earlier ABI 6 evidence remains in
`build/reports/transient-memory-smoke-pass.log`; both failed setup runs are
preserved under the filenames above. No repeat is needed for this exact
increment. No visible-window repeat or Minecraft launch is required by this
attachment-only step.

The overall weighted completion estimate is now **32.1% ≈ 32%**. This step
credits the shaders/pipelines/render-pass category at 5% (1.1 project points),
up from the prior 31% total. Shader translation and actual drawing remain the
large outstanding part of that category.
