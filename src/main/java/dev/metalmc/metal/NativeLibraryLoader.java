package dev.metalmc.metal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class NativeLibraryLoader {
    private static boolean loaded;

    private NativeLibraryLoader() {}

    // Intentional JNI loading; launch with --enable-native-access=ALL-UNNAMED.
    @SuppressWarnings("restricted")
    static synchronized void load() {
        if (loaded) {
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.startsWith("mac") || !(arch.equals("aarch64") || arch.equals("arm64"))) {
            throw new UnsupportedOperationException("MetalMC requires macOS with an arm64 JVM; found "
                    + os + "/" + arch);
        }
        String resource = "/natives/macos-arm64/libmetalmc.dylib";
        try (InputStream input = NativeLibraryLoader.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing native resource " + resource + "; rebuild MetalMC");
            }
            Path directory = Files.createTempDirectory("metalmc-native-");
            directory.toFile().deleteOnExit();
            Path library = directory.resolve("libmetalmc.dylib");
            library.toFile().deleteOnExit();
            Files.copy(input, library);
            System.load(library.toAbsolutePath().toString());
        } catch (IOException failure) {
            throw new IllegalStateException("Could not extract MetalMC native library", failure);
        }
        int abi = NativeMetal.abiVersion();
        if (abi != 16) {
            throw new LinkageError("MetalMC JNI ABI mismatch: expected 16, received " + abi);
        }
        loaded = true;
    }
}
