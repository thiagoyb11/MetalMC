package dev.metalmc.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Private 2D/2D-array Metal storage. Diagnostic transfers wait for completion;
 * the command encoder provides separate asynchronous transfers/retirement.
 */
public final class MetalGpuTexture implements GpuTexture {
    private final MetalContext context;
    private final String label;
    private final int usage, width, height, layers, mips;
    private final GpuFormat format;
    private final Set<MetalGpuTextureView> views = new HashSet<>();
    private long handle;

    MetalGpuTexture(MetalContext context, long handle, String label, int usage,
            GpuFormat format, int width, int height, int layers, int mips) {
        if (handle == 0) throw new IllegalStateException("Native texture creation returned null");
        this.context = context;
        this.handle = handle;
        this.label = label;
        this.usage = usage;
        this.format = format;
        this.width = width;
        this.height = height;
        this.layers = layers;
        this.mips = mips;
    }

    // Explicit ABI IDs, independent of Minecraft enum ordinals.
    static int nativeFormat(GpuFormat format) {
        return switch (Objects.requireNonNull(format, "format")) {
            case R8_UNORM -> 1;
            case RG8_UNORM -> 2;
            case RGBA8_UNORM -> 3;
            case R32_FLOAT -> 4;
            case RG32_FLOAT -> 5;
            case RGBA16_FLOAT -> 6;
            case RGBA32_FLOAT -> 7;
            case D32_FLOAT -> 8;
            default -> throw new UnsupportedOperationException("Metal texture format not implemented: " + format);
        };
    }

    static void validate(int usage, GpuFormat format, int width, int height, int layers, int mips) {
        nativeFormat(format);
        int supported = USAGE_COPY_DST | USAGE_COPY_SRC | USAGE_TEXTURE_BINDING | USAGE_RENDER_ATTACHMENT;
        if (usage == 0 || (usage & ~supported) != 0) {
            throw new UnsupportedOperationException("Unsupported texture usage (cubemaps pending): " + usage);
        }
        if (width < 1 || height < 1 || width > 8192 || height > 8192 || layers < 1 || layers > 256) {
            throw new IllegalArgumentException("Initial texture limits: 1..8192 pixels, 1..256 array layers");
        }
        int maxMips = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
        if (mips < 1 || mips > maxMips) throw new IllegalArgumentException("Invalid texture mip count");
        long bytes = 0;
        for (int mip = 0; mip < mips; mip++) {
            bytes += (long)Math.max(1, width >> mip) * Math.max(1, height >> mip) * layers * format.blockSize();
        }
        if (bytes > MetalGpuBuffer.MAX_SIZE) throw new IllegalArgumentException("Texture exceeds initial 256 MiB cap");
    }

    private void mip(int level) {
        if (level < 0 || level >= mips) throw new IllegalArgumentException("Mip outside texture");
    }
    @Override public int getWidth(int level) { mip(level); return Math.max(1, width >> level); }
    @Override public int getHeight(int level) { mip(level); return Math.max(1, height >> level); }
    @Override public int getDepthOrLayers() { return layers; }
    @Override public int getMipLevels() { return mips; }
    @Override public GpuFormat getFormat() { return format; }
    @Override public int usage() { return usage; }
    @Override public String getLabel() { return label; }
    @Override public boolean isClosed() { context.checkOwner(); return handle == 0; }

    MetalGpuTextureView createView(MetalContext device, int baseMip, int count) {
        checkOpen();
        if (device != context) throw new IllegalArgumentException("Texture belongs to another device");
        if (baseMip < 0 || count < 1 || baseMip >= mips || count > mips - baseMip) {
            throw new IllegalArgumentException("Texture view mip range outside texture");
        }
        var view = new MetalGpuTextureView(context, this,
                NativeMetal.createTextureView(handle, baseMip, count), baseMip, count);
        views.add(view);
        return view;
    }

    void removeView(MetalGpuTextureView view) { views.remove(view); }
    void checkOpen() {
        context.checkOpen();
        if (handle == 0) throw new IllegalStateException("Metal texture is closed");
    }

    long commandHandle(MetalContext expected, int requiredUsage) {
        checkOpen();
        if (context != expected) throw new IllegalArgumentException("Texture belongs to another Metal device");
        if ((usage & requiredUsage) != requiredUsage) throw new IllegalStateException("Texture command usage missing");
        return handle;
    }

    long regionSize(int level, int layer, int x, int y, int regionWidth, int regionHeight) {
        checkOpen();
        mip(level);
        if (layer < 0 || layer >= layers || x < 0 || y < 0 || regionWidth < 1 || regionHeight < 1
                || x > getWidth(level) || regionWidth > getWidth(level) - x
                || y > getHeight(level) || regionHeight > getHeight(level) - y) {
            throw new IllegalArgumentException("Region outside texture mip/layer");
        }
        return (long)regionWidth * regionHeight * format.blockSize();
    }

    private int transferSize(int level, int layer) {
        checkOpen();
        mip(level);
        if (layer < 0 || layer >= layers) throw new IllegalArgumentException("Layer outside texture");
        return Math.multiplyExact(Math.multiplyExact(getWidth(level), getHeight(level)), format.blockSize());
    }

    /** Test-only tightly packed full-mip upload; preserves source position/limit. */
    public void uploadDiagnostic(int level, int layer, ByteBuffer source) {
        int size = transferSize(level, layer);
        if ((usage & USAGE_COPY_DST) == 0) throw new IllegalStateException("Texture lacks COPY_DST usage");
        if (source.remaining() != size) throw new IllegalArgumentException("Upload must contain one complete mip/layer");
        ByteBuffer staging = ByteBuffer.allocateDirect(size);
        staging.put(source.duplicate()).flip();
        NativeMetal.transferTextureDiagnostic(handle, level, layer, staging, size, true);
    }

    /** Test-only tightly packed full-mip GPU readback. */
    public byte[] readDiagnostic(int level, int layer) {
        int size = transferSize(level, layer);
        if ((usage & USAGE_COPY_SRC) == 0) throw new IllegalStateException("Texture lacks COPY_SRC usage");
        ByteBuffer staging = ByteBuffer.allocateDirect(size);
        NativeMetal.transferTextureDiagnostic(handle, level, layer, staging, size, false);
        byte[] result = new byte[size];
        staging.get(result);
        return result;
    }

    /** Test-only render-attachment clear of one mip/layer. For D32, red is depth. */
    public void clearDiagnostic(int level, int layer, float red, float green, float blue, float alpha) {
        transferSize(level, layer);
        if ((usage & USAGE_RENDER_ATTACHMENT) == 0) throw new IllegalStateException("Texture lacks RENDER_ATTACHMENT usage");
        if (!Float.isFinite(red) || !Float.isFinite(green) || !Float.isFinite(blue) || !Float.isFinite(alpha)
                || (format == GpuFormat.D32_FLOAT && (red < 0 || red > 1))) {
            throw new IllegalArgumentException("Invalid clear value");
        }
        NativeMetal.clearTextureDiagnostic(handle, level, layer, red, green, blue, alpha);
    }

    @Override public void close() {
        context.checkOwner();
        if (handle != 0) {
            for (var view : Set.copyOf(views)) view.close();
            NativeMetal.destroyTexture(handle);
            handle = 0;
            context.removeTexture(this);
        }
    }
}
