# Async texture commands, clears and readbacks — 2026-10-03

JNI ABI **6** extends `MetalCommandEncoder` against the installed Minecraft 26.3
contracts. The complete GPU smoke suite **passed in the user's normal Terminal**
on **Apple M5**, at **15:48:48 local**, with `MTL_DEBUG_LAYER=1` and `-Xcheck:jni`.
The user supplied the output; the same complete PASS log was read from
`build/reports/native-smoke.log`. Java/JNI/native compilation keeps warnings fatal.
The dylib was loaded from `build/libs/metalmc-0.1.0-dev.jar`.

Minecraft's vanilla backend remains active. This is transfer/resource validation,
not in-game rendering or a performance result.

## Implemented semantics

- `writeToTexture(texture, data, mip, layer, x, y, width, height)` snapshots the
  tightly packed region's bytes immediately into budgeted shared Metal staging.
  It preserves Java position/limit, accepts extra trailing input, and uploads
  asynchronously to a private 2D/array texture. Input mutation after recording
  does not alter the GPU upload.
- `copyBufferToTexture` reads a subrectangle from a GPU buffer's source image:
  source X/Y, source width/height and slice offset are separate from destination
  X/Y, region extent, mip and array layer. Its native blit preserves source row
  stride and validates the actual addressed byte span, including source Y.
  Source row widths above the initial 8192-pixel limit fail explicitly.
- `copyTextureToBuffer` copies a full mip or region at layer zero into tightly
  packed destination buffer rows. Pixel-size-aligned offsets, usages, bounds,
  mapped state and ownership are validated before recording. The exact API has
  no readback array-layer parameter; the native operation selects layer zero.
- `copyTextureToTexture` copies same-format regions between distinct textures,
  with the same mip on both sides and layer zero, following the inspected Vulkan
  bytecode's destination-X/Y then source-X/Y parameter order.
- Full `clearColorTexture`, `clearDepthTexture` and combined color/depth clears
  use native attachment load clears for every mip of a single-layer texture.
  Combined clears validate both textures before recording and require matching
  mip counts. Array clears and scissored clears remain unsupported, matching the
  frontend's single-layer full-clear restriction. Scissored clears need later
  rendering support; they fail explicitly before recording commands.
- Texture bytes, buffer storage and upload staging survive Java close while
  native batches need them. Texture allocation accounting uses retained native
  lifetime instead of only live IDs, including closed recorded/submitted textures.
  The combined resource cap remains 1 GiB; encoder staging has a 64 MiB cap.
  Diagnostic transfers/sampling/clears reject recorded, unsubmitted texture use
  and drain submitted resource work before their synchronous command stream.
- Readback callbacks run exactly once on the creating Java thread after a
  successful submission completes. Native completion handlers never call Java.
  `submit` (including an empty submit), successful fence waits, explicit
  `MetalContext.pollCallbacks()` and context shutdown dispatch ready callbacks.
  Completion tickets follow submission order; callbacks within a batch preserve
  registration order. Polling is reentry-safe, and callback failures propagate
  after the remaining callbacks in that completed batch have been attempted.
  Caller code must continue submitting/polling or wait on a fence for dispatch;
  no independent event thread is installed.
- Closing an unsubmitted encoder cancels its callbacks and releases tickets,
  texture references and staging. Device shutdown cancels pending batches,
  drains submitted work and dispatches completed readbacks before closing their
  destination buffers. Closing the context from inside its callback is rejected
  to prevent destruction of resources during dispatch. New encoder creation
  during shutdown is rejected.

Pixel-size alignment and row-stride handling follow Apple's documented
[buffer-to-texture blit requirements](https://developer.apple.com/documentation/metal/mtlblitcommandencoder/copy%28from%3Asourceoffset%3Asourcebytesperrow%3Asourcebytesperimage%3Asourcesize%3Ato%3Adestinationslice%3Adestinationlevel%3Adestinationorigin%3Aoptions%3A%29?language=objc)
and [texture-to-buffer blit requirements](https://developer.apple.com/documentation/metal/mtlblitcommandencoder/copy%28from%3Asourceslice%3Asourcelevel%3Asourceorigin%3Asourcesize%3Ato%3Adestinationoffset%3Adestinationbytesperrow%3Adestinationbytesperimage%3A%29?language=objc).
Texture transfers use tightly packed or pixel-aligned strided rows; they do not
inherit the four-byte restriction on the initial buffer-to-buffer copy subset.
The tests below validate actual GPU behavior, independently of those references.

`commandStats()` now returns six values: submitted, completed, in flight, peak,
buffer/staging bytes, texture bytes. Texture views share allocations and do not
increase the texture counter. This is partial renderer accounting; Java staging,
diagnostic staging, pipelines, samplers and drawables remain outside it.

## Runtime evidence

`TextureCommandTest` passed these real-GPU checks:

- All eight formats: R8/RG8/RGBA8 UNORM, R32/RG32/RGBA32 FLOAT, RGBA16 FLOAT,
  D32 FLOAT, each with three mip levels and three array layers.
- Full asynchronous initialization followed by partial region patches, with
  complete byte comparisons preserving adjacent texels, mips and layers.
- Positioned read-only upload input, unused trailing bytes and mutation of the
  original input after recording. The captured patch remains unchanged.
- Nonzero GPU buffer slice offset, padded source image rows, nonzero source X/Y,
  destination coordinates, mip and layer. Source buffers close before submission
  without breaking the copy. Tight rows include seven-byte R8 rows, not only rows
  aligned to 256 bytes.
- Asymmetric source/destination coordinates for native texture-to-texture copies,
  and nonzero readback destination offsets with untouched surrounding bytes.
- Actual full color/depth clears across all mips of every supported format,
  combined color/depth clears, whole-mip D32 readback and region readback.
- Callback thread ownership, completed-buffer mapping inside callbacks, single
  invocation, reentrant polling, cancellation, exception propagation and later
  callback execution after an earlier callback throws.
- Java texture close before GPU submission, including close of the texture used
  as a readback source. Recorded allocations remain budgeted until completion.
  Completed temporary buffers, staging and textures leave no allocated bytes.
- Context close drains submitted work, dispatches the callback and then closes
  its destination buffer. A callback cannot reenter context close.
- Region/mip/layer bounds, negative coordinates, zero-size rejection, source-Y
  slice overflow, pixel alignment, mismatched formats/mip counts, mapped buffers,
  missing usage, cross-device ownership and foreign-thread calls.
- Direct JNI validation rejects heap/undersized transfers, stale resources,
  invalid offsets/strides and diagnostic access to unsubmitted texture commands.

The earlier buffer suite also passed **100 completed submissions**, peak **3**
in flight, plus all triangle, resource sampling and initializer checks. Visible
SDL presentation was last human-verified at ABI 2; no new visible-window result
is claimed for this resource-only increment.

Agent runs at 15:43:11 and 15:43:35 failed before any test with no Metal device.
The first log is retained in `build/reports/texture-commands-smoke-unavailable.log`.
An escalated attempt was rejected by session policy; it did not execute. The
subsequent normal-Terminal PASS establishes this increment's GPU result. The
reason for differing device availability remains unresolved.

The installed JOML `Vector4f` has type annotations inconsistent with its legacy
classfile version, producing a javac classfile warning. Tests construct the real
installed JOML vector reflectively; no compiler lint is disabled and no dependency
is modified.

Remaining work: reusable transient upload arenas, timestamps, extra formats and
cubemaps, scissored clears, shader/pipeline translation, general render passes,
device capabilities, the game surface and safe backend selection.

## User test workflow

The user requested that every future test be supplied as terminal commands for
them to execute. Do not automatically launch new tests. This suite's command is:

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/smoke_test.py
```

Read the saved log after the user's run; report actual results and provide the
command again only if a fix or unresolved concern requires another run.
