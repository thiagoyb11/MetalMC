# Indexed and CPU multi-draw encoding — JNI ABI 11

Implemented and GPU validated 2026-10-03. **The complete user-run ABI 11 suite
passed on Apple M5 at 19:41:23–31 with Metal API validation and JNI checks.**
The user confirmed all tests pass; the agent inspected the entire log and
preserved it as `build/reports/indexed-draw-smoke-pass.log`. The current raw log
`build/reports/native-smoke.log` contains that ABI 11 result. All prior suites
also passed. Only the known LWJGL Unsafe deprecation warning remains.
Strict Java/native compilation, JNI generation, arm64 signing and packaging
succeed. No tests were launched by the agent.
Minecraft keeps its vanilla backend; frontend activation remains disabled.

## Contract and implementation

Read-only bytecode inspection of the installed checksum-verified 26.3 frontend,
Vulkan and OpenGL backends established these exact argument layouts:

| Method | Frontend parameters |
| --- | --- |
| `drawIndexed` | indexCount, instanceCount, firstIndex, signed baseVertex, firstInstance |
| Indexed interleaved multi | IntBuffer rows (firstIndex, indexCount, signed baseVertex), common instanceCount/firstInstance, drawCount |
| Indexed separate multi | PointerBuffer of index **byte offsets**, IntBuffer indexCounts, IntBuffer signed baseVertices, drawCount; one instance, firstInstance zero |
| Direct interleaved multi | IntBuffer rows (firstVertex, vertexCount), common instanceCount/firstInstance, drawCount |
| Direct separate multi | IntBuffer firstVertices, IntBuffer vertexCounts, drawCount; one instance, firstInstance zero |

The Vulkan row structures are documented by Khronos for
[interleaved indexed multi-draw](https://docs.vulkan.org/refpages/latest/refpages/source/VkMultiDrawIndexedInfoEXT.html).
The OpenGL bytecode forwards separate pointer values to element offsets,
not host-memory vertex pointers; MetalMC only reads their numeric values and
never dereferences them. Normal installed `IndexType.SHORT`/`INT` map to unsigned
16/32-bit Metal indices. `firstIndex` is converted to a byte offset with 64-bit
arithmetic, and negative baseVertex remains signed. Apple's corresponding
[indexed instance draw method](https://developer.apple.com/documentation/metal/mtlrendercommandencoder/drawindexedprimitives(type:indexcount:indextype:indexbuffer:indexbufferoffset:instancecount:basevertex:baseinstance:))
is used without GPU waits or CPU index-buffer inspection.

`setIndexBuffer` binds a whole normal same-device `MetalGpuBuffer` at byte offset
zero, requiring INDEX usage, an unmapped live wrapper and at least one element.
Transient index wrappers reject explicitly: this method has no slice offset or
length, so it cannot describe their reserved subrange. Index state survives
pipeline switches and resets at pass finish/cancellation, like vertex state.
Batches explicitly retain each bound index allocation through completion, even
if Java closes the buffer before the draw or submission. Existing CPU hazard
checks protect bound index buffers; closed wrappers cannot be rebound.

Native direct/indexed/multi draws now share state/range validation. Positive
draws require a pipeline, every active shader resource and declared constants,
required vertex buffers, an index buffer for indexed operations, and in-bounds
instance ranges. `(firstIndex + indexCount) * indexBytes` must fit the whole
index allocation; integer arithmetic cannot wrap. Indexed vertex bindings must
fit the minimum element implied by a nonnegative baseVertex and each required
attribute's extent. Zero counts/instances skip drawing after pipeline/range sign
checks, without requiring remaining bindings.

**Index contents and their resulting vertex addresses remain a caller invariant.**
They can come from earlier GPU uploads/copies, so reading shared CPU contents at
recording time would see the wrong version. This increment does not inspect,
clamp or validate data-dependent vertex indices or negative-base underflow.
The frontend/game must supply valid indices into each vertex slice. Known index
ranges and instance bounds are checked; this is not full GPU input robustness
or primitive-restart/topology parity. Tests use valid triangles and signed bases
whose effective vertex addresses stay within their slices. Remaining broader
vanilla/input validation is still pending before activation.

## Multi-draw ownership and bounded behavior

All four frontend CPU multi formats accept their current remaining range;
Java reads absolute values into fresh primitive arrays without advancing any
positions/limits. Heap/direct/read-only IntBuffers are supported. JNI copies
those arrays into owned native vectors before encoding; input mutation or
closure after return cannot alter recorded draws. Separate index byte offsets
must be nonnegative, aligned to the bound index type and fit the initial signed
firstIndex range (actual native buffer bounds are also checked).

Native encoding expands each positive row to an ordered ordinary Metal draw
inside the current render pass. It preflights **all rows and the full operation
cost before the first draw**, so an invalid late row or batch-cap failure
cannot encode an earlier row. No pipeline/resource state changes between rows.
Multi calls support 0..1024 rows; each positive row counts toward the existing
4096-operation batch limit. No GPU staging or persistent registry object is
needed for CPU argument snapshots.

`DrawIndex`/`gl_DrawID` shaders now reject during translation, because expanding
one multi-call to ordinary draws cannot preserve that builtin. A dedicated
shader/resource path is required before supporting it. At ABI 11, GPU indirect
argument buffers remained unsupported; the subsequent ABI 12 implementation is
documented in [indirect-draw-validation.md](indirect-draw-validation.md) and awaits
its new manual GPU gate. Timestamps and transient
whole-buffer index binding remain unsupported. No DeviceInfo feature claims, pipeline cache,
worker compilation, game selection or backend activation were added.

## Completed user-run GPU gate

`IndexedDrawTest` runs after `ShaderResourceTest` in the complete smoke suite:

- Real UInt16/UInt32 pixel checks with nonzero firstIndex, including two-byte
  alignment, positive and negative baseVertex, nonzero vertex slice offset,
  ordered index uploads and source mutation after recording.
- Close-before-draw/submit, CPU mapping hazards, shader/vertex/index retention
  and completed allocation counters.
- Nonzero firstInstance for indexed, interleaved indexed multi and direct multi
  with native per-instance attributes, UBOs/push constants and transient vertices.
  Missing resources/constants/index state, oversized instance ranges and expired
  transient vertex generations reject.
- All four multi formats, position/limit preservation, heap/direct/read-only
  parameter ranges, separate byte offsets and input mutation after return.
- Invalid late records preserve clear-only pixels for direct and indexed multi.
  A three-draw call with only two batch operations remaining must reject as a
  whole. Zero rows/counts and the 1024-row bound are exercised.
- Device/usage/size/type/thread/closed-pass checks, unsupported transient indices,
  cancellation preserving old pixels, native arrays/type/range/stale IDs,
  `DrawIndex` rejection without registered state, and 16 asynchronous indexed
  submissions with closed PSOs/vertex/index buffers and final GPU pixels.

Run in your separate normal macOS Terminal:

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/smoke_test.py
```

The complete run passed `PASS: UInt16/UInt32 indexed pixels` and the final
overall PASS. No repeat of this exact increment, independent visible-window
test or Minecraft launch is needed. The validated estimate is now
**40% (39.8% weighted)**. Next: GPU indirect argument buffers and wider frontend
compatibility; game activation and parity remain pending.
