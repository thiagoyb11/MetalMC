package dev.metalmc.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import dev.metalmc.backend.MetalGpuDeviceBackend;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Real SPIR-V -> retained PSOs -> direct rasterization -> pixel/depth readback. */
final class PipelineDrawTest {
    private static final int TEXTURE_USAGE = 1 | 2 | 8;
    private static final String VERTEX = """
            #version 450
            layout(location=0) in vec3 position;
            layout(location=1) in vec4 color;
            layout(location=0) out vec4 tint;
            void main() { gl_Position = vec4(position, 1); tint = color; }
            """;
    private static final String FRAGMENT = """
            #version 450
            layout(location=0) in vec4 tint;
            layout(location=0) out vec4 outputColor;
            void main() { outputColor = tint; }
            """;
    private static final List<BackendRenderPipeline.CreateInfo.VertexBuffer> LAYOUTS = List.of(new BackendRenderPipeline.CreateInfo.VertexBuffer(3, 16, 0));
    private static final List<BackendRenderPipeline.CreateInfo.AttribBinding> ATTRIBUTES = List.of(
            new BackendRenderPipeline.CreateInfo.AttribBinding(3, 0, 0, GpuFormat.RGB32_FLOAT),
            new BackendRenderPipeline.CreateInfo.AttribBinding(3, 1, 12, GpuFormat.RGBA8_UNORM));
    private PipelineDrawTest() {}

    static void run() throws Exception {
        verifyPixelsAndRetirement();
        verifyBlendAndScissor();
        verifyDepth();
        verifyInstancesAndTransient();
        verifyBoundariesAndCancellation();
        System.out.println("PASS: retained Metal pipelines, exact frontend modules, vertex layouts/offsets, direct draws/instances, blend/write masks, depth/scissor pixels, cancellation and GPU retirement.");
    }
    private static SPIRVModule module(String source, ShaderType type) {
        byte[] bytes = ShaderTranslationTest.compile(source, type == ShaderType.VERTEX ? shaderc_vertex_shader : shaderc_fragment_shader);
        ByteBuffer owned = MemoryUtil.memAlloc(bytes.length).order(ByteOrder.LITTLE_ENDIAN); owned.put(bytes).flip();
        return new SPIRVModule(owned, type);
    }
    private static BackendRenderPipeline.CreateInfo info(SPIRVModule vertex, SPIRVModule fragment,
            List<BackendRenderPipeline.CreateInfo.VertexBuffer> layouts, List<BackendRenderPipeline.CreateInfo.AttribBinding> attributes,
            ColorTargetState color, CompareOp compare, boolean writeDepth, boolean cull) {
        return new BackendRenderPipeline.CreateInfo("pipeline GPU fixture", List.of(
                new BackendRenderPipeline.CreateInfo.Shader("vertex", "main", vertex),
                new BackendRenderPipeline.CreateInfo.Shader("fragment", "main", fragment)), layouts, attributes,
                List.of(), 0, new DepthStencilState(compare, writeDepth), PolygonMode.FILL, cull, List.of(color), PrimitiveTopology.TRIANGLES);
    }
    private static MetalRenderPipeline pipeline(MetalContext context, ColorTargetState color, CompareOp compare, boolean writeDepth) {
        try (var vertex = module(VERTEX, ShaderType.VERTEX); var fragment = module(FRAGMENT, ShaderType.FRAGMENT)) {
            var pending = context.compilePipeline(info(vertex, fragment, LAYOUTS, ATTRIBUTES, color, compare, writeDepth, false));
            var result = (MetalRenderPipeline)pending.finishCompile();
            expect(IllegalStateException.class, pending::finishCompile);
            check(vertex.getReflectionInfoIfAvailable() == null, "Pipeline changed module reflection ownership");
            return result; // Modules close now, PSOs must remain valid.
        }
    }
    private static ColorTargetState color(Optional<BlendFunction> blend, int mask) {
        return new ColorTargetState(blend, GpuFormat.RGBA8_UNORM, mask);
    }
    private static ByteBuffer triangles(int prefixBytes, float[] depths, int[][] colors) {
        var data = ByteBuffer.allocate(prefixBytes + depths.length * 48).order(ByteOrder.LITTLE_ENDIAN);
        data.position(prefixBytes);
        for (int triangle = 0; triangle < depths.length; triangle++) {
            float[][] positions = {{-1,-1}, {3,-1}, {-1,3}};
            for (var point : positions) {
                data.putFloat(point[0]).putFloat(point[1]).putFloat(depths[triangle]);
                for (int component : colors[triangle]) data.put((byte)component);
            }
        }
        return data.flip();
    }
    private static RenderPassDescriptor descriptor(MetalGpuTextureView view, MetalGpuTextureView depth, float r, float g, float b, float a) {
        var builder = RenderPassDescriptor.builder(() -> "pipeline pixels").withColorAttachment(view,
                Optional.of(TextureCommandTest.color(r, g, b, a)));
        if (depth != null) builder.withDepthAttachment(depth, OptionalDouble.of(1));
        return builder.build();
    }
    private static void waitFor(MetalCommandEncoder encoder) {
        try (var fence = encoder.createFence()) { encoder.submit(); check(fence.awaitCompletion(GpuFence.NO_TIMEOUT), "Draw submission did not complete"); }
    }
    private static void pixels(byte[] data, int width, int height, int[] inside, int[] outside, int x, int y, int w, int h) {
        for (int py = 0; py < height; py++) for (int px = 0; px < width; px++) {
            int[] expected = px >= x && px < x + w && py >= y && py < y + h ? inside : outside;
            for (int c = 0; c < 4; c++) {
                int actual = Byte.toUnsignedInt(data[(py * width + px) * 4 + c]);
                check(Math.abs(actual - expected[c]) <= 1, "Draw pixel mismatch at " + px + "," + py + " channel " + c + ": " + actual + " vs " + expected[c]);
            }
        }
    }
    private static void verifyPixelsAndRetirement() {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder();
                var target = context.createTexture(null, TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, 17, 11, 1, 1);
                var view = context.createTextureView(target, 0, 1)) {
            var pipeline = pipeline(context, color(Optional.empty(), ColorTargetState.WRITE_ALL), CompareOp.ALWAYS_PASS, false);
            // Draw order is (vertexCount, instanceCount, firstVertex, firstInstance), not first/count.
            var vertices = context.createBuffer(GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_READ,
                    triangles(16, new float[]{0.9f, 0.25f}, new int[][]{{0,0,0,0}, {128,64,255,128}}));
            var pass = encoder.createRenderPass(descriptor(view, null, 0, 0, 0, 0)); pass.setPipeline(pipeline);
            pass.setVertexBuffer(3, vertices.slice(16, 96));
            expect(IllegalArgumentException.class, () -> pass.draw(4, 1, 3, 0));
            expect(IllegalStateException.class, () -> vertices.map(true, false));
            pipeline.close(); vertices.close();
            check(context.pipelineCount() == 1, "Recorded pipeline not retained after close");
            pass.draw(3, 1, 3, 0); encoder.submitRenderPass(); waitFor(encoder);
            pixels(target.readDiagnostic(0, 0), 17, 11, new int[]{128,64,255,128}, null, 0, 0, 17, 11);
            check(context.pipelineCount() == 0 && context.commandStats()[4] == 0, "Completed draw retained closed pipeline/buffer");
            // Asynchronous repeated submissions must hold the PSO even with Java close before completion.
            for (int i = 0; i < 16; i++) {
                var p = pipeline(context, color(Optional.empty(), ColorTargetState.WRITE_ALL), CompareOp.ALWAYS_PASS, false);
                var v = context.createBuffer(GpuBuffer.USAGE_VERTEX, triangles(0, new float[]{0.5f}, new int[][]{{i,20,30,255}}));
                var next = encoder.createRenderPass(descriptor(view, null, 0, 0, 0, 0)); next.setPipeline(p); next.setVertexBuffer(3, v.slice());
                next.draw(3,1,0,0); p.close(); v.close(); encoder.submitRenderPass(); encoder.submit();
            }
            waitFor(encoder);
            pixels(target.readDiagnostic(0, 0), 17, 11, new int[]{15,20,30,255}, null, 0,0,17,11);
            check(context.pipelineCount() == 0 && context.commandStats()[2] == 0, "Draw stress failed to retire pipelines");
        }
    }
    private static void verifyBlendAndScissor() {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder();
                var target = context.createTexture(null, TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, 13, 9, 1, 1);
                var view = context.createTextureView(target, 0, 1);
                var pipeline = pipeline(context, color(Optional.of(BlendFunction.TRANSLUCENT), ColorTargetState.WRITE_RED | ColorTargetState.WRITE_BLUE), CompareOp.ALWAYS_PASS, false);
                var vertices = context.createBuffer(GpuBuffer.USAGE_VERTEX, triangles(0, new float[]{0.5f}, new int[][]{{255,0,0,128}}))) {
            var pass = encoder.createRenderPass(descriptor(view, null, 0, 0.25f, 1, 1));
            pass.setPipeline(pipeline); pass.setVertexBuffer(3, vertices.slice()); pass.enableScissor(2,1,7,5); pass.draw(3,1,0,0);
            encoder.submitRenderPass(); waitFor(encoder);
            pixels(target.readDiagnostic(0,0),13,9,new int[]{128,64,127,255},new int[]{0,64,255,255},2,1,7,5);
            pass = encoder.createRenderPass(descriptor(view, null, 0, 0.25f, 1, 1));
            pass.setPipeline(pipeline); pass.setVertexBuffer(3, vertices.slice()); pass.enableScissor(1,1,1,1); pass.disableScissor(); pass.draw(3,1,0,0);
            encoder.submitRenderPass(); waitFor(encoder);
            pixels(target.readDiagnostic(0,0),13,9,new int[]{128,64,127,255},null,0,0,13,9);
        }
    }
    private static void verifyDepth() {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder();
                var target = context.createTexture(null, TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, 10, 7, 1, 1);
                var depth = context.createTexture(null, TEXTURE_USAGE, GpuFormat.D32_FLOAT, 10, 7, 1, 1);
                var view = context.createTextureView(target, 0, 1); var dv = context.createTextureView(depth, 0, 1);
                var pipeline = pipeline(context, color(Optional.empty(), ColorTargetState.WRITE_ALL), CompareOp.LESS_THAN, true);
                var vertices = context.createBuffer(GpuBuffer.USAGE_VERTEX, triangles(0, new float[]{0.25f,0.75f}, new int[][]{{255,0,0,255},{0,255,0,255}}))) {
            var pass = encoder.createRenderPass(descriptor(view, dv, 0, 0, 1, 1)); pass.setPipeline(pipeline); pass.setVertexBuffer(3, vertices.slice());
            pass.draw(3,1,0,0); pass.draw(3,1,3,0); encoder.submitRenderPass(); waitFor(encoder);
            pixels(target.readDiagnostic(0,0),10,7,new int[]{255,0,0,255},null,0,0,10,7);
            var bytes = ByteBuffer.wrap(depth.readDiagnostic(0,0)).order(ByteOrder.LITTLE_ENDIAN);
            while (bytes.hasRemaining()) check(Math.abs(bytes.getFloat() - 0.25f) < 0.00001f, "Depth comparison/write/range mismatch");
            // The very same PSO wrapper must select its no-depth variant and disable depth state.
            pass = encoder.createRenderPass(descriptor(view, null, 0,0,1,1)); pass.setPipeline(pipeline); pass.setVertexBuffer(3,vertices.slice());
            pass.draw(3,1,0,0); pass.draw(3,1,3,0); encoder.submitRenderPass(); waitFor(encoder);
            pixels(target.readDiagnostic(0,0),10,7,new int[]{0,255,0,255},null,0,0,10,7);
        }
    }
    private static void verifyInstancesAndTransient() {
        String instanceVertex = """
                #version 450
                layout(location=0) in vec3 position;
                layout(location=1) in vec4 unusedColor;
                layout(location=2) in vec4 instanceColor;
                layout(location=0) out vec4 tint;
                void main() { gl_Position = vec4(position, 1); tint = instanceColor + unusedColor * 0.0; }
                """;
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder();
                var target = context.createTexture(null,TEXTURE_USAGE,GpuFormat.RGBA8_UNORM,8,6,1,1);
                var view = context.createTextureView(target,0,1);
                var vertex = module(instanceVertex,ShaderType.VERTEX); var fragment = module(FRAGMENT,ShaderType.FRAGMENT)) {
            var layouts = List.of(LAYOUTS.getFirst(), new BackendRenderPipeline.CreateInfo.VertexBuffer(7,16,1));
            var attributes = List.of(ATTRIBUTES.get(0), ATTRIBUTES.get(1), new BackendRenderPipeline.CreateInfo.AttribBinding(7,2,0,GpuFormat.RGBA32_FLOAT));
            try (var pipeline = (MetalRenderPipeline)context.compilePipeline(info(vertex,fragment,layouts,attributes,
                    color(Optional.empty(),ColorTargetState.WRITE_ALL),CompareOp.ALWAYS_PASS,false,false)).finishCompile()) {
                var memory = encoder.transientMemory();
                var vertices = memory.uploadGpu(List.of(triangles(0,new float[]{0.5f},new int[][]{{0,0,0,0}})),16,GpuBuffer.USAGE_VERTEX,48,1);
                var data = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
                data.putFloat(1).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0.5f).putFloat(1).putFloat(1).flip();
                var instances = memory.uploadGpu(List.of(data),16,GpuBuffer.USAGE_VERTEX,32,1);
                var pass = encoder.createRenderPass(descriptor(view,null,0,0,0,0)); pass.setPipeline(pipeline);
                pass.setVertexBuffer(3,vertices); pass.setVertexBuffer(7,instances);
                expect(IllegalArgumentException.class, () -> pass.draw(3,2,0,1));
                pass.draw(3,1,0,1); pass.draw(3,2,0,0); encoder.submitRenderPass(); waitFor(encoder);
                pixels(target.readDiagnostic(0,0),8,6,new int[]{0,128,255,255},null,0,0,8,6);
                var next = encoder.createRenderPass(descriptor(view,null,0,0,0,0)); next.setPipeline(pipeline);
                expect(IllegalStateException.class, () -> next.setVertexBuffer(3,vertices));
                encoder.submitRenderPass(); waitFor(encoder);
            }
        }
    }
    private static void verifyBoundariesAndCancellation() throws InterruptedException {
        try (var context = MetalContext.create(); var other = MetalContext.create();
                var target = context.createTexture(null,TEXTURE_USAGE,GpuFormat.RGBA8_UNORM,4,3,1,1); var view = context.createTextureView(target,0,1);
                var foreign = pipeline(other,color(Optional.empty(),ColorTargetState.WRITE_ALL),CompareOp.ALWAYS_PASS,false);
                var vertices = context.createBuffer(GpuBuffer.USAGE_VERTEX,triangles(0,new float[]{0.5f},new int[][]{{255,0,0,255}}));
                var noUsage = context.createBuffer(GpuBuffer.USAGE_COPY_DST,48)) {
            var encoder = context.createCommandEncoder();
            var pipeline = pipeline(context,color(Optional.empty(),ColorTargetState.WRITE_ALL),CompareOp.ALWAYS_PASS,false);
            byte[] original = new byte[4 * 3 * 4];
            for (int i = 0; i < original.length; i += 4) { original[i + 2] = (byte)255; original[i + 3] = (byte)255; }
            target.uploadDiagnostic(0,0,ByteBuffer.wrap(original));
            var pass = encoder.createRenderPass(descriptor(view,null,0,1,0,1));
            expect(IllegalStateException.class, () -> pass.draw(3,1,0,0));
            expect(IllegalArgumentException.class, () -> pass.setPipeline(foreign));
            expect(IllegalArgumentException.class, () -> pass.setPipeline(null));
            pass.setPipeline(pipeline);
            expect(IllegalStateException.class, () -> pass.draw(3,1,0,0));
            expect(IllegalStateException.class, () -> pass.setVertexBuffer(3,noUsage.slice()));
            expect(IllegalArgumentException.class, () -> pass.setVertexBuffer(16,vertices.slice()));
            expect(IllegalArgumentException.class, () -> pass.setVertexBuffer(3,new GpuBufferSlice(vertices,1,16)));
            expect(IllegalArgumentException.class, () -> pass.setVertexBuffer(3,new GpuBufferSlice(vertices,0,100)));
            pass.setVertexBuffer(3,vertices.slice());
            expect(IllegalArgumentException.class, () -> pass.draw(-1,1,0,0));
            expect(IllegalArgumentException.class, () -> pass.draw(3,1,Integer.MAX_VALUE,0));
            var failure = new AtomicReference<Throwable>();
            var thread = new Thread(() -> { try { pass.draw(3,1,0,0); } catch (Throwable error) { failure.set(error); } });
            thread.start(); thread.join(); check(failure.get() instanceof IllegalStateException,"Draw accepted foreign thread");
            pass.draw(0,1,0,0); pass.draw(3,0,0,0); pass.draw(3,1,0,0);
            encoder.close(); // Discard both the clear and draw.
            check(context.pipelineCount() == 1,"Cancellation retained extra pipeline");
            check(java.util.Arrays.equals(original,target.readDiagnostic(0,0)),"Cancelled pipeline draw changed pixels");
            expect(IllegalStateException.class, () -> pass.draw(3,1,0,0));
            long id = pipeline.commandHandle(context); pipeline.close();
            expect(IllegalStateException.class, () -> NativeMetal.destroyRenderPipeline(id));
            check(context.pipelineCount() == 0,"Closed pipeline was not released after cancellation");
            try (var vertex = module(VERTEX,ShaderType.VERTEX); var fragment = module(FRAGMENT,ShaderType.FRAGMENT)) {
                var bad = info(vertex,fragment,List.of(),ATTRIBUTES,color(Optional.empty(),ColorTargetState.WRITE_ALL),CompareOp.ALWAYS_PASS,false,false);
                expect(IllegalArgumentException.class, () -> context.compilePipeline(bad));
                check(context.pipelineCount() == 0,"Failed pipeline creation leaked native state");
            }
            // Exact device adapter exposes compilePipeline.
            try (var device = MetalGpuDeviceBackend.createForDevelopment(); var vertex = module(VERTEX,ShaderType.VERTEX); var fragment = module(FRAGMENT,ShaderType.FRAGMENT)) {
                var pending = device.compilePipeline(info(vertex,fragment,LAYOUTS,ATTRIBUTES,color(Optional.empty(),ColorTargetState.WRITE_ALL),CompareOp.ALWAYS_PASS,false,false));
                try (var compiled = pending.finishCompile()) { check(!compiled.isClosed(),"Device pipeline factory returned closed object"); }
            }
        }
        try (var vertex = module(VERTEX,ShaderType.VERTEX); var fragment = module(FRAGMENT,ShaderType.FRAGMENT)) {
            var abandoned = MetalContext.create();
            var pending = abandoned.compilePipeline(info(vertex,fragment,LAYOUTS,ATTRIBUTES,
                    color(Optional.empty(),ColorTargetState.WRITE_ALL),CompareOp.ALWAYS_PASS,false,false));
            check(abandoned.pipelineCount() == 1,"Unconsumed Pending did not own pipeline");
            abandoned.close();
            expect(IllegalStateException.class,pending::finishCompile);
        }
        expect(IllegalStateException.class, () -> NativeMetal.renderPassDraw(0,3,1,0,0));
        try (var context = MetalContext.create()) {
            // Fail before compiling source or registering state; raw JNI arrays are checked too.
            expect(IllegalArgumentException.class, () -> NativeMetal.createRenderPipeline(context.nativeHandle(),null,
                    null,null,null,null,new int[1],new int[0],new int[0],new int[5],0,0,new int[0],new int[0],0));
            check(context.pipelineCount() == 0,"Invalid native pipeline arrays leaked state");
            String resourceVertex = """
                    #version 450
                    layout(set=0,binding=0) uniform Scene { vec4 position; } scene;
                    layout(location=0) out vec4 tint;
                    void main() { gl_Position = scene.position; tint = vec4(1); }
                    """;
            try (var vertex = module(resourceVertex,ShaderType.VERTEX); var fragment = module(FRAGMENT,ShaderType.FRAGMENT)) {
                expect(IllegalArgumentException.class, () -> context.compilePipeline(info(vertex,fragment,List.of(),List.of(),
                        color(Optional.empty(),ColorTargetState.WRITE_ALL),CompareOp.ALWAYS_PASS,false,false)));
            }
        }
    }
    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", received " + error,error);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
