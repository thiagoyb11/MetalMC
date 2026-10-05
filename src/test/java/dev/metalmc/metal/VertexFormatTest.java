package dev.metalmc.metal;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Real installed vanilla layouts with synthetic shaders, not full vanilla shader parity. */
final class VertexFormatTest {
    private static final int TARGET_USAGE = 1 | 2 | 8;
    private static final String FRAGMENT = """
            #version 450
            layout(location=0) in vec4 tint;
            layout(location=0) out vec4 color;
            void main() { color=tint; }
            """;
    private static final float[][] POINTS = {{-1,-1}, {3,-1}, {-1,3}};
    private VertexFormatTest() {}

    static void run() {
        verifyInstalledLayouts();
        verifySignedAndNormalizedPixels();
        verifyAlignmentAndTypes();
        verifyTransientAndCancellation();
        verifyAsyncRetirement();
        System.out.println("PASS: vanilla vertex layouts, signed short lightmaps/overlays, SNORM vec3/vec4 normals, exact integer chunk positions, alignment/type/range checks, transient and retained format draws.");
    }
    private static SPIRVModule module(String source, ShaderType stage) {
        byte[] bytes = ShaderTranslationTest.compile(source, stage == ShaderType.VERTEX ? shaderc_vertex_shader : shaderc_fragment_shader);
        var owned = MemoryUtil.memAlloc(bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        owned.put(bytes).flip();
        return new SPIRVModule(owned, stage);
    }
    private static BackendRenderPipeline.CreateInfo info(SPIRVModule vertex, SPIRVModule fragment,
            List<BackendRenderPipeline.CreateInfo.VertexBuffer> layouts, List<BackendRenderPipeline.CreateInfo.AttribBinding> attributes) {
        return new BackendRenderPipeline.CreateInfo("vanilla vertex format fixture", List.of(
                new BackendRenderPipeline.CreateInfo.Shader("vertex", "main", vertex),
                new BackendRenderPipeline.CreateInfo.Shader("fragment", "main", fragment)),
                layouts, attributes, List.of(), 0, new DepthStencilState(CompareOp.ALWAYS_PASS, false),
                PolygonMode.FILL, false, List.of(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL)),
                PrimitiveTopology.TRIANGLES);
    }
    private static MetalRenderPipeline pipeline(MetalContext context, String source,
            List<BackendRenderPipeline.CreateInfo.VertexBuffer> layouts, List<BackendRenderPipeline.CreateInfo.AttribBinding> attributes) {
        try (var vertex = module(source, ShaderType.VERTEX); var fragment = module(FRAGMENT, ShaderType.FRAGMENT)) {
            return (MetalRenderPipeline)context.compilePipeline(info(vertex, fragment, layouts, attributes)).finishCompile();
        }
    }
    private static RenderPassDescriptor descriptor(MetalGpuTextureView view) {
        return RenderPassDescriptor.builder(() -> "vertex format pixels")
                .withColorAttachment(view, Optional.of(TextureCommandTest.color(0, 0, 1, 1))).build();
    }
    private static void waitFor(MetalCommandEncoder encoder) {
        try (var fence = encoder.createFence()) {
            encoder.submit();
            check(fence.awaitCompletion(GpuFence.NO_TIMEOUT), "Vertex-format submission did not complete");
        }
    }
    private static void pixels(byte[] data, int... expected) {
        for (int i = 0; i < data.length; i++) {
            int actual = Byte.toUnsignedInt(data[i]);
            check(Math.abs(actual - expected[i % 4]) <= 1, "Vertex-format pixel byte " + i + ": " + actual + " vs " + expected[i % 4]);
        }
    }

    // These are the actual format objects from 26.3, not copied layout definitions.
    private static void verifyInstalledLayouts() {
        var formats = List.of(DefaultVertexFormat.BLOCK, DefaultVertexFormat.CHUNK_DATA_INSTANCED,
                DefaultVertexFormat.ENTITY, DefaultVertexFormat.ENTITY_GLINT_SPECIAL, DefaultVertexFormat.PARTICLE,
                DefaultVertexFormat.POSITION, DefaultVertexFormat.POSITION_COLOR, DefaultVertexFormat.POSITION_COLOR_NORMAL,
                DefaultVertexFormat.POSITION_COLOR_LIGHTMAP, DefaultVertexFormat.POSITION_TEX, DefaultVertexFormat.POSITION_TEX_COLOR,
                DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR,
                DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL, DefaultVertexFormat.POSITION_COLOR_LINE_WIDTH,
                DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH);
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder();
                var target = context.createTexture(null, TARGET_USAGE, GpuFormat.RGBA8_UNORM, 9, 7, 1, 1);
                var view = context.createTextureView(target, 0, 1)) {
            for (var format : formats) {
                var attributes = new ArrayList<BackendRenderPipeline.CreateInfo.AttribBinding>();
                var source = new StringBuilder("#version 450\nlayout(location=0) out vec4 tint;\n");
                var checks = new StringBuilder("bool valid=true;\n");
                int positionLocation = -1;
                for (var element : format.getElements()) {
                    int location = attributes.size();
                    attributes.add(new BackendRenderPipeline.CreateInfo.AttribBinding(2, location, element.offset(), element.format()));
                    String type = switch (element.format()) {
                        case RGB32_FLOAT -> "vec3"; case RGBA8_UNORM -> "vec4"; case RG32_FLOAT -> "vec2";
                        case RG16_SINT -> "ivec2"; case RGBA8_SNORM -> "vec3";
                        case RGB32_SINT -> "ivec3"; case R32_FLOAT -> "float";
                        default -> throw new AssertionError("Unrecognized installed vanilla format " + element);
                    };
                    source.append("layout(location=").append(location).append(") in ").append(type).append(" a").append(location).append(";\n");
                    if (element.name().equals("Position")) { positionLocation = location; continue; }
                    String comparison = switch (element.format()) {
                        case RGBA8_UNORM -> "all(lessThan(abs(a%d-vec4(0.25,0.5,0.75,1)),vec4(0.003)))";
                        case RG32_FLOAT -> "all(equal(a%d,vec2(0.25,-0.5)))";
                        case RG16_SINT -> "all(equal(a%d,ivec2(-32768,32767)))";
                        case RGBA8_SNORM -> "all(lessThan(abs(a%d-vec3(-1,-64.0/127.0,1)),vec3(0.00001)))";
                        case RGB32_SINT -> "all(equal(a%d,ivec3(-16777217,16777219,-2147483647)))";
                        case R32_FLOAT -> "a%d==0.75";
                        default -> throw new AssertionError("Unrecognized vanilla semantic " + element);
                    };
                    checks.append("valid=valid&&(").append(comparison.formatted(location)).append(");\n");
                }
                source.append("void main(){\n").append(checks);
                if (positionLocation >= 0) source.append("gl_Position=vec4(a").append(positionLocation).append(",1);\n");
                else source.append("vec2 p[3]=vec2[3](vec2(-1,-1),vec2(3,-1),vec2(-1,3)); gl_Position=vec4(p[gl_VertexIndex],0.5,1);\n");
                source.append("tint=valid?vec4(1):vec4(1,0,1,1);}\n");
                var layouts = List.of(new BackendRenderPipeline.CreateInfo.VertexBuffer(2, format.getVertexSize(), format.getStepRate()));
                try (var p = pipeline(context, source.toString(), layouts, attributes);
                        var buffer = context.createBuffer(GpuBuffer.USAGE_VERTEX, vanillaBytes(format))) {
                    var pass = encoder.createRenderPass(descriptor(view)); pass.setPipeline(p);
                    pass.setVertexBuffer(2, buffer.slice(16, buffer.size() - 16));
                    if (format.getStepRate() == 0) pass.draw(3, 1, 3, 0);
                    else pass.draw(3, 1, 0, 2);
                    encoder.submitRenderPass(); waitFor(encoder);
                    pixels(target.readDiagnostic(0, 0), 255, 255, 255, 255);
                }
            }
            check(context.pipelineCount() == 0 && context.commandStats()[4] == 0, "Vanilla layout draws retained resources");
        }
    }
    private static ByteBuffer vanillaBytes(VertexFormat format) {
        int rows = format.getStepRate() == 0 ? 6 : 3;
        var data = ByteBuffer.allocate(16 + rows * format.getVertexSize()).order(ByteOrder.LITTLE_ENDIAN);
        // Earlier vertices/instances contain zeros; requested firstVertex/firstInstance selects valid records.
        for (int row = format.getStepRate() == 0 ? 3 : 2; row < rows; row++) {
            for (var element : format.getElements()) {
                data.position(16 + row * format.getVertexSize() + element.offset());
                switch (element.format()) {
                    case RGB32_FLOAT -> data.putFloat(POINTS[row % 3][0]).putFloat(POINTS[row % 3][1]).putFloat(0.5f);
                    case RGBA8_UNORM -> data.put(new byte[]{64, (byte)128, (byte)191, (byte)255});
                    case RG32_FLOAT -> data.putFloat(0.25f).putFloat(-0.5f);
                    case RG16_SINT -> data.putShort(Short.MIN_VALUE).putShort(Short.MAX_VALUE);
                    case RGBA8_SNORM -> data.put(new byte[]{-128, -64, 127, -127});
                    case RGB32_SINT -> data.putInt(-16777217).putInt(16777219).putInt(-2147483647);
                    case R32_FLOAT -> data.putFloat(0.75f);
                    default -> throw new AssertionError("Unrecognized installed format");
                }
            }
        }
        data.position(data.capacity()); return data.flip();
    }

    private static final String NORMAL_VERTEX = """
            #version 450
            layout(location=0) in vec3 position;
            layout(location=1) in vec4 normal;
            layout(location=0) out vec4 tint;
            void main(){gl_Position=vec4(position,1);tint=normal*0.5+0.5;}
            """;
    private static List<BackendRenderPipeline.CreateInfo.VertexBuffer> normalLayouts() {
        return List.of(new BackendRenderPipeline.CreateInfo.VertexBuffer(0, 20, 0));
    }
    private static List<BackendRenderPipeline.CreateInfo.AttribBinding> normalAttributes(int offset) {
        return List.of(new BackendRenderPipeline.CreateInfo.AttribBinding(0, 0, 0, GpuFormat.RGB32_FLOAT),
                new BackendRenderPipeline.CreateInfo.AttribBinding(0, 1, offset, GpuFormat.RGBA8_SNORM));
    }
    private static ByteBuffer normalBytes(byte[] normal) {
        var data = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN);
        for (var point : POINTS) {
            int start = data.position();
            data.putFloat(point[0]).putFloat(point[1]).putFloat(0.5f);
            data.position(start + 13); data.put(normal); data.position(start + 20);
        }
        return data.flip();
    }
    private static void verifySignedAndNormalizedPixels() {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder();
                var target = context.createTexture(null, TARGET_USAGE, GpuFormat.RGBA8_UNORM, 8, 6, 1, 1);
                var view = context.createTextureView(target, 0, 1);
                var p = pipeline(context, NORMAL_VERTEX, normalLayouts(), normalAttributes(13))) {
            for (byte[] values : new byte[][]{{-128,-127,0,127}, {-64,64,126,-126}}) {
                try (var vertices = context.createBuffer(GpuBuffer.USAGE_VERTEX, normalBytes(values))) {
                    var pass = encoder.createRenderPass(descriptor(view)); pass.setPipeline(p); pass.setVertexBuffer(0, vertices.slice());
                    pass.draw(3, 1, 0, 0); encoder.submitRenderPass(); waitFor(encoder);
                    int[] expected = new int[4];
                    for (int c = 0; c < 4; c++) expected[c] = Math.round((Math.max(-1, values[c] / 127f) * 0.5f + 0.5f) * 255);
                    pixels(target.readDiagnostic(0, 0), expected);
                }
            }
        }
    }

    private static final String SHORT_VERTEX = """
            #version 450
            layout(location=0) in vec3 position;
            layout(location=1) in ivec2 light;
            layout(location=0) out vec4 tint;
            void main(){gl_Position=vec4(position,1);tint=all(equal(light,ivec2(-32768,32767)))?vec4(0,1,0,1):vec4(1,0,0,1);}
            """;
    private static List<BackendRenderPipeline.CreateInfo.AttribBinding> shortAttributes(int offset, GpuFormat format) {
        return List.of(new BackendRenderPipeline.CreateInfo.AttribBinding(0, 0, 0, GpuFormat.RGB32_FLOAT),
                new BackendRenderPipeline.CreateInfo.AttribBinding(0, 1, offset, format));
    }
    private static void verifyAlignmentAndTypes() {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder();
                var target = context.createTexture(null, TARGET_USAGE, GpuFormat.RGBA8_UNORM, 8, 6, 1, 1);
                var view = context.createTextureView(target, 0, 1);
                var p = pipeline(context, SHORT_VERTEX, normalLayouts(), shortAttributes(14, GpuFormat.RG16_SINT))) {
            var data = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN);
            for (var point : POINTS) {
                int start = data.position(); data.putFloat(point[0]).putFloat(point[1]).putFloat(0.5f);
                data.position(start + 14); data.putShort(Short.MIN_VALUE).putShort(Short.MAX_VALUE); data.position(start + 20);
            }
            data.flip();
            try (var vertices = context.createBuffer(GpuBuffer.USAGE_VERTEX, data)) {
                var pass = encoder.createRenderPass(descriptor(view)); pass.setPipeline(p);
                pass.setVertexBuffer(0, vertices.slice(0, 57));
                expect(IllegalArgumentException.class, () -> pass.draw(3, 1, 0, 0)); // last short pair ends at byte 58
                pass.setVertexBuffer(0, vertices.slice(0, 58)); pass.draw(3, 1, 0, 0);
                encoder.submitRenderPass(); waitFor(encoder); pixels(target.readDiagnostic(0, 0), 0, 255, 0, 255);
            }
            long count = context.pipelineCount();
            expect(IllegalArgumentException.class, () -> pipeline(context, SHORT_VERTEX, normalLayouts(), shortAttributes(13, GpuFormat.RG16_SINT)));
            expect(IllegalArgumentException.class, () -> pipeline(context, SHORT_VERTEX, normalLayouts(), shortAttributes(18, GpuFormat.RG16_SINT)));
            expect(IllegalArgumentException.class, () -> pipeline(context, SHORT_VERTEX, normalLayouts(), shortAttributes(12, GpuFormat.RG32_FLOAT)));
            expect(IllegalArgumentException.class, () -> pipeline(context, SHORT_VERTEX.replace("ivec2 light", "uvec2 light").replace("ivec2(-32768,32767)", "uvec2(0,1)"), normalLayouts(), shortAttributes(14, GpuFormat.RG16_SINT)));
            expect(IllegalArgumentException.class, () -> pipeline(context, NORMAL_VERTEX, normalLayouts(), shortAttributes(12, GpuFormat.RG16_SINT)));
            expect(UnsupportedOperationException.class, () -> pipeline(context, SHORT_VERTEX, normalLayouts(), shortAttributes(14, GpuFormat.RG16_UINT)));
            check(context.pipelineCount() == count, "Rejected vertex formats leaked pipelines");
            // Native metadata must reject invalid code, 16/32-bit alignment and full stored extent independently.
            try (var vertex = module(SHORT_VERTEX, ShaderType.VERTEX); var fragment = module(FRAGMENT, ShaderType.FRAGMENT)) {
                var source = MetalShaderTranslator.translate(new BackendRenderPipeline.CreateInfo.Shader("vertex", "main", vertex),
                        new MetalShaderTranslator.Options(false, false));
                var f = MetalShaderTranslator.translate(new BackendRenderPipeline.CreateInfo.Shader("fragment", "main", fragment), new MetalShaderTranslator.Options(false, false));
                for (int[] bad : new int[][]{{0,1,13,5}, {0,1,18,5}, {0,1,2,7}, {0,1,12,8}, {0,1,18,6}}) {
                    expect(IllegalArgumentException.class, () -> NativeMetal.createRenderPipeline(context.nativeHandle(), null,
                            source.msl(), source.entryPoint(), f.msl(), f.entryPoint(), new int[]{0,20,0},
                            new int[]{0,0,0,2, bad[0],bad[1],bad[2],bad[3]}, new int[]{3,15,0,0,0,0,0,0,0},
                            new int[]{7,0,0,0,3}, 0,0, new int[0],new int[0],0));
                }
            }
            check(context.pipelineCount() == count, "Rejected native formats leaked pipeline registration");
        }
    }
    private static void verifyTransientAndCancellation() {
        try (var context = MetalContext.create();
                var target = context.createTexture(null, TARGET_USAGE, GpuFormat.RGBA8_UNORM, 8, 6, 1, 1);
                var view = context.createTextureView(target, 0, 1);
                var p = pipeline(context, NORMAL_VERTEX, normalLayouts(), normalAttributes(13))) {
            byte[] before;
            try (var encoder = context.createCommandEncoder()) {
                var slice = encoder.transientMemory().uploadGpu(normalBytes(new byte[]{-128,0,127,127}), 4, GpuBuffer.USAGE_VERTEX);
                var pass = encoder.createRenderPass(descriptor(view)); pass.setPipeline(p); pass.setVertexBuffer(0, slice);
                pass.draw(3,1,0,0); encoder.submitRenderPass();
                try (var foreign = context.createCommandEncoder()) {
                    var next = foreign.createRenderPass(descriptor(view)); next.setPipeline(p);
                    expect(IllegalArgumentException.class, () -> next.setVertexBuffer(0, slice));
                }
                waitFor(encoder);
                pixels(target.readDiagnostic(0,0),0,128,255,255);
                before = target.readDiagnostic(0,0);
                var expiredPass = encoder.createRenderPass(descriptor(view)); expiredPass.setPipeline(p);
                expect(IllegalStateException.class, () -> expiredPass.setVertexBuffer(0, slice));
                encoder.submitRenderPass(); // try-with-resources cancels this unsubmitted clear.
            }
            check(Arrays.equals(before, target.readDiagnostic(0,0)), "Cancelled format draw changed target");
        }
    }
    private static void verifyAsyncRetirement() {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder();
                var target = context.createTexture(null, TARGET_USAGE, GpuFormat.RGBA8_UNORM, 8, 6, 1, 1);
                var view = context.createTextureView(target, 0, 1)) {
            for (int i = 0; i < 16; i++) {
                var p = pipeline(context, NORMAL_VERTEX, normalLayouts(), normalAttributes(13));
                var vertices = context.createBuffer(GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, 60);
                encoder.writeToBuffer(vertices.slice(), normalBytes(new byte[]{(byte)i,0,127,127}));
                var pass = encoder.createRenderPass(descriptor(view)); pass.setPipeline(p); pass.setVertexBuffer(0, vertices.slice());
                p.close(); vertices.close(); pass.draw(3,1,0,0); encoder.submitRenderPass(); encoder.submit();
            }
            waitFor(encoder);
            pixels(target.readDiagnostic(0,0), Math.round((15/127f*0.5f+0.5f)*255),128,255,255);
            check(context.pipelineCount() == 0 && context.commandStats()[4] == 0 && context.commandStats()[2] == 0,
                    "Vertex-format stress retained closed GPU resources");
        }
    }
    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", received " + error, error);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
