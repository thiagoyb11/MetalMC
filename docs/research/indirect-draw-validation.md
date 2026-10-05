# GPU indirect draw arguments — JNI ABI 12

Implemented 2026-10-03. **Complete user-run ABI 12 GPU PASS on Apple M5 at
20:18:57–20:19:07**, Metal API validation and JNI checks. The user confirmed all
passed; the complete saved log includes the indirect PASS and every prior suite.
Evidence preserved as `build/reports/indirect-draw-smoke-pass.log`. Strict build,
JNI generation, arm64 signing, JAR packaging and test compilation passed.
No tests were launched by the agent. Current ABI is 13 with a new vertex-format
gate pending; this document records validated ABI 12 behavior.
Minecraft's selected backend remains active; game activation is disabled.

## Exact layout and static bounds

Read-only inspection of the installed checksum-verified 26.3 Vulkan backend
shows `drawIndirect(slice, count)` forwarding the buffer and slice offset with
`VkDrawIndirectCommand.SIZEOF` stride, and `drawIndexedIndirect` using
`VkDrawIndexedIndirectCommand.SIZEOF`. Metal's installed SDK declares compatible
layouts:

| Operation | Record bytes | Packed fields |
| --- | ---: | --- |
| Direct | 16 | uint32 vertexCount, instanceCount, firstVertex, firstInstance |
| Indexed | 20 | uint32 indexCount, instanceCount, firstIndex; int32 baseVertex; uint32 firstInstance |

The implementation uses Apple's
[direct argument structure](https://developer.apple.com/documentation/metal/mtldrawprimitivesindirectarguments),
[indexed argument structure](https://developer.apple.com/documentation/metal/mtldrawindexedprimitivesindirectarguments)
and native indirect render methods. All struct sizes/field offsets are checked
by native `static_assert`. Signed baseVertex stays signed. The SDK's
`MTLRenderCommandEncoder.h` documents four-byte argument offsets; 20-byte indexed
stride is valid. Whole normal index bindings remain at offset zero; firstIndex
comes from the GPU record. Both UInt16 and UInt32 indices are supported.

Java requires a live unmapped same-device slice with INDIRECT_PARAMETERS usage,
four-byte offset alignment and length sufficient for `count * stride`. It accepts
normal buffers and same-encoder transient reservations using the existing
encoder slice validation, including usage, reserved range and generation.
JNI independently checks buffer/device, allocation range, alignment, signed
count and full argument span using non-overflowing arithmetic. A valid slice
may contain extra trailing bytes; only the requested complete records are read.

Each call accepts 0..1024 records. A zero CPU record count still requires a
pipeline and valid live slice, but no other bindings and no new retention or
operation. A positive record count requires the active shader descriptors,
declared push constants, every required vertex binding with its minimum element
extent, and an index buffer for indexed draws. These fixed checks and the full
operation cost happen before encoding any record. The entire call fails if it
would exceed the existing 4096-operation batch cap. Each requested record costs
one operation, including those whose GPU vertex/index count or instance count
is zero; recording cannot know which records will become no-ops.

## Execution and ownership

The batch records an ordered native Metal indirect draw for each record. GPU
argument bytes are consumed at execution after earlier queued uploads/copies,
without CPU reads, new staging or a GPU wait. This is deliberately different
from the CPU multi-draw snapshots in ABI 11. The ordinary upload command still
snapshots the caller's ByteBuffer; mutating that source after upload does not
change the queued transfer.

The batch explicitly retains each argument allocation through GPU completion
or cancellation, with recorded-use accounting for CPU mapping hazards. Existing
vertex/index/pipeline/shader resources have the same retention. Closing Java
wrappers after recording is safe; rebinding a closed argument wrapper rejects.
Transient reservations remain valid only for their owning encoder/generation;
the underlying storage retires with the submission before reuse.

**All GPU-provided draw ranges and resulting vertex/index/instance addresses
remain caller invariants.** Static validation cannot inspect record contents,
which can be produced by prior GPU transfers. It checks the argument storage
span and minimum fixed binding extents, but does not validate/clamp GPU counts,
first indices/vertices/instances, signed-base underflow or index contents.
Fixtures supply valid records and effective vertex addresses. No complete
input robustness, full vanilla shader/state parity or game rendering is claimed.

`DrawIndex`/`gl_DrawID` shaders remain explicitly rejected because expanding
records into distinct native draw calls would change that builtin. No indirect
command buffers, compute dispatch, count-buffer draw extension, timestamps,
pipeline-cache/worker compilation or new DeviceInfo feature claims are added.

## Completed user-run GPU gate

`IndirectDrawTest` follows `IndexedDrawTest` in the complete suite:

- Direct and UInt16/UInt32 indirect pixel checks, offset four, 16/20-byte strides,
  firstIndex, positive/negative baseVertex, nonzero vertex slice and zero GPU
  counts/instances. Separate scissor regions verify the selected geometry.
- Ordered buffer upload then GPU copy into arguments, source mutation after
  upload, mapping hazards, closed resource retirement and allocation counters.
- Nonzero firstInstance with per-instance attributes, transient argument/vertex/
  uniform slices and push constants; missing descriptors/constants/index state,
  pipeline rebinding, foreign encoders and expired generations.
- Usage/device/thread/map/liveness, alignment, negative/overflow/short ranges,
  zero CPU count, 1024 GPU-zero records and the count cap. Invalid argument spans
  and a three-record call with only two batch slots left preserve clear-only
  pixels, establishing static preflight atomicity.
- Cancelled work preserves prior pixels; raw JNI repeats range/device/count/
  stale-batch checks. Sixteen asynchronous alternating direct/indexed submissions
  close arguments, PSOs and vertex/index buffers before completion, verify final
  pixels and zero in-flight/resource accounting. No CPU buffer initialization
  drains the queue during that stress loop.

Run in the user's separate normal macOS Terminal:

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/smoke_test.py
```

The run printed `PASS: GPU indirect 16/20-byte arguments`, every prior PASS and
the final overall PASS. The entire log was inspected and preserved. No repeat
of this exact increment, window test or Minecraft launch is needed. Validated
completion is **41% (40.9% weighted)**, with the shader/pipeline/pass stage at 45%.
