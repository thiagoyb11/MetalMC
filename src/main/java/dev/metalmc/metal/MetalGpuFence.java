package dev.metalmc.metal;

import com.mojang.renderpearl.api.commands.GpuFence;

/** Submission completion with nanosecond timeouts; no Java callbacks on Metal threads. */
public final class MetalGpuFence implements GpuFence {
    private final MetalContext context;
    private long handle;

    MetalGpuFence(MetalContext context, long handle) {
        if (handle == 0) throw new IllegalStateException("Native fence creation returned null");
        this.context = context;
        this.handle = handle;
    }
    @Override public boolean awaitCompletion(long timeoutNanos) {
        context.checkOpen();
        if (handle == 0) throw new IllegalStateException("Metal fence is closed");
        if (timeoutNanos < NO_TIMEOUT) throw new IllegalArgumentException("Invalid fence timeout");
        boolean completed = NativeMetal.awaitBatch(handle, timeoutNanos);
        if (completed) context.pollCallbacks();
        return completed;
    }
    @Override public void close() {
        context.checkOwner();
        if (handle != 0) {
            NativeMetal.releaseBatch(handle);
            handle = 0;
            context.removeFence(this);
        }
    }
}
