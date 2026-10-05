package dev.metalmc.metal;

import dev.metalmc.MetalMCClient;
import dev.metalmc.backend.MetalGpuDeviceBackend;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

/** Integration test using the actual packaged dylib and real Metal commands. */
public final class NativeSmokeTest {
    private NativeSmokeTest() {}

    public static void main(String[] args) throws Exception {
        Path report = args.length == 0 ? Path.of("build/reports/metal-triangle.png") : Path.of(args[0]);
        try (MetalContext context = MetalContext.create()) {
            check(!context.deviceName().isBlank(), "Device name is empty");
            System.out.println("Metal device: " + context.deviceName());
            MetalImage image = context.renderDiagnosticTriangle(257, 129);
            verifyPixels(image);
            verifyPixels(context.renderDiagnosticTriangle(67, 43));
            verifyPixels(context.renderDiagnosticTriangle(64, 64));
            check(Arrays.equals(image.copyRgba(), context.renderDiagnosticTriangle(257, 129).copyRgba()),
                    "Repeated rendering changed pixels");
            check(context.renderDiagnosticTriangle(1, 1).copyRgba().length == 4, "Minimum dimensions failed");
            expect(IllegalArgumentException.class, () -> context.renderDiagnosticTriangle(0, 64));
            expect(IllegalArgumentException.class, () -> context.renderDiagnosticTriangle(64, -1));
            expect(IllegalArgumentException.class, () -> context.renderDiagnosticTriangle(4097, 64));
            expect(IndexOutOfBoundsException.class, () -> image.component(-1, 0, 0));
            byte[] copy = image.copyRgba();
            int original = image.component(0, 0, 0);
            copy[0] = 100;
            check(image.component(0, 0, 0) == original, "Image array escaped ownership");

            AtomicReference<Throwable> foreignThreadFailure = new AtomicReference<>();
            Thread foreign = new Thread(() -> {
                try { context.deviceName(); }
                catch (Throwable failure) { foreignThreadFailure.set(failure); }
            });
            foreign.start();
            foreign.join();
            check(foreignThreadFailure.get() instanceof IllegalStateException, "Foreign-thread use was accepted");
            writeImage(image, report);
        }

        MetalContext closed = MetalContext.create();
        closed.close();
        closed.close();
        expect(IllegalStateException.class, closed::deviceName);
        expect(IllegalStateException.class, () -> closed.renderDiagnosticTriangle(64, 64));

        // Bypass Java validation to verify the native boundary rejects stale IDs
        // and bad dimensions without dereferencing raw memory.
        expect(IllegalStateException.class, () -> NativeMetal.deviceName(0));
        long stale = NativeMetal.createContext();
        NativeMetal.destroyContext(stale);
        expect(IllegalStateException.class, () -> NativeMetal.deviceName(stale));
        expect(IllegalStateException.class, () -> NativeMetal.renderTriangle(stale, 64, 64));
        expect(IllegalStateException.class, () -> NativeMetal.destroyContext(stale));
        expect(IllegalArgumentException.class, () -> NativeMetal.renderTriangle(stale, 0, 64));
        // This runner is intentionally NOT the macOS main thread. Surface calls
        // must reject it before dereferencing any SDL pointer or acquiring a drawable.
        expect(IllegalStateException.class, () -> NativeMetal.createSurface(stale, 0));
        expect(IllegalStateException.class, () -> NativeMetal.presentTriangle(0, 64, 64));
        expect(IllegalStateException.class, () -> NativeMetal.surfaceStats(0));
        expect(IllegalStateException.class, () -> NativeMetal.drainSurface(0));
        expect(IllegalStateException.class, () -> NativeMetal.destroySurface(0));
        for (int i = 0; i < 4; i++) {
            try (MetalContext context = MetalContext.create()) {
                verifyPixels(context.renderDiagnosticTriangle(64, 64));
            }
        }
        verifyBuffers();
        TextureResourceTest.run();
        CommandEncoderTest.run();
        TextureCommandTest.run();
        TransientMemoryTest.run();
        RenderPassTest.run();
        ShaderTranslationTest.run();
        PipelineDrawTest.run();
        ShaderResourceTest.run();
        IndexedDrawTest.run();
        IndirectDrawTest.run();
        VertexFormatTest.run();
        DeviceInfoTest.run();
        ScissoredClearTest.run();
        TimestampQueryTest.run();

        // Exercise the Fabric initializer directly, including both feature-switch
        // paths. This is not a Fabric launcher or in-game integration test.
        System.clearProperty("metalmc.probe");
        new MetalMCClient().onInitializeClient();
        System.setProperty("metalmc.probe", "true");
        new MetalMCClient().onInitializeClient();
        System.clearProperty("metalmc.probe");
        System.out.println("PASS: RGBA pixels, padded rows, repeated rendering, buffers, texture GPU transfers/sampling, async commands/fences, lifecycle, thread ownership, JNI errors.");
        System.out.println("Diagnostic image: " + report.toAbsolutePath());
    }

    private static void verifyBuffers() throws Exception {
        int usage = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE
                | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST;
        try (MetalContext context = MetalContext.create();
             MetalGpuBuffer source = context.createBuffer(usage, 128);
             MetalGpuBuffer destination = context.createBuffer(usage, 128)) {
            expect(IllegalArgumentException.class, () -> context.createBuffer(usage, 0));
            expect(IllegalArgumentException.class, () -> context.createBuffer(usage, Long.MAX_VALUE));
            expect(UnsupportedOperationException.class,
                    () -> context.createBuffer(GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, 64));
            expect(IllegalArgumentException.class, () -> source.map(Long.MAX_VALUE, 2, true, false));
            expect(IllegalArgumentException.class, () -> source.slice(Long.MAX_VALUE, Long.MAX_VALUE));
            expect(IllegalArgumentException.class, () -> source.map(0, 8, false, false));
            try (var mapped = source.slice(16, 64).map(false, true)) {
                for (int i = 0; i < 64; i++) mapped.data().put(i, (byte)(i * 3));
                // Changing position/limit must not truncate the flush on close.
                mapped.data().position(30).limit(31);
                expect(IllegalStateException.class, source::close);
                expect(IllegalStateException.class, () -> source.map(0, 4, true, false));
            }
            source.copyDiagnosticTo(16, destination, 32, 64);
            try (var mapped = destination.map(true, false)) {
                check(mapped.data().isReadOnly(), "Read-only mapping was mutable");
                expect(ReadOnlyBufferException.class, () -> mapped.data().put(0, (byte)1));
                for (int i = 0; i < 128; i++) {
                    byte expected = i >= 32 && i < 96 ? (byte)((i - 32) * 3) : 0;
                    check(mapped.data().get(i) == expected, "GPU buffer copy or untouched bytes incorrect at " + i);
                }
            }
            expect(IllegalArgumentException.class, () -> source.copyDiagnosticTo(1, destination, 0, 4));
            expect(IllegalArgumentException.class, () -> source.copyDiagnosticTo(0, source, 0, 4));
            ByteBuffer input = ByteBuffer.wrap(new byte[] {9, 7, 6, 5, 4, 3});
            input.position(1).limit(5);
            source.upload(0, input);
            check(input.position() == 1 && input.limit() == 5, "Upload changed caller buffer position/limit");
            try (var mapped = source.map(0, 4, true, true)) {
                check(mapped.data().get(0) == 7 && mapped.data().get(3) == 4, "Upload used wrong source range");
                mapped.data().put(1, (byte)42);
            }
            try (var mapped = source.map(0, 4, false, true)) {
                mapped.data().put(2, (byte)55);
            }
            try (var mapped = source.map(0, 4, true, false)) {
                check(mapped.data().get(0) == 7 && mapped.data().get(1) == 42
                        && mapped.data().get(2) == 55 && mapped.data().get(3) == 4,
                        "Write-only mapping destroyed untouched bytes");
            }
            var view = source.map(0, 0, true, false);
            view.close();
            view.close();
            AtomicReference<Throwable> foreignFailure = new AtomicReference<>();
            Thread foreign = new Thread(() -> {
                try { source.upload(0, ByteBuffer.allocate(4)); }
                catch (Throwable failure) { foreignFailure.set(failure); }
            });
            foreign.start();
            foreign.join();
            check(foreignFailure.get() instanceof IllegalStateException, "Foreign-thread buffer access accepted");
            try (MetalContext other = MetalContext.create(); MetalGpuBuffer otherBuffer = other.createBuffer(usage, 64)) {
                expect(IllegalArgumentException.class, () -> source.copyDiagnosticTo(0, otherBuffer, 0, 4));
            }
        }
        // Exercise the real GpuDeviceBackend interface against the installed game.
        MetalGpuDeviceBackend backend = MetalGpuDeviceBackend.createForDevelopment();
        ByteBuffer data = ByteBuffer.wrap(new byte[] {4, 3, 2, 1});
        try (GpuBuffer buffer = backend.createBuffer(() -> "initial data", GpuBuffer.USAGE_MAP_READ, data)) {
            check(buffer instanceof MetalGpuBuffer, "Backend did not create a native buffer");
            try (var mapped = buffer.map(true, false)) {
                check(mapped.data().getInt(0) == ByteBuffer.wrap(new byte[] {4, 3, 2, 1})
                        .order(java.nio.ByteOrder.nativeOrder()).getInt(), "Initial buffer bytes incorrect");
            }
            expect(IllegalStateException.class, () -> buffer.map(false, true));
        }
        check(backend.createCommandEncoder().transientMemory() instanceof MetalTransientMemory,
                "Backend did not provide transient memory");
        if (backend.getDeviceInfo().name().isBlank()) throw new AssertionError("Backend device info has no name");
        GpuBuffer autoClosed = backend.createBuffer(null, usage, 16);
        backend.close();
        backend.close();
        check(autoClosed.isClosed(), "Context did not close its buffers");
        autoClosed.close();
        expect(IllegalStateException.class, () -> backend.createBuffer(null, usage, 16));

        long ctx = NativeMetal.createContext();
        long nativeBuffer = NativeMetal.createBuffer(ctx, 32, "MetalMC buffer 🧪");
        expect(IllegalStateException.class, () -> NativeMetal.destroyContext(ctx));
        expect(IllegalArgumentException.class, () -> NativeMetal.writeBuffer(nativeBuffer, 0, ByteBuffer.allocate(4), 4));
        expect(IllegalArgumentException.class, () -> NativeMetal.writeBuffer(nativeBuffer, 0, ByteBuffer.allocateDirect(4), 8));
        expect(IllegalArgumentException.class, () -> NativeMetal.readBuffer(nativeBuffer, 0,
                ByteBuffer.allocateDirect(4).asReadOnlyBuffer(), 4));
        expect(IllegalArgumentException.class, () -> NativeMetal.readBuffer(nativeBuffer, Long.MAX_VALUE, ByteBuffer.allocateDirect(4), 4));
        NativeMetal.destroyBuffer(nativeBuffer);
        expect(IllegalStateException.class, () -> NativeMetal.readBuffer(nativeBuffer, 0, ByteBuffer.allocateDirect(4), 4));
        expect(IllegalStateException.class, () -> NativeMetal.destroyBuffer(nativeBuffer));
        NativeMetal.destroyContext(ctx);
        System.out.println("PASS: native buffer allocation, staged mappings, partial GPU blits, bounds, usages and ownership.");
    }

    private static void verifyPixels(MetalImage image) {
        check(image.hasDiagnosticContent(), "Missing triangle or clear color");
        int[] expectedClear = {6, 9, 15, 255};
        for (int channel = 0; channel < 4; channel++) {
            check(Math.abs(image.component(0, 0, channel) - expectedClear[channel]) <= 1,
                    "Incorrect RGBA channel or clear color");
            check(Math.abs(image.component(image.width() - 1, image.height() - 1, channel)
                    - expectedClear[channel]) <= 1, "Incorrect final row or padding");
        }
        int colored = 0;
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                check(image.component(x, y, 3) == 255, "Opaque target has invalid alpha");
                int sum = image.component(x, y, 0) + image.component(x, y, 1) + image.component(x, y, 2);
                if (sum > 180) {
                    check(Math.abs(sum - 255) <= 2, "Interpolated triangle channels are invalid");
                    colored++;
                }
            }
        }
        double coverage = (double) colored / (image.width() * image.height());
        check(coverage > 0.28 && coverage < 0.36, "Triangle coverage incorrect: " + coverage);
        // Blue vertex is at the top; red and green are bottom left/right.
        int topX = image.width() / 2, topY = image.height() / 4;
        check(image.component(topX, topY, 2) > image.component(topX, topY, 0), "Image vertical orientation incorrect");
        int leftX = image.width() / 4, bottomY = image.height() * 3 / 4;
        check(image.component(leftX, bottomY, 0) > image.component(leftX, bottomY, 1), "Image red/green order incorrect");
    }

    private static void writeImage(MetalImage image, Path path) throws Exception {
        BufferedImage output = new BufferedImage(image.width(), image.height(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                output.setRGB(x, y, (image.component(x, y, 3) << 24) | (image.component(x, y, 0) << 16)
                        | (image.component(x, y, 1) << 8) | image.component(x, y, 2));
            }
        }
        Files.createDirectories(path.toAbsolutePath().getParent());
        check(ImageIO.write(output, "png", path.toFile()), "PNG writer unavailable");
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
