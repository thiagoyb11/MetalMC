package dev.metalmc.metal;

import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import java.util.Objects;
import java.util.Set;

/** Immutable native device facts, kept separate from the backend's supported subset.
 * The recommended working set is advisory and is not the renderer's memory budget.
 */
public record MetalDeviceProfile(String name, String operatingSystemVersion, long maxBufferLength,
        long recommendedMaxWorkingSetSize, boolean unifiedMemory, boolean supportsApple7) {
    public MetalDeviceProfile {
        if (Objects.requireNonNull(name).isBlank() || Objects.requireNonNull(operatingSystemVersion).isBlank()
                || maxBufferLength < 1 || recommendedMaxWorkingSetSize < 0) {
            throw new IllegalArgumentException("Invalid native Metal device properties");
        }
    }

    /** Limits describe implemented backend policy, bounded by the real device.
     * This object does not express format/topology/resource-usage support or budgets.
     */
    public DeviceInfo backendInfo() {
        return backendInfo(0);
    }
    /** Positive period is supplied only by a context with the actual timestamp counter set. */
    DeviceInfo backendInfo(float timestampPeriod) {
        if (timestampPeriod != 0 && timestampPeriod != 1) throw new IllegalArgumentException("Invalid timestamp period");
        if (!unifiedMemory || !supportsApple7) {
            throw new UnsupportedOperationException("MetalMC requires a unified-memory Apple7-or-later device");
        }
        var limits = new DeviceLimits(16, 4, 8192, Math.min(MetalGpuBuffer.MAX_SIZE, maxBufferLength), 1024, 8, 1024);
        // Wireframe remains unadvertised until raster parity is validated. DrawIndex
        // and persistent native mapping are unsupported. Expanded multi-draws are
        // available with shaderDrawParameters false; there is no gl_DrawID claim.
        var features = new DeviceFeatures(false, false, true, true, true, true, true, false);
        return new DeviceInfo(name, "Apple", "Metal / " + operatingSystemVersion, true,
                "MetalMC development", timestampPeriod, limits, features, Set.of(),
                new HintsAndWorkarounds(false, false, false, false), DeviceType.INTEGRATED);
    }
}
