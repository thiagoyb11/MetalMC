package dev.metalmc.metal;

/** Packed RGBA8 readback. Row zero is the top row in the Metal render target. */
public final class MetalImage {
    private final int width;
    private final int height;
    private final byte[] rgba;

    MetalImage(int width, int height, byte[] rgba) {
        if (width < 1 || height < 1 || rgba == null
                || rgba.length != Math.multiplyExact(Math.multiplyExact(width, height), 4)) {
            throw new IllegalArgumentException("Invalid RGBA8 readback size");
        }
        this.width = width;
        this.height = height;
        this.rgba = rgba;
    }

    public int width() { return width; }
    public int height() { return height; }

    public int component(int x, int y, int channel) {
        if (x < 0 || y < 0 || x >= width || y >= height || channel < 0 || channel > 3) {
            throw new IndexOutOfBoundsException("Pixel or channel outside image");
        }
        return Byte.toUnsignedInt(rgba[(y * width + x) * 4 + channel]);
    }

    public byte[] copyRgba() { return rgba.clone(); }

    public boolean hasDiagnosticContent() {
        return component(0, 0, 3) == 255
                && component(0, 0, 0) < 15
                && component(0, 0, 1) < 15
                && component(width / 2, height / 2, 3) == 255
                && component(width / 2, height / 2, 0)
                   + component(width / 2, height / 2, 1)
                   + component(width / 2, height / 2, 2) > 180;
    }
}
