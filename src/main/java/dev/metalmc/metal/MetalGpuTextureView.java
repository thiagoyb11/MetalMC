package dev.metalmc.metal;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

/** Native view shares its parent's storage and restricts visible mip levels. */
public final class MetalGpuTextureView implements GpuTextureView {
    private final MetalContext context;
    private final MetalGpuTexture texture;
    private final int baseMip, mips;
    private long handle;

    MetalGpuTextureView(MetalContext context, MetalGpuTexture texture, long handle, int baseMip, int mips) {
        if (handle == 0) throw new IllegalStateException("Native texture view creation returned null");
        this.context = context;
        this.texture = texture;
        this.handle = handle;
        this.baseMip = baseMip;
        this.mips = mips;
    }
    @Override public boolean isClosed() { context.checkOwner(); return handle == 0; }
    @Override public GpuTexture texture() { return texture; }
    @Override public int baseMipLevel() { return baseMip; }
    @Override public int mipLevels() { return mips; }
    private void mip(int level) {
        if (level < 0 || level >= mips) throw new IllegalArgumentException("Mip outside texture view");
    }
    @Override public int getWidth(int level) { mip(level); return texture.getWidth(baseMip + level); }
    @Override public int getHeight(int level) { mip(level); return texture.getHeight(baseMip + level); }

    long attachmentHandle(MetalContext expected) {
        context.checkOpen();
        if (context != expected) throw new IllegalArgumentException("Texture view belongs to another device");
        if (handle == 0) throw new IllegalStateException("Texture view is closed");
        return texture.commandHandle(expected, GpuTexture.USAGE_RENDER_ATTACHMENT);
    }
    long sampleHandle(MetalContext expected) {
        context.checkOpen();
        if (context != expected) throw new IllegalArgumentException("Texture view belongs to another device");
        if (handle == 0) throw new IllegalStateException("Texture view is closed");
        texture.commandHandle(expected, GpuTexture.USAGE_TEXTURE_BINDING);
        return handle;
    }

    /** Test-only GPU sampling of a color 2D view. Returns four float components. */
    public float[] sampleDiagnostic(MetalGpuSampler sampler, float u, float v, float lod) {
        context.checkOpen();
        texture.checkOpen();
        if (handle == 0) throw new IllegalStateException("Texture view is closed");
        if ((texture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0) {
            throw new IllegalStateException("Texture lacks TEXTURE_BINDING usage");
        }
        if (texture.getDepthOrLayers() != 1 || !texture.getFormat().hasColorAspect()) {
            throw new UnsupportedOperationException("Diagnostic sampler requires a color 2D texture");
        }
        if (!Float.isFinite(u) || !Float.isFinite(v) || !Float.isFinite(lod) || lod < 0) {
            throw new IllegalArgumentException("Invalid sample coordinates/LOD");
        }
        return NativeMetal.sampleTextureDiagnostic(handle, sampler.nativeHandle(context), u, v, lod);
    }
    @Override public void close() {
        context.checkOwner();
        if (handle != 0) {
            NativeMetal.destroyTextureView(handle);
            handle = 0;
            texture.removeView(this);
        }
    }
}
