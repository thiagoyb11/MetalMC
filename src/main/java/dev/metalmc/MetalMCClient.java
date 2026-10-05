package dev.metalmc;

import dev.metalmc.metal.MetalContext;
import dev.metalmc.metal.MetalImage;
import net.fabricmc.api.ClientModInitializer;

/** Development entrypoint. Backend selection is deliberately a later milestone. */
public final class MetalMCClient implements ClientModInitializer {
    private static final System.Logger LOGGER = System.getLogger("MetalMC");

    @Override
    public void onInitializeClient() {
        LOGGER.log(System.Logger.Level.INFO,
                "MetalMC native foundation loaded; Minecraft's selected backend remains active.");
        if (!Boolean.getBoolean("metalmc.probe")) {
            return;
        }
        try (MetalContext context = MetalContext.create()) {
            MetalImage image = context.renderDiagnosticTriangle(64, 64);
            if (!image.hasDiagnosticContent()) {
                throw new IllegalStateException("Metal readback did not contain the diagnostic triangle");
            }
            LOGGER.log(System.Logger.Level.INFO,
                    "Metal GPU probe passed on {0}: {1}x{2} offscreen image. Game backend unchanged.",
                    context.deviceName(), image.width(), image.height());
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Metal GPU probe failed; Minecraft's selected backend remains active.", failure);
        }
    }
}
