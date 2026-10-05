package dev.metalmc.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;

/** Real GPU attachment load/clear/store, ordering and retirement; no draw claim. */
final class RenderPassTest {
    private static final int USAGE = 1 | 2 | 8;
    private static final GpuFormat[] FORMATS = {GpuFormat.R8_UNORM, GpuFormat.RG8_UNORM, GpuFormat.RGBA8_UNORM,
            GpuFormat.R32_FLOAT, GpuFormat.RG32_FLOAT, GpuFormat.RGBA16_FLOAT, GpuFormat.RGBA32_FLOAT, GpuFormat.D32_FLOAT};
    private RenderPassTest() {}

    static void run() throws InterruptedException {
        verifyMipsAndLoads();
        verifyMultipleAttachments();
        verifyStateAndCancellation();
        verifyLifetime();
        verifyNativeBoundary();
        System.out.println("PASS: render attachment passes, eight formats, base mips, load/clear/store, MRT holes, state boundaries and retirement (no draws).");
    }
    private static RenderPassDescriptor descriptor(MetalGpuTextureView view, boolean clear) {
        var builder = RenderPassDescriptor.builder(() -> "MetalMC attachment test 🧱");
        if (view.texture().getFormat().hasDepthAspect()) {
            builder.withDepthAttachment(view, clear ? OptionalDouble.of(0.5) : OptionalDouble.empty());
        } else builder.withColorAttachment(view, clear ? Optional.of(TextureCommandTest.color(0.5f, 0.25f, 1, 1)) : Optional.empty());
        return builder.build();
    }
    private static void waitFor(MetalCommandEncoder encoder) {
        try (var fence = encoder.createFence()) {
            encoder.submit();
            check(fence.awaitCompletion(5_000_000_000L), "Render submission timed out");
        }
    }
    private static void verifyMipsAndLoads() {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder()) {
            for (GpuFormat format : FORMATS) {
                try (var texture = context.createTexture(null, USAGE, format, 7, 5, 1, 3);
                        var view = context.createTextureView(texture, 1, 2)) {
                    byte[][] original = new byte[3][];
                    for (int mip = 0; mip < 3; mip++) {
                        original[mip] = TextureCommandTest.texels(format, texture.getWidth(mip) * texture.getHeight(mip), 21);
                        encoder.writeToTexture(texture, ByteBuffer.wrap(original[mip]), mip, 0, 0, 0, texture.getWidth(mip), texture.getHeight(mip));
                    }
                    encoder.createRenderPass(descriptor(view, false));
                    encoder.submitRenderPass();
                    waitFor(encoder);
                    for (int mip = 0; mip < 3; mip++) check(Arrays.equals(original[mip], texture.readDiagnostic(mip, 0)), "Load/store changed mip contents: " + format);
                    var pass = encoder.createRenderPass(descriptor(view, true));
                    pass.pushDebugGroup(() -> "clear + state 🧱");
                    pass.enableScissor(1, 0, 2, 1);
                    pass.disableScissor();
                    pass.popDebugGroup();
                    encoder.submitRenderPass();
                    // A following load pass must preserve the clear; scissor state never restricts load clears.
                    encoder.createRenderPass(descriptor(view, false));
                    encoder.submitRenderPass();
                    waitFor(encoder);
                    TextureCommandTest.verifyClear(format, texture.readDiagnostic(1, 0));
                    check(Arrays.equals(original[0], texture.readDiagnostic(0, 0)), "Render pass changed preceding mip");
                    check(Arrays.equals(original[2], texture.readDiagnostic(2, 0)), "Render pass changed subsequent mip");
                    expect(IllegalStateException.class, pass::disableScissor);
                }
            }
            NativeMetal.drainCommands(context.nativeHandle());
            check(context.commandStats()[5] == 0, "Render attachment storage leaked");
        }
    }
    private static void verifyMultipleAttachments() {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder();
                var a = context.createTexture(null, USAGE, GpuFormat.RGBA8_UNORM, 3, 2, 1, 1);
                var b = context.createTexture(null, USAGE, GpuFormat.RGBA16_FLOAT, 3, 2, 1, 1);
                var depth = context.createTexture(null, USAGE, GpuFormat.D32_FLOAT, 6, 4, 1, 2);
                var av = context.createTextureView(a, 0, 1); var bv = context.createTextureView(b, 0, 1);
                var dv = context.createTextureView(depth, 1, 1)) {
            var color = Optional.of(TextureCommandTest.color(0.5f, 0.25f, 1, 1));
            var descriptor = RenderPassDescriptor.builder(() -> "MRT slots 0 + 2 + depth")
                    .withColorAttachment(av, color).withUnusedColorAttachment().withColorAttachment(bv, color)
                    .withDepthAttachment(dv, OptionalDouble.of(0.5)).build();
            encoder.createRenderPass(descriptor); encoder.submitRenderPass();
            waitFor(encoder);
            TextureCommandTest.verifyClear(a.getFormat(), a.readDiagnostic(0, 0));
            TextureCommandTest.verifyClear(b.getFormat(), b.readDiagnostic(0, 0));
            TextureCommandTest.verifyClear(depth.getFormat(), depth.readDiagnostic(1, 0));
            // Exercise all eight slots with distinct render targets (32 bytes/pixel total).
            MetalGpuTexture[] targets = new MetalGpuTexture[8];
            try {
                var builder = RenderPassDescriptor.builder(() -> "eight color slots");
                for (int i = 0; i < targets.length; i++) {
                    targets[i] = context.createTexture(null, USAGE, GpuFormat.RGBA8_UNORM, 2, 1, 1, 1);
                    builder.withColorAttachment(context.createTextureView(targets[i], 0, 1), color);
                }
                encoder.createRenderPass(builder.build()); encoder.submitRenderPass(); waitFor(encoder);
                for (var target : targets) TextureCommandTest.verifyClear(target.getFormat(), target.readDiagnostic(0, 0));
            } finally { for (var target : targets) if (target != null) target.close(); }
        }
    }
    private static void verifyStateAndCancellation() throws InterruptedException {
        try (var context = MetalContext.create(); var other = MetalContext.create();
                var texture = context.createTexture(null, USAGE, GpuFormat.RGBA8_UNORM, 4, 3, 1, 1);
                var foreign = other.createTexture(null, USAGE, GpuFormat.RGBA8_UNORM, 4, 3, 1, 1);
                var foreignView = other.createTextureView(foreign, 0, 1);
                var buffer = context.createBuffer(GpuBuffer.USAGE_COPY_DST, 4)) {
            var encoder = context.createCommandEncoder();
            var view = context.createTextureView(texture, 0, 1);
            expect(IllegalStateException.class, encoder::submitRenderPass);
            expect(IllegalArgumentException.class, () -> encoder.createRenderPass(descriptor(foreignView, false)));
            expect(UnsupportedOperationException.class, () -> encoder.createRenderPass(RenderPassDescriptor.builder(() -> "partial")
                    .withColorAttachment(view).withRenderArea(new RenderPass.RenderArea(1, 0, 3, 3)).build()));
            expect(IllegalArgumentException.class, () -> encoder.createRenderPass(RenderPassDescriptor.builder(() -> "duplicate")
                    .withColorAttachment(view).withColorAttachment(view).build()));
            expect(IllegalArgumentException.class, () -> encoder.createRenderPass(RenderPassDescriptor.builder(() -> "wrong aspect")
                    .withDepthAttachment(view).build()));
            expect(IllegalArgumentException.class, () -> encoder.createRenderPass(RenderPassDescriptor.builder(() -> "bad value")
                    .withColorAttachment(view, Optional.of(TextureCommandTest.color(Float.NaN, 0, 0, 0))).build()));
            expect(IllegalArgumentException.class, () -> encoder.createRenderPass(RenderPassDescriptor.builder(() -> "no attachments")
                    .withRenderArea(new RenderPass.RenderArea(0, 0, 4, 3)).build()));
            var tooMany = RenderPassDescriptor.builder(() -> "nine slots").withColorAttachment(view);
            for (int i = 0; i < 8; i++) tooMany.withUnusedColorAttachment();
            expect(UnsupportedOperationException.class, () -> encoder.createRenderPass(tooMany.build()));
            try (var noUsage = context.createTexture(null, 1 | 2, GpuFormat.RGBA8_UNORM, 4, 3, 1, 1);
                    var noUsageView = context.createTextureView(noUsage, 0, 1);
                    var array = context.createTexture(null, USAGE, GpuFormat.RGBA8_UNORM, 4, 3, 2, 1);
                    var arrayView = context.createTextureView(array, 0, 1);
                    var small = context.createTexture(null, USAGE, GpuFormat.D32_FLOAT, 2, 1, 1, 1);
                    var smallView = context.createTextureView(small, 0, 1)) {
                expect(IllegalStateException.class, () -> encoder.createRenderPass(descriptor(noUsageView, false)));
                expect(UnsupportedOperationException.class, () -> encoder.createRenderPass(descriptor(arrayView, false)));
                expect(UnsupportedOperationException.class, () -> encoder.createRenderPass(RenderPassDescriptor.builder(() -> "mismatch")
                        .withColorAttachment(view).withDepthAttachment(smallView).build()));
            }
            byte[] original = TextureCommandTest.texels(texture.getFormat(), 12, 7);
            texture.uploadDiagnostic(0, 0, ByteBuffer.wrap(original));
            var memory = encoder.transientMemory();
            try (var mapping = memory.allocateStaging(4, 4, GpuBuffer.USAGE_COPY_SRC)) {
                mapping.data().putInt(0, 7);
                expect(IllegalStateException.class, () -> encoder.createRenderPass(descriptor(view, true)));
            }
            var pass = encoder.createRenderPass(descriptor(view, true));
            expect(IllegalStateException.class, () -> memory.allocateGpu(4, 4, GpuBuffer.USAGE_VERTEX));
            expect(IllegalStateException.class, encoder::submit);
            expect(IllegalStateException.class, encoder::createFence);
            expect(IllegalStateException.class, () -> encoder.createRenderPass(descriptor(view, false)));
            expect(IllegalStateException.class, () -> encoder.writeToBuffer(buffer.slice(), ByteBuffer.allocate(0)));
            expect(IllegalStateException.class, () -> encoder.writeToTexture(texture, ByteBuffer.allocate(48), 0, 0, 0, 0, 4, 3));
            expect(IllegalStateException.class, () -> texture.readDiagnostic(0, 0));
            expect(IllegalArgumentException.class, () -> pass.enableScissor(Integer.MAX_VALUE, 0, 1, 1));
            expect(IllegalArgumentException.class, () -> pass.enableScissor(0, 0, 0, 1));
            expect(IllegalStateException.class, pass::popDebugGroup);
            expect(IllegalStateException.class, () -> pass.draw(3, 1, 0, 0));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = new Thread(() -> { try { pass.disableScissor(); } catch (Throwable error) { failure.set(error); } });
            thread.start(); thread.join();
            check(failure.get() instanceof IllegalStateException, "Render pass accepted foreign thread");
            pass.pushDebugGroup(() -> "unbalanced, then cancel");
            expect(IllegalStateException.class, encoder::submitRenderPass);
            encoder.close(); // Must unwind native debug groups and end the encoder without submitting.
            expect(IllegalStateException.class, pass::disableScissor);
            check(Arrays.equals(original, texture.readDiagnostic(0, 0)), "Cancelled clear reached the GPU");
            view.close();
            try (var next = context.createCommandEncoder()) {
                expect(IllegalStateException.class, () -> next.createRenderPass(descriptor(view, true)));
            }
        }
    }
    private static void verifyLifetime() {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder();
                var readback = context.createBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 24)) {
            var texture = context.createTexture(null, USAGE, GpuFormat.RGBA8_UNORM, 3, 2, 1, 1);
            var view = context.createTextureView(texture, 0, 1);
            var pass = encoder.createRenderPass(descriptor(view, true));
            texture.close(); // Closes the view too, but native pass keeps its attachment storage.
            check(view.isClosed() && context.commandStats()[5] > 0, "Open pass lost closed attachment allocation");
            pass.disableScissor(); encoder.submitRenderPass();
            waitFor(encoder);
            check(context.commandStats()[5] == 0, "Completed pass retained closed attachment");
            var source = context.createTexture(null, USAGE, GpuFormat.RGBA8_UNORM, 3, 2, 1, 1);
            encoder.createRenderPass(descriptor(context.createTextureView(source, 0, 1), true)); encoder.submitRenderPass();
            encoder.copyTextureToBuffer(source, readback, 0, () -> {}, 0);
            source.close();
            waitFor(encoder);
            try (var mapped = readback.map(true, false)) {
                byte[] bytes = new byte[24]; mapped.data().get(bytes);
                TextureCommandTest.verifyClear(GpuFormat.RGBA8_UNORM, bytes);
            }
            // Context shutdown must cancel an unfinished pass, including its open debug group.
            try (var temporary = MetalContext.create()) {
                var t = temporary.createTexture(null, USAGE, GpuFormat.RGBA8_UNORM, 2, 1, 1, 1);
                temporary.createCommandEncoder().createRenderPass(descriptor(temporary.createTextureView(t, 0, 1), true))
                        .pushDebugGroup(() -> "context shutdown");
            }
        }
    }
    private static void verifyNativeBoundary() {
        try (var context = MetalContext.create(); var other = MetalContext.create();
                var target = context.createTexture(null, USAGE, GpuFormat.RGBA8_UNORM, 3, 2, 1, 1);
                var foreign = other.createTexture(null, USAGE, GpuFormat.RGBA8_UNORM, 3, 2, 1, 1)) {
            long batch = NativeMetal.createBatch(context.nativeHandle());
            long texture = target.commandHandle(context, 0);
            try {
                expect(IllegalArgumentException.class, () -> NativeMetal.beginRenderPass(batch, new long[]{texture}, new int[0], new boolean[1], new float[4], 0, 0, false, 0, 3, 2, null));
                expect(IllegalArgumentException.class, () -> begin(batch, texture, 1, 3, 2));
                expect(IllegalArgumentException.class, () -> begin(batch, texture, 0, 2, 2));
                expect(IllegalArgumentException.class, () -> begin(batch, foreign.commandHandle(other, 0), 0, 3, 2));
                expect(IllegalStateException.class, () -> begin(batch, Long.MAX_VALUE, 0, 3, 2));
                begin(batch, texture, 0, 3, 2);
                expect(IllegalStateException.class, () -> NativeMetal.submitBatch(batch));
                expect(IllegalStateException.class, () -> NativeMetal.encodeTextureClear(batch, texture, 1, 1, 1, 1, 0, 0));
                expect(IllegalStateException.class, () -> begin(batch, texture, 0, 3, 2));
                expect(IllegalArgumentException.class, () -> NativeMetal.renderPassScissor(batch, 0, 0, 4, 1));
                NativeMetal.renderPassDebugGroup(batch, "native", true);
                expect(IllegalStateException.class, () -> NativeMetal.endRenderPass(batch));
                NativeMetal.renderPassDebugGroup(batch, null, false);
                NativeMetal.endRenderPass(batch);
                expect(IllegalStateException.class, () -> NativeMetal.renderPassScissor(batch, 0, 0, 1, 1));
                NativeMetal.submitBatch(batch);
                check(NativeMetal.awaitBatch(batch, GpuFence.NO_TIMEOUT), "Native pass fence failed");
                TextureCommandTest.verifyClear(target.getFormat(), target.readDiagnostic(0, 0));
            } finally { NativeMetal.releaseBatch(batch); }
        }
    }
    private static void begin(long batch, long texture, int mip, int width, int height) {
        NativeMetal.beginRenderPass(batch, new long[]{texture}, new int[]{mip}, new boolean[]{true},
                new float[]{0.5f, 0.25f, 1, 1}, 0, 0, false, 0, width, height, null);
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
