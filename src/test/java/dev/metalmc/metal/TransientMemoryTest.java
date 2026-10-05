package dev.metalmc.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Real GPU arena data, generation, completion, cancellation and reuse checks. */
final class TransientMemoryTest {
    private static final int SOURCE = GpuBuffer.USAGE_COPY_SRC;
    private static final int TARGET = GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ;
    private TransientMemoryTest() {}

    static void run() throws InterruptedException {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder();
                var target = context.createBuffer(TARGET, 1024)) {
            var memory = encoder.transientMemory();
            check(memory == encoder.transientMemory(), "Encoder replaced its arena");
            ByteBuffer cpu = memory.allocateCpu(37, 256);
            check(cpu.isDirect() && cpu.capacity() == 37 && cpu.alignmentOffset(0, 256) == 0,
                    "CPU allocation lost size/alignment");
            ByteBuffer first = ByteBuffer.wrap(new byte[]{99, 1, 2, 3, 4, 88});
            first.position(1).limit(5);
            var staged = memory.uploadStaging(first, 256, SOURCE);
            var next = memory.uploadStaging(ByteBuffer.wrap(new byte[]{5, 6, 7, 8}), 256, SOURCE);
            check(staged.offset() == 0 && next.offset() == 256 && staged.buffer() != next.buffer(),
                    "Arena suballocation/alignment failed");
            check(first.position() == 1 && first.limit() == 5, "Upload changed source bounds");
            first.put(1, (byte)0);
            encoder.copyToBuffer(staged.slice(0, 4), target.slice(0, 4));
            encoder.copyToBuffer(next.slice(0, 4), target.slice(4, 4));
            expect(IllegalArgumentException.class, () -> encoder.copyToBuffer(
                    new GpuBufferSlice(staged.buffer(), 512, 4), target.slice(0, 4)));
            try (var other = context.createCommandEncoder()) {
                expect(IllegalArgumentException.class, () -> other.copyToBuffer(staged, target.slice(0, 4)));
            }
            var mapped = memory.allocateGpuMapped(16, 16, SOURCE);
            for (int i = 0; i < 16; i++) mapped.data().put(i, (byte)(20 + i));
            expect(IllegalStateException.class, encoder::submit);
            expect(IllegalStateException.class, () -> encoder.copyToBuffer(mapped.slice(), target.slice(16, 16)));
            expect(IllegalStateException.class, mapped.slice().buffer()::close);
            mapped.close(); mapped.close();
            mapped.data().put(0, (byte)99); // Detached bytes cannot mutate recorded native staging.
            encoder.copyToBuffer(mapped.slice(), target.slice(16, 16));
            mapped.slice().buffer().close(); // Logical closure preserves already recorded GPU work.
            expect(IllegalStateException.class, () -> encoder.copyToBuffer(mapped.slice(), target.slice(16, 16)));

            var combined = memory.uploadGpu(List.of(ByteBuffer.wrap(new byte[]{9, 10, 11}),
                    ByteBuffer.wrap(new byte[]{12, 13, 14, 15, 16})), 4, SOURCE);
            check(combined.length() == 12, "List upload alignment/padding differs from Vulkan contract");
            encoder.copyToBuffer(combined, target.slice(32, 12));
            var multi = memory.multiUploadStaging(List.of(ByteBuffer.wrap(new byte[]{41, 42, 43, 44}),
                    ByteBuffer.wrap(new byte[]{51, 52, 53, 54, 55, 56, 57, 58})), 64, SOURCE);
            check(multi.size() == 2 && multi.get(0).length() == 4 && multi.get(1).length() == 8,
                    "Multi-upload lost input order/lengths");
            encoder.copyToBuffer(multi.get(0), target.slice(48, 4));
            encoder.copyToBuffer(multi.get(1), target.slice(52, 8));
            var multiGpu = memory.multiUploadGpu(List.of(ByteBuffer.wrap(new byte[]{61, 62, 63, 64})), 32, SOURCE);
            encoder.copyToBuffer(multiGpu.getFirst(), target.slice(60, 4));
            var stagingMap = memory.allocateStaging(8, 4, SOURCE);
            for (int i = 0; i < 8; i++) stagingMap.data().put(i, (byte)(71 + i));
            stagingMap.close();
            var gpu = memory.allocateGpu(8, 4, SOURCE | GpuBuffer.USAGE_COPY_DST);
            encoder.copyToBuffer(stagingMap.slice(), gpu);
            encoder.copyToBuffer(gpu, target.slice(64, 8));
            expect(IllegalStateException.class, () -> staged.buffer().map(true, false));
            expect(IllegalStateException.class, staged.buffer()::slice);
            expect(IllegalStateException.class, () -> encoder.writeToBuffer(staged, ByteBuffer.allocate(4)));
            expect(IllegalArgumentException.class, () -> memory.allocateGpu(4, 0, SOURCE));
            expect(IllegalArgumentException.class, () -> memory.allocateGpu(4, 1, SOURCE, 5, 1));
            expect(IllegalArgumentException.class, () -> memory.allocateGpu(4, 1, SOURCE, 1, 0));
            expect(IllegalArgumentException.class, () -> memory.allocateGpu(Long.MAX_VALUE, 1, SOURCE));
            expect(UnsupportedOperationException.class, () -> memory.allocateCpu(16, 3));
            expect(UnsupportedOperationException.class, () -> memory.allocateGpu(4, 4, 0));
            AtomicReference<Throwable> foreign = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try { memory.allocateCpu(4, 4); } catch (Throwable failure) { foreign.set(failure); }
            });
            thread.start(); thread.join();
            check(foreign.get() instanceof IllegalStateException, "Foreign-thread allocation accepted");
            try (var fence = encoder.createFence()) {
                encoder.submit();
                check(staged.buffer().isClosed() && next.buffer().isClosed(), "Submit did not expire transient slices");
                expect(IllegalStateException.class, () -> encoder.copyToBuffer(staged, target.slice(0, 4)));
                check(fence.awaitCompletion(GpuFence.NO_TIMEOUT), "Transient transfer fence failed");
            }
            verify(target, 0, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
            byte[] pattern = new byte[16];
            for (int i = 0; i < 16; i++) pattern[i] = (byte)(20 + i);
            verify(target, 16, pattern);
            verify(target, 32, new byte[]{9, 10, 11, 0, 12, 13, 14, 15, 16, 0, 0, 0});
            verify(target, 48, new byte[]{41, 42, 43, 44, 51, 52, 53, 54, 55, 56, 57, 58});
            verify(target, 60, new byte[]{61, 62, 63, 64, 71, 72, 73, 74, 75, 76, 77, 78});
            cpu.put(0, (byte)99); // CPU views never point into reusable native storage either.

            // Fourth/fifth parameters allow a partial tail, rounded to the requested quantum.
            var almostFull = memory.allocateGpu(1024 * 1024 - 12, 4, SOURCE);
            var partial = memory.allocateGpu(64, 4, SOURCE, 4, 8);
            check(partial.offset() == almostFull.length() && partial.length() == 8, "Partial tail contract failed");
            encoder.submit();
        }
        verifyReuse();
        verifyAsynchronousReuse();
        verifyCancellationAndClose();
        verifyLimits();
        System.out.println("PASS: transient arenas, aligned/partial allocations, upload snapshots, GPU bytes, generations, reuse and cancellation.");
    }

    private static void verifyReuse() {
        try (var context = MetalContext.create(); var target = context.createBuffer(TARGET, 4)) {
            var encoder = context.createCommandEncoder();
            var memory = encoder.transientMemory();
            for (int frame = 0; frame < 64; frame++) {
                var slice = memory.uploadGpu(ByteBuffer.wrap(new byte[]{(byte)frame, 2, 3, 4}), 256, SOURCE);
                encoder.copyToBuffer(slice.slice(0, 4), target.slice());
                try (var fence = encoder.createFence()) {
                    encoder.submit();
                    check(fence.awaitCompletion(GpuFence.NO_TIMEOUT), "Reuse fence failed");
                }
                check(context.commandStats()[4] == 1024 * 1024 + 4, "Completed arena grew instead of reusing storage");
                verify(target, 0, new byte[]{(byte)frame, 2, 3, 4});
            }
            encoder.close();
            check(context.commandStats()[4] == 4, "Encoder close leaked arena blocks");
            expect(IllegalStateException.class, () -> memory.allocateCpu(4, 4));
        }
    }
    private static void verifyAsynchronousReuse() {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder();
                var target = context.createBuffer(TARGET, 64 * 4)) {
            var memory = encoder.transientMemory();
            for (int frame = 0; frame < 64; frame++) {
                var source = memory.uploadStaging(ByteBuffer.wrap(new byte[]{(byte)frame, 3, 2, 1}), 4, SOURCE);
                encoder.copyToBuffer(source, target.slice(frame * 4L, 4));
                encoder.submit();
            }
            try (var fence = encoder.createFence()) {
                encoder.submit();
                check(fence.awaitCompletion(GpuFence.NO_TIMEOUT), "Async arena fence failed");
            }
            for (int frame = 0; frame < 64; frame++) verify(target, frame * 4L, new byte[]{(byte)frame, 3, 2, 1});
            check(context.commandStats()[4] <= 4L * 1024 * 1024 + target.size(), "Async arenas exceeded bounded in-flight reuse");
            check(context.commandStats()[3] <= 3, "Arenas bypassed submission backpressure");
        }
    }
    private static void verifyCancellationAndClose() {
        try (var context = MetalContext.create(); var target = context.createBuffer(TARGET, ByteBuffer.wrap(new byte[]{7, 7, 7, 7}))) {
            var encoder = context.createCommandEncoder();
            var memory = encoder.transientMemory();
            var slice = memory.uploadStaging(ByteBuffer.wrap(new byte[]{1, 2, 3, 4}), 4, SOURCE);
            encoder.copyToBuffer(slice, target.slice());
            var cancelled = encoder.createFence();
            encoder.close();
            expect(IllegalStateException.class, () -> cancelled.awaitCompletion(0));
            cancelled.close();
            check(slice.buffer().isClosed(), "Cancelled generation still live");
            verify(target, 0, new byte[]{7, 7, 7, 7});
            check(context.commandStats()[4] == 4, "Cancellation leaked arena/staging");
            var submitted = context.createCommandEncoder();
            var uploaded = submitted.transientMemory().uploadGpu(ByteBuffer.wrap(new byte[]{4, 3, 2, 1}), 4, SOURCE);
            submitted.copyToBuffer(uploaded, target.slice());
            try (var fence = submitted.createFence()) {
                submitted.submit();
                submitted.close(); // Native commands retain closed arena buffers through GPU completion.
                check(fence.awaitCompletion(GpuFence.NO_TIMEOUT), "Retired arena was released early");
            }
            verify(target, 0, new byte[]{4, 3, 2, 1});
            var unclosed = context.createCommandEncoder();
            var mapping = unclosed.transientMemory().allocateStaging(7, 4, SOURCE);
            unclosed.close();
            expect(IllegalStateException.class, mapping::close);
            check(mapping.slice().buffer().isClosed(), "Encoder close left an active mapped token");
            mapping.data().put(0, (byte)1); // Detached view remains safe after native storage is destroyed.
            check(context.commandStats()[4] == 4, "Mapped cancellation leaked pool blocks");
        }
    }
    private static void verifyLimits() {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder()) {
            var memory = encoder.transientMemory();
            memory.allocateGpu(MetalTransientMemory.MAX_BYTES, 4, SOURCE);
            expect(IllegalStateException.class, () -> memory.allocateGpu(4, 4, SOURCE));
            check(context.commandStats()[4] == MetalTransientMemory.MAX_BYTES, "Pool bound failed before rejection");
        }
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder()) {
            var memory = encoder.transientMemory();
            for (int i = 0; i < 1024; i++) memory.allocateGpu(4, 4, SOURCE);
            expect(IllegalStateException.class, () -> memory.allocateGpu(4, 4, SOURCE));
        }
    }
    private static void verify(MetalGpuBuffer buffer, long offset, byte[] expected) {
        try (var view = buffer.map(offset, expected.length, true, false)) {
            for (int i = 0; i < expected.length; i++) check(view.data().get(i) == expected[i], "Transient GPU bytes differ at " + (offset + i));
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void expect(Class<? extends Throwable> type, Runnable operation) {
        try { operation.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
