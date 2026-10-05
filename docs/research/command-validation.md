# Async buffer commands and fences — 2026-10-03

This buffer increment introduced JNI ABI **5** (current ABI is **6**). It adds the first exact-26.3 `CommandEncoderBackend` subset.
`MetalGpuDeviceBackend.createCommandEncoder()` returns `MetalCommandEncoder`.
No backend is registered and the initializer still leaves vanilla rendering active.

## Implemented behavior

- `writeToBuffer`: copies the remaining Java bytes into owned native shared
  staging immediately, preserving position/limit. GPU execution copies that
  snapshot into the destination in command order; it does not write directly
  into an in-use destination's CPU memory.
- `copyToBuffer`: validates usages, allocation/slice bounds, device/thread
  ownership and mapping state before encoding native buffer blits.
- `submit`: commits a retained Metal command buffer without waiting for its own
  completion. At most three buffer submissions are in flight per context;
  saturated submission waits for an earlier completion. Empty submits without
  recorded work or fences allocate nothing.
- `createFence`: retains the current batch. The inspected Vulkan backend's fence
  stores its current submission index, so Metal fences likewise include later
  commands recorded into that submission. Fence creation does not submit work.
  Call `submit` before waiting. Zero timeout polls; positive timeouts are
  nanoseconds; `NO_TIMEOUT = -1` waits indefinitely on submitted work. Finite
  waits before submission return false immediately; an indefinite pre-submit
  wait fails explicitly to avoid blocking the owning thread before it can submit.
  Large finite timeouts saturate the deadline instead of overflowing it.
- Resource lifetime: batches retain native buffers and upload staging until the
  completion handler runs. Closing a Java buffer immediately invalidates its
  Java/native ID but does not release storage referenced by recorded/submitted
  work. Fence close releases only that fence's handle and does not cancel work.
- CPU hazards: direct native reads/writes reject a buffer referenced by recorded,
  unsubmitted commands. They drain submitted buffer work before touching shared
  memory. Mapping uses detached Java staging and mapped buffers cannot be newly
  encoded. This conservative context-wide drain is a correctness foundation,
  with a potential performance cost; no frame-rate improvement is claimed.
- Encoder close cancels unsubmitted commands and retires their retained storage.
  Cancelled fences fail explicitly. Context close cancels pending encoder batches,
  drains submitted work, then releases fences and child resources. A GPU error
  remains observable through fences, submission, CPU access, stats and draining;
  close still releases resources after a drain reports an execution failure.
  GPU/device failure injection has not been performed.

The current subset requires four-byte-aligned upload/copy offsets and lengths,
and distinct source/destination buffers for copies. Initial bounds: 4096 buffer
operations per batch, 64 MiB aggregate native encoder staging, 1024 batch/fence
IDs per context. The existing 1 GiB resource budget now includes encoder staging
and buffers retained after Java close. Texture allocations remain counted once;
Java staging, synchronous diagnostic staging, pipeline states and drawables are
outside this budget. `commandStats` reports buffer/staging bytes, not texture or
total renderer memory. Reusable upload arenas are pending.

At ABI 5, transient memory, render passes, texture transfers/clears, timestamps
and pipelines were explicit unsupported operations. ABI 6 now implements
texture transfers, full clears and owner-thread readbacks; see the
[validated texture increment](texture-command-validation.md). General render
passes, scissored clears, transient memory, timestamps and pipelines remain pending. The new
factory is insufficient for safe frontend activation.

## Validation evidence

Final `python3 tools/smoke_test.py` at **15:18:59 local** on **Apple M5**:
**PASS**, with `MTL_DEBUG_LAYER=1`, `-Xcheck:jni`, generated JNI headers and
Java/Objective-C++ warnings treated as errors. The dylib was loaded from the
packaged mod JAR. Complete output: `build/reports/native-smoke.log` (last run).

`CommandEncoderTest` verifies real GPU results:

- Heap input range preservation and a staging snapshot unaffected by subsequent
  source mutation, ordered upload followed by copy and untouched target bytes.
- Two fences on one submission, commands added after fence creation, polling,
  finite/infinite waits, invalid timeout rejection, large timeout handling,
  cancellation and close-before-submit without cancelling the encoder's batch.
- CPU reads/writes blocked by unsubmitted buffer references; CPU mapping after
  submission sees completed GPU writes without an explicit fence.
- Source Java close before submission, and immediate temporary-buffer close
  after each asynchronous submission, without releasing GPU storage prematurely.
  The closed recorded source remains in the memory budget until retirement.
- **100 submitted/completed batches**, peak **3** in flight, **0** pending after
  synchronization. The final buffer contains the last of 96 sequential stress
  patterns. Exactly the two live 1024-byte buffers remain accounted; no retired
  buffer/staging allocation remains.
- Alignment, unequal slices, forged out-of-range slices, missing usage,
  cross-device resources, active mappings, foreign-thread use, native heap or
  undersized direct upload rejection, duplicate submit and stale batch handles.
- Cancellation releases staging and CPU hazards. Context close drains submitted
  work and closes buffers/fences even when the caller has not waited.

Previous triangle, buffer, texture/view/sampler and initializer tests also pass.
An earlier complete run at 15:16:32 passed; the final run additionally tests
early fence close and very large timeout behavior and saves the raw GPU log.
The first GPU attempt at 15:15:27 reported no Metal device before any test;
it is retained in `build/reports/commands-smoke-unavailable.log`. The cause of
that inconsistent availability remains unresolved.

Independent SDL presentation was last visually confirmed with ABI 2. This
increment does not establish a new window or in-game rendering result. No
terrain, entities, GUI or effects currently render through this adapter.
