package dev.metalmc.metal;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.util.OptionalLong;

/** Bounded timestamp counters. Reads poll owned completion results and never wait. */
public final class MetalGpuQueryPool implements GpuQueryPool {
    public static final int MAX_QUERIES = 4096;
    private final MetalContext context;
    private final int size;
    private long handle;

    MetalGpuQueryPool(MetalContext context, int size, long handle) {
        if (handle == 0) throw new IllegalStateException("Native query pool creation returned null");
        this.context = context; this.size = size; this.handle = handle;
    }
    private void checkOpen() {
        context.checkOpen();
        if (handle == 0) throw new IllegalStateException("Metal timestamp query pool is closed");
    }
    @Override public int size() { checkOpen(); return size; }
    @Override public OptionalLong getValue(int index) { return getValues(index, 1)[0]; }
    @Override public OptionalLong[] getValues(int first, int count) {
        checkOpen();
        if (first < 0 || count < 0 || first > size || count > size - first) {
            throw new IndexOutOfBoundsException("Timestamp query range outside pool");
        }
        long[] values = NativeMetal.queryValues(handle, first, count);
        if (values == null || values.length != count * 2) throw new LinkageError("Invalid native timestamp query result");
        var result = new OptionalLong[count];
        for (int i = 0; i < count; i++) {
            if (values[i * 2] != 0 && values[i * 2] != 1) throw new LinkageError("Invalid native timestamp availability");
            result[i] = values[i * 2] == 0 ? OptionalLong.empty() : OptionalLong.of(values[i * 2 + 1]);
        }
        return result;
    }
    long commandHandle(MetalContext expected, int index) {
        checkOpen();
        if (context != expected) throw new IllegalArgumentException("Timestamp pool belongs to another Metal device");
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("Timestamp query index outside pool");
        return handle;
    }
    @Override public void close() {
        context.checkOwner();
        if (handle != 0) {
            NativeMetal.destroyQueryPool(handle);
            handle = 0;
            context.removeQueryPool(this);
        }
    }
}
