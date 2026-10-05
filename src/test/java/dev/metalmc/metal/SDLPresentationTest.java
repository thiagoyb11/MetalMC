package dev.metalmc.metal;

import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicReference;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.sdl.SDLError.*;
import static org.lwjgl.sdl.SDLEvents.*;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLKeycode.*;
import static org.lwjgl.sdl.SDLMetal.*;
import static org.lwjgl.sdl.SDLTimer.*;
import static org.lwjgl.sdl.SDLVideo.*;

/** Independent development window; never changes the Minecraft backend. */
public final class SDLPresentationTest {
    private SDLPresentationTest() {}

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        boolean exercise = args.length > 1 && Boolean.parseBoolean(args[1]);
        if (!SDL_IsMainThread()) throw new IllegalStateException("Launch with -XstartOnFirstThread");
        if (!SDL_Init(SDL_INIT_VIDEO)) {
            String error = SDL_GetError();
            SDL_Quit();
            throw new IllegalStateException("SDL video initialization failed: " + error);
        }
        try {
            runWindow(seconds, exercise);
        } finally {
            SDL_Quit();
        }
    }

    private static void runWindow(int seconds, boolean exercise) throws Exception {
        long window = SDL_CreateWindow("MetalMC | F fullscreen | M minimize | H hide | Esc close",
                960, 600, SDL_WINDOW_METAL | SDL_WINDOW_RESIZABLE | SDL_WINDOW_HIGH_PIXEL_DENSITY);
        if (window == 0) throw new IllegalStateException("SDL window creation failed: " + SDL_GetError());
        long view = 0;
        try {
            view = SDL_Metal_CreateView(window);
            if (view == 0) throw new IllegalStateException("SDL Metal view creation failed: " + SDL_GetError());
            long layer = SDL_Metal_GetLayer(view);
            if (layer == 0) throw new IllegalStateException("SDL Metal layer is null: " + SDL_GetError());
            try (MetalContext context = MetalContext.create();
                 MetalSurface surface = context.attachSdlMetalLayer(layer);
                 SDL_Event event = SDL_Event.calloc();
                 MemoryStack stack = MemoryStack.stackPush()) {
                System.out.println("Metal device: " + context.deviceName());
                System.out.println("Direct SDL/CAMetalLayer presentation; BGRA8 sRGB, max 3 frames in flight.");
                System.out.println("Resize the window. F: fullscreen; M: minimize/restore after 2s; H: hide/show after 2s; Esc: close.");
                verifyBoundary(context, surface, layer);
                IntBuffer width = stack.mallocInt(1), height = stack.mallocInt(1);
                IntBuffer logicalWidth = stack.mallocInt(1), logicalHeight = stack.mallocInt(1);
                boolean running = true;
                long start = System.nanoTime(), restoreAt = 0, showAt = 0;
                int step = 0, previousWidth = -1, previousHeight = -1;
                long observed = 0;
                while (running) {
                    while (SDL_PollEvent(event)) {
                        int type = event.type();
                        if (type == SDL_EVENT_QUIT || type == SDL_EVENT_WINDOW_CLOSE_REQUESTED) running = false;
                        if (type >= SDL_EVENT_WINDOW_FIRST && type <= SDL_EVENT_WINDOW_LAST) {
                            System.out.println("Window event " + type + " data=" + event.window().data1() + "," + event.window().data2());
                        }
                        if (type == SDL_EVENT_KEY_DOWN && !event.key().repeat()) {
                            int key = event.key().key();
                            if (key == SDLK_ESCAPE) running = false;
                            if (key == SDLK_F) toggleFullscreen(window);
                            if (key == SDLK_M) {
                                require(SDL_MinimizeWindow(window), "minimize");
                                restoreAt = System.nanoTime() + 2_000_000_000L;
                            }
                            if (key == SDLK_H) {
                                require(SDL_HideWindow(window), "hide");
                                showAt = System.nanoTime() + 2_000_000_000L;
                            }
                        }
                    }
                    if (!running) break;
                    long now = System.nanoTime();
                    double elapsed = (now - start) / 1e9;
                    if (restoreAt != 0 && now >= restoreAt) {
                        require(SDL_RestoreWindow(window), "restore");
                        restoreAt = 0;
                    }
                    if (showAt != 0 && now >= showAt) {
                        require(SDL_ShowWindow(window), "show");
                        showAt = 0;
                    }
                    // Timed operations are requests. Events and pixel sizes record
                    // what the window manager actually did (fullscreen is async).
                    if (exercise && step < 7 && elapsed >= (step + 1) * 2.0) {
                        switch (step) {
                            case 0 -> require(SDL_SetWindowSize(window, 641, 413), "resize small");
                            case 1 -> require(SDL_SetWindowSize(window, 1100, 700), "resize large");
                            case 2 -> { require(SDL_HideWindow(window), "hide"); showAt = now + 1_000_000_000L; }
                            case 3 -> { require(SDL_MinimizeWindow(window), "minimize"); restoreAt = now + 1_000_000_000L; }
                            case 4 -> require(SDL_SetWindowFullscreen(window, true), "enter fullscreen");
                            case 5 -> require(SDL_SetWindowFullscreen(window, false), "leave fullscreen");
                            case 6 -> require(SDL_SetWindowSize(window, 960, 600), "restore size");
                            default -> throw new AssertionError("Unexpected exercise step");
                        }
                        System.out.println("Exercise action " + step + " requested at " + elapsed + "s");
                        step++;
                    }
                    require(SDL_GetWindowSizeInPixels(window, width, height), "query pixel size");
                    int w = width.get(0), h = height.get(0);
                    if (w != previousWidth || h != previousHeight) {
                        require(SDL_GetWindowSize(window, logicalWidth, logicalHeight), "query logical size");
                        System.out.println("Size: logical=" + logicalWidth.get(0) + "x" + logicalHeight.get(0)
                                + " pixels=" + w + "x" + h + " density=" + SDL_GetWindowPixelDensity(window));
                        previousWidth = w;
                        previousHeight = h;
                    }
                    long flags = SDL_GetWindowFlags(window);
                    boolean inactive = (flags & (SDL_WINDOW_MINIMIZED | SDL_WINDOW_HIDDEN | SDL_WINDOW_OCCLUDED)) != 0;
                    surface.presentDiagnosticTriangle(inactive ? 0 : w, inactive ? 0 : h);
                    MetalSurface.Stats stats = surface.stats();
                    check(stats.inFlight() <= 3 && stats.peakInFlight() <= 3, "Frames-in-flight limit exceeded");
                    if (stats.completed() / 120 > observed) {
                        observed = stats.completed() / 120;
                        System.out.println("Frame stats: " + stats);
                    }
                    if (seconds > 0 && elapsed >= seconds) running = false;
                    // Event-loop pacing, not GPU completion synchronization.
                    SDL_Delay(8);
                }
                surface.drain();
                MetalSurface.Stats stats = surface.stats();
                check(stats.submitted() > 0, "No drawable frames were submitted");
                check(stats.completed() == stats.submitted() && stats.inFlight() == 0, "GPU work did not drain");
                if (exercise) check(step == 7, "Exercise interrupted before all actions were requested");
                System.out.println("Final frame stats: " + stats);
                verifyClosed(surface);
                // A second association verifies detach/reattach without replacing SDL.
                try (MetalSurface replacement = context.attachSdlMetalLayer(layer)) {
                    check(!replacement.presentDiagnosticTriangle(0, 0), "Zero-size surface acquired a drawable");
                }
                System.out.println("PASS: GPU submission/completion, frame bound, lifecycle, and JNI boundary checks.");
                System.out.println("Visual correctness and window-manager transitions require observation; logs alone do not prove them.");
            }
        } finally {
            // The try-with-resources scope drains and releases native surfaces first.
            if (view != 0) SDL_Metal_DestroyView(view);
            SDL_DestroyWindow(window);
        }
    }

    private static void verifyBoundary(MetalContext context, MetalSurface surface, long layer) throws Exception {
        check(!surface.presentDiagnosticTriangle(0, 600), "Zero width acquired a drawable");
        check(!surface.presentDiagnosticTriangle(800, 0), "Zero height acquired a drawable");
        expect(IllegalArgumentException.class, () -> surface.presentDiagnosticTriangle(-1, 600));
        expect(IllegalArgumentException.class, () -> surface.presentDiagnosticTriangle(16385, 600));
        expect(IllegalStateException.class, () -> context.attachSdlMetalLayer(layer));
        expect(IllegalStateException.class, () -> NativeMetal.presentTriangle(0, 64, 64));
        expect(IllegalStateException.class, () -> NativeMetal.surfaceStats(0));
        expect(IllegalStateException.class, () -> NativeMetal.destroySurface(0));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread foreign = new Thread(() -> {
            try { surface.presentDiagnosticTriangle(64, 64); }
            catch (Throwable error) { failure.set(error); }
        });
        foreign.start();
        foreign.join();
        check(failure.get() instanceof IllegalStateException, "Foreign-thread surface use was accepted");
    }

    private static void toggleFullscreen(long window) {
        require(SDL_SetWindowFullscreen(window, (SDL_GetWindowFlags(window) & SDL_WINDOW_FULLSCREEN) == 0), "fullscreen");
    }

    private static void verifyClosed(MetalSurface surface) {
        surface.close();
        surface.close();
        expect(IllegalStateException.class, surface::stats);
    }

    private static void require(boolean ok, String operation) {
        if (!ok) throw new IllegalStateException("SDL " + operation + " failed: " + SDL_GetError());
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable operation) {
        try { operation.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type.getSimpleName(), failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
