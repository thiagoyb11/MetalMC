package dev.metalmc.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.metalmc.backend.MetalGpuDeviceBackend;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;

/** Actual native identity/properties, conservative backend feature policy and resource contracts. */
final class DeviceInfoTest {
    private DeviceInfoTest() {}
    static void run() throws Exception {
        verifyNativeIdentity();
        verifyResourceLimits();
        verifyBoundedPolicy();
        verifyOwnershipAndNativeIds();
        System.out.println("PASS: native Metal device properties, bounded DeviceInfo limits/features, max-axis texture GPU bytes, allocation/sampler limits, immutable snapshots and ownership (no frontend activation).");
    }
    private static void verifyNativeIdentity() {
        try (var context = MetalContext.create()) {
            long[] before = context.commandStats();
            var profile = context.deviceProfile();
            var info = context.deviceInfo();
            long[] values = NativeMetal.deviceProperties(context.nativeHandle());
            check(profile.name().equals(context.deviceName()) && info.name().equals(profile.name()), "Native device name mismatch");
            check(profile.operatingSystemVersion().equals(NativeMetal.operatingSystemVersion(context.nativeHandle())), "Native OS version mismatch");
            check(values.length == 4 && profile.maxBufferLength() == values[0]
                    && profile.recommendedMaxWorkingSetSize() == values[1] && values[2] == 1 && values[3] == 1,
                    "Native device properties mismatch");
            values[0] = 1;
            check(profile.maxBufferLength() == NativeMetal.deviceProperties(context.nativeHandle())[0], "Native properties escaped by reference");
            check(context.deviceProfile() == profile && context.deviceInfo() == info, "Device snapshots were not cached");
            check(info.vendorName().equals("Apple") && info.type() == DeviceType.INTEGRATED
                    && info.isZZeroToOne() && info.driverInfo().contains(profile.operatingSystemVersion())
                    && info.backendName().equals("MetalMC development"), "Incorrect device identity/coordinate policy");
            check(info.timestampPeriod() == (context.supportsTimestampQueries() ? 1 : 0)
                    && info.underlyingExtensions().isEmpty(), "Timestamp counter/extension policy mismatch");
            expect(UnsupportedOperationException.class, () -> info.underlyingExtensions().add("invented"));
            check(Arrays.equals(before, context.commandStats()) && context.pipelineCount() == 0,
                    "Device property reads allocated GPU resources or submitted commands");
            var limits = info.limits();
            check(limits.maxMemoryAllocationSize() == Math.min(MetalGpuBuffer.MAX_SIZE, profile.maxBufferLength()), "Allocation limit exceeds native/backend cap");
            check(limits.maxTextureSize() == 8192 && limits.minUniformOffsetAlignment() == 4
                    && limits.maxColorAttachments() == 8 && limits.maxAnisotropy() == 16
                    && limits.maxMultiDrawDirectInterleavedDrawCount() == 1024 && limits.maxDrawIndirectDrawCount() == 1024,
                    "Backend limits do not match the implemented contracts");
            check(limits.maxTextureSizeForFormat(GpuFormat.R8_UNORM) == 8192
                    && limits.maxTextureSizeForFormat(GpuFormat.RGBA32_FLOAT) <= 4096, "Format-dependent allocation bound ignored");
            var features = info.features();
            check(features.multiDrawDirectInterleaved() && features.multiDrawDirectSeparate()
                    && features.multiDrawIndirect() && features.drawIndirect() && features.nonZeroFirstInstance(),
                    "Validated draw features unavailable");
            check(!features.shaderDrawParameters() && !features.persistentMapping() && !features.wireframeFillMode(),
                    "Unimplemented or unvalidated feature advertised");
            var hints = info.hintsAndWorkarounds();
            check(!hints.writeToBufferIsSlow() && !hints.anisotropyHasKnownIssues()
                    && !hints.isExplicitDepthRequired() && !hints.multiDrawIndirectHasKnownIssues(), "Unestablished workaround claim");
            System.out.println("Device profile: " + profile);
            System.out.println("Backend device limits: " + limits);
            System.out.println("Backend device features: " + features);
        }
    }
    private static void verifyResourceLimits() {
        try (var device = MetalGpuDeviceBackend.createForDevelopment();
                var encoder = (MetalCommandEncoder)device.createCommandEncoder()) {
            var info = device.getDeviceInfo();
            check(info == device.getDeviceInfo(), "Backend did not reuse device snapshot");
            var limits = info.limits();
            Class<? extends Throwable> allocationError = limits.maxMemoryAllocationSize() == MetalGpuBuffer.MAX_SIZE
                    ? IllegalArgumentException.class : IllegalStateException.class;
            expect(allocationError, () -> device.createBuffer(null, GpuBuffer.USAGE_COPY_DST, limits.maxMemoryAllocationSize() + 1));
            expect(IllegalArgumentException.class, () -> device.createTexture(null, GpuTexture.USAGE_COPY_DST, GpuFormat.R8_UNORM,
                    limits.maxTextureSize() + 1, 1, 1, 1));
            expect(IllegalArgumentException.class, () -> device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, limits.maxAnisotropy() + 1, OptionalDouble.empty()));
            expect(UnsupportedOperationException.class, () -> device.createBuffer(null, GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, 16));
            expect(IllegalArgumentException.class, () -> device.createTimestampQueryPool(0));
            if (info.timestampPeriod() == 0) {
                expect(UnsupportedOperationException.class, () -> device.createTimestampQueryPool(1));
                expect(UnsupportedOperationException.class, device::getTimestampCalibrationOffset);
            }
            expect(UnsupportedOperationException.class, () -> device.createSurface(0, () -> true));
            int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC;
            int pixels = limits.maxTextureSize();
            byte[] pattern = new byte[pixels];
            for (int i = 0; i < pattern.length; i++) pattern[i] = (byte)(i * 31 + 7);
            // Max width and max height without allocating a max-area texture.
            try (var wide = (MetalGpuTexture)device.createTexture(null, usage, GpuFormat.R8_UNORM, pixels, 1, 1, 1);
                    var tall = (MetalGpuTexture)device.createTexture(null, usage, GpuFormat.R8_UNORM, 1, pixels, 1, 1);
                    var sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.REPEAT,
                            FilterMode.NEAREST, FilterMode.LINEAR, limits.maxAnisotropy(), OptionalDouble.of(0))) {
                check(sampler.getMaxAnisotropy() == limits.maxAnisotropy(), "Advertised anisotropy cannot be created");
                encoder.writeToTexture(wide, ByteBuffer.wrap(pattern), 0, 0, 0, 0, pixels, 1);
                encoder.writeToTexture(tall, ByteBuffer.wrap(pattern), 0, 0, 0, 0, 1, pixels);
                try (var fence = encoder.createFence()) {
                    encoder.submit(); check(fence.awaitCompletion(GpuFence.NO_TIMEOUT), "Capability resource submission failed");
                }
                check(Arrays.equals(pattern, wide.readDiagnostic(0,0)) && Arrays.equals(pattern, tall.readDiagnostic(0,0)),
                        "Advertised maximum texture axis did not preserve GPU bytes");
            }
        }
    }
    private static void verifyBoundedPolicy() {
        var conservative = new MetalDeviceProfile("fixture", "fixture OS", 4 * 1024 * 1024, Long.MAX_VALUE, true, true);
        check(conservative.backendInfo().limits().maxMemoryAllocationSize() == 4 * 1024 * 1024, "Physical limit not applied");
        var wide = new MetalDeviceProfile("fixture", "fixture OS", Long.MAX_VALUE, 0, true, true);
        check(wide.backendInfo().limits().maxMemoryAllocationSize() == MetalGpuBuffer.MAX_SIZE, "Device size bypassed backend allocation cap");
        expect(UnsupportedOperationException.class, () -> new MetalDeviceProfile("fixture", "fixture OS", 1024, 0, false, true).backendInfo());
        expect(UnsupportedOperationException.class, () -> new MetalDeviceProfile("fixture", "fixture OS", 1024, 0, true, false).backendInfo());
        expect(IllegalArgumentException.class, () -> new MetalDeviceProfile("fixture", "fixture OS", 0, 0, true, true));
        expect(IllegalArgumentException.class, () -> new MetalDeviceProfile("fixture", "fixture OS", 1, -1, true, true));
        expect(IllegalArgumentException.class, () -> new MetalDeviceProfile("", "fixture OS", 1, 0, true, true));
    }
    private static void verifyOwnershipAndNativeIds() throws InterruptedException {
        var device = MetalGpuDeviceBackend.createForDevelopment();
        try {
            var info = device.getDeviceInfo();
            var failure = new AtomicReference<Throwable>();
            var thread = new Thread(() -> { try { device.getDeviceInfo(); } catch (Throwable error) { failure.set(error); } });
            thread.start(); thread.join(); check(failure.get() instanceof IllegalStateException, "Device info accepted foreign thread after caching");
            device.close();
            expect(IllegalStateException.class, device::getDeviceInfo);
            check(!info.name().isBlank(), "Closed device invalidated independent immutable snapshot");
        } finally { device.close(); }
        expect(IllegalStateException.class, () -> NativeMetal.deviceProperties(0));
        expect(IllegalStateException.class, () -> NativeMetal.operatingSystemVersion(0));
        var context = MetalContext.create();
        long stale = context.nativeHandle();
        context.deviceProfile(); context.deviceInfo(); context.close();
        expect(IllegalStateException.class, context::deviceProfile);
        expect(IllegalStateException.class, context::deviceInfo);
        expect(IllegalStateException.class, () -> NativeMetal.deviceProperties(stale));
        expect(IllegalStateException.class, () -> NativeMetal.operatingSystemVersion(stale));
    }
    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", received " + error, error);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
