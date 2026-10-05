package dev.metalmc.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import dev.metalmc.metal.MetalContext;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Development-only exact-26.3 adapter. Not registered as a backend candidate.
 * Initial resources, buffer/texture commands and full clears are implemented in code;
 * incomplete operations fail explicitly. In particular
 * the game surface, timestamp/profiler and pipeline-worker contracts remain
 * pending before frontend activation. Device info reports the supported subset.
 */
public final class MetalGpuDeviceBackend implements GpuDeviceBackend, AutoCloseable {
    private final MetalContext context;

    private MetalGpuDeviceBackend(MetalContext context) { this.context = context; }

    public static MetalGpuDeviceBackend createForDevelopment() {
        return new MetalGpuDeviceBackend(MetalContext.create());
    }

    @Override public GpuBuffer createBuffer(Supplier<String> label, int usage, long size) {
        return context.createBuffer(label == null ? null : label.get(), usage, size);
    }

    @Override public GpuBuffer createBuffer(Supplier<String> label, int usage, ByteBuffer data) {
        return context.createBuffer(label == null ? null : label.get(), usage, data);
    }

    @Override public void close() { context.close(); }
    @Override public List<String> getLastDebugMessages() { throw pending("debug message collection"); }
    @Override public boolean isDebuggingEnabled() { throw pending("debug options"); }
    @Override public DeviceInfo getDeviceInfo() { return context.deviceInfo(); }
    @Override public GpuSurfaceBackend createSurface(long window, BooleanSupplier active) { throw pending("game surface"); }
    @Override public CommandEncoderBackend createCommandEncoder() { return context.createCommandEncoder(); }
    @Override public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag,
            int anisotropy, OptionalDouble maxLod) { return context.createSampler(u, v, min, mag, anisotropy, maxLod); }
    @Override public GpuTexture createTexture(String label, int usage, GpuFormat format, int width,
            int height, int depth, int mips) { return context.createTexture(label, usage, format, width, height, depth, mips); }
    @Override public GpuTextureView createTextureView(GpuTexture texture, int baseMip, int mips) {
        return context.createTextureView(texture, baseMip, mips);
    }
    @Override public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo info) { return context.compilePipeline(info); }
    @Override public GpuQueryPool createTimestampQueryPool(int count) { return context.createTimestampQueryPool(count); }
    @Override public long getTimestampCalibrationOffset() { return context.timestampCalibrationOffset(); }

    private static UnsupportedOperationException pending(String feature) {
        return new UnsupportedOperationException("MetalMC backend is incomplete: " + feature + " is not implemented");
    }
}
