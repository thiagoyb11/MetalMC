package dev.metalmc.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** Real GPU rectangle preservation, depth output, snapshots and command lifetime. */
final class ScissoredClearTest {
    private static final int USAGE = GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT;
    private static final float[] FIRST = {0.5f, 0.25f, 1, 1}, SECOND = {0.25f, 0.5f, 0, 1};
    private ScissoredClearTest() {}
    static void run() throws Exception {
        verifyFormatsAndSnapshots();
        verifyValidationAndCancellation();
        verifyRetirement();
        verifyNativeBoundariesAndOperationLimit();
        System.out.println("PASS: scissored color/D32 clears, seven color formats, asymmetric pixels/mip preservation, snapshots, atomic validation, cancellation and async retirement.");
    }
    private static void verifyFormatsAndSnapshots() throws ReflectiveOperationException {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder()) {
            for (var format : new GpuFormat[]{GpuFormat.R8_UNORM, GpuFormat.RG8_UNORM, GpuFormat.RGBA8_UNORM,
                    GpuFormat.R32_FLOAT, GpuFormat.RG32_FLOAT, GpuFormat.RGBA16_FLOAT, GpuFormat.RGBA32_FLOAT}) {
                try (var color = context.createTexture("rectangle color", USAGE, format, 9, 7, 1, 3);
                        var depth = context.createTexture("rectangle depth", USAGE, GpuFormat.D32_FLOAT, 9, 7, 1, 3)) {
                    byte[][] originalColor = initialize(encoder, color), originalDepth = initialize(encoder, depth);
                    var value = TextureCommandTest.color(FIRST[0], FIRST[1], FIRST[2], FIRST[3]);
                    encoder.clearColorAndDepthTextures(color, value, depth, 0.5, 2, 1, 3, 2, 0);
                    // The same mutable vector is reused; earlier parameter bytes must remain owned.
                    value.getClass().getMethod("set", float.class, float.class, float.class, float.class)
                            .invoke(value, SECOND[0], SECOND[1], SECOND[2], SECOND[3]);
                    encoder.clearColorAndDepthTextures(color, value, depth, 0, 0, 5, 2, 1, 0);
                    encoder.clearColorAndDepthTextures(color, TextureCommandTest.color(FIRST[0], FIRST[1], FIRST[2], FIRST[3]),
                            depth, 0.5, 1, 1, 2, 1, 1);
                    encoder.clearColorAndDepthTextures(color, value, depth, 0, 3, 2, 1, 1, 0);
                    try (var view = color.createView(context, 0, 1)) {
                        encoder.createRenderPass(RenderPassDescriptor.builder(() -> "load after rectangle clear")
                                .withColorAttachment(view).build());
                        encoder.submitRenderPass();
                    }
                    expect(IllegalStateException.class, () -> color.readDiagnostic(0, 0));
                    finish(encoder);
                    for (int mip = 0; mip < 3; mip++) {
                        byte[] actualColor = color.readDiagnostic(mip, 0), actualDepth = depth.readDiagnostic(mip, 0);
                        int w = color.getWidth(mip), h = color.getHeight(mip);
                        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                            boolean first = mip == 0 && inside(x, y, 2, 1, 3, 2) || mip == 1 && inside(x, y, 1, 1, 2, 1);
                            boolean second = mip == 0 && (inside(x, y, 0, 5, 2, 1) || inside(x, y, 3, 2, 1, 1));
                            int pixel = y * w + x;
                            if (first || second) {
                                verifyPixel(format, actualColor, pixel, second ? SECOND : FIRST);
                                verifyPixel(GpuFormat.D32_FLOAT, actualDepth, pixel, new float[]{second ? 0 : 0.5f});
                            } else {
                                unchanged(format, originalColor[mip], actualColor, pixel);
                                unchanged(GpuFormat.D32_FLOAT, originalDepth[mip], actualDepth, pixel);
                            }
                        }
                    }
                    // Entire smallest mip; endpoints prove depth replacement is independent of old depth.
                    encoder.clearColorAndDepthTextures(color, TextureCommandTest.color(0.25f, 0.5f, 0, 1), depth, 1, 0, 0, 2, 1, 2);
                    finish(encoder);
                    for (int i = 0; i < 2; i++) {
                        verifyPixel(format, color.readDiagnostic(2, 0), i, SECOND);
                        verifyPixel(GpuFormat.D32_FLOAT, depth.readDiagnostic(2, 0), i, new float[]{1});
                    }
                }
            }
            check(context.commandStats()[4] == 0 && context.commandStats()[5] == 0, "Format clear resources/staging leaked");
        }
    }
    private static byte[][] initialize(MetalCommandEncoder encoder, MetalGpuTexture texture) {
        byte[][] bytes = new byte[texture.getMipLevels()][];
        for (int mip = 0; mip < bytes.length; mip++) {
            bytes[mip] = TextureCommandTest.texels(texture.getFormat(), texture.getWidth(mip) * texture.getHeight(mip), 3 + mip);
            encoder.writeToTexture(texture, ByteBuffer.wrap(bytes[mip]), mip, 0, 0, 0, texture.getWidth(mip), texture.getHeight(mip));
        }
        return bytes;
    }
    private static void verifyValidationAndCancellation() throws InterruptedException {
        try (var context = MetalContext.create(); var foreign = MetalContext.create(); var encoder = context.createCommandEncoder();
                var color = context.createTexture(null, USAGE, GpuFormat.RGBA8_UNORM, 9, 7, 1, 3);
                var depth = context.createTexture(null, USAGE, GpuFormat.D32_FLOAT, 9, 7, 1, 3);
                var small = context.createTexture(null, USAGE, GpuFormat.D32_FLOAT, 7, 5, 1, 3);
                var fewer = context.createTexture(null, USAGE, GpuFormat.D32_FLOAT, 9, 7, 1, 2);
                var array = context.createTexture(null, USAGE, GpuFormat.RGBA8_UNORM, 9, 7, 2, 3);
                var missing = context.createTexture(null, GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, 9, 7, 1, 3);
                var other = foreign.createTexture(null, USAGE, GpuFormat.D32_FLOAT, 9, 7, 1, 3);
                var view = color.createView(context, 0, 1)) {
            var value = TextureCommandTest.color(0.5f, 0.25f, 1, 1);
            byte[][] original = initialize(encoder, color);
            initialize(encoder, depth); finish(encoder);
            long[] before = context.commandStats();
            for (int[] rectangle : new int[][]{{-1,0,1,1,0}, {0,-1,1,1,0}, {0,0,0,1,0}, {0,0,1,0,0},
                    {8,0,2,1,0}, {0,6,1,2,0}, {1,0,Integer.MAX_VALUE,1,0}, {0,0,1,1,-1}, {0,0,1,1,3}}) {
                expect(IllegalArgumentException.class, () -> encoder.clearColorAndDepthTextures(color, value, depth, 0.5,
                        rectangle[0], rectangle[1], rectangle[2], rectangle[3], rectangle[4]));
            }
            expect(IllegalArgumentException.class, () -> encoder.clearColorAndDepthTextures(color, value, small, 0.5, 0,0,1,1,0));
            expect(IllegalArgumentException.class, () -> encoder.clearColorAndDepthTextures(color, value, fewer, 0.5, 0,0,1,1,0));
            expect(IllegalArgumentException.class, () -> encoder.clearColorAndDepthTextures(color, value, other, 0.5, 0,0,1,1,0));
            expect(IllegalArgumentException.class, () -> encoder.clearColorAndDepthTextures(depth, value, color, 0.5, 0,0,1,1,0));
            expect(UnsupportedOperationException.class, () -> encoder.clearColorAndDepthTextures(array, value, depth, 0.5, 0,0,1,1,0));
            expect(IllegalStateException.class, () -> encoder.clearColorAndDepthTextures(missing, value, depth, 0.5, 0,0,1,1,0));
            expect(NullPointerException.class, () -> encoder.clearColorAndDepthTextures(color, null, depth, 0.5, 0,0,1,1,0));
            for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1}) {
                expect(IllegalArgumentException.class, () -> encoder.clearColorAndDepthTextures(color, value, depth, invalid, 0,0,1,1,0));
            }
            expect(IllegalArgumentException.class, () -> encoder.clearColorAndDepthTextures(color,
                    TextureCommandTest.color(0, Float.NaN, 0, 1), depth, 0.5, 0,0,1,1,0));
            var failure = new AtomicReference<Throwable>();
            var thread = new Thread(() -> { try { encoder.clearColorAndDepthTextures(color, value, depth, 0.5, 0,0,1,1,0); }
                catch (Throwable error) { failure.set(error); } });
            thread.start(); thread.join(); check(failure.get() instanceof IllegalStateException, "Foreign thread accepted clear");
            check(Arrays.equals(before, context.commandStats()), "Rejected clear allocated/staged/submitted work");
            encoder.createRenderPass(RenderPassDescriptor.builder(() -> "clear boundary").withColorAttachment(view).build());
            expect(IllegalStateException.class, () -> encoder.clearColorAndDepthTextures(color, value, depth, 0.5, 0,0,1,1,0));
            encoder.submitRenderPass(); finish(encoder);
            check(Arrays.equals(original[0], color.readDiagnostic(0,0)), "Validation/pass boundary changed pixels");
            var cancel = context.createCommandEncoder();
            try {
                cancel.clearColorAndDepthTextures(color, value, depth, 0.5, 2,1,3,2,0);
                check(context.commandStats()[4] == 32, "Clear parameter snapshot not budgeted");
                try (var fence = cancel.createFence()) {
                    cancel.close();
                    expect(IllegalStateException.class, () -> fence.awaitCompletion(0));
                }
            } finally { cancel.close(); }
            check(context.commandStats()[4] == 0 && Arrays.equals(original[0], color.readDiagnostic(0,0)), "Cancelled clear changed pixels or leaked staging");
        }
    }
    private static void verifyRetirement() {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder();
                var colorReadback = context.createBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 9 * 7 * 4);
                var depthReadback = context.createBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 9 * 7 * 4)) {
            for (int iteration = 0; iteration < 16; iteration++) {
                var color = context.createTexture(null, USAGE, GpuFormat.RGBA8_UNORM, 9,7,1,1);
                var depth = context.createTexture(null, USAGE, GpuFormat.D32_FLOAT, 9,7,1,1);
                try {
                    initialize(encoder, color); initialize(encoder, depth);
                    encoder.clearColorAndDepthTextures(color, TextureCommandTest.color(0.5f,0.25f,1,1), depth, 0.5, 2,1,3,2,0);
                    encoder.copyTextureToBuffer(color, colorReadback, 0, () -> {}, 0);
                    encoder.copyTextureToBuffer(depth, depthReadback, 0, () -> {}, 0);
                } finally { color.close(); depth.close(); }
                if (iteration == 15) finish(encoder); else encoder.submit();
            }
            try (var c = colorReadback.map(true,false); var d = depthReadback.map(true,false)) {
                byte[] colors = new byte[9 * 7 * 4], depths = new byte[9 * 7 * 4];
                c.data().get(colors); d.data().get(depths);
                byte[] oldColors = TextureCommandTest.texels(GpuFormat.RGBA8_UNORM, 63, 3), oldDepths = TextureCommandTest.texels(GpuFormat.D32_FLOAT, 63, 3);
                for (int y = 0; y < 7; y++) for (int x = 0; x < 9; x++) {
                    int pixel = y * 9 + x;
                    if (inside(x,y,2,1,3,2)) {
                        verifyPixel(GpuFormat.RGBA8_UNORM, colors, pixel, FIRST);
                        verifyPixel(GpuFormat.D32_FLOAT, depths, pixel, new float[]{0.5f});
                    } else {
                        unchanged(GpuFormat.RGBA8_UNORM, oldColors, colors, pixel);
                        unchanged(GpuFormat.D32_FLOAT, oldDepths, depths, pixel);
                    }
                }
            }
            long[] stats = context.commandStats();
            check(stats[0] == 16 && stats[1] == 16 && stats[2] == 0 && stats[4] == 2 * 9 * 7 * 4 && stats[5] == 0,
                    "Retained clear resources did not retire");
        }
    }
    private static void verifyNativeBoundariesAndOperationLimit() {
        try (var context = MetalContext.create(); var foreign = MetalContext.create();
                var color = context.createTexture(null, USAGE, GpuFormat.RGBA8_UNORM, 2,2,1,1);
                var depth = context.createTexture(null, USAGE, GpuFormat.D32_FLOAT, 2,2,1,1);
                var other = foreign.createTexture(null, USAGE, GpuFormat.D32_FLOAT, 2,2,1,1)) {
            color.uploadDiagnostic(0,0,ByteBuffer.wrap(TextureCommandTest.texels(GpuFormat.RGBA8_UNORM, 4, 3)));
            depth.uploadDiagnostic(0,0,ByteBuffer.wrap(TextureCommandTest.texels(GpuFormat.D32_FLOAT, 4, 3)));
            long batch = NativeMetal.createBatch(context.nativeHandle());
            long c = color.commandHandle(context, 0), d = depth.commandHandle(context, 0);
            try {
                expect(IllegalStateException.class, () -> nativeClear(0,c,d,0,0,1,1,0));
                expect(IllegalStateException.class, () -> nativeClear(batch,0,d,0,0,1,1,0));
                expect(IllegalArgumentException.class, () -> nativeClear(batch,c,other.commandHandle(foreign,0),0,0,1,1,0));
                expect(IllegalArgumentException.class, () -> nativeClear(batch,d,c,0,0,1,1,0));
                expect(IllegalArgumentException.class, () -> nativeClear(batch,c,d,1,0,Integer.MAX_VALUE,1,0));
                expect(IllegalArgumentException.class, () -> nativeClear(batch,c,d,0,0,1,1,1));
                expect(IllegalArgumentException.class, () -> NativeMetal.encodeScissoredClear(batch,c,Float.NaN,0,0,1,d,0.5,0,0,1,1,0));
                expect(IllegalArgumentException.class, () -> NativeMetal.encodeScissoredClear(batch,c,0,0,0,1,d,Double.NaN,0,0,1,1,0));
                for (int i = 0; i < 4096; i++) nativeClear(batch,c,d,0,0,1,1,0);
                long bytes = context.commandStats()[4];
                check(bytes == 4096 * 32, "Per-clear snapshot cost differs");
                expect(IllegalStateException.class, () -> nativeClear(batch,c,d,0,0,1,1,0));
                check(context.commandStats()[4] == bytes, "Over-cap clear allocated staging");
                NativeMetal.cancelBatch(batch);
                check(context.commandStats()[4] == 0, "Cancelled operation-limit batch leaked staging");
                check(Arrays.equals(color.readDiagnostic(0,0), TextureCommandTest.texels(GpuFormat.RGBA8_UNORM, 4, 3))
                        && Arrays.equals(depth.readDiagnostic(0,0), TextureCommandTest.texels(GpuFormat.D32_FLOAT, 4, 3)),
                        "Cancelled native clears changed initialized pixels");
            } finally { NativeMetal.cancelBatch(batch); NativeMetal.releaseBatch(batch); }
            expect(IllegalStateException.class, () -> nativeClear(batch,c,d,0,0,1,1,0));
        }
    }
    private static void nativeClear(long batch, long color, long depth, int x, int y, int width, int height, int mip) {
        NativeMetal.encodeScissoredClear(batch,color,0.5f,0.25f,1,1,depth,0.5,x,y,width,height,mip);
    }
    private static void finish(MetalCommandEncoder encoder) {
        try (var fence = encoder.createFence()) { encoder.submit(); check(fence.awaitCompletion(GpuFence.NO_TIMEOUT), "Clear fence failed"); }
    }
    private static boolean inside(int x, int y, int rx, int ry, int w, int h) { return x >= rx && x < rx+w && y >= ry && y < ry+h; }
    private static void verifyPixel(GpuFormat format, byte[] bytes, int pixel, float[] values) {
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()); data.position(pixel * format.blockSize());
        for (int component = 0; component < values.length && component < format.componentCount(); component++) {
            float expected = values[component];
            if (format == GpuFormat.RGBA16_FLOAT) check(data.getShort() == Float.floatToFloat16(expected), "Half clear mismatch");
            else if (format == GpuFormat.R8_UNORM || format == GpuFormat.RG8_UNORM || format == GpuFormat.RGBA8_UNORM) {
                check(Math.abs(Byte.toUnsignedInt(data.get()) - Math.round(expected*255)) <= 1, "UNORM clear mismatch");
            } else check(data.getFloat() == expected, "Float/depth rectangle mismatch");
        }
    }
    private static void unchanged(GpuFormat format, byte[] original, byte[] actual, int pixel) {
        int start = pixel * format.blockSize();
        for (int i = start; i < start + format.blockSize(); i++) check(original[i] == actual[i], "Clear changed an untouched pixel: " + format);
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
