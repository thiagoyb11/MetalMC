# Texture, view and sampler validation — 2026-10-03

This increment introduced JNI ABI **4** (current ABI is **6**). It extends the existing foundation. Java sources implement the exact
installed Minecraft Java 26.3 `GpuTexture`, `GpuTextureView`, `GpuSampler` and
device factory contracts. `tools/audit_backend.py` records their signatures from
the checksum-verified game JAR. The adapter remains development-only.

## Implemented subset

- Private `MTLTexture` 2D or 2D-array storage, all requested mip levels.
- Eight explicit format mappings: R8/RG8/RGBA8 UNORM, R32/RG32/RGBA32 FLOAT,
  RGBA16 FLOAT and D32 FLOAT. No ordinal-based JNI mapping or format substitution.
- Native same-format views restrict mip ranges and share parent storage.
  Java texture close closes its views; native direct close rejects live views.
- Native immutable samplers: U/V repeat or clamp to edge, independent min/mag
  nearest or linear, **linear interpolation between mips**, max LOD, anisotropy
  1..16. The installed GL/Vulkan sampler constructor bytecode was inspected for
  mip-filter parity. This is interface inspection, not reused implementation.
- Synchronous diagnostics: whole-mip/layer upload/readback through GPU blits,
  attachment clears and compute sampling. Data transfer rows are padded to 256
  bytes internally and returned tightly packed; source position/limit preserved.
- Java owner-thread/device checks and native stale-ID/range/usage checks. Opaque
  handles do not expose native pointers. Context close cleans up child resources.
- Combined native budget: buffer lengths + texture `allocatedSize` <= 1 GiB per
  context. Views share storage and are counted once. Per-resource cap 256 MiB.
  ABI 5 additionally counts retained buffers and encoder upload staging. ABI 6
  also counts retained textures after Java close, validated in the user's Terminal.

Initial limits are 8192×8192 pixels and 256 array layers. Cubemaps, 3D textures,
remaining color/stencil formats, reinterpretation/swizzle views and asynchronous
gameplay integration was pending at this increment. ABI 6 now adds validated
asynchronous transfers/clears/callbacks; see
[texture command validation](texture-command-validation.md). No device capability object advertises these
limits yet. No game backend is registered or activated.

Textures must be initialized by upload or clear before use; allocation does not
define their contents. Java staging and synchronous diagnostic staging, offscreen diagnostic
targets, pipelines, samplers and SDL drawables are **outside** the budget. This
does not establish total renderer memory accounting or gameplay performance.
The diagnostic waits must not be copied into the future gameplay encoder.

## GPU results

`python3 tools/smoke_test.py` completed at **14:51:43 local time** on Apple M5,
with `MTL_DEBUG_LAYER=1`, `-Xcheck:jni`, Java/native warnings treated as errors and
the native library loaded from the packaged Fabric JAR. Exit code **0**, PASS.

`TextureResourceTest` checked:

- Exact byte roundtrips for all eight formats, 7×3 base size, three mip levels,
  three array layers; uploading later subresources preserves earlier data.
- Real render-attachment clears in every format, including D32 depth; exact float
  and half encodings and UNORM tolerance. Adjacent mip/layer data remains intact.
- Relative view dimensions, base-mip GPU sampling and relative LOD selection.
- Repeat/clamp in each coordinate, linear pixel filtering, interpolation between
  mip levels and maximum LOD clamping by comparing actual GPU float samples.
- Anisotropy 16 sampler creation (anisotropic visual quality is not measured).
- Overflow/range/usage checks, invalid dimensions/formats/cubemap rejection,
  direct-buffer capacity/read-only validation, source range preservation,
  closed/stale handles, cross-device rejection, foreign-thread rejection and
  device/texture/view/sampler cleanup.

Existing triangle, buffer and initializer diagnostics also passed. An earlier
texture/sampling run at 14:40:05 passed before attachment-clear tests were added.
The first final run at 14:51:28 reported no Metal device **before any test**;
the fresh final run at 14:51:43 passed the complete suite including clears.
The failed invocation is preserved in `build/reports/resources-smoke-unavailable.log`.
Device availability in agent-launched processes is inconsistent; no cause is
established, and no mock/software result replaces the failure.

Visible SDL presentation was last human-verified with ABI 2; ABI 4 changes only
resource support and preserves that presentation path. No new visible-window or
in-game renderer result is claimed.

## Metal API references

The native mip views use Apple's
[texture view API](https://developer.apple.com/documentation/metal/mtltexture/newtextureviewwithpixelformat%3Atexturetype%3Alevels%3Aslices%3A).
Sampler validation follows Apple's documented
[anisotropy range](https://developer.apple.com/documentation/metal/mtlsamplerdescriptor/maxanisotropy).
These API references support implementation choices; the runtime findings above
come from local tests.
