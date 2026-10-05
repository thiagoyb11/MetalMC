package dev.metalmc.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import static org.lwjgl.util.spvc.Spvc.SPVC_BASETYPE_FP32;
import static org.lwjgl.util.spvc.Spvc.SPVC_BASETYPE_INT32;

/** Retained pipeline subset: uniform blocks, combined images/samplers and push constants.
 * Coordinate conventions are explicit; the development backend uses Vulkan depth and no Y flip.
 */
public final class MetalRenderPipeline implements BackendRenderPipeline {
    private final MetalContext context;
    private long handle;
    private final int pushConstantsSize;

    private MetalRenderPipeline(MetalContext context, long handle, int pushConstantsSize) {
        if (handle == 0) throw new IllegalStateException("Native pipeline creation returned null");
        this.context = context; this.handle = handle;
        this.pushConstantsSize = pushConstantsSize;
    }

    static MetalRenderPipeline compile(MetalContext context, CreateInfo info, MetalShaderTranslator.Options options) {
        Objects.requireNonNull(info, "pipeline info"); Objects.requireNonNull(options, "coordinate options");
        var uniforms = List.copyOf(Objects.requireNonNull(info.uniforms(), "uniform declarations"));
        if (uniforms.size() > 64 || info.pushConstantsSize() < 0 || info.pushConstantsSize() > 4096
                || (info.pushConstantsSize() & 3) != 0) throw new IllegalArgumentException("Invalid uniform count/push constant size");
        int[] uniformTypes = new int[uniforms.size()];
        var names = new HashSet<String>();
        for (int i = 0; i < uniforms.size(); i++) {
            var uniform = uniforms.get(i);
            if (uniform.name() == null || uniform.name().isEmpty() || !names.add(uniform.name())) throw new IllegalArgumentException("Invalid/duplicate uniform name");
            uniformTypes[i] = switch (Objects.requireNonNull(uniform.type())) {
                case UNIFORM_BUFFER -> 0; case COMBINED_IMAGE_SAMPLER -> 1;
                default -> throw new UnsupportedOperationException("Texel-buffer uniforms are pending");
            };
        }
        var shaders = List.copyOf(info.shaders());
        if (shaders.size() != 2) throw new IllegalArgumentException("Pipeline requires one vertex and one fragment shader");
        MetalShaderTranslator.Translation vertex = null, fragment = null;
        for (var shader : shaders) {
            var translation = MetalShaderTranslator.translate(shader, options);
            if (translation.stage() == ShaderType.VERTEX) {
                if (vertex != null) throw new IllegalArgumentException("Duplicate vertex shader");
                vertex = translation;
            } else {
                if (fragment != null) throw new IllegalArgumentException("Duplicate fragment shader");
                fragment = translation;
            }
        }
        if (vertex == null || fragment == null) throw new IllegalArgumentException("Missing vertex or fragment stage");
        var resources = new ArrayList<Integer>();
        var imageDimensions = new HashMap<Integer, Boolean>();
        for (var translation : List.of(vertex, fragment)) for (var binding : translation.bindings()) {
            int kind = switch (binding.kind()) {
                case UNIFORM_BUFFER -> 0; case COMBINED_IMAGE_SAMPLER -> 1; case PUSH_CONSTANT -> 2;
                default -> throw new UnsupportedOperationException("Backend contract requires combined image/sampler descriptors");
            };
            int index = binding.binding();
            if (kind == 2) {
                index = -1;
                if (binding.byteSize() > info.pushConstantsSize()) throw new IllegalArgumentException("Push declaration is smaller than shader block");
            } else if (binding.descriptorSet() != 0 || index < 0 || index >= uniforms.size()
                    || uniformTypes[index] != kind) throw new IllegalArgumentException("Shader descriptor does not match frontend set-zero uniform index/type");
            if (kind == 1) {
                Boolean dimension = imageDimensions.putIfAbsent(index, binding.imageArrayed());
                if (dimension != null && dimension != binding.imageArrayed()) throw new IllegalArgumentException("Shader stages disagree on texture dimension");
            }
            for (int value : new int[]{translation.stage() == ShaderType.VERTEX ? 0 : 1, index, kind,
                    binding.metalBuffer(), binding.metalTexture(), binding.metalSampler(), (int)binding.byteSize(), binding.imageArrayed() ? 1 : 0}) resources.add(value);
        }
        for (var input : fragment.inputs()) {
            var output = vertex.outputs().stream().filter(v -> v.location() == input.location()).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Fragment input has no vertex output"));
            if (input.baseType() != output.baseType() || input.bitWidth() != output.bitWidth() || input.components() != output.components()) {
                throw new IllegalArgumentException("Vertex/fragment interface type mismatch");
            }
        }
        var layouts = List.copyOf(info.vertexBuffers());
        var attributes = List.copyOf(info.attribBindings());
        if (layouts.size() > 16 || attributes.size() > 16) throw new UnsupportedOperationException("Initial vertex layout limit is 16");
        var slots = new HashMap<Integer, CreateInfo.VertexBuffer>();
        int[] nativeLayouts = new int[layouts.size() * 3];
        for (int i = 0; i < layouts.size(); i++) {
            var layout = layouts.get(i);
            if (layout.bufferSlot() < 0 || layout.bufferSlot() > 15 || layout.stride() < 4 || layout.stride() > 2048
                    || (layout.stride() & 3) != 0 || layout.stepRate() < 0 || layout.stepRate() > 1
                    || slots.put(layout.bufferSlot(), layout) != null) throw new IllegalArgumentException("Invalid/duplicate vertex layout; step rates 0/1 only");
            nativeLayouts[i * 3] = layout.bufferSlot(); nativeLayouts[i * 3 + 1] = layout.stride(); nativeLayouts[i * 3 + 2] = layout.stepRate();
        }
        var locations = new HashMap<Integer, CreateInfo.AttribBinding>();
        var usedSlots = new HashSet<Integer>();
        int[] nativeAttributes = new int[attributes.size() * 4];
        for (int i = 0; i < attributes.size(); i++) {
            var attribute = attributes.get(i);
            var layout = slots.get(attribute.bufferSlot());
            var format = vertexFormat(attribute.format());
            int bytes = format.bytes();
            if (layout == null || attribute.location() < 0 || attribute.location() > 15 || attribute.offset() < 0
                    || attribute.offset() > layout.stride() || bytes > layout.stride() - attribute.offset()
                    || attribute.offset() % format.alignment() != 0
                    || locations.put(attribute.location(), attribute) != null) throw new IllegalArgumentException("Invalid/duplicate vertex attribute");
            usedSlots.add(attribute.bufferSlot());
            nativeAttributes[i * 4] = attribute.bufferSlot(); nativeAttributes[i * 4 + 1] = attribute.location();
            nativeAttributes[i * 4 + 2] = attribute.offset(); nativeAttributes[i * 4 + 3] = format.code();
        }
        if (!usedSlots.equals(slots.keySet())) throw new IllegalArgumentException("Vertex layout has no attributes");
        for (var input : vertex.inputs()) {
            var attribute = locations.get(input.location());
            if (attribute == null) throw new IllegalArgumentException("Active shader input has no vertex attribute");
            var format = vertexFormat(attribute.format());
            // Vanilla stores normals as four signed bytes but declares a vec3 input.
            boolean normal3 = format.code() == 6 && input.components() == 3;
            if (input.baseType() != format.baseType() || input.bitWidth() != 32
                    || (input.components() != format.components() && !normal3)) {
                throw new IllegalArgumentException("Vertex attribute does not match active shader input");
            }
        }
        // Preserve null slots (MRT holes); List.copyOf intentionally cannot be used here.
        var colors = new ArrayList<>(Objects.requireNonNull(info.colorTargetStates(), "color targets"));
        if (colors.size() > 8) throw new UnsupportedOperationException("At most eight pipeline color slots");
        int[] nativeColors = new int[colors.size() * 9];
        int colorBytes = 0;
        for (int i = 0; i < colors.size(); i++) {
            var color = colors.get(i);
            if (color == null) { nativeColors[i * 9] = -1; continue; }
            if (color.format().hasDepthAspect()) throw new IllegalArgumentException("Color pipeline target has depth format");
            colorBytes += color.format().blockSize();
            if (colorBytes > 32 || (color.writeMask() & ~ColorTargetState.WRITE_ALL) != 0) throw new IllegalArgumentException("Invalid color target budget/write mask");
            nativeColors[i * 9] = MetalGpuTexture.nativeFormat(color.format());
            // Metal uses a different bit order, so encode explicit RGBA bits.
            nativeColors[i * 9 + 1] = (color.writeRed() ? 1 : 0) | (color.writeGreen() ? 2 : 0)
                    | (color.writeBlue() ? 4 : 0) | (color.writeAlpha() ? 8 : 0);
            var blend = Objects.requireNonNull(color.blendFunction(), "blend function");
            if (blend.isPresent()) {
                // R32/RG32/RGBA32 float blending isn't in the initial portable subset.
                if (color.format() == GpuFormat.R32_FLOAT || color.format() == GpuFormat.RG32_FLOAT || color.format() == GpuFormat.RGBA32_FLOAT) {
                    throw new UnsupportedOperationException("32-bit float target blending is pending");
                }
                nativeColors[i * 9 + 2] = 1;
                encodeBlend(nativeColors, i * 9 + 3, blend.orElseThrow().color());
                encodeBlend(nativeColors, i * 9 + 6, blend.orElseThrow().alpha());
            }
        }
        for (var output : fragment.outputs()) {
            if (output.location() >= colors.size() || colors.get(output.location()) == null
                    || output.baseType() != SPVC_BASETYPE_FP32) throw new IllegalArgumentException("Fragment output lacks compatible color target");
        }
        var depth = Objects.requireNonNull(info.depthStencilState(), "depth state");
        if (!Float.isFinite(depth.depthBiasConstant()) || !Float.isFinite(depth.depthBiasScaleFactor())) throw new IllegalArgumentException("Nonfinite depth bias");
        int[] state = {compare(depth.depthTest()), depth.writeDepth() ? 1 : 0,
                Objects.requireNonNull(info.polygonMode()) == PolygonMode.WIREFRAME ? 1 : 0, info.cull() ? 1 : 0, topology(info.primitiveTopology())};
        return new MetalRenderPipeline(context, NativeMetal.createRenderPipeline(context.nativeHandle(), info.name(),
                vertex.msl(), vertex.entryPoint(), fragment.msl(), fragment.entryPoint(), nativeLayouts, nativeAttributes,
                nativeColors, state, depth.depthBiasConstant(), depth.depthBiasScaleFactor(), uniformTypes,
                resources.stream().mapToInt(Integer::intValue).toArray(), info.pushConstantsSize()), info.pushConstantsSize());
    }
    int pushConstantsSize() { return pushConstantsSize; }

    private record VertexFormat(int code, int bytes, int components, int alignment, int baseType) {}
    private static VertexFormat vertexFormat(GpuFormat format) {
        return switch (Objects.requireNonNull(format)) {
            case R32_FLOAT -> new VertexFormat(0, 4, 1, 4, SPVC_BASETYPE_FP32);
            case RG32_FLOAT -> new VertexFormat(1, 8, 2, 4, SPVC_BASETYPE_FP32);
            case RGB32_FLOAT -> new VertexFormat(2, 12, 3, 4, SPVC_BASETYPE_FP32);
            case RGBA32_FLOAT -> new VertexFormat(3, 16, 4, 4, SPVC_BASETYPE_FP32);
            case RGBA8_UNORM -> new VertexFormat(4, 4, 4, 1, SPVC_BASETYPE_FP32);
            case RG16_SINT -> new VertexFormat(5, 4, 2, 2, SPVC_BASETYPE_INT32);
            case RGBA8_SNORM -> new VertexFormat(6, 4, 4, 1, SPVC_BASETYPE_FP32);
            case RGB32_SINT -> new VertexFormat(7, 12, 3, 4, SPVC_BASETYPE_INT32);
            default -> throw new UnsupportedOperationException("Unsupported initial vertex format: " + format);
        };
    }
    private static int compare(CompareOp op) {
        return switch (Objects.requireNonNull(op)) {
            case NEVER_PASS -> 0; case LESS_THAN -> 1; case EQUAL -> 2; case LESS_THAN_OR_EQUAL -> 3;
            case GREATER_THAN -> 4; case NOT_EQUAL -> 5; case GREATER_THAN_OR_EQUAL -> 6; case ALWAYS_PASS -> 7;
        };
    }
    private static int topology(PrimitiveTopology topology) {
        return switch (Objects.requireNonNull(topology)) {
            case POINTS -> 0; case DEBUG_LINES -> 1; case DEBUG_LINE_STRIP -> 2; case TRIANGLES -> 3; case TRIANGLE_STRIP -> 4;
            default -> throw new UnsupportedOperationException("Initial topology unsupported: " + topology);
        };
    }
    private static void encodeBlend(int[] values, int offset, BlendEquation equation) {
        Objects.requireNonNull(equation);
        values[offset] = factor(equation.sourceFactor()); values[offset + 1] = factor(equation.destFactor());
        values[offset + 2] = switch (Objects.requireNonNull(equation.op())) {
            case ADD -> 0; case SUBTRACT -> 1; case REVERSE_SUBTRACT -> 2; case MIN -> 3; case MAX -> 4;
        };
    }
    private static int factor(BlendFactor factor) {
        return switch (Objects.requireNonNull(factor)) {
            case ZERO -> 0; case ONE -> 1; case SRC_COLOR -> 2; case ONE_MINUS_SRC_COLOR -> 3;
            case SRC_ALPHA -> 4; case ONE_MINUS_SRC_ALPHA -> 5; case DST_COLOR -> 6; case ONE_MINUS_DST_COLOR -> 7;
            case DST_ALPHA -> 8; case ONE_MINUS_DST_ALPHA -> 9; case SRC_ALPHA_SATURATE -> 10;
            default -> throw new UnsupportedOperationException("Constant blend factors require an unimplemented blend constant contract");
        };
    }
    long commandHandle(MetalContext expected) {
        context.checkOpen();
        if (handle == 0) throw new IllegalStateException("Metal pipeline is closed");
        if (context != expected) throw new IllegalArgumentException("Pipeline belongs to another Metal device");
        return handle;
    }
    @Override public boolean isClosed() { context.checkOwner(); return handle == 0; }
    @Override public void close() {
        context.checkOwner();
        if (handle != 0) { NativeMetal.destroyRenderPipeline(handle); handle = 0; context.removePipeline(this); }
    }
}
