package dev.metalmc.metal;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Actual installed shaderc/SPIRV-Cross and Metal function compilation, without draw claims. */
final class ShaderTranslationTest {
    private static final MetalShaderTranslator.Options VULKAN = new MetalShaderTranslator.Options(false, false);
    private static final String BLOCKS = """
            layout(set=3, binding=7, std140) uniform Scene {
                layout(row_major) mat4 transform;
                vec4 tint;
                vec4 extra[2];
            } scene;
            layout(push_constant, std430) uniform Params {
                layout(offset=16) vec4 factor;
            } params;
            """;
    private static final String VERTEX = "#version 450\n" + BLOCKS + """
            layout(location=0) in vec3 Position;
            layout(location=1) in vec4 Color;
            layout(location=0) out vec4 vertexColor;
            void main() {
                gl_Position = scene.transform * vec4(Position, 1.0);
                gl_Position.x += params.factor.x;
                vertexColor = Color * scene.tint + scene.extra[1];
            }
            """;
    private static final String FRAGMENT = "#version 450\n" + BLOCKS + """
            layout(location=0) in vec4 vertexColor;
            layout(location=0) out vec4 color;
            layout(set=4, binding=9) uniform sampler2D combinedImage;
            layout(set=0, binding=9) uniform texture2D separateImage;
            layout(set=2, binding=9) uniform sampler separateSampler;
            void main() {
                color = vertexColor * scene.tint * params.factor
                      + texture(combinedImage, vec2(0.25, 0.5))
                      + texture(sampler2D(separateImage, separateSampler), vec2(0.75, 0.5));
            }
            """;
    private ShaderTranslationTest() {}

    static void run() throws Exception {
        byte[] vertex = compile(VERTEX, shaderc_vertex_shader);
        byte[] fragment = compile(FRAGMENT, shaderc_fragment_shader);
        var vs = translate(vertex, ShaderType.VERTEX, VULKAN);
        var fs = translate(fragment, ShaderType.FRAGMENT, VULKAN);
        verifyReflection(vs, fs);
        verifyInputOwnership(vertex, vs);
        verifyErrors(vertex);
        verifyConcurrency(vertex, vs);
        try (var context = MetalContext.create()) {
            long[] stats = context.commandStats();
            context.validateShaderDiagnostic(vs);
            context.validateShaderDiagnostic(fs);
            var gl = translate(vertex, ShaderType.VERTEX, new MetalShaderTranslator.Options(true, false));
            var flip = translate(vertex, ShaderType.VERTEX, new MetalShaderTranslator.Options(false, true));
            check(!gl.msl().equals(vs.msl()) && !flip.msl().equals(vs.msl()), "Explicit coordinate options did not change vertex MSL");
            context.validateShaderDiagnostic(gl); context.validateShaderDiagnostic(flip);
            // Exact installed frontend module ownership: it memFrees its SPIR-V on close.
            var owned = MemoryUtil.memAlloc(vertex.length).order(ByteOrder.LITTLE_ENDIAN);
            owned.put(vertex).flip();
            try (var module = new SPIRVModule(owned, ShaderType.VERTEX)) {
                int position = module.spv().position(), limit = module.spv().limit();
                var shader = new BackendRenderPipeline.CreateInfo.Shader("real 26.3 frontend module", "main", module);
                var adapted = MetalShaderTranslator.translate(shader, VULKAN);
                check(adapted.msl().equals(vs.msl()), "Real SpvModule adapter changed translation");
                check(module.spv().position() == position && module.spv().limit() == limit, "SpvModule bytes mutated");
                check(module.getReflectionInfoIfAvailable() == null, "Translator mutated frontend reflection ownership");
                context.validateShaderDiagnostic(adapted);
            }
            var array = translate(compile("""
                    #version 450
                    layout(set=1,binding=5) uniform sampler2DArray atlas;
                    layout(location=0) out vec4 color;
                    void main() { color = texture(atlas, vec3(0.5,0.5,1)); }
                    """, shaderc_fragment_shader), ShaderType.FRAGMENT, VULKAN);
            context.validateShaderDiagnostic(array);
            expect(IllegalStateException.class, () -> NativeMetal.validateShaderDiagnostic(context.nativeHandle(), "this is invalid MSL", "main", 0));
            expect(IllegalArgumentException.class, () -> NativeMetal.validateShaderDiagnostic(context.nativeHandle(), vs.msl(), "missing_function", 0));
            expect(IllegalArgumentException.class, () -> NativeMetal.validateShaderDiagnostic(context.nativeHandle(), vs.msl(), vs.entryPoint(), 1));
            expect(IllegalArgumentException.class, () -> NativeMetal.validateShaderDiagnostic(context.nativeHandle(), vs.msl(), vs.entryPoint(), 2));
            expect(IllegalArgumentException.class, () -> NativeMetal.validateShaderDiagnostic(context.nativeHandle(), null, vs.entryPoint(), 0));
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread foreign = new Thread(() -> { try { context.validateShaderDiagnostic(vs); } catch (Throwable failure) { error.set(failure); } });
            foreign.start(); foreign.join();
            check(error.get() instanceof IllegalStateException, "Foreign-thread Metal function compilation accepted");
            // Translation/temporary Metal compilation must not record commands or change resource accounting.
            check(Arrays.equals(stats, context.commandStats()), "Shader validation recorded GPU work or leaked accounted resources");
            Path directory = Path.of("build/reports/shaders"); Files.createDirectories(directory);
            Files.writeString(directory.resolve("reflected-vertex.metal"), vs.msl());
            Files.writeString(directory.resolve("reflected-fragment.metal"), fs.msl());
            Files.writeString(directory.resolve("binding-layout.txt"), vs.toString() + "\n" + fs.toString());
        }
        expect(IllegalStateException.class, () -> NativeMetal.validateShaderDiagnostic(0, vs.msl(), vs.entryPoint(), 0));
        System.out.println("PASS: SPIR-V to MSL, real frontend module, reflected/remapped uniforms/images/samplers/push constants, ownership and Metal function compilation (no draws).");
    }
    private static void verifyReflection(MetalShaderTranslator.Translation vertex, MetalShaderTranslator.Translation fragment) {
        var uniform = binding(vertex, MetalShaderTranslator.ResourceKind.UNIFORM_BUFFER, 3, 7);
        check(uniform.metalBuffer() == 16 && uniform.byteSize() == 112 && uniform.members().size() == 3, "Uniform block mapping/size mismatch");
        check(uniform.members().get(0).offset() == 0 && uniform.members().get(0).matrixStride() == 16
                && uniform.members().get(0).rowMajor(), "Matrix layout reflection mismatch");
        check(uniform.members().get(1).offset() == 64 && uniform.members().get(2).offset() == 80
                && uniform.members().get(2).arrayStride() == 16, "Uniform offsets/array stride mismatch");
        var push = vertex.bindings().stream().filter(b -> b.kind() == MetalShaderTranslator.ResourceKind.PUSH_CONSTANT).findFirst().orElseThrow();
        check(push.metalBuffer() == 30 && push.byteSize() == 32 && push.members().getFirst().offset() == 16, "Push-constant offset/size/mapping mismatch");
        var image = binding(fragment, MetalShaderTranslator.ResourceKind.IMAGE, 0, 9);
        var sampler = binding(fragment, MetalShaderTranslator.ResourceKind.SAMPLER, 2, 9);
        var combined = binding(fragment, MetalShaderTranslator.ResourceKind.COMBINED_IMAGE_SAMPLER, 4, 9);
        check(image.metalTexture() == 0 && image.metalSampler() == -1 && sampler.metalSampler() == 0
                && sampler.metalTexture() == -1 && combined.metalTexture() == 1 && combined.metalSampler() == 1,
                "Distinct descriptor sets or separate namespaces collided");
        check(vertex.inputs().size() == 2 && vertex.inputs().get(0).location() == 0
                && vertex.inputs().get(0).components() == 3 && vertex.inputs().get(1).location() == 1,
                "Vertex attribute reflection mismatch");
        check(vertex.outputs().size() == 1 && fragment.inputs().size() == 1 && fragment.outputs().size() == 1,
                "Stage interface reflection mismatch");
        expect(UnsupportedOperationException.class, () -> vertex.bindings().clear());
        expect(UnsupportedOperationException.class, () -> uniform.members().clear());
    }
    private static MetalShaderTranslator.Binding binding(MetalShaderTranslator.Translation translation,
            MetalShaderTranslator.ResourceKind kind, int set, int binding) {
        return translation.bindings().stream().filter(b -> b.kind() == kind && b.descriptorSet() == set && b.binding() == binding).findFirst().orElseThrow();
    }
    private static void verifyInputOwnership(byte[] spirv, MetalShaderTranslator.Translation expected) {
        ByteBuffer source = ByteBuffer.allocateDirect(spirv.length + 11).order(ByteOrder.BIG_ENDIAN);
        source.position(3).put(spirv).limit(3 + spirv.length).position(3);
        var translated = MetalShaderTranslator.translate("positioned read-only source", source.asReadOnlyBuffer(), ShaderType.VERTEX, "main", VULKAN);
        check(source.position() == 3 && source.limit() == 3 + spirv.length, "Translation changed source range");
        source.put(3, (byte)0);
        check(translated.msl().equals(expected.msl()), "Source buffer escaped translation ownership");
    }
    private static void verifyErrors(byte[] vertex) {
        expect(IllegalArgumentException.class, () -> translate(new byte[19], ShaderType.VERTEX, VULKAN));
        expect(IllegalArgumentException.class, () -> translate(new byte[24], ShaderType.VERTEX, VULKAN));
        var truncated = Arrays.copyOf(vertex, vertex.length - 1);
        expect(IllegalArgumentException.class, () -> translate(truncated, ShaderType.VERTEX, VULKAN));
        byte[] bad = vertex.clone(); ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 0);
        expect(IllegalArgumentException.class, () -> translate(bad, ShaderType.VERTEX, VULKAN));
        expect(IllegalArgumentException.class, () -> translate(vertex, ShaderType.FRAGMENT, VULKAN));
        expect(IllegalArgumentException.class, () -> MetalShaderTranslator.translate("missing", ByteBuffer.wrap(vertex), ShaderType.VERTEX, "does_not_exist", VULKAN));
        expect(IllegalArgumentException.class, () -> MetalShaderTranslator.translate("nul", ByteBuffer.wrap(vertex), ShaderType.VERTEX, "main\0", VULKAN));
        String[] unsupported = {
                "layout(set=0,binding=0,std430) buffer Storage { float value; } storage; void main() { color=vec4(storage.value); }",
                "layout(set=0,binding=0) uniform sampler2D images[2]; void main() { color=texture(images[1],vec2(0.5)); }",
                "layout(set=0,binding=0) uniform samplerCube cubeImage; void main() { color=texture(cubeImage,vec3(1,0,0)); }",
                "layout(constant_id=0) const float value=1; void main() { color=vec4(value); }"
        };
        for (String source : unsupported) {
            byte[] words = compile("#version 450\nlayout(location=0) out vec4 color;\n" + source, shaderc_fragment_shader);
            expect(UnsupportedOperationException.class, () -> translate(words, ShaderType.FRAGMENT, VULKAN));
        }
        var excessive = new StringBuilder("#version 450\nlayout(location=0) out vec4 color;\n");
        for (int i = 0; i < 15; i++) excessive.append("layout(set=0,binding=").append(i)
                .append(",std140) uniform Block").append(i).append(" { vec4 value; } b").append(i).append(";\n");
        excessive.append("void main() { color=");
        for (int i = 0; i < 15; i++) { if (i > 0) excessive.append('+'); excessive.append('b').append(i).append(".value"); }
        excessive.append("; }");
        var words = compile(excessive.toString(), shaderc_fragment_shader);
        expect(UnsupportedOperationException.class, () -> translate(words, ShaderType.FRAGMENT, VULKAN));
    }
    private static void verifyConcurrency(byte[] vertex, MetalShaderTranslator.Translation expected) throws InterruptedException {
        AtomicReference<Throwable> error = new AtomicReference<>();
        Runnable work = () -> {
            try { for (int i = 0; i < 8; i++) check(translate(vertex, ShaderType.VERTEX, VULKAN).msl().equals(expected.msl()), "Concurrent translation changed MSL"); }
            catch (Throwable failure) { error.compareAndSet(null, failure); }
        };
        Thread a = new Thread(work), b = new Thread(work); a.start(); b.start(); a.join(); b.join();
        if (error.get() != null) throw new AssertionError("Per-call shader context ownership failed", error.get());
    }
    private static MetalShaderTranslator.Translation translate(byte[] words, ShaderType stage, MetalShaderTranslator.Options options) {
        return MetalShaderTranslator.translate("test shader", ByteBuffer.wrap(words), stage, "main", options);
    }
    static byte[] compile(String source, int stage) {
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) throw new AssertionError("Could not create installed shaderc compiler");
        long options = 0, result = 0;
        try {
            options = shaderc_compile_options_initialize();
            if (options == 0) throw new AssertionError("Could not create shaderc options");
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_0);
            shaderc_compile_options_set_generate_debug_info(options);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_zero);
            result = shaderc_compile_into_spv(compiler, source, stage, "metalmc-test.glsl", "main", options);
            if (result == 0 || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new AssertionError("Test GLSL compilation failed: " + (result == 0 ? "no result" : shaderc_result_get_error_message(result)));
            }
            var words = shaderc_result_get_bytes(result); byte[] copy = new byte[words.remaining()]; words.get(copy);
            return copy;
        } finally {
            if (result != 0) shaderc_result_release(result);
            if (options != 0) shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }
    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", received " + failure, failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
