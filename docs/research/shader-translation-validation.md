# SPIR-V → MSL translation — ABI 8

Implemented 2026-10-03. Strict offline Java/native compilation, JNI generation,
signing, packaging and test compilation succeed. **The complete user-run ABI 8
suite passed on Apple M5 at 17:28:25–27, with Metal API validation and JNI checks.**
The full log and generated MSL/binding reports were inspected. The PASS is preserved
in `build/reports/shader-translation-smoke-pass.log`. The earlier ABI 7 attachment
PASS remains preserved in `build/reports/render-pass-smoke-pass.log`.

## Translation contract

`MetalShaderTranslator` accepts the installed 26.3
`BackendRenderPipeline.CreateInfo.Shader` / `SpvModule` contract or an explicit
SPIR-V byte range, vertex/fragment stage, entry point and coordinate options.
It copies the bytes into owned little-endian storage, checks the header and
instruction lengths, selects the exact entry point/stage, and creates one
SPIRV-Cross context per call. Native compiler/reflection pointers never escape.
The frontend module is neither closed nor reflected, and its buffer range is
unchanged. Result strings and metadata lists are owned immutable Java data.

The installed LWJGL 3.4.3 SPIRV-Cross translates active resources to macOS MSL
2.3 with explicit resource remaps and argument buffers disabled. OpenGL depth
conversion and vertex Y inversion are explicit options; the eventual game
integration must establish its projection conventions before choosing them.
SPIRV-Cross documents these transformations and its C context ownership in its
[official project documentation](https://github.com/KhronosGroup/SPIRV-Cross).

## Initial subset and binding layout

| SPIR-V resource | Initial Metal mapping |
| --- | --- |
| Uniform block | Buffer slots 16–29; 14 blocks maximum, each at most 64 KiB |
| Push-constant block | Buffer slot 30; one block, at most 4 KiB |
| Separate non-depth 2D/2D-array image | Texture slots 0–15 |
| Separate sampler | Sampler slots 0–15 |
| Combined 2D/2D-array image/sampler | One texture and one sampler slot |

Resources are ordered by descriptor set and binding, with duplicate pairs
rejected. Buffer, texture and sampler namespaces are separate. Vertex and
fragment layouts are independent: future bindings must consult each stage's
table instead of assuming equal slots. Slots 0–15 in the buffer namespace are
reserved for the future vertex-buffer path; there is no such path yet.
Emitted resource indices are checked against the requested remaps after translation.

Block reflection preserves declared byte sizes and top-level member offsets,
array/matrix strides and row-major decorations. Nested fixed structs are accepted
with a depth cap of 32; reflection lists have a 256-entry cap. Stage interfaces
are located 32-bit scalars/vectors, with unique locations 0–31. Input SPIR-V is
capped at 4 MiB and generated MSL at 8 MiB characters. These are initial project
limits, not measured device capabilities.

Storage buffers/images, subpass inputs, atomic counters, acceleration structures,
shader-record buffers, plain GL uniforms, tensors, descriptor arrays, runtime
arrays, specialization constants, depth/comparison or multisample/cubemap images,
complex stage interfaces and auxiliary Metal resource requirements fail explicitly.
This is bounded structural validation and reflection, not a complete SPIR-V
semantic validator or vanilla shader compatibility claim.

## Native compilation diagnostic

`MetalContext.validateShaderDiagnostic` compiles the translated source into a
temporary MTLLibrary and resolves the returned entry point with the expected
vertex/fragment function type. It uses the context's actual Metal device, MSL
2.3 and disabled fast math. Temporary objects are released through ARC; it
does not create backend pipelines, record draws, submit commands or change
accounted GPU resources. Apple's
[runtime library compilation API](https://developer.apple.com/documentation/metal/mtldevice/makelibrary%28source%3Aoptions%3A%29?language=objc)
is the native entry point used here.

JNI ABI is now **8**, consistently declared by loader, headers and native code.
Offline compile/runtime classpaths resolve installed core/SPIRV-Cross/shaderc
Java and arm64 native JARs from Minecraft metadata, with deduplication. shaderc
only generates test fixtures; production accepts the game's existing SPIR-V.
No dependencies are downloaded or bundled. Gradle dependencies are declared,
but the Loom build remains unvalidated.

## Completed validation — user Terminal

`ShaderTranslationTest`, included in `NativeSmokeTest`, uses actual shaderc
SPIR-V with a row-major matrix, std140 arrays, offset push constants, separate
images/samplers and a combined sampler. It checks reflection, distinct descriptor
sets, slot remaps, source ownership, malformed/unsupported inputs, limits,
concurrent independent contexts and the actual frontend SPIRVModule adapter.
It then checks real Metal function compilation, 2D-array sampling compilation,
coordinate-option variants, JNI/stage/error/thread boundaries and unchanged
command statistics. All previous resource and attachment suites still run.
All checks passed in the user's 2026-10-03 run, followed by the initializer and
final offscreen-pixel PASS. The log includes a deprecation warning for
`sun.misc.Unsafe::objectFieldOffset` in installed LWJGL 3.4.3; the suite continued
and passed. It was not suppressed or treated as a Metal failure.

The command run by the user was (no repeat needed for this exact increment):

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/smoke_test.py
```

The successful suite printed `PASS: SPIR-V to MSL, real frontend module, ...`
and wrote `build/reports/shaders/reflected-vertex.metal`,
`reflected-fragment.metal` and `binding-layout.txt`. The full result is in
`build/reports/native-smoke.log` and its preserved copy. The generated vertex
function is `main0` with uniform buffer 16, push buffer 30, vertex attributes 0/1
and a row-major matrix multiplication. The fragment function uses texture slots
0/1 and sampler slots 0/1. These artifacts agree with the reflected layouts.

Next: native retained pipeline objects, vertex layouts and render-state mapping,
per-stage resource binding, draw/indexed-draw encoding, pixel parity and GPU
retirement. The game backend remains inactive. Validated project completion
is approximately **34%** (34.3% weighted); the bounded translation step credits
15% of the shaders/pipelines/passes stage. No backend pipeline or draw parity is claimed.
