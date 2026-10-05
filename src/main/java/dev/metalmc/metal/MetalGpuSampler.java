package dev.metalmc.metal;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.Objects;
import java.util.OptionalDouble;

/** Immutable native sampler. Mip interpolation is linear, matching 26.3's backends. */
public final class MetalGpuSampler implements GpuSampler {
    private final MetalContext context;
    private final AddressMode u, v;
    private final FilterMode min, mag;
    private final int anisotropy;
    private final OptionalDouble maxLod;
    private long handle;

    MetalGpuSampler(MetalContext context, long handle, AddressMode u, AddressMode v,
            FilterMode min, FilterMode mag, int anisotropy, OptionalDouble maxLod) {
        if (handle == 0) throw new IllegalStateException("Native sampler creation returned null");
        this.context = context;
        this.handle = handle;
        this.u = u;
        this.v = v;
        this.min = min;
        this.mag = mag;
        this.anisotropy = anisotropy;
        this.maxLod = maxLod;
    }
    static void validate(AddressMode u, AddressMode v, FilterMode min, FilterMode mag,
            int anisotropy, OptionalDouble maxLod) {
        Objects.requireNonNull(u, "u");
        Objects.requireNonNull(v, "v");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(mag, "mag");
        Objects.requireNonNull(maxLod, "maxLod");
        if (anisotropy < 1 || anisotropy > 16) throw new IllegalArgumentException("Anisotropy must be 1..16");
        if (maxLod.isPresent() && (!Double.isFinite(maxLod.getAsDouble())
                || maxLod.getAsDouble() < 0 || maxLod.getAsDouble() > Float.MAX_VALUE)) {
            throw new IllegalArgumentException("Max LOD must be finite, nonnegative and fit a float");
        }
    }
    long nativeHandle(MetalContext device) {
        context.checkOpen();
        if (handle == 0) throw new IllegalStateException("Metal sampler is closed");
        if (device != context) throw new IllegalArgumentException("Sampler belongs to another device");
        return handle;
    }
    @Override public AddressMode getAddressModeU() { return u; }
    @Override public AddressMode getAddressModeV() { return v; }
    @Override public FilterMode getMinFilter() { return min; }
    @Override public FilterMode getMagFilter() { return mag; }
    @Override public int getMaxAnisotropy() { return anisotropy; }
    @Override public OptionalDouble getMaxLod() { return maxLod; }
    @Override public boolean isClosed() { context.checkOwner(); return handle == 0; }
    @Override public void close() {
        context.checkOwner();
        if (handle != 0) {
            NativeMetal.destroySampler(handle);
            handle = 0;
            context.removeSampler(this);
        }
    }
}
