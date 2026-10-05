# Device identity, limits and features — JNI ABI 14

Implemented 2026-10-03. **Complete user-run ABI 14 GPU PASS on Apple M5 at 22:33:26–39**, with
Metal API validation and JNI checks. The user confirmed all tests passed; the
agent inspected the complete log, including DeviceInfoTest and every earlier
suite. Strict build, JNI generation, signing, packaging and all test compilation
also pass. Evidence is preserved in `build/reports/device-info-smoke-pass.log`;
the raw last-run log now records the ABI 16 timestamp failure; device reporting
and every earlier suite passed in that run. Current source/build is ABI 16 with
its corrected timestamp-query gate awaiting a rerun. No tests were
launched by the agent.
No backend selection or frontend/game activation is added.

## Native facts and backend policy

`MetalDeviceProfile` snapshots the actual context device's name, macOS version,
maxBufferLength, recommendedMaxWorkingSetSize, unified-memory property and
Apple7-family support. JNI returns owned values, rejecting stale context IDs
and values that cannot fit Java's signed long. Profile and DeviceInfo are cached
per context, with owner-thread/open checks even after caching. Reading them
records no commands, allocations or GPU waits. Retained immutable snapshots
remain readable after context close; accessing the context again rejects.

Native context creation now explicitly requires Apple7-family support in
addition to unified memory, establishing the Apple Silicon baseline used by
the existing backend subset. This gate passed on the target Apple M5.
Native facts use Apple's [device properties](https://developer.apple.com/documentation/metal/mtldevice),
[GPU-family query](https://developer.apple.com/documentation/metal/mtldevice/supportsfamily(_:)),
[buffer length limit](https://developer.apple.com/documentation/metal/mtldevice/maxbufferlength)
and [recommended working set](https://developer.apple.com/documentation/metal/mtldevice/recommendedmaxworkingsetsize).
The working set is advisory; it does not change the existing 1 GiB accounted
resource budget or promise available memory. `driverInfo` identifies Metal and
the actual macOS version, rather than inventing a standalone driver version.

`MetalGpuDeviceBackend.getDeviceInfo()` now returns a bounded development
backend description. Reported limits combine actual buffer constraints with
implemented policy and Apple's [family limits](https://developer.apple.com/metal/limits/):

| DeviceLimits field | Reported policy |
| --- | --- |
| maxAnisotropy | 16 |
| minUniformOffsetAlignment | 4 bytes |
| maxTextureSize | 8192 per axis |
| maxMemoryAllocationSize | min(256 MiB, actual maxBufferLength) |
| maxMultiDrawDirectInterleavedDrawCount | 1024 |
| maxColorAttachments | 8, still subject to 32 color bytes/pixel |
| maxDrawIndirectDrawCount | 1024 |

DeviceLimits.maxTextureSizeForFormat additionally bounds square dimensions using
the per-allocation cap and format block size, as the inspected 26.3 implementation
requires. Format/mip/layer/usage limits, retained resource budgets, draw argument
invariants and batch operation limits continue to apply. DeviceInfo has no fields
for this complete support matrix; returning it is not a full-renderer claim.

## Feature policy

The exact installed DeviceFeatures record carries these eight booleans:

| Feature | Reported | Reason |
| --- | --- | --- |
| wireframeFillMode | false | Initial native fill state exists; raster parity unvalidated |
| shaderDrawParameters | false | DrawIndex/gl_DrawID unsupported |
| multiDrawDirectInterleaved | true | Complete ABI 11 GPU gate |
| multiDrawDirectSeparate | true | Complete ABI 11 GPU gate |
| multiDrawIndirect | true | Complete ABI 12 GPU gate, expanded native draws |
| drawIndirect | true | Complete ABI 12 GPU gate |
| nonZeroFirstInstance | true | Prior direct/indexed/multi/indirect pixel gates |
| persistentMapping | false | Detached Java staging; no persistent native mapping |

Underlying extensions are an empty immutable set. No unestablished performance
or driver workaround flags are set. Device type is INTEGRATED for the gated
unified-memory Apple device; depth convention is zero-to-one, matching the
development translation options.

At ABI 14, timestamp APIs explicitly threw and timestampPeriod was zero as an
unavailable sentinel. ABI 16 adds encoder-boundary timestamp pools/calibration
and reports period 1 only when native counter/stage support is available; its
GPU gate is pending (see timestamp-query-validation.md). Render-pass timestamps
remain unsupported. The exact API has no timestamp-support feature flag. The inspected
FrontendGpuDevice can unconditionally create TracyGpuProfiler when Tracy is
available, so **do not construct/activate the frontend before implementing that
profiler/query contract**. Debug collection/options, worker pipeline compilation,
game surface and other required backend contracts remain pending.

## Completed user-run gate

`DeviceInfoTest` follows VertexFormatTest in the complete smoke suite:

- Cross-checks native identity/properties against immutable cached Java snapshots,
  driver/OS text, bounded policy, signed sizes and unchanged GPU allocation/
  submission counters. Prints actual device properties, limits and features.
- Uses reported limits to reject oversized buffers, texture axes and anisotropy;
  verifies unsupported texel-buffer/query/surface operations still reject.
- Creates maximum-width and maximum-height R8 textures (8192×1 and 1×8192),
  asynchronously uploads all bytes, waits through the existing diagnostic fence
  and compares exact GPU readback. Maximum anisotropy sampler creation succeeds.
  No maximum-area or 256 MiB allocation is needed.
- Validates smaller hypothetical physical buffer limits remain conservative;
  invalid/unsupported profiles, mutable-extension attempts, foreign-thread and
  closed cached getter calls, zero/stale JNI handles reject.
- The old smoke expectation that getDeviceInfo throws was replaced before the
  build. All existing drawing/resource suites still precede this new suite.

Command executed in the user's separate normal macOS Terminal (no repeat needed):

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/smoke_test.py
```

The complete log includes `PASS: native Metal device properties`, every prior suite and the final
overall PASS. No repeat is needed for this exact increment, the visible window
or Minecraft. Validated completion is **43% (43.2% weighted)**: backend integration
is credited at 40% (4.8 project points), up from 30% (3.6 points). Device reporting
implements one required integration contract; game rendering remains inactive.
Pre-increment source/tools/docs backup: `/tmp/metalmc-abi13-source`.


## Observed native facts

The user-run log reports Apple M5, macOS Version 27.2 (Build 26B5091g),
maxBufferLength 9,534,832,640 bytes, recommendedMaxWorkingSetSize
12,713,115,648 bytes, unifiedMemory=true and supportsApple7=true. Backend policy
remains 256 MiB per allocation and 1 GiB accounted resources; the advisory
working set does not replace those caps. The known LWJGL Unsafe deprecation
warning appeared, and all suites continued to PASS.
