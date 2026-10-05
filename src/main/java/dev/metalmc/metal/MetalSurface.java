package dev.metalmc.metal;

/** Owns a retained CAMetalLayer association, but never the SDL view or window. */
public final class MetalSurface implements AutoCloseable {
    private final MetalContext context;
    private long handle;

    MetalSurface(MetalContext context, long handle) {
        if (handle == 0) throw new IllegalStateException("Native Metal surface creation returned null");
        this.context = context;
        this.handle = handle;
    }

    /** Asynchronous submission; zero dimensions skip acquisition.
     * Also pass zero dimensions while hidden, minimized, or occluded.
     * False means the frame was skipped (including full in-flight capacity).
     */
    public boolean presentDiagnosticTriangle(int pixelWidth, int pixelHeight) {
        checkOpen();
        if (pixelWidth < 0 || pixelHeight < 0 || pixelWidth > 16384 || pixelHeight > 16384) {
            throw new IllegalArgumentException("Surface dimensions must be between 0 and 16384");
        }
        return NativeMetal.presentTriangle(handle, pixelWidth, pixelHeight);
    }

    public Stats stats() {
        checkOpen();
        long[] values = NativeMetal.surfaceStats(handle);
        return new Stats(values[0], values[1], values[2], values[3], values[4], values[5], values[6]);
    }

    /** Shutdown/test barrier only. Never call in a gameplay frame loop. */
    public void drain() {
        checkOpen();
        NativeMetal.drainSurface(handle);
    }

    private void checkOpen() {
        context.checkOpen();
        if (handle == 0) throw new IllegalStateException("Metal surface is closed");
    }

    @Override
    public void close() {
        context.checkOwner();
        if (handle != 0) {
            context.checkOpen();
            NativeMetal.destroySurface(handle);
            handle = 0;
            context.removeSurface(this);
        }
    }

    public record Stats(long submitted, long completed, long skipped, long inFlight,
                        long peakInFlight, long pixelWidth, long pixelHeight) {}
}
