package dev.metalmc.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Objects;
import java.util.ArrayList;
import java.util.function.Supplier;
import org.lwjgl.PointerBuffer;
import com.mojang.renderpearl.util.TextureViewAndSampler;

/** Attachment/lifecycle subset of the exact 26.3 pass contract.
 * Full-area, single-layer passes; resource bindings, direct/indexed/multi/indirect draws.
 */
final class MetalRenderPass implements RenderPassBackend {
    private final MetalCommandEncoder encoder;
    private final MetalContext context;
    private final long[] colors;
    private final int[] mips;
    private final boolean[] clear;
    private final float[] values;
    private final long depth;
    private final int depthMip, width, height;
    private final boolean clearDepth;
    private final double depthValue;
    private final String label;
    private long batch;
    private int debugGroups;
    private MetalRenderPipeline pipeline;
    private IndexType indexType;
    private static final int MAX_MULTI_DRAWS = 1024;

    MetalRenderPass(MetalContext context, MetalCommandEncoder encoder, RenderPassDescriptor descriptor) {
        this.encoder = encoder; this.context = context;
        Objects.requireNonNull(descriptor, "render pass descriptor");
        var area = Objects.requireNonNull(descriptor.renderArea(), "render area");
        width = area.width(); height = area.height();
        if (width < 1 || height < 1 || width > 8192 || height > 8192 || area.x() < 0 || area.y() < 0) {
            throw new IllegalArgumentException("Invalid render area");
        }
        var attachments = new ArrayList<>(Objects.requireNonNull(descriptor.colorAttachments(), "color attachments"));
        if (attachments.size() > 8) throw new UnsupportedOperationException("Initial Metal passes support at most eight color slots");
        colors = new long[attachments.size()]; mips = new int[attachments.size()];
        clear = new boolean[attachments.size()]; values = new float[attachments.size() * 4];
        int active = 0;
        int colorBytes = 0;
        for (int i = 0; i < attachments.size(); i++) {
            if (attachments.get(i) == null) continue;
            var attachment = attachments.get(i);
            var view = attachment(context, attachment, false);
            colorBytes += view.texture().getFormat().blockSize();
            if (colorBytes > 32) throw new UnsupportedOperationException("Initial Metal passes limit combined color attachments to 32 bytes per pixel");
            if (!area.fillsTexture(view)) throw new UnsupportedOperationException("Partial render areas require shader-based clears");
            colors[i] = view.attachmentHandle(context); mips[i] = view.baseMipLevel();
            for (int j = 0; j < i; j++) if (colors[j] == colors[i] && mips[j] == mips[i]) {
                throw new IllegalArgumentException("Duplicate color attachment subresource");
            }
            var value = Objects.requireNonNull(attachment.clearValue(), "color clear value");
            clear[i] = value.isPresent();
            if (clear[i]) {
                var color = value.orElseThrow();
                float[] components = {color.x(), color.y(), color.z(), color.w()};
                for (int component = 0; component < 4; component++) {
                    if (!Float.isFinite(components[component])) throw new IllegalArgumentException("Invalid clear color");
                    values[i * 4 + component] = components[component];
                }
            }
            active++;
        }
        var depthAttachment = descriptor.depthAttachment();
        if (depthAttachment == null) { depth = 0; depthMip = 0; clearDepth = false; depthValue = 0; }
        else {
            var view = attachment(context, depthAttachment, true);
            if (!area.fillsTexture(view)) throw new UnsupportedOperationException("Partial render areas require shader-based clears");
            depth = view.attachmentHandle(context); depthMip = view.baseMipLevel();
            var value = Objects.requireNonNull(depthAttachment.clearValue(), "depth clear value");
            clearDepth = value.isPresent(); depthValue = value.orElse(0);
            if (!Double.isFinite(depthValue) || depthValue < 0 || depthValue > 1) throw new IllegalArgumentException("Invalid clear depth");
            active++;
        }
        if (active == 0) throw new IllegalArgumentException("Render pass requires an attachment");
        label = descriptor.label() == null ? null : descriptor.label().get();
    }

    private static MetalGpuTextureView attachment(MetalContext context, RenderPassDescriptor.Attachment<?> attachment, boolean depth) {
        if (!(attachment.textureView() instanceof MetalGpuTextureView view)) throw new IllegalArgumentException("Attachment view was not created by MetalMC");
        view.attachmentHandle(context);
        if (view.texture().getFormat().hasDepthAspect() != depth) throw new IllegalArgumentException("Attachment aspect mismatch");
        if (view.texture().getDepthOrLayers() != 1) throw new UnsupportedOperationException("Initial render attachments require a single layer");
        return view;
    }
    void begin(long handle) {
        NativeMetal.beginRenderPass(handle, colors, mips, clear, values, depth, depthMip, clearDepth, depthValue, width, height, label);
        batch = handle;
    }
    private void checkOpen() {
        encoder.checkOpen();
        if (batch == 0) throw new IllegalStateException("Metal render pass is finished or cancelled");
    }
    void finish() {
        checkOpen();
        if (debugGroups != 0) throw new IllegalStateException("Pop render debug groups before finishing the pass");
        NativeMetal.endRenderPass(batch);
        invalidate();
    }
    void invalidate() { batch = 0; pipeline = null; indexType = null; }
    @Override public void pushDebugGroup(Supplier<String> supplier) {
        checkOpen();
        if (debugGroups == 64) throw new IllegalStateException("Render debug group nesting exceeds 64");
        String value = Objects.requireNonNull(supplier, "debug label supplier").get();
        checkOpen();
        NativeMetal.renderPassDebugGroup(batch, value, true);
        debugGroups++;
    }
    @Override public void popDebugGroup() {
        checkOpen();
        if (debugGroups == 0) throw new IllegalStateException("No render debug group to pop");
        NativeMetal.renderPassDebugGroup(batch, null, false);
        debugGroups--;
    }
    @Override public void enableScissor(int x, int y, int w, int h) {
        checkOpen();
        if (x < 0 || y < 0 || w < 1 || h < 1 || x > width || w > width - x || y > height || h > height - y) {
            throw new IllegalArgumentException("Scissor outside render area");
        }
        NativeMetal.renderPassScissor(batch, x, y, w, h);
    }
    @Override public void disableScissor() { checkOpen(); NativeMetal.renderPassScissor(batch, 0, 0, width, height); }
    private UnsupportedOperationException pending(String feature) {
        checkOpen();
        return new UnsupportedOperationException("MetalMC render pass is incomplete: " + feature);
    }
    @Override public void setPipeline(BackendRenderPipeline pipeline) {
        checkOpen();
        if (!(pipeline instanceof MetalRenderPipeline metal)) throw new IllegalArgumentException("Pipeline was not created by MetalMC");
        NativeMetal.renderPassPipeline(batch, metal.commandHandle(context));
        this.pipeline = metal;
    }
    @Override public void setUniform(int index, Object value) {
        checkOpen();
        if (pipeline == null) throw new IllegalStateException("Set pipeline before binding uniforms");
        if (value == null) NativeMetal.renderPassClearUniform(batch, index);
        else if (value instanceof GpuBufferSlice slice) {
            if (slice.offset() < 0 || (slice.offset() & 3) != 0 || slice.length() < 1) throw new IllegalArgumentException("Invalid uniform slice/alignment");
            NativeMetal.renderPassUniformBuffer(batch, index, encoder.renderBuffer(slice, GpuBuffer.USAGE_UNIFORM), slice.offset(), slice.length());
        } else if (value instanceof TextureViewAndSampler pair) {
            if (!(pair.view() instanceof MetalGpuTextureView view) || !(pair.sampler() instanceof MetalGpuSampler sampler)) throw new IllegalArgumentException("Uniform texture/sampler was not created by MetalMC");
            NativeMetal.renderPassUniformTexture(batch, index, view.sampleHandle(context), sampler.nativeHandle(context));
        } else throw new IllegalArgumentException("Uniform requires a buffer slice or TextureViewAndSampler");
    }
    @Override public void pushConstants(ByteBuffer data) {
        checkOpen(); Objects.requireNonNull(data, "push constant data");
        if (pipeline == null) throw new IllegalStateException("Set pipeline before pushing constants");
        int length = pipeline.pushConstantsSize();
        if (length == 0 || data.remaining() < length) throw new IllegalArgumentException("Missing/undersized push constant declaration/data");
        var range = data.duplicate(); range.limit(range.position() + length);
        var snapshot = ByteBuffer.allocateDirect(length); snapshot.put(range).flip();
        NativeMetal.renderPassPushConstants(batch, snapshot, length);
    }
    @Override public void setVertexBuffer(int slot, GpuBufferSlice buffer) {
        checkOpen(); Objects.requireNonNull(buffer, "vertex slice");
        if (slot < 0 || slot > 15 || (buffer.offset() & 3) != 0 || buffer.length() < 1) throw new IllegalArgumentException("Invalid vertex slot/slice/alignment");
        NativeMetal.renderPassVertexBuffer(batch, slot,
                encoder.renderBuffer(buffer, GpuBuffer.USAGE_VERTEX), buffer.offset(), buffer.length());
    }
    @Override public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
        checkOpen(); Objects.requireNonNull(buffer, "index buffer"); Objects.requireNonNull(type, "index type");
        if (buffer instanceof MetalTransientMemory.TransientBuffer) throw new UnsupportedOperationException("Transient index slices have no whole-buffer binding contract");
        if (!(buffer instanceof MetalGpuBuffer metal)) throw new IllegalArgumentException("Index buffer was not created by MetalMC");
        NativeMetal.renderPassIndexBuffer(batch, metal.commandHandle(context, 0, metal.size(), GpuBuffer.USAGE_INDEX), type.bytes);
        indexType = type;
    }
    @Override public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
        checkOpen();
        if (indexCount < 0 || instanceCount < 0 || firstIndex < 0 || firstInstance < 0) throw new IllegalArgumentException("Negative indexed draw range");
        NativeMetal.renderPassDrawIndexed(batch, indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
    }
    private void multiCount(int count) {
        checkOpen();
        if (count < 0 || count > MAX_MULTI_DRAWS) throw new IllegalArgumentException("Multi-draw count must be 0..1024");
    }
    private static void remaining(IntBuffer buffer, int count) {
        if (Objects.requireNonNull(buffer, "draw parameters").remaining() < count) throw new IllegalArgumentException("Insufficient multi-draw parameters");
    }
    @Override public void multiDrawIndexed(IntBuffer data, int instanceCount, int firstInstance, int count) {
        multiCount(count); remaining(data, count * 3);
        if (instanceCount < 0 || firstInstance < 0) throw new IllegalArgumentException("Negative multi-draw instance range");
        int[] commands = new int[count * 5];
        int start = data.position();
        for (int i = 0; i < count; i++) {
            commands[i * 5] = data.get(start + i * 3 + 1); commands[i * 5 + 1] = instanceCount;
            commands[i * 5 + 2] = data.get(start + i * 3); commands[i * 5 + 3] = data.get(start + i * 3 + 2);
            commands[i * 5 + 4] = firstInstance;
        }
        NativeMetal.renderPassMultiDraw(batch, true, commands);
    }
    @Override public void multiDrawIndexed(PointerBuffer offsets, IntBuffer counts, IntBuffer baseVertices, int count) {
        multiCount(count); remaining(counts, count); remaining(baseVertices, count);
        if (Objects.requireNonNull(offsets, "index byte offsets").remaining() < count) throw new IllegalArgumentException("Insufficient index byte offsets");
        if (indexType == null && count != 0) throw new IllegalStateException("Set index buffer before separate indexed multi-draw");
        int[] commands = new int[count * 5];
        for (int i = 0; i < count; i++) {
            long offset = offsets.get(offsets.position() + i);
            if (offset < 0 || offset % indexType.bytes != 0 || offset / indexType.bytes > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid index byte offset");
            commands[i * 5] = counts.get(counts.position() + i); commands[i * 5 + 1] = 1;
            commands[i * 5 + 2] = (int)(offset / indexType.bytes); commands[i * 5 + 3] = baseVertices.get(baseVertices.position() + i);
        }
        NativeMetal.renderPassMultiDraw(batch, true, commands);
    }
    @Override public void drawIndexedIndirect(GpuBufferSlice buffer, int count) { indirect(buffer, count, true); }
    private void indirect(GpuBufferSlice buffer, int count, boolean indexed) {
        multiCount(count); Objects.requireNonNull(buffer, "indirect parameter slice");
        int stride = indexed ? 20 : 16;
        if (buffer.offset() < 0 || (buffer.offset() & 3) != 0 || buffer.length() < (long)count * stride) {
            throw new IllegalArgumentException("Invalid indirect parameter range/alignment");
        }
        NativeMetal.renderPassDrawIndirect(batch, indexed,
                encoder.renderBuffer(buffer, GpuBuffer.USAGE_INDIRECT_PARAMETERS), buffer.offset(), buffer.length(), count);
    }
    @Override public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        checkOpen();
        if (vertexCount < 0 || instanceCount < 0 || firstVertex < 0 || firstInstance < 0) throw new IllegalArgumentException("Negative draw range");
        NativeMetal.renderPassDraw(batch, vertexCount, instanceCount, firstVertex, firstInstance);
    }
    @Override public void multiDraw(IntBuffer data, int instanceCount, int firstInstance, int count) {
        multiCount(count); remaining(data, count * 2);
        if (instanceCount < 0 || firstInstance < 0) throw new IllegalArgumentException("Negative multi-draw instance range");
        int[] commands = new int[count * 4];
        int start = data.position();
        for (int i = 0; i < count; i++) {
            commands[i * 4] = data.get(start + i * 2 + 1); commands[i * 4 + 1] = instanceCount;
            commands[i * 4 + 2] = data.get(start + i * 2); commands[i * 4 + 3] = firstInstance;
        }
        NativeMetal.renderPassMultiDraw(batch, false, commands);
    }
    @Override public void multiDraw(IntBuffer firstVertices, IntBuffer counts, int count) {
        multiCount(count); remaining(firstVertices, count); remaining(counts, count);
        int[] commands = new int[count * 4];
        for (int i = 0; i < count; i++) {
            commands[i * 4] = counts.get(counts.position() + i); commands[i * 4 + 1] = 1;
            commands[i * 4 + 2] = firstVertices.get(firstVertices.position() + i);
        }
        NativeMetal.renderPassMultiDraw(batch, false, commands);
    }
    @Override public void drawIndirect(GpuBufferSlice buffer, int count) { indirect(buffer, count, false); }
    @Override public void writeTimestamp(GpuQueryPool pool, int index) { throw pending("timestamps"); }
}
