package dev.metalmc.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Vector4fc;

/** Actual GPU region copies, tight/strided rows, mip/array isolation and owner-thread readbacks. */
final class TextureCommandTest {
    private static final int COPY = GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
    private static final int BUFFER = GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_MAP_READ;
    private static final GpuFormat[] FORMATS = {GpuFormat.R8_UNORM, GpuFormat.RG8_UNORM, GpuFormat.RGBA8_UNORM,
            GpuFormat.R32_FLOAT, GpuFormat.RG32_FLOAT, GpuFormat.RGBA16_FLOAT, GpuFormat.RGBA32_FLOAT, GpuFormat.D32_FLOAT};
    private TextureCommandTest() {}

    static void run() throws InterruptedException {
        verifyTransfers();
        verifyClears();
        verifyRetirementAndCallbacks();
        verifyBoundsAndOwnership();
        verifyNativeBoundary();
        System.out.println("PASS: async texture regions, eight formats, tight/strided rows, mip/layer isolation, clears, readback callbacks and retirement.");
    }

    private static void verifyTransfers() {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder()) {
            Thread owner = Thread.currentThread();
            for (GpuFormat format : FORMATS) {
                int pixel = format.blockSize();
                try (var source = context.createTexture("async source " + format, COPY, format, 7, 5, 3, 3);
                        var target = context.createTexture("async target " + format, COPY, format, 7, 5, 3, 3);
                        var readback = context.createBuffer(BUFFER, 20L * pixel)) {
                    byte[][] initial = new byte[9][], expected = new byte[9][];
                    for (int mip = 0; mip < 3; mip++) for (int layer = 0; layer < 3; layer++) {
                        int size = source.getWidth(mip) * source.getHeight(mip);
                        initial[mip * 3 + layer] = texels(format, size, 2 + mip * 3 + layer);
                        expected[mip * 3 + layer] = texels(format, size, 19 + mip * 3 + layer);
                        encoder.writeToTexture(source, ByteBuffer.wrap(initial[mip * 3 + layer]), mip, layer,
                                0, 0, source.getWidth(mip), source.getHeight(mip));
                        encoder.writeToTexture(target, ByteBuffer.wrap(expected[mip * 3 + layer]), mip, layer,
                                0, 0, target.getWidth(mip), target.getHeight(mip));
                    }
                    // A positioned source with extra tail bytes, then mutated after recording.
                    byte[] patch = texels(format, 2, 41);
                    ByteBuffer input = ByteBuffer.allocate(patch.length + 8);
                    input.position(3).put(patch).position(3);
                    int limit = input.limit();
                    encoder.writeToTexture(source, input.asReadOnlyBuffer(), 1, 1, 1, 0, 2, 1);
                    check(input.position() == 3 && input.limit() == limit, "Texture upload changed source range");
                    input.put(3, (byte)0);
                    replace(initial[4], 3, 1, 0, patch, 2, 1, pixel);

                    // GPU source image has row padding, a nonzero slice offset and nonzero source X/Y.
                    byte[] image = texels(format, 9 * 7, 53);
                    ByteBuffer padded = ByteBuffer.allocate(image.length + 3 * pixel);
                    padded.position(3 * pixel).put(image).flip();
                    var buffer = context.createBuffer(BUFFER, padded);
                    encoder.copyBufferToTexture(buffer.slice(3L * pixel, image.length), 2, 1, 9, 7,
                            source, 2, 1, 3, 2, 0, 2);
                    buffer.close(); // Batch must keep the closed source allocation alive.
                    replace(initial[2], 7, 2, 1, extract(image, 9, 2, 1, 3, 2, pixel), 3, 2, pixel);
                    encoder.copyTextureToTexture(source, target, 1, 0, 1, 1, 0, 2, 1);
                    replace(expected[3], 3, 0, 1, extract(initial[3], 3, 1, 0, 2, 1, pixel), 2, 1, pixel);

                    AtomicInteger callbacks = new AtomicInteger();
                    byte[] region = extract(expected[3], 3, 0, 1, 2, 1, pixel);
                    encoder.copyTextureToBuffer(target, readback, 3L * pixel, () -> {
                        check(Thread.currentThread() == owner, "Readback callback ran on another thread");
                        check(callbacks.incrementAndGet() == 1, "Readback callback ran twice");
                        try (var mapped = readback.map(true, false)) {
                            byte[] actual = new byte[region.length];
                            mapped.data().position(3 * pixel).get(actual);
                            check(Arrays.equals(actual, region), "Region readback differs: " + format);
                            for (int i = 0; i < 20 * pixel; i++) if (i < 3 * pixel || i >= 5 * pixel) {
                                check(mapped.data().get(i) == 0, "Readback overwrote untouched buffer byte");
                            }
                        }
                        context.pollCallbacks(); // Reentrant polling must not duplicate callbacks.
                    }, 1, 0, 1, 2, 1);
                    check(callbacks.get() == 0, "Readback callback ran before submission");
                    expect(IllegalStateException.class, () -> source.readDiagnostic(0, 0));
                    expect(IllegalStateException.class, () -> readback.map(true, false));
                    try (var fence = encoder.createFence()) {
                        encoder.submit();
                        check(fence.awaitCompletion(5_000_000_000L), "Texture transfer fence timed out");
                    }
                    context.pollCallbacks();
                    check(callbacks.get() == 1, "Readback callback missing");
                    for (int mip = 0; mip < 3; mip++) for (int layer = 0; layer < 3; layer++) {
                        check(Arrays.equals(initial[mip * 3 + layer], source.readDiagnostic(mip, layer)), "Async source region/mip/layer mismatch: " + format);
                        check(Arrays.equals(expected[mip * 3 + layer], target.readDiagnostic(mip, layer)), "Texture copy modified another mip/layer: " + format);
                    }
                }
            }
            NativeMetal.drainCommands(context.nativeHandle());
            long[] stats = context.commandStats();
            check(stats[2] == 0 && stats[4] == 0 && stats[5] == 0, "Texture transfer allocations leaked");
        }
    }

    private static void verifyClears() {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder()) {
            for (GpuFormat format : FORMATS) {
                try (var texture = context.createTexture(null, COPY | GpuTexture.USAGE_RENDER_ATTACHMENT, format, 7, 5, 1, 3)) {
                    for (int mip = 0; mip < 3; mip++) encoder.writeToTexture(texture,
                            ByteBuffer.wrap(texels(format, texture.getWidth(mip) * texture.getHeight(mip), 9)),
                            mip, 0, 0, 0, texture.getWidth(mip), texture.getHeight(mip));
                    if (format.hasDepthAspect()) encoder.clearDepthTexture(texture, 0.5);
                    else encoder.clearColorTexture(texture, color(0.5f, 0.25f, 1, 1));
                    try (var fence = encoder.createFence()) {
                        encoder.submit();
                        check(fence.awaitCompletion(GpuFence.NO_TIMEOUT), "Clear fence failed");
                    }
                    for (int mip = 0; mip < 3; mip++) verifyClear(format, texture.readDiagnostic(mip, 0));
                }
            }
            try (var color = context.createTexture(null, COPY | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, 7, 5, 1, 3);
                    var depth = context.createTexture(null, COPY | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.D32_FLOAT, 7, 5, 1, 3);
                    var readback = context.createBuffer(BUFFER, 7 * 5 * 4)) {
                encoder.clearColorAndDepthTextures(color, color(0.5f, 0.25f, 1, 1), depth, 0.5);
                AtomicInteger callback = new AtomicInteger();
                encoder.copyTextureToBuffer(depth, readback, 0, () -> {
                    try (var mapped = readback.map(true, false)) {
                        byte[] bytes = new byte[7 * 5 * 4]; mapped.data().get(bytes);
                        verifyClear(GpuFormat.D32_FLOAT, bytes);
                    }
                    callback.incrementAndGet();
                }, 0);
                try (var fence = encoder.createFence()) { encoder.submit(); check(fence.awaitCompletion(GpuFence.NO_TIMEOUT), "Combined clear fence failed"); }
                check(callback.get() == 1, "Whole-mip depth readback callback missing");
                for (int mip = 0; mip < 3; mip++) {
                    verifyClear(GpuFormat.RGBA8_UNORM, color.readDiagnostic(mip, 0));
                    verifyClear(GpuFormat.D32_FLOAT, depth.readDiagnostic(mip, 0));
                }
            }
        }
    }

    private static void verifyRetirementAndCallbacks() {
        var context = MetalContext.create();
        var encoder = context.createCommandEncoder();
        var texture = context.createTexture(null, COPY, GpuFormat.R8_UNORM, 7, 5, 1, 1);
        var target = context.createTexture(null, COPY, GpuFormat.R8_UNORM, 7, 5, 1, 1);
        var buffer = context.createBuffer(BUFFER, 35);
        byte[] expected = texels(GpuFormat.R8_UNORM, 35, 7);
        encoder.writeToTexture(texture, ByteBuffer.wrap(expected), 0, 0, 0, 0, 7, 5);
        encoder.copyTextureToTexture(texture, target, 0, 0, 0, 0, 0, 7, 5);
        long textureBytes = context.commandStats()[5];
        texture.close();
        check(context.commandStats()[5] == textureBytes, "Recorded closed texture disappeared from budget");
        AtomicInteger callbacks = new AtomicInteger();
        encoder.copyTextureToBuffer(target, buffer, 0, () -> {
            try (var mapped = buffer.map(true, false)) {
                byte[] actual = new byte[35]; mapped.data().get(actual);
                check(Arrays.equals(actual, expected), "Closed source texture did not survive GPU copy");
            }
            expect(IllegalStateException.class, context::close);
            callbacks.incrementAndGet();
        }, 0);
        target.close(); // Readback must retain its source until execution completes.
        encoder.submit();
        context.close(); // Drains and dispatches callbacks before closing their destination buffer.
        check(callbacks.get() == 1 && buffer.isClosed(), "Device close lost callback or resource cleanup");
        context.close();

        try (var cancelledContext = MetalContext.create(); var cancel = cancelledContext.createCommandEncoder();
                var image = cancelledContext.createTexture(null, COPY, GpuFormat.R8_UNORM, 7, 5, 1, 1);
                var destination = cancelledContext.createBuffer(BUFFER, 35)) {
            encoder = cancel;
            encoder.writeToTexture(image, ByteBuffer.wrap(expected), 0, 0, 0, 0, 7, 5);
            encoder.copyTextureToBuffer(image, destination, 0, () -> { throw new AssertionError("Cancelled callback ran"); }, 0);
            var fence = encoder.createFence();
            encoder.close();
            cancelledContext.pollCallbacks();
            expect(IllegalStateException.class, () -> fence.awaitCompletion(0));
            fence.close();
            try (var mapped = destination.map(true, false)) { check(mapped.data().get(0) == 0, "Cancelled readback wrote data"); }
            check(cancelledContext.commandStats()[4] == 35, "Cancellation leaked staging");
        }
        // A failing callback must not prevent later callbacks or leak its batch ID.
        try (var callbackContext = MetalContext.create(); var commands = callbackContext.createCommandEncoder();
                var image = callbackContext.createTexture(null, COPY, GpuFormat.R8_UNORM, 7, 5, 1, 1);
                var destination = callbackContext.createBuffer(BUFFER, 70)) {
            commands.writeToTexture(image, ByteBuffer.wrap(expected), 0, 0, 0, 0, 7, 5);
            AtomicInteger count = new AtomicInteger();
            var sentinel = new IllegalArgumentException("callback test");
            commands.copyTextureToBuffer(image, destination, 0, () -> { count.incrementAndGet(); throw sentinel; }, 0);
            commands.copyTextureToBuffer(image, destination, 35, count::incrementAndGet, 0);
            try (var fence = commands.createFence()) {
                try { commands.submit(); fence.awaitCompletion(GpuFence.NO_TIMEOUT); throw new AssertionError("Callback failure swallowed"); }
                catch (IllegalArgumentException failure) { check(failure == sentinel, "Wrong callback failure propagated"); }
            }
            callbackContext.pollCallbacks();
            check(count.get() == 2, "Callbacks duplicated/skipped after failure");
        }
    }

    private static void verifyBoundsAndOwnership() throws InterruptedException {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder();
                var texture = context.createTexture(null, COPY | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, 7, 5, 1, 3);
                var buffer = context.createBuffer(BUFFER | GpuBuffer.USAGE_MAP_WRITE, 256)) {
            expect(IllegalArgumentException.class, () -> encoder.writeToTexture(texture, ByteBuffer.allocate(4), 0, 0, 6, 4, 2, 1));
            expect(IllegalArgumentException.class, () -> encoder.writeToTexture(texture, ByteBuffer.allocate(4), 0, 0, -1, 0, 1, 1));
            expect(IllegalArgumentException.class, () -> encoder.writeToTexture(texture, ByteBuffer.allocate(4), 0, -1, 0, 0, 1, 1));
            expect(IllegalArgumentException.class, () -> encoder.writeToTexture(texture, ByteBuffer.allocate(4), 3, 0, 0, 0, 1, 1));
            expect(IllegalArgumentException.class, () -> encoder.writeToTexture(texture, ByteBuffer.allocate(4), 0, 0, 0, 0, 0, 1));
            expect(IllegalArgumentException.class, () -> encoder.writeToTexture(texture, ByteBuffer.allocate(3), 0, 0, 0, 0, 1, 1));
            expect(IllegalArgumentException.class, () -> encoder.copyBufferToTexture(buffer.slice(0, 8), 0, 2, 3, 3, texture, 0, 0, 1, 1, 0, 0));
            expect(IllegalArgumentException.class, () -> encoder.copyBufferToTexture(buffer.slice(), Integer.MAX_VALUE, 0, 3, 3, texture, 0, 0, 1, 1, 0, 0));
            expect(IllegalArgumentException.class, () -> encoder.copyBufferToTexture(buffer.slice(1, 128), 0, 0, 3, 3, texture, 0, 0, 1, 1, 0, 0));
            expect(IllegalArgumentException.class, () -> encoder.copyTextureToBuffer(texture, buffer, 1, () -> {}, 0, 0, 0, 1, 1));
            expect(IllegalArgumentException.class, () -> encoder.copyTextureToBuffer(texture, buffer, Long.MAX_VALUE, () -> {}, 0));
            expect(NullPointerException.class, () -> encoder.copyTextureToBuffer(texture, buffer, 0, null, 0));
            expect(IllegalArgumentException.class, () -> encoder.clearDepthTexture(texture, 1));
            expect(IllegalArgumentException.class, () -> encoder.clearColorTexture(texture, color(Float.NaN, 0, 0, 0)));
            expect(IllegalArgumentException.class, () -> encoder.clearColorAndDepthTextures(null, null, null, 1, 0, 0, 1, 1, 0));
            try (var mapped = buffer.map(false, true)) {
                check(mapped.data().remaining() == 256, "Mapped range differs");
                expect(IllegalStateException.class, () -> encoder.copyTextureToBuffer(texture, buffer, 0, () -> {}, 0));
                expect(IllegalStateException.class, () -> encoder.copyBufferToTexture(buffer.slice(), 0, 0, 3, 3, texture, 0, 0, 1, 1, 0, 0));
            }
            try (var missing = context.createTexture(null, GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1)) {
                expect(IllegalStateException.class, () -> encoder.writeToTexture(missing, ByteBuffer.allocate(4), 0, 0, 0, 0, 1, 1));
            }
            try (var array = context.createTexture(null, COPY | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, 1, 1, 2, 1)) {
                expect(UnsupportedOperationException.class, () -> encoder.clearColorTexture(array, color(1, 1, 1, 1)));
            }
            try (var depth = context.createTexture(null, COPY | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.D32_FLOAT, 7, 5, 1, 2)) {
                expect(IllegalArgumentException.class, () -> encoder.clearColorAndDepthTextures(texture, color(1, 1, 1, 1), depth, 1));
                expect(IllegalArgumentException.class, () -> encoder.clearDepthTexture(depth, Double.NaN));
                expect(IllegalArgumentException.class, () -> encoder.copyTextureToTexture(texture, depth, 0, 0, 0, 0, 0, 1, 1));
            }
            expect(UnsupportedOperationException.class, () -> encoder.copyTextureToTexture(texture, texture, 0, 0, 0, 1, 0, 1, 1));
            try (var other = MetalContext.create(); var foreign = other.createTexture(null, COPY, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1)) {
                expect(IllegalArgumentException.class, () -> encoder.copyTextureToTexture(texture, foreign, 0, 0, 0, 0, 0, 1, 1));
            }
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try { encoder.writeToTexture(texture, ByteBuffer.allocate(4), 0, 0, 0, 0, 1, 1); }
                catch (Throwable caught) { failure.set(caught); }
            });
            thread.start(); thread.join();
            check(failure.get() instanceof IllegalStateException, "Foreign-thread texture command accepted");
            encoder.submit();
            check(context.commandStats()[0] == 0, "Invalid texture commands recorded GPU work");
        }
    }

    private static void verifyNativeBoundary() {
        long context = NativeMetal.createContext();
        long batch = NativeMetal.createBatch(context);
        long texture = NativeMetal.createTexture(context, null, COPY, 1, 7, 5, 1, 1);
        long buffer = NativeMetal.createBuffer(context, 35, null);
        expect(IllegalArgumentException.class, () -> NativeMetal.encodeTextureWrite(batch, texture, ByteBuffer.allocateDirect(35), 35, 0, 0, Integer.MAX_VALUE, 0, 7, 5));
        expect(IllegalArgumentException.class, () -> NativeMetal.encodeTextureWrite(batch, texture, ByteBuffer.allocate(35), 35, 0, 0, 0, 0, 7, 5));
        expect(IllegalArgumentException.class, () -> NativeMetal.encodeBufferTexture(batch, buffer, Long.MAX_VALUE, 7, texture, 0, 0, 0, 0, 7, 5, false));
        expect(IllegalArgumentException.class, () -> NativeMetal.encodeBufferTexture(batch, buffer, 0, 6, texture, 0, 0, 0, 0, 7, 5, false));
        expect(IllegalArgumentException.class, () -> NativeMetal.encodeTextureClear(batch, 0, 0, 0, 0, 0, 0, 0));
        expect(IllegalStateException.class, () -> NativeMetal.encodeTextureClear(batch, texture, 0, 0, 0, 1, 0, 0));
        ByteBuffer input = ByteBuffer.allocateDirect(35); for (int i = 0; i < 35; i++) input.put(i, (byte)i);
        NativeMetal.encodeTextureWrite(batch, texture, input, 35, 0, 0, 0, 0, 7, 5);
        expect(IllegalStateException.class, () -> NativeMetal.transferTextureDiagnostic(texture, 0, 0, ByteBuffer.allocateDirect(35), 35, false));
        NativeMetal.encodeBufferTexture(batch, buffer, 0, 7, texture, 0, 0, 0, 0, 7, 5, false);
        NativeMetal.destroyTexture(texture);
        NativeMetal.submitBatch(batch);
        check(NativeMetal.awaitBatch(batch, GpuFence.NO_TIMEOUT), "Native texture batch failed");
        NativeMetal.drainCommands(context);
        ByteBuffer output = ByteBuffer.allocateDirect(35); NativeMetal.readBuffer(buffer, 0, output, 35);
        for (int i = 0; i < 35; i++) check(output.get(i) == (byte)i, "Native closed texture readback corrupted");
        NativeMetal.releaseBatch(batch); NativeMetal.destroyBuffer(buffer); NativeMetal.destroyContext(context);
    }

    static Vector4fc color(float x, float y, float z, float w) {
        // Instantiate the real installed JOML class at runtime: javac otherwise
        // warns about modern type annotations on its legacy classfile version.
        // This keeps all project compiler warnings fatal without altering JOML.
        try {
            return (Vector4fc) Class.forName("org.joml.Vector4f")
                    .getConstructor(float.class, float.class, float.class, float.class).newInstance(x, y, z, w);
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Could not create installed JOML color", failure); }
    }

    static byte[] texels(GpuFormat format, int pixels, int seed) {
        var data = ByteBuffer.allocate(pixels * format.blockSize()).order(ByteOrder.nativeOrder());
        if (format == GpuFormat.RGBA16_FLOAT) while (data.hasRemaining()) data.putShort((short)(0x3400 + seed));
        else if (format == GpuFormat.R32_FLOAT || format == GpuFormat.RG32_FLOAT || format == GpuFormat.RGBA32_FLOAT || format == GpuFormat.D32_FLOAT) {
            while (data.hasRemaining()) data.putFloat((seed + data.position() / 4) / 256.0f);
        } else while (data.hasRemaining()) data.put((byte)(seed + data.position() * 3));
        return data.array();
    }
    private static byte[] extract(byte[] data, int stride, int x, int y, int width, int height, int pixel) {
        byte[] result = new byte[width * height * pixel];
        for (int row = 0; row < height; row++) System.arraycopy(data, ((y + row) * stride + x) * pixel, result, row * width * pixel, width * pixel);
        return result;
    }
    private static void replace(byte[] data, int stride, int x, int y, byte[] patch, int width, int height, int pixel) {
        for (int row = 0; row < height; row++) System.arraycopy(patch, row * width * pixel, data, ((y + row) * stride + x) * pixel, width * pixel);
    }
    static void verifyClear(GpuFormat format, byte[] bytes) {
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        float[] color = {0.5f, 0.25f, 1, 1};
        int components = format.hasDepthAspect() ? 1 : format.componentCount();
        int index = 0;
        while (data.hasRemaining()) {
            float expected = color[index++ % components];
            if (format == GpuFormat.RGBA16_FLOAT) check(Short.toUnsignedInt(data.getShort()) == (expected == 0.5f ? 0x3800 : expected == 0.25f ? 0x3400 : 0x3c00), "Half clear mismatch");
            else if (format == GpuFormat.R32_FLOAT || format == GpuFormat.RG32_FLOAT || format == GpuFormat.RGBA32_FLOAT || format.hasDepthAspect()) check(data.getFloat() == expected, "Float/depth clear mismatch");
            else check(Math.abs(Byte.toUnsignedInt(data.get()) - Math.round(expected * 255)) <= 1, "UNORM clear mismatch");
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
