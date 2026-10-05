package dev.metalmc.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.metalmc.backend.MetalGpuDeviceBackend;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

/** Actual GPU counters, nanosecond calibration, nonblocking polling and retained generations. */
final class TimestampQueryTest {
    private TimestampQueryTest() {}
    static void run() throws Exception {
        verifyGpuClockAndTransfers();
        verifyCancellationAndOwnership();
        verifyAsyncRetirementAndShutdown();
        verifyNativeBoundariesAndLimits();
        System.out.println("PASS: real GPU timestamp pools, encoder-boundary samples, nonblocking optional reads, nanosecond clock calibration, reuse/cancellation, limits and async retirement (render-pass queries pending).");
    }
    private static void verifyGpuClockAndTransfers() throws InterruptedException {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder(); var alternate = context.createCommandEncoder()) {
            check(context.supportsTimestampQueries(), "Actual timestamp counter/stage-boundary support is required for this GPU gate");
            check(context.deviceInfo().timestampPeriod() == 1, "Supported GPU timestamps are not reported in nanoseconds");
            long offset = context.timestampCalibrationOffset();
            try (var pool = context.createTimestampQueryPool(8);
                    var from = context.createBuffer(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST, 65536);
                    var to = context.createBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 65536)) {
                check(pool.size() == 8 && pool.getValues(8,0).length == 0, "Pool size/empty tail differs");
                for (var value : pool.getValues(0,8)) check(value.isEmpty(), "Never-written timestamp was available");
                byte[] pattern = new byte[65536];
                for (int i = 0; i < pattern.length; i++) pattern[i] = (byte)(i*31+7);
                long before = System.nanoTime();
                encoder.writeTimestamp(pool, 2);
                check(pool.getValue(2).isEmpty(), "Recorded query published before submission");
                expect(IllegalStateException.class, () -> encoder.writeTimestamp(pool, 2));
                expect(IllegalStateException.class, () -> alternate.writeTimestamp(pool, 2));
                encoder.writeToBuffer(from.slice(), ByteBuffer.wrap(pattern));
                encoder.copyToBuffer(from.slice(), to.slice());
                encoder.writeTimestamp(pool, 3);
                finish(encoder);
                long after = System.nanoTime();
                long start = pool.getValue(2).orElseThrow(), end = pool.getValue(3).orElseThrow();
                check(start > 0 && end >= start, "GPU timestamp counters are not positive/ordered around blits: start="
                        + start + ", end=" + end + ", calibrationOffset=" + offset);
                check(start + offset >= before - 10_000_000L && end + offset <= after + 10_000_000L,
                        "GPU timestamp values are not correlated with JVM nanoseconds: start=" + start + ", end=" + end
                        + ", offset=" + offset + ", before=" + before + ", after=" + after);
                try (var mapped = to.map(true,false)) {
                    byte[] result = new byte[pattern.length]; mapped.data().get(result);
                    check(Arrays.equals(result, pattern), "Sampling corrupted ordered GPU buffer transfers");
                }
                var results = pool.getValues(1,4);
                check(results[0].isEmpty() && results[1].orElseThrow() == start && results[2].orElseThrow() == end && results[3].isEmpty(),
                        "Partial query availability/range mismatch");
                results[1] = OptionalLong.empty();
                check(pool.getValue(2).orElseThrow() == start, "Query result array escaped mutable storage");
                long[] stats = context.commandStats();
                for (int i = 0; i < 64; i++) pool.getValues(0,8);
                check(Arrays.equals(stats, context.commandStats()), "Query polling submitted commands or allocated native resources");
                // Diagnostic pause between completed submissions checks actual nanosecond units.
                Thread.sleep(20);
                encoder.writeTimestamp(pool, 2);
                check(pool.getValue(2).isEmpty(), "Reused query exposed the previous generation");
                finish(encoder);
                long next = pool.getValue(2).orElseThrow();
                check(next - end >= 5_000_000L && next - end < 5_000_000_000L,
                        "GPU timestamp period is not nanoseconds: elapsed=" + (next-end));
                long recalibration = context.timestampCalibrationOffset();
                check(Math.abs(recalibration - offset) < 10_000_000L,
                        "GPU/JVM calibration origin changed unexpectedly: old=" + offset + ", new=" + recalibration);
                System.out.println("Timestamp clock: nanoseconds; GPU interval=" + (end-start) + " ns, JVM calibration offset=" + offset + " ns");
                // Unsampled work before the first marker and between several markers
                // reproduces the driver sequence that omitted later blit samples.
                encoder.writeToBuffer(from.slice(), ByteBuffer.wrap(pattern));
                encoder.writeTimestamp(pool,0);
                encoder.copyToBuffer(from.slice(), to.slice());
                encoder.writeTimestamp(pool,1);
                byte[] replacement = pattern.clone();
                for (int i = 0; i < replacement.length; i++) replacement[i] ^= (byte)0xA5;
                encoder.writeToBuffer(from.slice(), ByteBuffer.wrap(replacement));
                encoder.writeTimestamp(pool,4);
                encoder.copyToBuffer(from.slice(), to.slice());
                encoder.writeTimestamp(pool,5);
                finish(encoder);
                long previous = next;
                for (int index : new int[]{0,1,4,5}) {
                    long value = pool.getValue(index).orElseThrow();
                    check(value > 0 && value >= previous,
                            "Timestamp after unsampled transfer is invalid/out of order: slot=" + index
                            + ", value=" + value + ", previous=" + previous);
                    previous = value;
                }
                check(pool.getValue(2).orElseThrow() == next && pool.getValue(3).orElseThrow() == end,
                        "New timestamp submission changed unrelated cached slots");
                try (var mapped = to.map(true,false)) {
                    byte[] result = new byte[replacement.length]; mapped.data().get(result);
                    check(Arrays.equals(result, replacement), "Timestamp event ordering changed interleaved GPU bytes");
                }
            }
            check(context.queryPoolCount() == 0, "Completed query pool leaked");
            check(context.commandStats()[4] == 0, "Completed query marker buffer leaked");
        }
        try (var device = MetalGpuDeviceBackend.createForDevelopment(); var encoder = (MetalCommandEncoder)device.createCommandEncoder();
                var pool = device.createTimestampQueryPool(1)) {
            check(device.getDeviceInfo().timestampPeriod() == 1 && pool.size() == 1, "Device query factory/policy differs");
            device.getTimestampCalibrationOffset();
            encoder.writeTimestamp(pool,0); finish(encoder);
            check(pool.getValue(0).isPresent(), "Backend factory query did not complete");
        }
    }
    private static void verifyCancellationAndOwnership() throws InterruptedException {
        try (var context = MetalContext.create(); var foreign = MetalContext.create(); var encoder = context.createCommandEncoder();
                var pool = context.createTimestampQueryPool(4); var other = foreign.createTimestampQueryPool(1);
                var texture = context.createTexture(null, GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, 2,2,1,1);
                var view = context.createTextureView(texture,0,1)) {
            expect(IllegalArgumentException.class, () -> context.createTimestampQueryPool(0));
            expect(IllegalArgumentException.class, () -> context.createTimestampQueryPool(MetalGpuQueryPool.MAX_QUERIES+1));
            for (int[] range : new int[][]{{-1,1},{0,-1},{4,1},{3,2},{1,Integer.MAX_VALUE}}) {
                expect(IndexOutOfBoundsException.class, () -> pool.getValues(range[0],range[1]));
            }
            expect(IndexOutOfBoundsException.class, () -> encoder.writeTimestamp(pool,-1));
            expect(IndexOutOfBoundsException.class, () -> encoder.writeTimestamp(pool,4));
            expect(IllegalArgumentException.class, () -> encoder.writeTimestamp(other,0));
            expect(IllegalArgumentException.class, () -> encoder.writeTimestamp(null,0));
            var failure = new AtomicReference<Throwable>();
            var thread = new Thread(() -> { try { pool.getValue(0); } catch (Throwable error) { failure.set(error); } });
            thread.start(); thread.join(); check(failure.get() instanceof IllegalStateException, "Pool accepted foreign thread");
            encoder.writeTimestamp(pool,0); finish(encoder);
            var cancel = context.createCommandEncoder();
            try {
                cancel.writeTimestamp(pool,0);
                check(pool.getValue(0).isEmpty(), "Cancelled generation retained an old query value");
                try (var fence = cancel.createFence()) {
                    cancel.close(); expect(IllegalStateException.class, () -> fence.awaitCompletion(0));
                }
            } finally { cancel.close(); }
            check(pool.getValue(0).isEmpty(), "Cancelled sampling produced a GPU timestamp");
            encoder.writeTimestamp(pool,0); finish(encoder);
            check(pool.getValue(0).isPresent(), "Cancelled slot could not be reused");
            encoder.writeTimestamp(pool,1);
            var pass = encoder.createRenderPass(RenderPassDescriptor.builder(() -> "timestamp boundary")
                    .withColorAttachment(view, java.util.Optional.of(TextureCommandTest.color(0,0,0,1))).build());
            expect(IllegalStateException.class, () -> encoder.writeTimestamp(pool,1));
            expect(UnsupportedOperationException.class, () -> pass.writeTimestamp(pool,1));
            encoder.submitRenderPass();
            encoder.writeTimestamp(pool,2); finish(encoder);
            long beforePass = pool.getValue(1).orElseThrow(), afterPass = pool.getValue(2).orElseThrow();
            check(beforePass > 0 && afterPass >= beforePass,
                    "Timestamp markers around a render pass are invalid/out of order: before=" + beforePass + ", after=" + afterPass);
            var closed = context.createTimestampQueryPool(1);
            long stale = closed.commandHandle(context,0); closed.close(); closed.close();
            expect(IllegalStateException.class, closed::size);
            expect(IllegalStateException.class, () -> closed.getValues(0,0));
            expect(IllegalStateException.class, () -> encoder.writeTimestamp(closed,0));
            expect(IllegalStateException.class, () -> NativeMetal.queryValues(stale,0,1));
        }
    }
    private static void verifyAsyncRetirementAndShutdown() {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder(); var pool = context.createTimestampQueryPool(64)) {
            for (int i = 0; i < 32; i++) {
                encoder.writeTimestamp(pool, i*2);
                var temporary = context.createTimestampQueryPool(1);
                try { encoder.writeTimestamp(temporary,0); } finally { temporary.close(); }
                encoder.writeTimestamp(pool,i*2+1);
                if (i == 0) {
                    check(context.queryPoolCount() == 2, "Recorded closed query allocation retired too early");
                    long[] recorded = context.commandStats();
                    check(recorded[4] == 1040 && recorded[5] == 520,
                            "Recorded query markers/resolution snapshots retired too early");
                }
                if (i == 31) finish(encoder); else encoder.submit();
            }
            long previous = 0;
            for (var value : pool.getValues(0,64)) {
                long current = value.orElseThrow(); check(current >= previous && current > 0, "Async GPU query order/availability differs"); previous = current;
            }
            long[] stats = context.commandStats();
            check(stats[0] == 32 && stats[1] == 32 && stats[2] == 0 && stats[4] == 512 && stats[5] == 0
                    && context.queryPoolCount() == 1, "Async query allocation/submission retirement differs");
        }
        var context = MetalContext.create();
        var pool = context.createTimestampQueryPool(2);
        var encoder = context.createCommandEncoder();
        encoder.writeTimestamp(pool,0); encoder.submit();
        encoder.writeTimestamp(pool,1); // Shutdown cancels this generation and drains the first.
        long stale = pool.commandHandle(context,0), staleContext = context.nativeHandle();
        context.close(); context.close();
        expect(IllegalStateException.class, () -> pool.getValue(0));
        expect(IllegalStateException.class, context::supportsTimestampQueries);
        expect(IllegalStateException.class, context::timestampCalibrationOffset);
        expect(IllegalStateException.class, () -> NativeMetal.queryValues(stale,0,1));
        expect(IllegalStateException.class, () -> NativeMetal.sampleGpuTimestamp(staleContext));
        pool.close(); encoder.close();
    }
    private static void verifyNativeBoundariesAndLimits() {
        expect(IllegalStateException.class, () -> NativeMetal.timestampSupported(0));
        expect(IllegalStateException.class, () -> NativeMetal.sampleGpuTimestamp(0));
        expect(IllegalStateException.class, () -> NativeMetal.createQueryPool(0,1));
        expect(IllegalStateException.class, () -> NativeMetal.queryValues(0,0,1));
        try (var context = MetalContext.create(); var foreign = MetalContext.create();
                var pool = context.createTimestampQueryPool(4096); var other = foreign.createTimestampQueryPool(1)) {
            long contextId = context.nativeHandle(), poolId = pool.commandHandle(context,0);
            check(pool.getValues(0,4096).length == 4096, "Maximum query pool range unavailable");
            expect(IllegalArgumentException.class, () -> NativeMetal.createQueryPool(contextId,0));
            expect(IllegalArgumentException.class, () -> NativeMetal.createQueryPool(contextId,4097));
            expect(IndexOutOfBoundsException.class, () -> NativeMetal.queryValues(poolId,0,Integer.MAX_VALUE));
            long batch = NativeMetal.createBatch(contextId);
            try {
                expect(IllegalStateException.class, () -> NativeMetal.destroyContext(contextId));
                expect(IllegalStateException.class, () -> NativeMetal.encodeTimestamp(0,poolId,0));
                expect(IllegalStateException.class, () -> NativeMetal.encodeTimestamp(batch,0,0));
                expect(IllegalArgumentException.class, () -> NativeMetal.encodeTimestamp(batch,other.commandHandle(foreign,0),0));
                expect(IndexOutOfBoundsException.class, () -> NativeMetal.encodeTimestamp(batch,poolId,4096));
                for (int i = 0; i < 4096; i++) NativeMetal.encodeTimestamp(batch,poolId,i);
                expect(IllegalStateException.class, () -> NativeMetal.encodeTimestamp(batch,poolId,0));
                NativeMetal.cancelBatch(batch);
                for (var value : pool.getValues(0,4096)) check(value.isEmpty(), "Cancelled max-operation samples became available");
                expect(IllegalStateException.class, () -> NativeMetal.encodeTimestamp(batch,poolId,0));
            } finally { NativeMetal.cancelBatch(batch); NativeMetal.releaseBatch(batch); }
            var limits = new MetalGpuQueryPool[15];
            try {
                for (int i = 0; i < limits.length; i++) limits[i] = context.createTimestampQueryPool(1);
                check(context.queryPoolCount() == 16, "Native pool-count cap differs");
                check(context.commandStats()[4] == (4096L + 15) * 8, "Timestamp marker buffers are not accounted");
                expect(IllegalStateException.class, () -> context.createTimestampQueryPool(1));
            } finally { for (var allocation : limits) if (allocation != null) allocation.close(); }
            check(context.queryPoolCount() == 1, "Pool limit rejection leaked allocations");
            check(context.commandStats()[4] == 4096L * 8, "Pool limit cleanup leaked marker buffers");
            check(context.commandStats()[5] == 0, "Cancelled timestamp resolution staging leaked");
        }
    }
    private static void finish(MetalCommandEncoder encoder) {
        try (var fence = encoder.createFence()) { encoder.submit(); check(fence.awaitCompletion(GpuFence.NO_TIMEOUT), "Timestamp fence failed"); }
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
