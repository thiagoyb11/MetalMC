package dev.metalmc.metal;

/** JNI ABI, generated with javac -h. Resources use opaque IDs; surface bootstrap
 * accepts only a live SDL_Metal_GetLayer pointer, never arbitrary pointers.
 */
final class NativeMetal {
    private NativeMetal() {}

    static native int abiVersion();
    static native long createContext();
    static native String deviceName(long handle);
    // maxBufferLength, recommendedMaxWorkingSetSize, unified-memory, Apple7 family.
    static native long[] deviceProperties(long handle);
    static native String operatingSystemVersion(long handle);
    static native boolean timestampSupported(long context);
    static native long sampleGpuTimestamp(long context);
    static native long createQueryPool(long context, int count);
    static native long[] queryValues(long pool, int first, int count);
    static native long queryPoolCount(long context);
    static native void destroyQueryPool(long pool);
    static native void encodeTimestamp(long batch, long pool, int index);
    static native byte[] renderTriangle(long handle, int width, int height);
    static native void destroyContext(long handle);
    // Only a live CAMetalLayer obtained from SDL_Metal_GetLayer is valid here.
    static native long createSurface(long context, long sdlMetalLayer);
    static native boolean presentTriangle(long surface, int pixelWidth, int pixelHeight);
    static native long[] surfaceStats(long surface);
    static native void drainSurface(long surface);
    static native void destroySurface(long surface);
    static native long createBuffer(long context, long size, String label);
    static native void readBuffer(long buffer, long offset, java.nio.ByteBuffer destination, int length);
    static native void writeBuffer(long buffer, long offset, java.nio.ByteBuffer source, int length);
    static native void copyBufferDiagnostic(long source, long sourceOffset, long destination, long destinationOffset, long length);
    static native void destroyBuffer(long buffer);
    static native long createTexture(long context, String label, int usage, int format,
            int width, int height, int layers, int mips);
    static native long createTextureView(long texture, int baseMip, int mips);
    static native void destroyTextureView(long view);
    static native void destroyTexture(long texture);
    static native long createSampler(long context, boolean repeatU, boolean repeatV,
            boolean linearMin, boolean linearMag, int anisotropy, double maxLod);
    static native void destroySampler(long sampler);
    static native void transferTextureDiagnostic(long texture, int mip, int layer,
            java.nio.ByteBuffer data, int length, boolean upload);
    static native float[] sampleTextureDiagnostic(long view, long sampler, float u, float v, float lod);
    static native void clearTextureDiagnostic(long texture, int mip, int layer, float red, float green,
            float blue, float alpha);
    static native long createBatch(long context);
    static native long retainBatch(long batch);
    static native void releaseBatch(long batch);
    static native void cancelBatch(long batch);
    static native void encodeBufferCopy(long batch, long source, long sourceOffset,
            long destination, long destinationOffset, long length);
    static native void encodeBufferWrite(long batch, long destination, long offset,
            java.nio.ByteBuffer data, int length);
    static native void submitBatch(long batch);
    static native boolean awaitBatch(long batch, long timeoutNanos);
    static native void drainCommands(long context);
    static native long[] commandStats(long context);
    static native void encodeTextureWrite(long batch, long texture, java.nio.ByteBuffer data, int length,
            int mip, int layer, int x, int y, int width, int height);
    static native void encodeBufferTexture(long batch, long buffer, long offset, int rowPixels,
            long texture, int mip, int layer, int x, int y, int width, int height, boolean upload);
    static native void encodeTextureCopy(long batch, long source, long target, int mip,
            int targetX, int targetY, int sourceX, int sourceY, int width, int height);
    static native void encodeTextureClear(long batch, long color, float red, float green, float blue,
            float alpha, long depth, double clearDepth);
    static native void encodeScissoredClear(long batch, long color, float red, float green, float blue,
            float alpha, long depth, double clearDepth, int x, int y, int width, int height, int mip);
    static native void beginRenderPass(long batch, long[] colors, int[] mips, boolean[] clear,
            float[] values, long depth, int depthMip, boolean clearDepth, double depthValue,
            int width, int height, String label);
    static native void endRenderPass(long batch);
    static native void renderPassDebugGroup(long batch, String label, boolean push);
    static native void renderPassScissor(long batch, int x, int y, int width, int height);
    static native void validateShaderDiagnostic(long context, String source, String function, int stage);
    static native long createRenderPipeline(long context, String label, String vertexSource, String vertexEntry,
            String fragmentSource, String fragmentEntry, int[] layouts, int[] attributes, int[] colors,
            int[] state, float depthBias, float slopeBias, int[] uniformTypes, int[] resources, int pushConstantsSize);
    static native void destroyRenderPipeline(long pipeline);
    static native long pipelineCount(long context);
    static native void renderPassPipeline(long batch, long pipeline);
    static native void renderPassVertexBuffer(long batch, int slot, long buffer, long offset, long length);
    static native void renderPassDraw(long batch, int vertexCount, int instanceCount, int firstVertex, int firstInstance);
    static native void renderPassIndexBuffer(long batch, long buffer, int indexBytes);
    static native void renderPassDrawIndexed(long batch, int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance);
    static native void renderPassMultiDraw(long batch, boolean indexed, int[] commands);
    static native void renderPassDrawIndirect(long batch, boolean indexed, long buffer, long offset, long length, int count);
    static native void renderPassClearUniform(long batch, int index);
    static native void renderPassUniformBuffer(long batch, int index, long buffer, long offset, long length);
    static native void renderPassUniformTexture(long batch, int index, long view, long sampler);
    static native void renderPassPushConstants(long batch, java.nio.ByteBuffer data, int length);
}
