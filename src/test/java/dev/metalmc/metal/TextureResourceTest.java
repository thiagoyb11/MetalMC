package dev.metalmc.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.metalmc.backend.MetalGpuDeviceBackend;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;

/** Actual GPU upload/readback and sampled view/sampler validation, no mocks. */
final class TextureResourceTest {
    private static final int COPY = GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
    private TextureResourceTest() {}

    static void run() throws Exception {
        verifyFormatsAndArrays();
        verifySampling();
        verifyOwnershipAndAdapter();
        verifyNativeBoundary();
        System.out.println("PASS: private textures, eight format roundtrips, mip/layer isolation, GPU view/sampler sampling and cleanup.");
    }

    private static void verifyFormatsAndArrays() {
        GpuFormat[] formats = {GpuFormat.R8_UNORM, GpuFormat.RG8_UNORM, GpuFormat.RGBA8_UNORM,
                GpuFormat.R32_FLOAT, GpuFormat.RG32_FLOAT, GpuFormat.RGBA16_FLOAT,
                GpuFormat.RGBA32_FLOAT, GpuFormat.D32_FLOAT};
        try (var context = MetalContext.create()) {
            for (GpuFormat format : formats) {
                try (var texture = context.createTexture("mips 🧪 " + format, COPY | GpuTexture.USAGE_RENDER_ATTACHMENT,
                        format, 7, 3, 3, 3)) {
                    check(texture.getWidth(2) == 1 && texture.getHeight(2) == 1, "Small mip dimensions must clamp to one");
                    check(texture.getFormat() == format && texture.getDepthOrLayers() == 3, "Texture metadata mismatch");
                    byte[][] expected = new byte[9][];
                    for (int mip = 0; mip < 3; mip++) {
                        for (int layer = 0; layer < 3; layer++) {
                            byte[] data = texels(format, texture.getWidth(mip) * texture.getHeight(mip), mip * 3 + layer);
                            expected[mip * 3 + layer] = data;
                            // Source begins at a nonzero position and includes unused tail bytes.
                            ByteBuffer input = ByteBuffer.allocate(data.length + 8);
                            input.position(3).put(data).limit(3 + data.length).position(3);
                            texture.uploadDiagnostic(mip, layer, input);
                            check(input.position() == 3 && input.limit() == data.length + 3, "Texture upload changed source range");
                        }
                    }
                    for (int mip = 0; mip < 3; mip++) for (int layer = 0; layer < 3; layer++) {
                        check(Arrays.equals(expected[mip * 3 + layer], texture.readDiagnostic(mip, layer)),
                                "GPU roundtrip/mip/layer isolation failed: " + format + " mip=" + mip + " layer=" + layer);
                    }
                    // A real render pass clears only the selected mip/layer.
                    texture.clearDiagnostic(1, 1, 0.5f, 0.25f, 1, 1);
                    verifyClear(format, texture.readDiagnostic(1, 1));
                    check(Arrays.equals(expected[3], texture.readDiagnostic(1, 0)), "Clear modified another layer");
                    check(Arrays.equals(expected[1], texture.readDiagnostic(0, 1)), "Clear modified another mip");
                    try (var view = context.createTextureView(texture, 1, 2)) {
                        check(view.texture() == texture && view.baseMipLevel() == 1 && view.mipLevels() == 2,
                                "View metadata mismatch");
                        check(view.getWidth(0) == 3 && view.getHeight(0) == 1 && view.getWidth(1) == 1, "View dimensions mismatch");
                        expect(IllegalArgumentException.class, () -> view.getWidth(2));
                    }
                    expect(IllegalArgumentException.class, () -> texture.getWidth(-1));
                    expect(IllegalArgumentException.class, () -> texture.getHeight(3));
                    expect(IllegalArgumentException.class, () -> texture.readDiagnostic(0, 3));
                    expect(IllegalArgumentException.class, () -> texture.uploadDiagnostic(0, 0, ByteBuffer.allocate(1)));
                    expect(IllegalArgumentException.class, () -> context.createTextureView(texture, 2, Integer.MAX_VALUE));
                    expect(IllegalArgumentException.class, () -> context.createTextureView(texture, -1, 1));
                    expect(IllegalArgumentException.class, () -> context.createTextureView(texture, 0, 0));
                }
            }
            expect(UnsupportedOperationException.class, () -> context.createTexture(null, COPY, GpuFormat.RGB8_UNORM, 4, 4, 1, 1));
            expect(UnsupportedOperationException.class, () -> context.createTexture(null,
                    COPY | GpuTexture.USAGE_CUBEMAP_COMPATIBLE, GpuFormat.RGBA8_UNORM, 4, 4, 6, 1));
            expect(UnsupportedOperationException.class, () -> context.createTexture(null, 0, GpuFormat.R8_UNORM, 1, 1, 1, 1));
            expect(IllegalArgumentException.class, () -> context.createTexture(null, COPY, GpuFormat.R8_UNORM, 0, 4, 1, 1));
            expect(IllegalArgumentException.class, () -> context.createTexture(null, COPY, GpuFormat.R8_UNORM, 4, 4, 257, 1));
            expect(IllegalArgumentException.class, () -> context.createTexture(null, COPY, GpuFormat.R8_UNORM, 4, 4, 1, 4));
            expect(IllegalArgumentException.class, () -> context.createTexture(null, COPY, GpuFormat.RGBA32_FLOAT, 8192, 8192, 256, 14));
            try (var texture = context.createTexture(null, GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.R8_UNORM, 1, 1, 1, 1)) {
                expect(IllegalStateException.class, () -> texture.uploadDiagnostic(0, 0, ByteBuffer.allocate(1)));
                expect(IllegalStateException.class, () -> texture.readDiagnostic(0, 0));
                expect(IllegalStateException.class, () -> texture.clearDiagnostic(0, 0, 0, 0, 0, 1));
            }
        }
    }

    private static void verifyClear(GpuFormat format, byte[] bytes) {
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        float[] color = {0.5f, 0.25f, 1, 1};
        int components = format == GpuFormat.D32_FLOAT ? 1 : format.componentCount();
        int index = 0;
        while (data.hasRemaining()) {
            float expected = color[index++ % components];
            if (format == GpuFormat.RGBA16_FLOAT) {
                int half = Short.toUnsignedInt(data.getShort());
                int encoded = expected == 0.5f ? 0x3800 : expected == 0.25f ? 0x3400 : 0x3c00;
                check(half == encoded, "RGBA16F clear encoding incorrect");
            } else if (format == GpuFormat.R32_FLOAT || format == GpuFormat.RG32_FLOAT
                    || format == GpuFormat.RGBA32_FLOAT || format == GpuFormat.D32_FLOAT) {
                check(data.getFloat() == expected, "Float/depth clear incorrect: " + format);
            } else {
                check(Math.abs(Byte.toUnsignedInt(data.get()) - Math.round(expected * 255)) <= 1,
                        "UNORM clear incorrect: " + format);
            }
        }
    }

    private static byte[] texels(GpuFormat format, int pixels, int seed) {
        ByteBuffer data = ByteBuffer.allocate(pixels * format.blockSize()).order(ByteOrder.nativeOrder());
        if (format == GpuFormat.RGBA16_FLOAT) {
            // Valid, exactly representable half values, varying by layer and mip.
            while (data.hasRemaining()) data.putShort((short)(0x3000 + seed * 32 + data.position()));
        } else if (format == GpuFormat.R32_FLOAT || format == GpuFormat.RG32_FLOAT
                || format == GpuFormat.RGBA32_FLOAT || format == GpuFormat.D32_FLOAT) {
            while (data.hasRemaining()) data.putFloat((seed + 1) / 16.0f + (data.position() % 16) / 64.0f);
        } else {
            while (data.hasRemaining()) data.put((byte)(seed * 19 + data.position() * 7));
        }
        return data.array();
    }

    private static void verifySampling() {
        try (var context = MetalContext.create();
             var texture = context.createTexture("sampled mips", COPY | GpuTexture.USAGE_TEXTURE_BINDING,
                     GpuFormat.RGBA8_UNORM, 4, 4, 1, 3);
             var repeat = context.createSampler(AddressMode.REPEAT, AddressMode.REPEAT,
                     FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
             var clamp = context.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                     FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
             var linear = context.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                     FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
             var lodZero = context.createSampler(AddressMode.REPEAT, AddressMode.REPEAT,
                     FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.of(0))) {
            ByteBuffer pixels = ByteBuffer.allocate(64);
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                pixels.put((byte)(x < 2 ? 255 : 0)).put((byte)(x >= 2 ? 255 : 0))
                        .put((byte)(y >= 2 ? 255 : 0)).put((byte)255);
            }
            pixels.flip();
            texture.uploadDiagnostic(0, 0, pixels);
            texture.uploadDiagnostic(1, 0, ByteBuffer.wrap(new byte[] {
                0, 0, -1, -1, 0, 0, -1, -1, 0, 0, -1, -1, 0, 0, -1, -1}));
            texture.uploadDiagnostic(2, 0, ByteBuffer.wrap(new byte[] {-1, -1, -1, -1}));
            try (var full = context.createTextureView(texture, 0, 3);
                 var tail = context.createTextureView(texture, 1, 2)) {
                color(full.sampleDiagnostic(repeat, 1.125f, 0.125f, 0), 1, 0, 0, "U repeat");
                color(full.sampleDiagnostic(clamp, 1.125f, 0.125f, 0), 0, 1, 0, "U clamp");
                color(full.sampleDiagnostic(repeat, 0.125f, 1.125f, 0), 1, 0, 0, "V repeat");
                color(full.sampleDiagnostic(clamp, 0.125f, 1.125f, 0), 1, 0, 1, "V clamp");
                color(full.sampleDiagnostic(linear, 0.5f, 0.125f, 0), 0.5f, 0.5f, 0, "Linear filtering");
                color(full.sampleDiagnostic(repeat, 0.125f, 0.125f, 0.5f), 0.5f, 0, 0.5f, "Mip interpolation");
                color(tail.sampleDiagnostic(repeat, 0.125f, 0.125f, 0), 0, 0, 1, "View base mip");
                color(tail.sampleDiagnostic(repeat, 0.125f, 0.125f, 1), 1, 1, 1, "View relative LOD");
                color(full.sampleDiagnostic(lodZero, 0.125f, 0.125f, 2), 1, 0, 0, "Max LOD clamp");
                expect(IllegalArgumentException.class, () -> full.sampleDiagnostic(repeat, Float.NaN, 0, 0));
                expect(IllegalArgumentException.class, () -> full.sampleDiagnostic(repeat, 0, 0, -1));
                try (var other = MetalContext.create(); var foreign = other.createSampler(AddressMode.REPEAT,
                        AddressMode.REPEAT, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty())) {
                    expect(IllegalArgumentException.class, () -> full.sampleDiagnostic(foreign, 0, 0, 0));
                }
            }
            check(repeat.getAddressModeU() == AddressMode.REPEAT && repeat.getMinFilter() == FilterMode.NEAREST
                    && repeat.getMaxAnisotropy() == 1 && repeat.getMaxLod().isEmpty(), "Sampler metadata mismatch");
            expect(IllegalArgumentException.class, () -> context.createSampler(AddressMode.REPEAT, AddressMode.REPEAT,
                    FilterMode.LINEAR, FilterMode.LINEAR, 17, OptionalDouble.empty()));
            expect(IllegalArgumentException.class, () -> context.createSampler(AddressMode.REPEAT, AddressMode.REPEAT,
                    FilterMode.LINEAR, FilterMode.LINEAR, 0, OptionalDouble.empty()));
            for (double invalid : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
                expect(IllegalArgumentException.class, () -> context.createSampler(AddressMode.REPEAT, AddressMode.REPEAT,
                        FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.of(invalid)));
            }
            try (var anisotropic = context.createSampler(AddressMode.REPEAT, AddressMode.REPEAT,
                    FilterMode.LINEAR, FilterMode.LINEAR, 16, OptionalDouble.of(2))) {
                check(anisotropic.getMaxAnisotropy() == 16, "Anisotropic sampler creation failed");
            }
        }
    }

    private static void verifyOwnershipAndAdapter() throws Exception {
        var backend = MetalGpuDeviceBackend.createForDevelopment();
        var texture = (MetalGpuTexture)backend.createTexture("adapter", COPY, GpuFormat.RGBA8_UNORM, 2, 2, 1, 2);
        var view = backend.createTextureView(texture, 0, 2);
        var sampler = backend.createSampler(AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.LINEAR, 1, OptionalDouble.empty());
        try (var other = MetalContext.create()) {
            expect(IllegalArgumentException.class, () -> other.createTextureView(texture, 0, 1));
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread foreign = new Thread(() -> {
            try { texture.readDiagnostic(0, 0); } catch (Throwable caught) { failure.set(caught); }
        });
        foreign.start();
        foreign.join();
        check(failure.get() instanceof IllegalStateException, "Foreign-thread texture access accepted");
        texture.close();
        texture.close();
        check(view.isClosed(), "Texture close did not invalidate its view");
        expect(IllegalStateException.class, () -> texture.readDiagnostic(0, 0));
        view.close();
        var autoTexture = backend.createTexture(null, COPY, GpuFormat.R8_UNORM, 2, 2, 1, 1);
        var autoView = backend.createTextureView(autoTexture, 0, 1);
        backend.close();
        backend.close();
        check(autoTexture.isClosed() && autoView.isClosed() && sampler.isClosed(), "Device did not close child resources");
        expect(IllegalStateException.class, () -> backend.createTexture(null, COPY, GpuFormat.R8_UNORM, 2, 2, 1, 1));
    }

    private static void verifyNativeBoundary() {
        long context = NativeMetal.createContext();
        long texture = NativeMetal.createTexture(context, "native 🧪", COPY | GpuTexture.USAGE_TEXTURE_BINDING, 3, 4, 4, 1, 3);
        long view = NativeMetal.createTextureView(texture, 1, 2);
        long sampler = NativeMetal.createSampler(context, true, true, false, false, 1, Float.MAX_VALUE);
        expect(IllegalStateException.class, () -> NativeMetal.destroyContext(context));
        expect(IllegalStateException.class, () -> NativeMetal.destroyTexture(texture));
        expect(IllegalArgumentException.class, () -> NativeMetal.createTextureView(texture, Integer.MAX_VALUE, Integer.MAX_VALUE));
        expect(IllegalArgumentException.class, () -> NativeMetal.transferTextureDiagnostic(texture, 0, 0, ByteBuffer.allocate(64), 64, true));
        expect(IllegalArgumentException.class, () -> NativeMetal.transferTextureDiagnostic(texture, 0, 0, ByteBuffer.allocateDirect(4), 64, true));
        expect(IllegalArgumentException.class, () -> NativeMetal.transferTextureDiagnostic(texture, 0, 0,
                ByteBuffer.allocateDirect(64).asReadOnlyBuffer(), 64, false));
        expect(IllegalArgumentException.class, () -> NativeMetal.transferTextureDiagnostic(texture, 3, 0, ByteBuffer.allocateDirect(64), 64, true));
        expect(UnsupportedOperationException.class, () -> NativeMetal.createTexture(context, null, COPY, 999, 4, 4, 1, 1));
        expect(IllegalArgumentException.class, () -> NativeMetal.createTexture(context, null, COPY, 3, Integer.MAX_VALUE, 4, 1, 1));
        expect(IllegalArgumentException.class, () -> NativeMetal.createSampler(context, true, true, true, true, 17, 0));
        NativeMetal.destroyTextureView(view);
        expect(IllegalStateException.class, () -> NativeMetal.sampleTextureDiagnostic(view, sampler, 0, 0, 0));
        expect(IllegalStateException.class, () -> NativeMetal.destroyTextureView(view));
        NativeMetal.destroyTexture(texture);
        expect(IllegalStateException.class, () -> NativeMetal.createTextureView(texture, 0, 1));
        expect(IllegalStateException.class, () -> NativeMetal.destroyTexture(texture));
        NativeMetal.destroySampler(sampler);
        expect(IllegalStateException.class, () -> NativeMetal.destroySampler(sampler));
        NativeMetal.destroyContext(context);
    }

    private static void color(float[] result, float r, float g, float b, String message) {
        float[] expected = {r, g, b, 1};
        for (int i = 0; i < 4; i++) check(Math.abs(result[i] - expected[i]) < 0.015f, message + ": " + Arrays.toString(result));
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
