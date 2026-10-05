package dev.metalmc.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Objects;
import org.joml.Vector4fc;

/** Initial exact-26.3 encoder: ordered transfers, full/scissored clears and submission fences.
 * Unsupported rendering operations fail before recording any work.
 * Batches retain native resources through completion, including Java-closed buffers.
 */
public final class MetalCommandEncoder implements CommandEncoderBackend, AutoCloseable {
    private final MetalContext context;
    private long batch;
    private boolean closed;
    private long callbacksHandle;
    private final ArrayList<Runnable> callbacks = new ArrayList<>();
    private MetalTransientMemory transientMemory;
    private MetalRenderPass renderPass;

    MetalCommandEncoder(MetalContext context) { this.context = context; }

    void checkOpen() {
        context.checkOpen();
        if (closed) throw new IllegalStateException("Metal command encoder is closed");
    }
    void checkOutsideRenderPass() {
        checkOpen();
        if (renderPass != null) throw new IllegalStateException("Finish the render pass before accessing encoder transfers or transient allocations");
    }
    private long batch() {
        checkOpen();
        if (renderPass != null) throw new IllegalStateException("Finish the render pass before recording encoder commands");
        if (batch == 0) batch = NativeMetal.createBatch(context.nativeHandle());
        return batch;
    }
    private long buffer(GpuBufferSlice slice, int usage) {
        checkOpen();
        if (renderPass != null) throw new IllegalStateException("Finish the render pass before recording transfers");
        return renderBuffer(slice, usage);
    }
    long renderBuffer(GpuBufferSlice slice, int usage) {
        checkOpen();
        if (slice.buffer() instanceof MetalTransientMemory.TransientBuffer transientBuffer) {
            return transientBuffer.commandHandle(this, slice.offset(), slice.length(), usage);
        }
        if (!(slice.buffer() instanceof MetalGpuBuffer metal)) {
            throw new IllegalArgumentException("Buffer was not created by MetalMC");
        }
        return metal.commandHandle(context, slice.offset(), slice.length(), usage);
    }
    private static void aligned(long... values) {
        for (long value : values) if ((value & 3) != 0) {
            throw new UnsupportedOperationException("Initial Metal encoder blits require 4-byte alignment");
        }
    }
    @Override public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
        long target = buffer(destination, GpuBuffer.USAGE_COPY_DST);
        int length = data.remaining();
        if (length > destination.length()) throw new IllegalArgumentException("Upload exceeds destination slice");
        aligned(destination.offset(), length);
        if (length == 0) return;
        ByteBuffer staging = ByteBuffer.allocateDirect(length);
        staging.put(data.duplicate()).flip();
        NativeMetal.encodeBufferWrite(batch(), target, destination.offset(), staging, length);
    }
    @Override public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice destination) {
        long from = buffer(source, GpuBuffer.USAGE_COPY_SRC);
        long to = buffer(destination, GpuBuffer.USAGE_COPY_DST);
        if (source.length() != destination.length()) throw new IllegalArgumentException("Copy slice lengths differ");
        aligned(source.offset(), destination.offset(), source.length());
        if (from == to) throw new UnsupportedOperationException("Initial encoder copies require distinct buffers");
        if (source.length() == 0) return;
        NativeMetal.encodeBufferCopy(batch(), from, source.offset(), to, destination.offset(), source.length());
    }
    @Override public void submit() {
        checkOpen();
        if (renderPass != null) throw new IllegalStateException("Finish the render pass before submitting");
        if (transientMemory != null) transientMemory.validateSubmit();
        if (transientMemory != null && transientMemory.hasAllocations()) batch();
        if (batch == 0) { context.pollCallbacks(); return; }
        MetalContext.CallbackBatch completion = null;
        if (callbacksHandle != 0) {
            completion = new MetalContext.CallbackBatch(callbacksHandle, callbacks);
            context.reserveCallbackBatch();
        }
        MetalTransientMemory.Retirement retirement = transientMemory == null ? null : transientMemory.prepareSubmit(batch);
        try { NativeMetal.submitBatch(batch); }
        catch (RuntimeException | Error failure) {
            if (retirement != null) NativeMetal.releaseBatch(retirement.handle);
            throw failure;
        }
        if (retirement != null) transientMemory.didSubmit(retirement);
        if (completion != null) context.enqueueCallbacks(completion);
        callbacksHandle = 0;
        callbacks.clear();
        NativeMetal.releaseBatch(batch);
        batch = 0;
        context.pollCallbacks();
    }
    /** Like the inspected Vulkan backend, a fence tracks the current submission,
     * including commands recorded after fence creation. Call submit before waiting.
     */
    @Override public MetalGpuFence createFence() {
        var fence = new MetalGpuFence(context, NativeMetal.retainBatch(batch()));
        context.addFence(fence);
        return fence;
    }
    @Override public void close() {
        context.checkOwner();
        if (closed) return;
        if (batch != 0) {
            NativeMetal.cancelBatch(batch);
            NativeMetal.releaseBatch(batch);
            batch = 0;
        }
        if (renderPass != null) { renderPass.invalidate(); renderPass = null; }
        if (callbacksHandle != 0) {
            NativeMetal.releaseBatch(callbacksHandle);
            callbacksHandle = 0;
        }
        callbacks.clear();
        if (transientMemory != null) transientMemory.close();
        closed = true;
        context.removeEncoder(this);
    }
    private UnsupportedOperationException pending(String feature) {
        checkOpen();
        return new UnsupportedOperationException("MetalMC command encoder is incomplete: " + feature);
    }
    @Override public TransientMemory transientMemory() {
        checkOpen();
        if (renderPass != null) throw new IllegalStateException("Finish the render pass before accessing transient memory");
        if (transientMemory == null) transientMemory = new MetalTransientMemory(context, this);
        return transientMemory;
    }
    /** Flush detached transient bytes through the ordered GPU upload path. */
    void writeTransient(MetalGpuBuffer target, long offset, ByteBuffer data) {
        checkOpen();
        long handle = target.commandHandle(context, offset, data.remaining(), GpuBuffer.USAGE_COPY_DST);
        NativeMetal.encodeBufferWrite(batch(), handle, offset, data, data.remaining());
    }
    @Override public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
        checkOpen();
        if (renderPass != null) throw new IllegalStateException("A render pass is already open");
        var pass = new MetalRenderPass(context, this, descriptor);
        if (transientMemory != null) transientMemory.validateSubmit();
        pass.begin(batch());
        renderPass = pass;
        return pass;
    }
    @Override public void submitRenderPass() {
        checkOpen();
        if (renderPass == null) throw new IllegalStateException("No render pass is open");
        renderPass.finish();
        renderPass = null;
    }
    private MetalGpuTexture texture(GpuTexture texture, int usage) {
        checkOpen();
        if (renderPass != null) throw new IllegalStateException("Finish the render pass before recording transfers or clears");
        if (!(texture instanceof MetalGpuTexture metal)) throw new IllegalArgumentException("Texture was not created by MetalMC");
        metal.commandHandle(context, usage);
        return metal;
    }
    private static void pixelAligned(long offset, int bytes) {
        if (offset < 0 || offset % bytes != 0) throw new IllegalArgumentException("Texture transfer offset must align to pixel size");
    }
    private MetalGpuTexture clearTarget(GpuTexture texture, boolean depth) {
        var metal = texture(texture, GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST);
        if (metal.getFormat().hasDepthAspect() != depth) throw new IllegalArgumentException("Texture clear aspect mismatch");
        if (metal.getDepthOrLayers() != 1) throw new UnsupportedOperationException("Game texture clears require a single layer");
        return metal;
    }
    private static void clearColor(Vector4fc value) {
        if (!Float.isFinite(value.x()) || !Float.isFinite(value.y()) || !Float.isFinite(value.z()) || !Float.isFinite(value.w())) {
            throw new IllegalArgumentException("Invalid clear color");
        }
    }
    private static void clearDepth(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("Invalid clear depth");
    }
    @Override public void clearColorTexture(GpuTexture texture, Vector4fc color) {
        var target = clearTarget(texture, false);
        clearColor(color);
        NativeMetal.encodeTextureClear(batch(), target.commandHandle(context, 0), color.x(), color.y(), color.z(), color.w(), 0, 0);
    }
    @Override public void clearDepthTexture(GpuTexture texture, double depth) {
        var target = clearTarget(texture, true);
        clearDepth(depth);
        NativeMetal.encodeTextureClear(batch(), 0, 0, 0, 0, 0, target.commandHandle(context, 0), depth);
    }
    @Override public void clearColorAndDepthTextures(GpuTexture color, Vector4fc value, GpuTexture depth, double clear) {
        var colorTarget = clearTarget(color, false);
        var depthTarget = clearTarget(depth, true);
        clearColor(value); clearDepth(clear);
        if (colorTarget.getMipLevels() != depthTarget.getMipLevels()) throw new IllegalArgumentException("Clear mip counts differ");
        NativeMetal.encodeTextureClear(batch(), colorTarget.commandHandle(context, 0), value.x(), value.y(), value.z(), value.w(),
                depthTarget.commandHandle(context, 0), clear);
    }
    @Override public void clearColorAndDepthTextures(GpuTexture color, Vector4fc value, GpuTexture depth, double clear,
            int x, int y, int width, int height, int mip) {
        var colorTarget = clearTarget(color, false);
        var depthTarget = clearTarget(depth, true);
        // Snapshot the caller's mutable vector before validating or encoding.
        Objects.requireNonNull(value, "clear color");
        float red = value.x(), green = value.y(), blue = value.z(), alpha = value.w();
        if (!Float.isFinite(red) || !Float.isFinite(green) || !Float.isFinite(blue) || !Float.isFinite(alpha)) {
            throw new IllegalArgumentException("Invalid clear color");
        }
        clearDepth(clear);
        if (colorTarget.getMipLevels() != depthTarget.getMipLevels()) throw new IllegalArgumentException("Clear mip counts differ");
        colorTarget.regionSize(mip, 0, x, y, width, height);
        depthTarget.regionSize(mip, 0, x, y, width, height);
        if (colorTarget.getWidth(mip) != depthTarget.getWidth(mip)
                || colorTarget.getHeight(mip) != depthTarget.getHeight(mip)) {
            throw new IllegalArgumentException("Scissored clear attachment dimensions differ");
        }
        NativeMetal.encodeScissoredClear(batch(), colorTarget.commandHandle(context, 0),
                red, green, blue, alpha, depthTarget.commandHandle(context, 0), clear, x, y, width, height, mip);
    }
    @Override public void writeToTexture(GpuTexture texture, ByteBuffer data, int mip, int layer, int x, int y, int width, int height) {
        var target = texture(texture, GpuTexture.USAGE_COPY_DST);
        int bytes = Math.toIntExact(target.regionSize(mip, layer, x, y, width, height));
        if (data.remaining() < bytes) throw new IllegalArgumentException("Texture upload data too small");
        if (bytes > 64 * 1024 * 1024) throw new IllegalArgumentException("Texture upload exceeds initial 64 MiB staging cap");
        ByteBuffer staging = ByteBuffer.allocateDirect(bytes);
        var source = data.duplicate();
        source.limit(source.position() + bytes);
        staging.put(source).flip();
        NativeMetal.encodeTextureWrite(batch(), target.commandHandle(context, 0), staging, bytes, mip, layer, x, y, width, height);
    }
    @Override public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight,
            GpuTexture texture, int x, int y, int width, int height, int mip, int layer) {
        long buffer = buffer(source, GpuBuffer.USAGE_COPY_SRC);
        var target = texture(texture, GpuTexture.USAGE_COPY_DST);
        target.regionSize(mip, layer, x, y, width, height);
        if (sourceX < 0 || sourceY < 0 || sourceWidth < 1 || sourceWidth > 8192 || sourceHeight < 1
                || sourceX > sourceWidth || width > sourceWidth - sourceX
                || sourceY > sourceHeight || height > sourceHeight - sourceY) {
            throw new IllegalArgumentException("Buffer image region outside source dimensions");
        }
        int bytes = target.getFormat().blockSize();
        long relative = ((long)sourceY * sourceWidth + sourceX) * bytes;
        long span = ((long)(height - 1) * sourceWidth + width) * bytes;
        if (relative > source.length() || span > source.length() - relative) throw new IllegalArgumentException("Buffer image exceeds source slice");
        long offset = Math.addExact(source.offset(), relative);
        pixelAligned(offset, bytes);
        NativeMetal.encodeBufferTexture(batch(), buffer, offset, sourceWidth, target.commandHandle(context, 0),
                mip, layer, x, y, width, height, true);
    }
    @Override public void copyTextureToBuffer(GpuTexture source, GpuBuffer target, long offset, Runnable callback, int mip) {
        var texture = texture(source, GpuTexture.USAGE_COPY_SRC);
        copyTextureToBuffer(texture, target, offset, callback, mip, 0, 0, texture.getWidth(mip), texture.getHeight(mip));
    }
    @Override public void copyTextureToBuffer(GpuTexture source, GpuBuffer target, long offset, Runnable callback,
            int mip, int x, int y, int width, int height) {
        var texture = texture(source, GpuTexture.USAGE_COPY_SRC);
        long bytes = texture.regionSize(mip, 0, x, y, width, height);
        long destination = buffer(new GpuBufferSlice(target, offset, bytes), GpuBuffer.USAGE_COPY_DST);
        pixelAligned(offset, texture.getFormat().blockSize());
        Objects.requireNonNull(callback, "readback callback");
        callbacks.ensureCapacity(callbacks.size() + 1);
        boolean retained = callbacksHandle == 0;
        if (retained) callbacksHandle = NativeMetal.retainBatch(batch());
        try {
            NativeMetal.encodeBufferTexture(batch(), destination, offset, width, texture.commandHandle(context, 0),
                    mip, 0, x, y, width, height, false);
        } catch (RuntimeException | Error failure) {
            if (retained) { NativeMetal.releaseBatch(callbacksHandle); callbacksHandle = 0; }
            throw failure;
        }
        callbacks.add(callback);
    }
    @Override public void copyTextureToTexture(GpuTexture source, GpuTexture target,
            int mip, int targetX, int targetY, int sourceX, int sourceY, int width, int height) {
        var from = texture(source, GpuTexture.USAGE_COPY_SRC);
        var to = texture(target, GpuTexture.USAGE_COPY_DST);
        from.regionSize(mip, 0, sourceX, sourceY, width, height);
        to.regionSize(mip, 0, targetX, targetY, width, height);
        if (from == to) throw new UnsupportedOperationException("Initial texture copies require distinct textures");
        if (from.getFormat() != to.getFormat()) throw new IllegalArgumentException("Texture copy formats differ");
        NativeMetal.encodeTextureCopy(batch(), from.commandHandle(context, 0), to.commandHandle(context, 0),
                mip, targetX, targetY, sourceX, sourceY, width, height);
    }
    @Override public void writeTimestamp(GpuQueryPool pool, int index) {
        checkOutsideRenderPass();
        if (!(pool instanceof MetalGpuQueryPool metal)) throw new IllegalArgumentException("Timestamp pool was not created by MetalMC");
        long handle = metal.commandHandle(context, index);
        NativeMetal.encodeTimestamp(batch(), handle, index);
    }
}
