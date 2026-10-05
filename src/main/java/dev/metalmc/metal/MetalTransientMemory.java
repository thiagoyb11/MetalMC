package dev.metalmc.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Submission-scoped bump arenas. Native blocks are reused only after their
 * completion ticket signals. Mapped bytes are detached Java snapshots, never
 * pointers into recycled Metal storage. Not a game backend activation.
 */
public final class MetalTransientMemory implements TransientMemory {
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final long BLOCK_BYTES = 1024 * 1024;
    private static final int MAX_ALLOCATIONS = 1024;
    private static final int STAGING = 0, GPU = 1, MAPPED = 2;
    private static final int STORAGE_USAGE = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE
            | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX
            | GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_INDIRECT_PARAMETERS;
    private final MetalContext context;
    private final MetalCommandEncoder encoder;
    private final ArrayList<Block> blocks = new ArrayList<>();
    private final ArrayList<Block> active = new ArrayList<>();
    private final ArrayList<Retirement> retired = new ArrayList<>();
    private final Block[] current = new Block[3];
    private long generation, residentBytes, cpuBytes;
    private int allocations, openMappings;
    private boolean closed;

    private static final class Block {
        final MetalGpuBuffer buffer;
        final int kind;
        long offset;
        boolean available = true;
        Block(MetalGpuBuffer buffer, int kind) { this.buffer = buffer; this.kind = kind; }
    }
    static final class Retirement {
        final long handle;
        final List<Block> blocks;
        Retirement(long handle, List<Block> blocks) { this.handle = handle; this.blocks = blocks; }
    }

    MetalTransientMemory(MetalContext context, MetalCommandEncoder encoder) {
        this.context = context;
        this.encoder = encoder;
    }
    private void checkOpen() {
        encoder.checkOutsideRenderPass();
        if (closed) throw new IllegalStateException("Transient allocator is closed");
    }
    private void validate(long size, long alignment, long minimum, long quantum) {
        checkOpen();
        if (size < 1 || size > MAX_BYTES || alignment < 1 || alignment > 4096
                || minimum < 1 || minimum > size || quantum < 1 || quantum > size) {
            throw new IllegalArgumentException("Transient size/minimum/quantum must fit 1..64 MiB; alignment 1..4096");
        }
        if (allocations >= MAX_ALLOCATIONS) throw new IllegalStateException("Transient allocation limit per submission reached");
    }
    private static long round(long value, long alignment) {
        return Math.multiplyExact(Math.floorDiv(Math.addExact(value, alignment - 1), alignment), alignment);
    }
    private static long gpuAlignment(long alignment) {
        long divisor = alignment;
        long other = 4;
        while (other != 0) { long remainder = divisor % other; divisor = other; other = remainder; }
        return alignment / divisor * 4;
    }
    private void reclaim() {
        for (int i = 0; i < retired.size();) {
            Retirement ticket = retired.get(i);
            // No callback dispatch here: allocation/reclamation must not reenter the encoder.
            if (!NativeMetal.awaitBatch(ticket.handle, 0)) { i++; continue; }
            NativeMetal.releaseBatch(ticket.handle);
            for (Block block : ticket.blocks) { block.available = true; block.offset = 0; }
            retired.remove(i);
        }
    }
    private GpuBufferSlice allocate(long size, long alignment, int usage, long minimum, long quantum, int kind) {
        validate(size, alignment, minimum, quantum);
        MetalGpuBuffer.validate(usage, size);
        reclaim();
        long effectiveAlignment = gpuAlignment(alignment);
        Block block = current[kind];
        long offset = block == null ? 0 : round(block.offset, effectiveAlignment);
        long selected = size;
        if (block != null && round(size, 4) > block.buffer.size() - offset) {
            long remaining = Math.max(0, block.buffer.size() - offset);
            selected = remaining / quantum * quantum;
            while (selected > 0 && round(selected, 4) > remaining) selected -= quantum;
            if (selected < minimum) block = null;
        }
        if (block == null) {
            selected = size;
            long required = round(size, 4);
            block = null;
            for (Block candidate : blocks) {
                if (candidate.available && candidate.kind == kind && candidate.buffer.size() >= required
                        && (block == null || candidate.buffer.size() < block.buffer.size())) block = candidate;
            }
            if (block == null) {
                long capacity = Math.max(BLOCK_BYTES, required);
                if (capacity > MAX_BYTES - residentBytes) {
                    throw new IllegalStateException("Transient native pool exceeds 64 MiB; submit/await or reduce allocations");
                }
                blocks.ensureCapacity(blocks.size() + 1);
                active.ensureCapacity(active.size() + 1);
                block = new Block(context.createBuffer("MetalMC transient arena", STORAGE_USAGE, capacity), kind);
                blocks.add(block);
                residentBytes += capacity;
            } else active.ensureCapacity(active.size() + 1);
            block.available = false;
            active.add(block);
            current[kind] = block;
            offset = 0;
        }
        block.offset = offset + round(selected, 4);
        allocations++;
        return new GpuBufferSlice(new TransientBuffer(block.buffer, usage, generation, offset, selected), offset, selected);
    }

    @Override public ByteBuffer allocateCpu(long size, long alignment, long minimum, long quantum) {
        validate(size, alignment, minimum, quantum);
        if ((alignment & (alignment - 1)) != 0) throw new UnsupportedOperationException("CPU alignment must be a power of two");
        long reserved = round(size, alignment) + alignment;
        if (reserved > MAX_BYTES - cpuBytes) throw new IllegalStateException("Transient CPU snapshots exceed 64 MiB per submission");
        ByteBuffer storage = ByteBuffer.allocateDirect(Math.toIntExact(reserved));
        ByteBuffer result = storage.alignedSlice(Math.toIntExact(alignment));
        result.limit(Math.toIntExact(size));
        cpuBytes += reserved;
        allocations++;
        return result.slice().order(ByteOrder.nativeOrder());
    }
    @Override public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long minimum, long quantum) {
        return allocate(size, alignment, usage, minimum, quantum, GPU);
    }
    private GpuBufferSlice.MappedView mapped(long size, long alignment, int usage, long minimum, long quantum, int kind) {
        // Reserve Java memory first, so an allocation failure cannot leave an inaccessible GPU reservation.
        validate(size, alignment, minimum, quantum);
        long reserved = round(size, 4);
        if (reserved > MAX_BYTES - cpuBytes) throw new IllegalStateException("Transient CPU snapshots exceed 64 MiB per submission");
        ByteBuffer storage = ByteBuffer.allocateDirect(Math.toIntExact(reserved)).order(ByteOrder.nativeOrder());
        var slice = allocate(size, alignment, usage, minimum, quantum, kind);
        TransientBuffer buffer = (TransientBuffer)slice.buffer();
        storage.limit(Math.toIntExact(round(slice.length(), 4)));
        ByteBuffer data = storage.duplicate().order(ByteOrder.nativeOrder());
        data.limit(Math.toIntExact(slice.length()));
        cpuBytes += reserved;
        openMappings++;
        buffer.mapped = true;
        boolean[] flushed = {false};
        return new GpuBufferSlice.MappedView(slice, data, () -> {
            context.checkOwner();
            if (flushed[0]) return;
            buffer.checkLive();
            // The native encoder snapshots immediately, preserving later writes to escaped Java views.
            encoder.writeTransient(buffer.storage, slice.offset(), storage);
            flushed[0] = true;
            buffer.mapped = false;
            openMappings--;
        });
    }
    @Override public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage, long minimum, long quantum) {
        return mapped(size, alignment, usage, minimum, quantum, STAGING);
    }
    @Override public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage, long minimum, long quantum) {
        return mapped(size, alignment, usage, minimum, quantum, MAPPED);
    }
    private GpuBufferSlice upload(List<ByteBuffer> sources, long alignment, int usage, long minimum, long quantum, int kind) {
        Objects.requireNonNull(sources, "sources");
        if (alignment < 1 || alignment > 4096) throw new IllegalArgumentException("Invalid transient alignment");
        long size = 0;
        for (ByteBuffer source : sources) size = round(Math.addExact(size, source.remaining()), alignment);
        try (var view = mapped(size, alignment, usage, minimum, quantum, kind)) {
            long offset = 0;
            for (ByteBuffer source : sources) {
                int length = (int)Math.min(source.remaining(), view.slice().length() - offset);
                ByteBuffer copy = source.duplicate();
                copy.limit(copy.position() + length);
                view.data().position(Math.toIntExact(offset)).put(copy);
                offset = round(offset + source.remaining(), alignment);
                if (offset >= view.slice().length()) break;
            }
            return view.slice();
        }
    }
    @Override public GpuBufferSlice uploadStaging(List<ByteBuffer> sources, long alignment, int usage, long minimum, long quantum) {
        return upload(sources, alignment, usage, minimum, quantum, STAGING);
    }
    @Override public GpuBufferSlice uploadGpu(List<ByteBuffer> sources, long alignment, int usage, long minimum, long quantum) {
        return upload(sources, alignment, usage, minimum, quantum, MAPPED);
    }
    private List<GpuBufferSlice> multi(List<ByteBuffer> sources, long alignment, int usage, int kind) {
        checkOpen();
        Objects.requireNonNull(sources, "sources");
        // Validate the full request before recording any upload.
        long total = 0;
        for (ByteBuffer source : sources) {
            validate(source.remaining(), alignment, source.remaining(), 1);
            MetalGpuBuffer.validate(usage, source.remaining());
            total = Math.addExact(total, round(round(source.remaining(), alignment), 4));
        }
        if (sources.size() > MAX_ALLOCATIONS - allocations || total > MAX_BYTES - cpuBytes) {
            throw new IllegalStateException("Transient multi-upload exceeds submission limits");
        }
        var result = new ArrayList<GpuBufferSlice>(sources.size());
        for (ByteBuffer source : sources) {
            result.add(upload(List.of(source), alignment, usage, source.remaining(), 1, kind).slice(0, source.remaining()));
        }
        return List.copyOf(result);
    }
    @Override public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> sources, long alignment, int usage) {
        return multi(sources, alignment, usage, STAGING);
    }
    @Override public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> sources, long alignment, int usage) {
        return multi(sources, alignment, usage, MAPPED);
    }

    void validateSubmit() {
        if (openMappings != 0) throw new IllegalStateException("Close transient mapped views before submission");
    }
    boolean hasAllocations() { return allocations != 0; }
    Retirement prepareSubmit(long batch) {
        if (!hasAllocations()) return null;
        reclaim();
        retired.ensureCapacity(retired.size() + 1);
        List<Block> snapshot = List.copyOf(active);
        return new Retirement(NativeMetal.retainBatch(batch), snapshot);
    }
    void didSubmit(Retirement retirement) {
        retired.add(retirement);
        active.clear();
        for (int i = 0; i < current.length; i++) current[i] = null;
        generation++;
        allocations = 0;
        cpuBytes = 0;
    }
    void close() {
        closed = true;
        generation++;
        for (Retirement ticket : retired) NativeMetal.releaseBatch(ticket.handle);
        retired.clear();
        for (Block block : blocks) block.buffer.close();
        blocks.clear();
        active.clear();
    }

    /** A generation token prevents stale slices from accessing a recycled block.
     * Like Vulkan's transient wrapper, it cannot be mapped or sliced as a resource.
     */
    final class TransientBuffer implements GpuBuffer {
        private final MetalGpuBuffer storage;
        private final int usage;
        private final long token, start, length;
        private boolean explicitlyClosed, mapped;
        TransientBuffer(MetalGpuBuffer storage, int usage, long token, long start, long length) {
            this.storage = storage; this.usage = usage; this.token = token; this.start = start; this.length = length;
        }
        private void checkLive() {
            encoder.checkOpen();
            if (closed) throw new IllegalStateException("Transient allocator is closed");
            if (isClosed()) throw new IllegalStateException("Transient slice expired or closed");
        }
        long commandHandle(MetalCommandEncoder owner, long offset, long size, int requiredUsage) {
            checkLive();
            if (owner != encoder) throw new IllegalArgumentException("Transient slice belongs to another encoder");
            if (mapped) throw new IllegalStateException("Close transient mapping before encoding its slice");
            if ((usage & requiredUsage) != requiredUsage) throw new IllegalStateException("Transient buffer usage missing");
            if (offset < start || size < 0 || offset - start > length || size > length - (offset - start)) {
                throw new IllegalArgumentException("Transient range exceeds its reservation");
            }
            return storage.commandHandle(context, offset, size, 0);
        }
        @Override public long size() { return storage.size(); }
        @Override public int usage() { return usage; }
        @Override public boolean isClosed() { context.checkOwner(); return explicitlyClosed || closed || token != generation; }
        @Override public void close() {
            context.checkOwner();
            if (mapped && !isClosed()) throw new IllegalStateException("Close transient mapped view before buffer");
            explicitlyClosed = true;
        }
        @Override public GpuBufferSlice slice(long offset, long size) { throw new IllegalStateException("Use the allocated transient slice"); }
        @Override public GpuBufferSlice slice() { throw new IllegalStateException("Use the allocated transient slice"); }
        @Override public GpuBufferSlice.MappedView map(long offset, long size, boolean read, boolean write) {
            throw new IllegalStateException("Use allocateStaging or allocateGpuMapped for transient mapping");
        }
    }
}
