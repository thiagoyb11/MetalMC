# Timestamp query foundation — JNI ABI 16

Implemented 2026-10-03. **Strict main Java/native compilation, JNI header
generation, arm64 signing, Fabric JAR packaging and all test compilation pass.
Three user-run ABI 16 GPU gates failed: the positive/order check, slot 3=0
despite the GPU marker, and slot 3=0 again with private counters/GPU resolution.
The user-run hardware probe at 00:39:46 on 2026-10-04 produced valid results in
all12 configurations. Production now uses real per-slot copies, matching that
probe; the fourth full gate still failed slot3=0 at00:44:16. A production-sequence
diagnostic at00:56:38 isolated unsampled transfer interleaving as the trigger.
The strategy probe at 01:03:53 validated compute markers and a GPU event boundary;
control and explicit transfer descriptors still failed. Production now adds event
ordering before every marker with increasing batch values. Strict build/signing/
all test compilation pass; complete manual GPU acceptance is pending. No tests
were launched by the agent.**
Latest complete user-run GPU PASS is ABI 15 at 23:08:32–45 on Apple M5 under
Metal/JNI validation, preserved as `build/reports/scissored-clear-smoke-pass.log`.
The raw last-run log records the fourth ABI 16 failure at00:44:16 on2026-10-04,
preserved as `build/reports/timestamp-query-abi16-copy-failure.log`. The third
at00:26:45 is preserved as `build/reports/timestamp-query-abi16-gpu-resolve-failure.log`.
The second ABI 16 failure at 23:44:47 is preserved as
`build/reports/timestamp-query-abi16-marker-failure.log`. The first failure at
23:39:34 remains in `build/reports/timestamp-query-abi16-first-failure.log`. Every earlier suite,
including scissored clears, passed in that run. No frontend/game activation.

## Exact interface and bounded scope

Read-only `javap -p -c` of the checksum-verified installed 26.3 GpuQueryPool,
VulkanQueryPool and TracyGpuProfiler establishes size(), getValue(index) and
getValues(first,count), with OptionalLong availability and repeatable reads.
The frontend profiler creates 1024 queries, applies DeviceInfo.timestampPeriod,
adds the backend calibration offset and reads the ring nonblockingly. Vulkan
resets a slot before recording its next write. Inspection files:
`build/reports/inspect-query-contract.txt`,
`inspect-backend-vulkan-VulkanQueryPool.txt` and
`inspect-frontend-TracyGpuProfiler.txt`. The first inspection reported one
incorrect Vulkan class spelling; the separately inspected real VulkanQueryPool
supersedes that missing-class line. Generated signatures now include the pool
and profiler. No external implementation code was copied.

MetalGpuQueryPool implements that bounded contract using actual timestamp counter
sample buffers. Creation checks the actual timestamp set/counter and native
stage-boundary sampling. Pools contain 1..4096 slots; live plus batch-retained
native pools are capped at 16/context and 32/process. Native counter storage is
opaque and is separately bounded; its bytes are not included in the partial
buffer/texture/upload-staging budget. Java/native slot metadata and result arrays
are also outside that GPU-byte accounting. This does not report total renderer
memory. Driver allocation errors propagate without mocks or CPU-time queries.

CommandEncoderBackend.writeTimestamp opens a short native blit pass with an end
sample attachment at the requested slot, copies an immutable per-slot word from
shared storage into a distinct private marker word,
then ends it in the current ordered batch. Immediately before each marker,
a signal/wait on the batch-owned GPU event orders preceding work; this includes
the first marker after transfers. Every boundary increments the event value,
so a later wait cannot shortcut through an already-signaled value. It costs one of the existing 4096 operations and introduces no CPU wait.
This increment supports sampling **between encoder operations only**.
Command-encoder writes during an open render pass reject, and
RenderPassBackend.writeTimestamp remains explicitly unsupported. No render pass
is silently split or GPU draw state changed. These stage samples are not a
claim of arbitrary draw-boundary timing or complete barrier/profiler parity.

Apple explains actual counter discovery, stage-boundary sampling on Apple Silicon,
completion-time resolution and timestamp domains in
[its Metal counters talk](https://developer.apple.com/videos/play/tech-talks/10001/)
and [sampling guide](https://developer.apple.com/documentation/metal/sampling-gpu-data-into-counter-sample-buffers).
Installed Metal headers specify start/end blit sample attachments and
resolveCounterRange. The original empty sampled pass failed the positive/order
gate; that assertion did not print the values, so zero versus reversed samples
cannot be distinguished from the saved log. Dawn documents missing counter
writes from empty blit encoders in its
[Metal workaround](https://dawn.googlesource.com/dawn/+/5eb3619d3e59cd829da5a5da3381cc76713868bc/src/dawn/native/metal/BackendMTL.mm).
The correction adds real GPU work and samples its end boundary. It retains a
private four-byte buffer per pool through cancellation/completion (the first
correction), counted against the existing 1 GiB budget. That is now replaced
by shared source/private destination buffers with four bytes each per logical
slot: eight bytes/slot total, max32KiB/pool. Source words are initialized once
to index+1 and never mutated; each write copies four bytes at its own slot offset.
No per-write buffer is allocated. Counter storage remains separately bounded. Zero/sentinel/out-of-range
samples now fail with the actual slot/value; order and clock assertions print
their values too. The second run proved that this correction alone was insufficient:
slot 2 passed native validity checks, then slot 3 resolved as zero. That does not
establish whether the sample write or CPU resolution produced the zero.
The new correction replaces CPU-side single-slot resolution with private
counter storage and explicit GPU resolution into per-batch shared snapshots.
The third run also failed slot3=0. The focused probe subsequently proved
all12 actual-copy/compute configurations work, including single-slot CPU reads.
Thus CPU resolution is functional for that workload. The production workload
was a repeated zero fill of the same word; the new correction uses the actual
per-slot copy pattern from the working probe. Exact fill optimization/driver
behavior remains an inference. The fourth full gate still failed with per-slot
copies. The production-sequence probe reproduced slot 3=0 only after unsampled
transfers, and the strategy probe validated an event boundary before the second
marker. Production therefore applies that boundary before every marker. The
complete gate must validate preceding transfers, multiple markers and render
pass boundaries as well as the original reproduction.
No fabricated value replaces an unsupported/invalid counter result.

## Results, generations and resource lifetime

Slot states are Empty, Recorded, Submitted, Available or Failed. Recording a
new generation invalidates the previous cached value. Reusing any Recorded or
Submitted slot rejects; independent slots/pools may span multiple batches.
Cancellation returns recorded slots to Empty and makes them reusable, including
when a previous generation was readable. Failed slots reject reads/reuse.

Batches retain pools after Java close until GPU completion or cancellation.
Each batch allocates one shared result snapshot per referenced pool on first use,
eight bytes per slot (at most 32 KiB/pool and 512 KiB/batch). These bytes count
against existing 64 MiB staging / 1 GiB resource budgets, including recorded or
in-flight batches, and retire on cancellation/completion. A batch also owns one
shared event for marker and query-resolution ordering; its metadata is separately bounded
by the existing batch handle limit. No buffer is allocated for subsequent writes
to the same pool in the same batch.

The batch event starts at zero; each marker signals/waits on its next value.
At submission, a further signal/wait on the next value orders counter generation before a blit
resolveCounters command. Each pool's complete zero-based range resolves into
its batch snapshot at offset zero. Only this batch's actually written slots
are published. Other unwritten/pending slots do not acquire availability from
the snapshot. The internal resolve stage does not consume another user operation
or require a CPU wait. This addresses both CPU single-slot resolution and
counter-generation/resolve ordering; the exact driver cause remains unproven.
Apple describes [GPU resolution](https://developer.apple.com/documentation/metal/converting-a-gpus-counter-data-into-a-readable-format);
Dawn documents shared-event serialization of timestamp generation and resolution
in its [Metal toggle](https://dawn.googlesource.com/dawn/+/abeb03e022fb22e937805857bab2c9c67289975e/src/dawn/native/Toggles.cpp).

In the native completion handler, actual resolved values are copied from the
completed batch's owned shared snapshot. Missing/invalid snapshot ranges, zero, MTLCounterErrorValue or values
outside signed Java long cause a submission error, surfaced through existing
fences/submission/drain and failed query reads. No driver thread invokes Java.
Available values are copied into mutex-protected owned scalar slots before
native references retire. Query getters only read those cached values; they
never submit commands, wait for GPU work, resolve still-pending storage or consume
the slot. Returned arrays/OptionalLong values are independent Java snapshots.

Unused/Recorded/Submitted/cancelled slots return OptionalLong.empty(). Ranges,
overflow, device ownership, closed handles and owner-thread access reject. Native
JNI repeats size/device/range/batch-state checks. Device shutdown cancels pending
sampling, drains submissions and closes all tracked pools before context release.

## Clock policy and calibration

The arm64 backend gates unified-memory Apple7 devices. On this Apple Silicon
target the timestamp domain is CPU-aligned nanoseconds, as shown in Apple's
counter examples; DeviceInfo.timestampPeriod is 1 when the actual timestamp
counter/stage support is present, otherwise the previous unavailable sentinel 0.
This ABI 16 period change has not yet passed its manual GPU gate. Physical device
facts and all other limits/features retain the validated ABI 14 policy.

getTimestampCalibrationOffset brackets five native sampleTimestamps calls with
System.nanoTime and chooses the shortest JNI interval, subtracting the sampled
GPU time from the Java interval midpoint. It allocates no GPU resources/commands
and does not wait for the GPU. The result correlates the GPU nanosecond domain
with this JVM's nanoTime origin; it is an estimated clock offset with JNI-window
uncertainty, not a precise performance measurement. The CPU value from the native
pair is not assumed to share the JVM's origin. Tests must verify that sampled
counter units and calibration agree with observed elapsed JVM time.

**Do not activate FrontendGpuDevice/Tracy:** timestamps within render passes,
full profiling/barrier semantics, safe worker compilation, debugging and the
game surface/backend selection still need implementation/validation.

## Prepared user-run GPU gate

The production-sequence probe completed at00:56:38. Both8/32-slot pools produced
valid slots2,3 with adjacent sampled passes; both produced slot3=0 with the two
unsampled transfers inserted. Actual marker/transfer bytes were valid and
completion-handler bytes matched post-wait bytes in all cases. Evidence is
preserved as `build/reports/timestamp-production-sequence-interleave-failure.log`.
Thus neither changing pool size nor moving the CPU read is supported as a fix.
The actual counter failure is reproduced outside Java/JNI by transfer interleaving.
Exact driver behavior is not established.

The user completed the strategy probe at **01:03:53**. The 8-slot interleaved
control and explicit unsampled blit descriptors both produced slot 3=0. One-thread
compute markers produced ordered positive values 55576796160791/55576796165958;
a GPU event boundary before the second blit marker produced
55576806456875/55576807154125. Workload bytes and callback/post-wait agreement
passed in all four cases. Evidence is preserved as
`build/reports/timestamp-interleaved-strategies-event-compute-valid.log`.
The probe used event value 1 before the second marker and value 2 before resolve.
This is diagnostic evidence, not complete ABI 16 acceptance.

The selected production correction keeps the blit copies and adds signal/wait
before **every** marker using increasing values on the existing batch event;
resolution increments the value again. Normal maximum is 4096 markers plus
resolution. This introduces GPU ordering but no CPU wait, new per-marker buffer
or new compute pipeline. Budgets/operation counts remain unchanged. The expanded
test suite is strictly compiled; full acceptance remains pending.

Historical diagnostic commands, already completed (no repeat needed):

```sh
python3 tools/timestamp_probe.py --production-sequence
python3 tools/timestamp_probe.py --interleaved-strategies
```

The sequence diagnostic compared 8/32 slots with/without the exact 64 KiB upload
and copy between samples 2,3, matching source/destination marker sizes and separate
JNI-style autorelease pools around each encoder. It checked actual transfer/marker
bytes and compared completion-callback resolution bytes with post-wait values.
The strategy diagnostic retained those checks while varying encoder type and
ordering. The loader extracts a fresh resource per process; it has no stable
native extraction cache. Raw logs remain in `build/reports/timestamp-production-sequence.log`
and `build/reports/timestamp-interleaved-strategies.log`. All diagnostics were
run by the user; none were launched by the agent.

The user completed the isolated matrix at00:39:46: all12 GPU cases VALID,
six distinct output words correct in every case, all shared CPU-full/single reads
agreeing with GPU resolution. Evidence is preserved as
`build/reports/timestamp-probe-12-configurations-valid.log`. Calibration CPU/GPU
both54129839774250; mach-timebase125/3; GPU raw timestamp frequency24000000 ticks/sec.
The legacy resolved-query nanosecond/JVM-calibration checks still require the
full gate. No probe repeat is needed. The historical probe command was:

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/timestamp_probe.py
```

`tools/timestamp_probe.mm` executes six nonempty passes per configuration across
blit copies/compute dispatches, start/end/paired samples, and shared/private
counter storage (12 cases). It verifies six distinct GPU-written words, prints
all 32 raw slots, compares GPU resolution with whole-buffer and per-slot CPU
resolution for shared counters, and reports native calibration/timebase/GPU
frequency. Results are saved as `build/reports/timestamp-probe.log`. It is
independent of JNI and renderer state. No mock/CPU values replace query samples.
The native diagnostic was compiled with strict warnings and signed using
`python3 tools/timestamp_probe.py --build-only`; it was run by the user, not the agent.
An exit zero means at least one complete configuration worked; the individual
VALID/INVALID results must be inspected. This is not an ABI16 acceptance PASS.
An exit one still saves the actual output for investigation.

The acceptance command below applies to the current event-boundary correction.
Strict compilation/signing/packaging pass; the corrected complete GPU gate has
not yet been run. The raw smoke log still records the fourth failure at 00:44:16.

TimestampQueryTest follows ScissoredClearTest in the complete suite and requires
the actual target timestamp counter/stage support; there is no skipped/mock PASS.

- Real positive counter values before/after ordered GPU uploads/copies, exact
  buffer bytes, clock correlation against the JNI-bracketed JVM range, a 20 ms
  diagnostic pause between completed samples and conservative unit/drift bounds.
- Transfers before the first marker, multiple unsampled transfers between markers,
  positive ordered samples, exact replacement bytes and unchanged cached slots
  from an earlier submission; markers before/after an actual render pass.
- Unwritten/recorded/partial OptionalLong availability, owned result arrays,
  repeated pure polling, reuse invalidating old values and duplicate pending-slot
  rejection across encoders; actual backend query factory/period/calibration.
- Cancellation/fences, slot reuse, bounds/overflow, device/thread/closed/stale
  handles and explicit unsupported render-pass queries.
- 32 asynchronous batches with 64 independent samples and closed temporary pools;
  final actual values and exact native pool/submission/marker/snapshot retirement counts.
- Shutdown with both submitted and pending samples, native invalid IDs/size/range,
  4096 recorded markers cancelled at the batch cap, and the 16-pool context limit.

An initial test compilation used the wrong descriptor-builder overload for its
clear value; corrected to the exact Optional<Vector4fc> signature. The final
strict build passes without disabling warnings or launching tests.

Run in the user's separate normal macOS Terminal:

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/smoke_test.py
```

Expect `PASS: real GPU timestamp pools`, every prior PASS and the final overall
PASS. Inspect complete output and preserve ABI 16 evidence only after an actual
complete PASS. No independent window repeat or game launch needed. Validated
estimate stays **44% (44.1% weighted)** pending this gate.
Pre-increment backup: `/tmp/metalmc-abi15-source`.
