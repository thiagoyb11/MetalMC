package dev.metalmc.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import dev.metalmc.backend.MetalGpuDeviceBackend;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/** Real GPU integration tests of ordering, fences, CPU visibility and retirement. */
final class CommandEncoderTest {
    private static final int USAGE = GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST
            | GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE;

    private CommandEncoderTest() {}

    static void run() throws InterruptedException {
        try (MetalContext context = MetalContext.create();
                MetalCommandEncoder encoder = context.createCommandEncoder();
                MetalGpuBuffer source = context.createBuffer(USAGE, 1024);
                MetalGpuBuffer target = context.createBuffer(USAGE, 1024)) {
            encoder.submit(); // Empty submits allocate nothing.
            check(context.commandStats()[0] == 0, "Empty submit issued GPU work");
            ByteBuffer input = ByteBuffer.allocate(80);
            input.position(8).limit(72);
            for (int i = 8; i < 72; i++) input.put(i, (byte)(i * 7));
            encoder.writeToBuffer(source.slice(16, 64), input);
            check(input.position() == 8 && input.limit() == 72, "Upload changed source range");
            input.put(8, (byte)0); // Native staging must already own a snapshot.
            expect(IllegalStateException.class, () -> source.map(16, 64, true, false));
            expect(IllegalStateException.class, () -> source.upload(16, ByteBuffer.allocate(4)));
            try (MetalGpuFence fence = encoder.createFence(); MetalGpuFence second = encoder.createFence()) {
                check(!fence.awaitCompletion(0), "Unsubmitted fence completed");
                expect(IllegalStateException.class, () -> fence.awaitCompletion(GpuFence.NO_TIMEOUT));
                expect(IllegalArgumentException.class, () -> fence.awaitCompletion(-2));
                encoder.copyToBuffer(source.slice(16, 64), target.slice(32, 64));
                encoder.submit();
                check(fence.awaitCompletion(5_000_000_000L), "Fence timed out");
                check(second.awaitCompletion(0), "Same-submission fence not signaled");
                verify(target, 32, 64, 8, 7);
            }
            expect(UnsupportedOperationException.class,
                    () -> encoder.copyToBuffer(source.slice(1, 4), target.slice(0, 4)));
            expect(UnsupportedOperationException.class, () -> encoder.writeToBuffer(target.slice(0, 4), ByteBuffer.allocate(3)));
            expect(UnsupportedOperationException.class, () -> encoder.copyToBuffer(source.slice(0, 4), source.slice(4, 4)));
            expect(IllegalArgumentException.class, () -> encoder.copyToBuffer(source.slice(0, 4), target.slice(0, 8)));
            expect(IllegalArgumentException.class, () -> encoder.writeToBuffer(target.slice(0, 4), ByteBuffer.allocate(8)));
            expect(IllegalArgumentException.class, () -> encoder.copyToBuffer(
                    new GpuBufferSlice(source, Long.MAX_VALUE, 4), target.slice(0, 4)));
            try (MetalContext other = MetalContext.create(); MetalGpuBuffer foreign = other.createBuffer(USAGE, 16)) {
                expect(IllegalArgumentException.class, () -> encoder.copyToBuffer(source.slice(0, 4), foreign.slice(0, 4)));
            }
            try (var mapping = source.map(0, 4, false, true)) {
                check(mapping.data().remaining() == 4, "Mapped test range differs");
                expect(IllegalStateException.class, () -> encoder.copyToBuffer(source.slice(0, 4), target.slice(0, 4)));
            }
            try (var noCopy = context.createBuffer(GpuBuffer.USAGE_MAP_READ, 16)) {
                expect(IllegalStateException.class, () -> encoder.writeToBuffer(noCopy.slice(), ByteBuffer.allocate(16)));
            }
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try { encoder.submit(); } catch (Throwable caught) { failure.set(caught); }
            });
            thread.start(); thread.join();
            check(failure.get() instanceof IllegalStateException, "Foreign-thread submission accepted");

            // CPU access without an explicit fence must synchronize submitted writes.
            encoder.writeToBuffer(source.slice(0, 16), ByteBuffer.wrap(new byte[16]));
            var earlyClosedFence = encoder.createFence();
            earlyClosedFence.close(); // Fence closure must not cancel the encoder's batch.
            encoder.submit();
            try (var read = source.map(0, 16, true, false)) {
                for (int i = 0; i < 16; i++) check(read.data().get(i) == 0, "CPU read raced GPU write");
            }
            // A recorded copy still works after its Java source has been closed.
            var retired = context.createBuffer(USAGE, ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));
            encoder.copyToBuffer(retired.slice(), target.slice(0, 4));
            retired.close();
            check(context.commandStats()[4] == 2052, "Closed recorded source disappeared from memory budget");
            try (var fence = encoder.createFence()) {
                encoder.submit();
                check(fence.awaitCompletion(GpuFence.NO_TIMEOUT), "Retained resource fence failed");
            }
            try (var read = target.map(0, 4, true, false)) {
                for (int i = 0; i < 4; i++) check(read.data().get(i) == i + 1, "Closed source released GPU storage early");
            }

            // Multiple asynchronous submissions, each with Java resources closed immediately.
            for (int iteration = 0; iteration < 96; iteration++) {
                ByteBuffer pattern = ByteBuffer.allocate(1024);
                for (int i = 0; i < 1024; i++) pattern.put(i, (byte)(iteration + i));
                var temporary = context.createBuffer(USAGE, 1024);
                encoder.writeToBuffer(temporary.slice(), pattern);
                encoder.copyToBuffer(temporary.slice(), target.slice());
                encoder.submit();
                temporary.close();
            }
            try (var fence = encoder.createFence()) {
                encoder.submit();
                check(fence.awaitCompletion(Long.MAX_VALUE), "Stress fence timed out");
            }
            try (var read = target.map(true, false)) {
                for (int i = 0; i < 1024; i++) check(read.data().get(i) == (byte)(95 + i), "Submission ordering failed at " + i);
            }
            long[] stats = context.commandStats();
            check(stats[0] == stats[1] && stats[2] == 0 && stats[3] <= 3, "Submission completion/bound failed");
            check(stats[4] == 2048, "Retired buffers/staging remained allocated: " + stats[4]);
            System.out.println("Async commands: " + stats[0] + " completed, peak " + stats[3] + " in flight");
        }
        verifyCancellation();
        verifyNativeBoundary();
        try (var backend = MetalGpuDeviceBackend.createForDevelopment()) {
            var encoder = backend.createCommandEncoder();
            check(encoder.transientMemory() instanceof MetalTransientMemory, "Backend did not provide transient arenas");
            // Render passes now exist; a missing descriptor is invalid input.
            // Valid attachment passes are exercised separately by RenderPassTest.
            expect(NullPointerException.class, () -> encoder.createRenderPass(null));
        }
        System.out.println("PASS: async buffer ordering, staging snapshot, fences, CPU hazards, retirement and cancellation");
    }

    private static void verifyCancellation() {
        var context = MetalContext.create();
        var buffer = context.createBuffer(USAGE, 16);
        var encoder = context.createCommandEncoder();
        encoder.writeToBuffer(buffer.slice(), ByteBuffer.allocate(16));
        var cancelled = encoder.createFence();
        encoder.close(); encoder.close();
        expect(IllegalStateException.class, () -> cancelled.awaitCompletion(0));
        expect(IllegalStateException.class, encoder::submit);
        try (var read = buffer.map(true, false)) { check(read.data().remaining() == 16, "Cancelled commands still block CPU mapping"); }
        check(context.commandStats()[4] == 16, "Cancellation leaked staging");
        cancelled.close(); cancelled.close();
        expect(IllegalStateException.class, () -> cancelled.awaitCompletion(0));
        encoder = context.createCommandEncoder();
        encoder.writeToBuffer(buffer.slice(), ByteBuffer.allocate(16));
        var fence = encoder.createFence();
        encoder.submit();
        context.close(); context.close();
        check(buffer.isClosed(), "Device did not close resource");
        expect(IllegalStateException.class, () -> fence.awaitCompletion(0));
        fence.close();
    }

    private static void verifyNativeBoundary() {
        long context = NativeMetal.createContext();
        long source = NativeMetal.createBuffer(context, 16, null);
        long target = NativeMetal.createBuffer(context, 16, null);
        long batch = NativeMetal.createBatch(context);
        expect(IllegalStateException.class, () -> NativeMetal.destroyContext(context));
        expect(IllegalArgumentException.class, () -> NativeMetal.encodeBufferCopy(batch, source, 1, target, 0, 4));
        expect(IllegalArgumentException.class, () -> NativeMetal.encodeBufferCopy(batch, source, Long.MAX_VALUE, target, 0, 4));
        expect(IllegalArgumentException.class, () -> NativeMetal.encodeBufferWrite(batch, target, 0, ByteBuffer.allocate(4), 4));
        expect(IllegalArgumentException.class, () -> NativeMetal.encodeBufferWrite(batch, target, 0, ByteBuffer.allocateDirect(4), 8));
        NativeMetal.encodeBufferCopy(batch, source, 0, target, 0, 16);
        expect(IllegalStateException.class, () -> NativeMetal.readBuffer(source, 0, ByteBuffer.allocateDirect(16), 16));
        NativeMetal.submitBatch(batch);
        expect(IllegalStateException.class, () -> NativeMetal.submitBatch(batch));
        expect(IllegalStateException.class, () -> NativeMetal.encodeBufferCopy(batch, source, 0, target, 0, 4));
        check(NativeMetal.awaitBatch(batch, 5_000_000_000L), "Native batch timed out");
        NativeMetal.drainCommands(context);
        NativeMetal.releaseBatch(batch);
        expect(IllegalStateException.class, () -> NativeMetal.awaitBatch(batch, 0));
        expect(IllegalStateException.class, () -> NativeMetal.releaseBatch(batch));
        NativeMetal.destroyBuffer(source); NativeMetal.destroyBuffer(target);
        NativeMetal.destroyContext(context);
    }

    private static void verify(MetalGpuBuffer target, int offset, int length, int base, int multiplier) {
        try (var read = target.map(true, false)) {
            for (int i = 0; i < target.size(); i++) {
                byte expected = i >= offset && i < offset + length ? (byte)((base + i - offset) * multiplier) : 0;
                check(read.data().get(i) == expected, "GPU copy/untouched bytes differ at " + i);
            }
        }
    }
    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", received " + failure, failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
