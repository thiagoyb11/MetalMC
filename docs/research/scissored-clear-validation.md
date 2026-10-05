# Scissored combined color/depth clears — JNI ABI 15

Implemented 2026-10-03. **Complete user-run ABI 15 GPU PASS on Apple M5 at
23:08:32–45**, with Metal API validation and JNI checks. The user confirmed it
passed; the agent inspected the complete saved log, including ScissoredClearTest
and every prior suite. Strict Java/native build, JNI generation, arm64 signing,
packaging and all test compilation also pass. Evidence is preserved in
`build/reports/scissored-clear-smoke-pass.log`; the raw last-run log is ABI 15.
Current source/build is ABI 16 with its separate timestamp-query gate pending.
No tests were launched by the agent.

## Exact installed frontend contract

Read-only `javap -p -c` of the checksum-verified Minecraft 26.3
FrontendCommandEncoder and VulkanCommandEncoder confirms the overloaded
`clearColorAndDepthTextures` arguments: color, vector color value, depth,
double depth value, x, y, width, height, mip. The command targets layer zero
at the selected mip, with positive region dimensions. Frontend validation
requires single-layer clear targets and equal mip counts. Vulkan selects one
mip view of each texture and clears that rectangle inside a full-size pass.
Inspection output is retained in `build/reports/inspect-frontend-FrontendCommandEncoder.txt`
and `build/reports/inspect-backend-vulkan-VulkanCommandEncoder.txt`.

MetalMC additionally requires equal selected-mip attachment dimensions, because
the internal native pass binds both attachments. Larger/smaller incompatible
targets reject before encoding. Full-clear operations retain their previous
all-mips behavior; ordinary render-pass load clears are unchanged.

## Native implementation and ownership

The new JNI operation uses an internal fullscreen triangle and scissor rectangle,
with both attachments set to Load/Store. It writes a constant float color and
explicit fragment depth, disables blending/culling, enables all color channels,
and uses Always depth comparison with depth writes. It opens and closes its own
native encoder in the current ordered resource batch. No user render pass may
be open, and no submitted/cancelled batch can record this operation.

The implementation follows Apple's
[scissor API](https://developer.apple.com/documentation/metal/mtlrendercommandencoder/setscissorrect(_:)),
[load actions](https://developer.apple.com/documentation/metal/mtlrenderpassattachmentdescriptor/loadaction),
[store actions](https://developer.apple.com/documentation/metal/mtlrenderpassattachmentdescriptor/storeaction)
and [MSL specification](https://developer.apple.com/metal/Metal-Shading-Language-Specification.pdf).
These references explain the chosen APIs; the user-run gate verified actual GPU behavior.

Each command snapshots four color floats and a float-converted D32 value into
an owned 32-byte shared buffer. Those bytes count against the existing 64 MiB
staging and 1 GiB accounted resource limits. Native batches retain both textures
and parameters through submission/completion, including Java close-before-submit.
Recorded-use checks prevent synchronous diagnostics from racing unsubmitted
clears. Cancellation releases references/staging without committing GPU work.

The internal library, Always depth state and format-specific PSOs are lazily
cached in the context. At most seven internal PSOs exist (one per supported
color format, always with D32); the retained batch keeps the context/cache alive.
They are separate from the 256 frontend pipeline-object cap and byte accounting,
which already excludes pipeline/library objects. A clear costs one of the 4096
batch operations, preflighted before parameter allocation or native encoding.
No new GPU waits enter recording or submission; existing three-in-flight
backpressure, fences and shutdown drain remain in use.

Supported color formats: R8/RG8/RGBA8 UNORM, R32/RG32/RGBA32 FLOAT and RGBA16
FLOAT, with D32 depth. Array/cubemap/stencil/multisample clears, empty/out-of-bounds
rectangles and mismatched selected-mip dimensions reject explicitly. All clear
values must be finite; depth is in [0,1]. Java and JNI repeat ownership, usage,
aspect, mip, region and dimension checks. Uninitialized pixels outside a clear
retain their undefined state; callers must initialize textures before preserving
or reading those pixels. No vanilla shader/state parity or game activation claim.

## Completed user-run GPU gate

`ScissoredClearTest` follows DeviceInfoTest in the complete suite:

- All seven color formats with D32: asymmetric interior and edge rectangles,
  overlapping replacement, mutable color-vector snapshots and depth 0/0.5/1.
- Exact unchanged bytes outside rectangles and at adjacent mips; selected mip
  one and a full smallest mip; ordinary load-only pass after queued clears.
- Range/overflow/mip/dimension/aspect/usage/array/device/thread/NaN rejection,
  no allocations/submissions on rejected Java calls, active-pass exclusion.
- Parameter budget counters, cancelled fence/work and unchanged initialized
  bytes; 16 asynchronous submissions close color/depth wrappers before commit,
  read back final pixels and verify all retained resources retire.
- JNI bad/stale IDs, wrong devices/aspects, invalid regions/values, 4096 recorded
  clears, rejection of the next operation before extra staging, and cancellation
  releasing all snapshots while preserving initial GPU bytes.

Command executed in the user's separate normal macOS Terminal (no repeat needed):

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/smoke_test.py
```

The complete log includes `PASS: scissored color/D32 clears`, every prior PASS
and the final overall PASS. All seven color formats/D32, untouched pixels/mips,
mutable snapshots, depth endpoints, cancellation, 16 asynchronous retained
submissions and the cancelled 4096-operation cap fixture passed. Only the known
LWJGL Unsafe deprecation warning remains. No independent window repeat or game
launch is needed. Validated completion is **44% (44.1% weighted)**: resources
increase from 80% to 85% (14.4 to 15.3 project points), with other weights unchanged.
No game parity or performance credit. Pre-increment backup:
`/tmp/metalmc-abi14-source`.
