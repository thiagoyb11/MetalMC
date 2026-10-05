# Vanilla vertex formats — JNI ABI 13

Implemented 2026-10-03. **Complete user-run ABI 13 GPU PASS at 21:53:40–54
on Apple M5 with Metal/JNI validation.** The user confirmed all passed, and the
complete saved log includes the new vertex-layout PASS and every previous suite.
Strict build, signing, packaging and all test compilation passed. Evidence is
preserved as `build/reports/vertex-formats-smoke-pass.log`; current raw log is
ABI 13. ABI 14 adds device reporting with its new manual gate pending.
Minecraft continues using its selected vanilla backend. No agent-launched tests.

## Exact installed-game evidence

Read-only `javap -p -c` inspection of the checksum-verified installed 26.3
`DefaultVertexFormat` establishes these previously unsupported attributes:

| Game attribute | GpuFormat | ABI code | Metal format | Shader input | Offset alignment |
| --- | --- | ---: | --- | --- | ---: |
| UV1 overlay / UV2 lightmap | RG16_SINT | 5 | Short2 | ivec2 | 2 bytes |
| Normal | RGBA8_SNORM | 6 | Char4Normalized | vec3 or vec4 | 1 byte |
| ChunkPosition | RGB32_SINT | 7 | Int3 | ivec3 | 4 bytes |

Existing ABI codes 0..4 retain float1/2/3/4 and RGBA8_UNORM meanings. No new
texture format, capability claim, game activation or topology expansion is added.
Both loader and native implementation require ABI 13. JNI signatures are
unchanged, but the native vertex format metadata contract has expanded, so ABI
12 Java/native objects must not be mixed with ABI 13.

Signed integer storage is matched to reflected 32-bit signed integer shader
inputs; normalized/float storage is matched to 32-bit floating inputs. Signed
shorts widen to signed integers, preserving negative coordinates. The mapping
follows Apple's [vertex attribute format contract](https://developer.apple.com/documentation/metal/mtlvertexattributedescriptor/format)
and [vertex formats](https://developer.apple.com/documentation/metal/mtlvertexformat).
These references support the mapping; actual GPU execution remains gated below.

Components must match exactly except the game's RGBA8_SNORM normal storage
accepts a vec3 shader input, where the fourth stored component is unused. The
full four-byte stored extent still participates in stride and draw slice bounds.
No other missing-component fill or truncation semantics are advertised.
Java and JNI independently enforce format code, per-component offset alignment,
stored extent within stride, slot/location bounds and uniqueness. The existing
four-byte buffer slice base alignment and 4..2048-byte stride limits remain.

Normal and transient buffers use the existing command path. PSOs and vertex
storage stay retained through completion or cancellation; CPU hazards,
generation checks, operation budgets and asynchronous submission are preserved.
No new GPU waits or CPU vertex reads enter rendering.

## Completed user-run GPU gate

`VertexFormatTest` follows `IndirectDrawTest` in the complete smoke suite:

- Every one of the sixteen public installed vanilla vertex layouts uses its
  real stride, step rate, attribute offsets and formats with synthetic shaders.
  These include BLOCK, ENTITY, PARTICLE, CHUNK_DATA_INSTANCED, UI/text and line
  layouts. The test draws triangles for each layout; it does not claim thick
  line or quad topology support. Pixel checks consume every attribute, use
  firstVertex/firstInstance and buffer slice offsets, and distinguish earlier
  zero-filled records from the selected records.
- Signed short extrema (-32768, 32767), overlay and lightmap attributes, and
  chunk integers exceeding exact float precision are compared in shaders.
  This catches unsigned interpretation, missing sign extension or float casts.
- Normals as vec3 and vec4, negative byte extrema, intermediate positive and
  negative SNORM values, unused stored W and one-byte attribute offsets.
- A two-byte-aligned short pair at offset 14, exact last-attribute extent with
  an unpadded final vertex, one-byte-short slice rejection, format/type/component
  mismatches, unsupported unsigned formats and native metadata failures.
- Transient vertices, foreign encoders, expired generations, cancellation,
  close-before-draw/submit and sixteen asynchronous normal-format submissions
  using ordered uploads; final pixels and resource counters verify retirement.

The fixture uses real frontend SPIR-V modules produced by shaderc and translated
by the production path. These are synthetic shaders applied to vanilla layouts,
not actual vanilla shader/pipeline parity or gameplay rendering.

Run in the user's separate normal macOS Terminal:

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/smoke_test.py
```

The complete run printed `PASS: vanilla vertex layouts`, every earlier PASS and
the final overall PASS. The full log was inspected and preserved. No repeat
needed for this exact increment or the independent window. Validated completion
is **42% (42.0% weighted)**, with shaders/pipelines/passes now at 50%.

Pre-increment source/tools/docs backup: `/tmp/metalmc-abi12-source`. A strict
javac warning about explicitly closing a try-with-resources encoder in the new
fixture was fixed; the final full build succeeds without suppressing lint.

## First user run: missing Java runtime dependency, fixed

At 20:46:41 on 2026-10-03, the user-run suite reached Apple M5 with Metal/JNI
validation and passed every earlier suite, including indirect draws. Loading
DefaultVertexFormat then failed with `NoClassDefFoundError:
com/google/common/base/Ticker`, through VertexFormat.Builder -> Mth -> Util.
No new vertex-format pixels or boundaries ran. The complete failure is preserved
as `build/reports/vertex-formats-smoke-missing-runtime.log`.

The standalone smoke runner now includes the installed game's complete
unclassified Java runtime libraries resolved from 26.3 metadata, rather than
a narrow selection of backend dependencies. All 54 Java runtime JARs exist;
read-only archive inspection confirmed Guava Ticker, logging/SLF4J, DataFixerUpper
and Commons Lang classes. Native/classified libraries remain on the explicit
arm64 LWJGL path. No dependencies were downloaded or bundled, no game profile
was modified, and no tests were run by the agent. The fix keeps ABI 13.
Gradle test runtime declarations include the newly required game dependencies;
Loom remains unvalidated. Full strict offline build and all test compilation
passed after the classpath fix. Rerun the command above in the user's Terminal.

## Second user run: missing jemalloc native dependency, fixed

At 21:39:48 on 2026-10-03, the repeat passed resource/transfer/transient/pass
checks and failed in ShaderTranslationTest with `UnsatisfiedLinkError:
Failed to locate library: libjemalloc.dylib`. Adding all Java modules had made
LWJGL auto-select jemalloc without including its native implementation. This
classpath regression precedes the new vertex-format checks. Complete failure
is preserved as `build/reports/vertex-formats-smoke-missing-jemalloc.log`.

`game_runtime_jars` now resolves installed Java libraries together with matching
macOS arm64 classified/native libraries from game metadata: 67 artifacts (54
Java plus 13 native/classified). It excludes foreign OS/x86 native artifacts.
Archive inspection confirmed native payloads, and `file` confirms the installed
jemalloc payload is arm64 Mach-O. The smoke runner deduplicates this full
runtime classpath; Gradle also declares paired jemalloc Java/native and jtracy
arm64 test dependencies. No downloads or dependency bundling were added.
Strict full offline build and all test compilation passed again. ABI stays 13;
no new tests were launched by the agent. A new manual complete run is required,
and validated progress stays **41%**.

## Final complete PASS

The user reran the full suite at 21:53:40–54 on 2026-10-03 and reported all tests
passed. The agent inspected the complete log: all sixteen real vanilla layouts,
SNORM/signed integer pixels and new format lifecycle/boundary tests passed with
every prior suite. Only the known LWJGL Unsafe warning remains. Both earlier
classpath failures stay preserved; the fixed full 67-artifact runtime is now
validated. No dependencies were downloaded or bundled, and no game profile
changed. The next ABI 14 device-reporting gate is independent of this completed
ABI 13 validation.
