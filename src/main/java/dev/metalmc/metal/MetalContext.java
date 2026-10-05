package dev.metalmc.metal;

import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.nio.ByteBuffer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.util.OptionalDouble;

/**
 * Owns a native device/queue and diagnostic pipeline on the creating thread.
 * Offscreen readback is synchronous; it must not become the gameplay frame path.
 */
public final class MetalContext implements AutoCloseable {
    private final Thread owner;
    private long handle;
    private MetalDeviceProfile deviceProfile;
    private com.mojang.renderpearl.api.device.DeviceInfo deviceInfo;
    private Boolean timestampSupported;
    private final Set<MetalSurface> surfaces = new HashSet<>();
    private final Set<MetalGpuBuffer> buffers = new HashSet<>();
    private final Set<MetalGpuTexture> textures = new HashSet<>();
    private final Set<MetalGpuSampler> samplers = new HashSet<>();
    private final Set<MetalCommandEncoder> encoders = new HashSet<>();
    private final Set<MetalRenderPipeline> pipelines = new HashSet<>();
    private final Set<MetalGpuFence> fences = new HashSet<>();
    private final Set<MetalGpuQueryPool> queryPools = new HashSet<>();
    private final ArrayList<CallbackBatch> callbacks = new ArrayList<>();
    private boolean dispatchingCallbacks, closing;

    static final class CallbackBatch {
        final long handle;
        final List<Runnable> callbacks;
        CallbackBatch(long handle, List<Runnable> callbacks) { this.handle = handle; this.callbacks = List.copyOf(callbacks); }
    }

    private MetalContext(long handle) {
        this.owner = Thread.currentThread();
        this.handle = handle;
    }

    public static MetalContext create() {
        NativeLibraryLoader.load();
        long handle = NativeMetal.createContext();
        if (handle == 0) {
            throw new IllegalStateException("Native Metal context creation returned a null handle");
        }
        return new MetalContext(handle);
    }

    public String deviceName() {
        checkOpen();
        return NativeMetal.deviceName(handle);
    }

    /** Snapshot of actual native device properties; no GPU commands or waits. */
    public MetalDeviceProfile deviceProfile() {
        checkOpen();
        if (deviceProfile == null) {
            long[] properties = NativeMetal.deviceProperties(handle);
            if (properties == null || properties.length != 4
                    || (properties[2] != 0 && properties[2] != 1)
                    || (properties[3] != 0 && properties[3] != 1)) {
                throw new LinkageError("Invalid native Metal device property contract");
            }
            deviceProfile = new MetalDeviceProfile(deviceName(), NativeMetal.operatingSystemVersion(handle),
                    properties[0], properties[1], properties[2] != 0, properties[3] != 0);
        }
        return deviceProfile;
    }

    /** Supported development-backend policy, not all capabilities of the GPU. */
    public com.mojang.renderpearl.api.device.DeviceInfo deviceInfo() {
        checkOpen();
        if (deviceInfo == null) deviceInfo = deviceProfile().backendInfo(supportsTimestampQueries() ? 1 : 0);
        return deviceInfo;
    }

    /** Timestamp-set and stage-boundary support, not arbitrary render-pass query support. */
    public boolean supportsTimestampQueries() {
        checkOpen();
        if (timestampSupported == null) timestampSupported = NativeMetal.timestampSupported(handle);
        return timestampSupported;
    }
    public MetalGpuQueryPool createTimestampQueryPool(int count) {
        checkOpen();
        if (closing) throw new IllegalStateException("Metal context is closing");
        if (count < 1 || count > MetalGpuQueryPool.MAX_QUERIES) throw new IllegalArgumentException("Timestamp pool size must be 1..4096");
        if (!supportsTimestampQueries()) throw new UnsupportedOperationException("Metal timestamp counter/stage sampling is unavailable");
        var pool = new MetalGpuQueryPool(this, count, NativeMetal.createQueryPool(handle, count));
        queryPools.add(pool);
        return pool;
    }
    void removeQueryPool(MetalGpuQueryPool pool) { queryPools.remove(pool); }
    /** Live and batch-retained native counter pools, capped at 16/context and 32/process. */
    public long queryPoolCount() { checkOpen(); return NativeMetal.queryPoolCount(handle); }

    /** Correlate nanosecond GPU timestamps with this JVM's nanoTime origin.
     * Bracket five native samples and use the one with the shortest JNI interval.
     * No commands, waits or uncalibrated CPU-time substitution for GPU queries.
     */
    public long timestampCalibrationOffset() {
        checkOpen();
        if (!supportsTimestampQueries()) throw new UnsupportedOperationException("Metal timestamp counter/stage sampling is unavailable");
        long shortest = Long.MAX_VALUE, offset = 0;
        for (int i = 0; i < 5; i++) {
            long before = System.nanoTime();
            long gpu = NativeMetal.sampleGpuTimestamp(handle);
            long after = System.nanoTime();
            long interval = after - before;
            if (interval >= 0 && interval < shortest) {
                shortest = interval;
                offset = Math.subtractExact(before + interval / 2, gpu);
            }
        }
        if (shortest == Long.MAX_VALUE) throw new IllegalStateException("Could not bracket Metal timestamp calibration");
        return offset;
    }

    public MetalImage renderDiagnosticTriangle(int width, int height) {
        checkOpen();
        if (width < 1 || height < 1 || width > 4096 || height > 4096) {
            throw new IllegalArgumentException("Diagnostic dimensions must be between 1 and 4096");
        }
        return new MetalImage(width, height, NativeMetal.renderTriangle(handle, width, height));
    }

    /** Development-only compilation check, with temporary library/function ownership.
     * Does not create a backend pipeline or record/submit a GPU draw.
     */
    public void validateShaderDiagnostic(MetalShaderTranslator.Translation translation) {
        checkOpen();
        java.util.Objects.requireNonNull(translation, "translated shader");
        if (translation.msl().isEmpty() || translation.msl().length() > MetalShaderTranslator.MAX_MSL_CHARS
                || translation.entryPoint().isEmpty() || translation.entryPoint().indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid translated Metal shader");
        }
        NativeMetal.validateShaderDiagnostic(handle, translation.msl(), translation.entryPoint(),
                translation.stage() == com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX ? 0 : 1);
    }

    void checkOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Metal context must be used on its creating thread");
        }
    }

    void checkOpen() {
        checkOwner();
        if (handle == 0) {
            throw new IllegalStateException("Metal context is closed");
        }
    }

    /** Attach only a live SDL_Metal_GetLayer result, on the macOS main thread.
     * The caller keeps the SDL view/window alive until this surface is closed.
     */
    public MetalSurface attachSdlMetalLayer(long layer) {
        checkOpen();
        if (layer == 0) throw new IllegalArgumentException("SDL Metal layer is null");
        MetalSurface surface = new MetalSurface(this, NativeMetal.createSurface(handle, layer));
        surfaces.add(surface);
        return surface;
    }

    void removeSurface(MetalSurface surface) {
        surfaces.remove(surface);
    }

    public MetalGpuBuffer createBuffer(int usage, long size) {
        return createBuffer(null, usage, size);
    }

    public MetalGpuBuffer createBuffer(String label, int usage, long size) {
        checkOpen();
        MetalGpuBuffer.validate(usage, size);
        MetalGpuBuffer buffer = new MetalGpuBuffer(this, NativeMetal.createBuffer(handle, size, label), usage, size);
        buffers.add(buffer);
        return buffer;
    }

    void removeBuffer(MetalGpuBuffer buffer) {
        buffers.remove(buffer);
    }

    public MetalGpuBuffer createBuffer(int usage, ByteBuffer data) {
        return createBuffer(null, usage, data);
    }

    public MetalGpuBuffer createBuffer(String label, int usage, ByteBuffer data) {
        MetalGpuBuffer buffer = createBuffer(label, usage, data.remaining());
        try {
            buffer.initialize(data);
            return buffer;
        } catch (RuntimeException | Error failure) {
            buffer.close();
            throw failure;
        }
    }

    public MetalGpuTexture createTexture(String label, int usage, GpuFormat format,
            int width, int height, int layers, int mips) {
        checkOpen();
        MetalGpuTexture.validate(usage, format, width, height, layers, mips);
        var texture = new MetalGpuTexture(this, NativeMetal.createTexture(handle, label, usage,
                MetalGpuTexture.nativeFormat(format), width, height, layers, mips),
                label, usage, format, width, height, layers, mips);
        textures.add(texture);
        return texture;
    }

    public MetalGpuTextureView createTextureView(GpuTexture texture, int baseMip, int mips) {
        checkOpen();
        if (!(texture instanceof MetalGpuTexture metal)) {
            throw new IllegalArgumentException("Texture was not created by MetalMC");
        }
        return metal.createView(this, baseMip, mips);
    }

    public MetalGpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag,
            int anisotropy, OptionalDouble maxLod) {
        checkOpen();
        MetalGpuSampler.validate(u, v, min, mag, anisotropy, maxLod);
        var sampler = new MetalGpuSampler(this, NativeMetal.createSampler(handle,
                u == AddressMode.REPEAT, v == AddressMode.REPEAT,
                min == FilterMode.LINEAR, mag == FilterMode.LINEAR, anisotropy,
                maxLod.orElse(Float.MAX_VALUE)), u, v, min, mag, anisotropy, maxLod);
        samplers.add(sampler);
        return sampler;
    }

    void removeTexture(MetalGpuTexture texture) { textures.remove(texture); }
    void removeSampler(MetalGpuSampler sampler) { samplers.remove(sampler); }

    public com.mojang.renderpearl.backend.api.BackendRenderPipeline.Pending compilePipeline(
            com.mojang.renderpearl.backend.api.BackendRenderPipeline.CreateInfo info) {
        return compilePipeline(info, new MetalShaderTranslator.Options(false, false));
    }

    public com.mojang.renderpearl.backend.api.BackendRenderPipeline.Pending compilePipeline(
            com.mojang.renderpearl.backend.api.BackendRenderPipeline.CreateInfo info, MetalShaderTranslator.Options options) {
        checkOpen();
        if (closing) throw new IllegalStateException("Metal context is closing");
        // Compile eagerly on the owner thread, consume the Pending exactly once.
        // Source modules may close immediately after this method returns.
        var pipeline = MetalRenderPipeline.compile(this, info, options);
        pipelines.add(pipeline);
        return new com.mojang.renderpearl.backend.api.BackendRenderPipeline.Pending() {
            private boolean finished;
            @Override public MetalRenderPipeline finishCompile() {
                checkOpen();
                if (finished || pipeline.isClosed()) throw new IllegalStateException("Pending pipeline already consumed or closed");
                finished = true;
                return pipeline;
            }
        };
    }
    void removePipeline(MetalRenderPipeline pipeline) { pipelines.remove(pipeline); }
    /** Live and batch-retained backend pipelines; excludes diagnostic pipelines. */
    public long pipelineCount() { checkOpen(); return NativeMetal.pipelineCount(handle); }

    public MetalCommandEncoder createCommandEncoder() {
        checkOpen();
        if (closing) throw new IllegalStateException("Metal context is closing");
        var encoder = new MetalCommandEncoder(this);
        encoders.add(encoder);
        return encoder;
    }
    long nativeHandle() { checkOpen(); return handle; }
    void removeEncoder(MetalCommandEncoder encoder) { encoders.remove(encoder); }
    void addFence(MetalGpuFence fence) { fences.add(fence); }
    void removeFence(MetalGpuFence fence) { fences.remove(fence); }
    void reserveCallbackBatch() { callbacks.ensureCapacity(callbacks.size() + 1); }
    void enqueueCallbacks(CallbackBatch completion) { callbacks.add(completion); }

    /** Retire completed readbacks on the device's creating thread. Also called
     * by submit, successful fence waits and context shutdown. Callbacks can map
     * completed buffers; none run on a Metal driver thread.
     */
    public void pollCallbacks() {
        checkOpen();
        if (dispatchingCallbacks) return;
        dispatchingCallbacks = true;
        Throwable failure = null;
        try {
            for (CallbackBatch completion : List.copyOf(callbacks)) {
                boolean ready;
                try { ready = NativeMetal.awaitBatch(completion.handle, 0); }
                catch (RuntimeException | Error error) {
                    callbacks.remove(completion);
                    NativeMetal.releaseBatch(completion.handle);
                    if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
                    continue;
                }
                if (!ready) break;
                // Remove and release before invoking user code to make reentry safe.
                callbacks.remove(completion);
                NativeMetal.releaseBatch(completion.handle);
                for (Runnable callback : completion.callbacks) {
                    try { callback.run(); }
                    catch (RuntimeException | Error error) {
                        if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
                    }
                }
            }
        } finally { dispatchingCallbacks = false; }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    /** Development statistics: submitted, completed, in flight, peak, buffer/staging bytes, texture bytes. */
    public long[] commandStats() { checkOpen(); return NativeMetal.commandStats(handle); }

    @Override
    public void close() {
        checkOwner();
        if (handle != 0) {
            if (dispatchingCallbacks) throw new IllegalStateException("Cannot close context from a readback callback");
            closing = true;
            for (MetalCommandEncoder encoder : Set.copyOf(encoders)) encoder.close();
            Throwable gpuFailure = null;
            try { NativeMetal.drainCommands(handle); }
            catch (RuntimeException failure) { gpuFailure = failure; }
            try { pollCallbacks(); }
            catch (RuntimeException | Error failure) {
                if (gpuFailure == null) gpuFailure = failure; else if (failure != gpuFailure) gpuFailure.addSuppressed(failure);
            }
            for (MetalGpuFence fence : Set.copyOf(fences)) fence.close();
            for (MetalGpuQueryPool pool : Set.copyOf(queryPools)) pool.close();
            for (MetalSurface surface : Set.copyOf(surfaces)) surface.close();
            for (MetalGpuBuffer buffer : Set.copyOf(buffers)) buffer.close();
            for (MetalGpuTexture texture : Set.copyOf(textures)) texture.close();
            for (MetalGpuSampler sampler : Set.copyOf(samplers)) sampler.close();
            for (MetalRenderPipeline pipeline : Set.copyOf(pipelines)) pipeline.close();
            NativeMetal.destroyContext(handle);
            handle = 0;
            if (gpuFailure instanceof RuntimeException runtime) throw runtime;
            if (gpuFailure instanceof Error error) throw error;
        }
    }
}
