package dev.metalmc.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/** Initial RenderPearl buffer wrapper. Mapping uses owned Java staging memory,
 * not escaped Metal pointers. Native CPU transfers reject unsubmitted GPU uses
 * and drain submitted commands before touching shared memory.
 */
public final class MetalGpuBuffer implements GpuBuffer {
    public static final long MAX_SIZE = 256L * 1024 * 1024;
    private final MetalContext context;
    private final int usage;
    private final long size;
    private long handle;
    private boolean mapped;

    MetalGpuBuffer(MetalContext context, long handle, int usage, long size) {
        if (handle == 0) throw new IllegalStateException("Native buffer creation returned null");
        this.context = context;
        this.handle = handle;
        this.usage = usage;
        this.size = size;
    }

    static void validate(int usage, long size) {
        if (size <= 0 || size > MAX_SIZE) throw new IllegalArgumentException("Buffer size must be 1..256 MiB");
        int supported = USAGE_MAP_READ | USAGE_MAP_WRITE | USAGE_HINT_CLIENT_STORAGE
                | USAGE_COPY_DST | USAGE_COPY_SRC | USAGE_VERTEX | USAGE_INDEX | USAGE_UNIFORM
                | USAGE_INDIRECT_PARAMETERS;
        if (usage == 0 || (usage & ~supported) != 0) {
            throw new UnsupportedOperationException("Unknown or unsupported buffer usage: " + usage);
        }
    }

    @Override public long size() { return size; }
    @Override public int usage() { return usage; }
    @Override public boolean isClosed() { context.checkOwner(); return handle == 0; }

    @Override public GpuBufferSlice slice(long offset, long length) {
        checkOpen();
        range(offset, length);
        return new GpuBufferSlice(this, offset, length);
    }

    @Override public GpuBufferSlice slice() { return slice(0, size); }

    @Override public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        checkOpen();
        range(offset, length);
        if (mapped) throw new IllegalStateException("Buffer already mapped");
        if (!read && !write) throw new IllegalArgumentException("Mapping must request read or write");
        if (read && (usage & USAGE_MAP_READ) == 0 || write && (usage & USAGE_MAP_WRITE) == 0) {
            throw new IllegalStateException("Mapping is not allowed by buffer usage");
        }
        ByteBuffer staging = ByteBuffer.allocateDirect(Math.toIntExact(length)).order(ByteOrder.nativeOrder());
        // Preserve bytes the caller does not overwrite, including write-only views.
        NativeMetal.readBuffer(handle, offset, staging, staging.capacity());
        ByteBuffer data = read && !write ? staging.asReadOnlyBuffer().order(ByteOrder.nativeOrder()) : staging;
        AtomicBoolean closed = new AtomicBoolean();
        mapped = true;
        return new GpuBufferSlice.MappedView(new GpuBufferSlice(this, offset, length), data, () -> {
            context.checkOwner();
            if (closed.get()) return;
            checkOpen();
            // Flush the complete mapped range regardless of the caller's position.
            if (write) NativeMetal.writeBuffer(handle, offset, staging, staging.capacity());
            mapped = false;
            closed.set(true);
        });
    }

    /** Upload remaining bytes without changing the source position/limit. */
    public void upload(long offset, ByteBuffer source) {
        checkOpen();
        if (mapped) throw new IllegalStateException("Close mapping before upload");
        if ((usage & USAGE_COPY_DST) == 0) throw new IllegalStateException("Buffer lacks COPY_DST usage");
        range(offset, source.remaining());
        ByteBuffer copy = ByteBuffer.allocateDirect(source.remaining());
        copy.put(source.duplicate()).flip();
        NativeMetal.writeBuffer(handle, offset, copy, copy.remaining());
    }

    void initialize(ByteBuffer source) {
        checkOpen();
        range(0, source.remaining());
        ByteBuffer copy = ByteBuffer.allocateDirect(source.remaining());
        copy.put(source.duplicate()).flip();
        NativeMetal.writeBuffer(handle, 0, copy, copy.remaining());
    }

    /** Test-only synchronous GPU blit. Not the eventual encoder/frame path. */
    public void copyDiagnosticTo(long sourceOffset, MetalGpuBuffer destination, long destinationOffset, long length) {
        checkOpen();
        destination.checkOpen();
        if (context != destination.context) throw new IllegalArgumentException("Buffers belong to different devices");
        if (mapped || destination.mapped) throw new IllegalStateException("Close mappings before GPU copies");
        if ((usage & USAGE_COPY_SRC) == 0 || (destination.usage & USAGE_COPY_DST) == 0) {
            throw new IllegalStateException("Buffer copy usages missing");
        }
        range(sourceOffset, length);
        destination.range(destinationOffset, length);
        NativeMetal.copyBufferDiagnostic(handle, sourceOffset, destination.handle, destinationOffset, length);
    }

    private void range(long offset, long length) {
        if (offset < 0 || length < 0 || offset > size || length > size - offset) {
            throw new IllegalArgumentException("Buffer range exceeds allocation");
        }
    }

    long commandHandle(MetalContext expected, long offset, long length, int requiredUsage) {
        checkOpen();
        if (context != expected) throw new IllegalArgumentException("Buffer belongs to another Metal device");
        if (mapped) throw new IllegalStateException("Close mapping before encoding commands");
        if ((usage & requiredUsage) != requiredUsage) throw new IllegalStateException("Buffer copy usage missing");
        range(offset, length);
        return handle;
    }

    private void checkOpen() {
        context.checkOpen();
        if (handle == 0) throw new IllegalStateException("Metal buffer is closed");
    }

    @Override public void close() {
        context.checkOwner();
        if (handle != 0) {
            if (mapped) throw new IllegalStateException("Close mapped view before buffer");
            NativeMetal.destroyBuffer(handle);
            handle = 0;
            context.removeBuffer(this);
        }
    }
}
