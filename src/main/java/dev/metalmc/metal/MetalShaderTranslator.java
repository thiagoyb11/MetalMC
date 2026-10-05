package dev.metalmc.metal;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.SpvcEntryPoint;
import org.lwjgl.util.spvc.SpvcMslResourceBinding2;
import org.lwjgl.util.spvc.SpvcReflectedResource;
import static org.lwjgl.util.spvc.Spv.*;
import static org.lwjgl.util.spvc.Spvc.*;

/** Original SPIRV-Cross adapter. Per-call native contexts; returned data owns no native pointers.
 * Initial vertex/fragment subset, not a pipeline or game backend activation.
 */
public final class MetalShaderTranslator {
    public static final int MAX_SPIRV_BYTES = 4 * 1024 * 1024;
    public static final int MAX_MSL_CHARS = 8 * 1024 * 1024;
    public static final int FIRST_UNIFORM_BUFFER = 16, PUSH_CONSTANT_BUFFER = 30;
    private static final int MSL_VERSION = 20300;

    /** Explicit choices: the game integration must establish its source projection convention. */
    public record Options(boolean openGlDepth, boolean flipVertexY) {}
    public enum ResourceKind { UNIFORM_BUFFER, COMBINED_IMAGE_SAMPLER, IMAGE, SAMPLER, PUSH_CONSTANT }
    public record Member(String name, long offset, long byteSize, int arrayStride, int matrixStride, boolean rowMajor) {}
    public record Binding(String name, ResourceKind kind, int descriptorSet, int binding,
            int metalBuffer, int metalTexture, int metalSampler, long byteSize, List<Member> members, boolean imageArrayed) {
        public Binding { members = List.copyOf(members); }
    }
    public record InterfaceVariable(String name, int location, int baseType, int bitWidth, int components) {}
    public record Translation(String label, ShaderType stage, String entryPoint, String msl,
            Options options, List<Binding> bindings, List<InterfaceVariable> inputs, List<InterfaceVariable> outputs) {
        public Translation {
            Objects.requireNonNull(stage); Objects.requireNonNull(entryPoint); Objects.requireNonNull(msl);
            Objects.requireNonNull(options);
            bindings = List.copyOf(bindings); inputs = List.copyOf(inputs); outputs = List.copyOf(outputs);
        }
    }
    private record Resource(int id, int type, String name, ResourceKind kind, int set, int binding,
            long byteSize, List<Member> members, boolean imageArrayed) {}
    private MetalShaderTranslator() {}

    public static Translation translate(BackendRenderPipeline.CreateInfo.Shader shader, Options options) {
        Objects.requireNonNull(shader, "shader");
        SpvModule module = Objects.requireNonNull(shader.module(), "shader module");
        return translate(shader.name(), module.spv(), module.type(), shader.entryPoint(), options);
    }

    /** Does not mutate or retain the caller's buffer/module and does not call module.reflect(). */
    public static Translation translate(String label, ByteBuffer spirv, ShaderType stage, String entryPoint, Options options) {
        Objects.requireNonNull(stage, "shader stage"); Objects.requireNonNull(options, "coordinate options");
        if (entryPoint == null || entryPoint.isEmpty() || entryPoint.indexOf('\0') >= 0 || entryPoint.length() > 1024) {
            throw new IllegalArgumentException("Invalid shader entry point");
        }
        Objects.requireNonNull(spirv, "SPIR-V bytes");
        int length = spirv.remaining();
        if (length < 20 || length > MAX_SPIRV_BYTES || (length & 3) != 0) throw new IllegalArgumentException("SPIR-V size must be 20 bytes..4 MiB and word aligned");
        ByteBuffer owned = MemoryUtil.memAlloc(length).order(ByteOrder.LITTLE_ENDIAN);
        try {
            owned.put(spirv.duplicate()).flip();
            validateWords(owned);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer result = stack.callocPointer(1);
                check(0, spvc_context_create(result), "create SPIRV-Cross context");
                long context = result.get(0);
                try {
                    int model = stage == ShaderType.VERTEX ? SpvExecutionModelVertex : SpvExecutionModelFragment;
                    check(context, spvc_context_parse_spirv(context, owned.asIntBuffer(), length / 4, result), "parse SPIR-V");
                    long ir = result.get(0);
                    check(context, spvc_context_create_compiler(context, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, result), "create MSL compiler");
                    long compiler = result.get(0);
                    selectEntry(context, compiler, entryPoint, model, stack);
                    check(context, spvc_compiler_create_compiler_options(compiler, result), "create options");
                    long nativeOptions = result.get(0);
                    check(context, spvc_compiler_options_set_uint(nativeOptions, SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION), "set MSL 2.3");
                    check(context, spvc_compiler_options_set_uint(nativeOptions, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS), "set macOS");
                    check(context, spvc_compiler_options_set_bool(nativeOptions, SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS, false), "disable argument buffers");
                    check(context, spvc_compiler_options_set_bool(nativeOptions, SPVC_COMPILER_OPTION_FIXUP_DEPTH_CONVENTION, options.openGlDepth()), "set depth convention");
                    check(context, spvc_compiler_options_set_bool(nativeOptions, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, options.flipVertexY()), "set vertex Y convention");
                    check(context, spvc_compiler_install_compiler_options(compiler, nativeOptions), "install options");
                    check(context, spvc_compiler_get_active_interface_variables(compiler, result), "get active variables");
                    long active = result.get(0);
                    check(context, spvc_compiler_set_enabled_interface_variables(compiler, active), "select active variables");
                    spvc_compiler_update_active_builtins(compiler);
                    if (spvc_compiler_has_active_builtin(compiler, SpvBuiltInDrawIndex, SpvStorageClassInput)) {
                        throw new UnsupportedOperationException("DrawIndex requires a dedicated multi-draw shader path");
                    }
                    check(context, spvc_compiler_create_shader_resources_for_active_variables(compiler, result, active), "reflect active resources");
                    long resources = result.get(0);
                    rejectUnsupported(context, resources, stack);
                    PointerBuffer constants = stack.callocPointer(1), count = stack.callocPointer(1);
                    check(context, spvc_compiler_get_specialization_constants(compiler, constants, count), "reflect specialization constants");
                    if (count.get(0) != 0) throw new UnsupportedOperationException("Shader specialization constants are pending");
                    List<Resource> reflected = new ArrayList<>();
                    reflect(context, compiler, resources, SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, ResourceKind.UNIFORM_BUFFER, reflected, stack);
                    reflect(context, compiler, resources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, ResourceKind.COMBINED_IMAGE_SAMPLER, reflected, stack);
                    reflect(context, compiler, resources, SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, ResourceKind.IMAGE, reflected, stack);
                    reflect(context, compiler, resources, SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS, ResourceKind.SAMPLER, reflected, stack);
                    reflect(context, compiler, resources, SPVC_RESOURCE_TYPE_PUSH_CONSTANT, ResourceKind.PUSH_CONSTANT, reflected, stack);
                    reflected.sort(Comparator.comparingInt(Resource::set).thenComparingInt(Resource::binding));
                    List<Binding> bindings = remap(context, compiler, model, reflected, stack);
                    List<InterfaceVariable> inputs = interfaces(context, compiler, resources, SPVC_RESOURCE_TYPE_STAGE_INPUT, stack);
                    List<InterfaceVariable> outputs = interfaces(context, compiler, resources, SPVC_RESOURCE_TYPE_STAGE_OUTPUT, stack);
                    check(context, spvc_compiler_compile(compiler, result), "compile MSL");
                    String source = MemoryUtil.memUTF8(result.get(0));
                    if (source.length() > MAX_MSL_CHARS) throw new IllegalArgumentException("Generated MSL exceeds 8 MiB character cap");
                    if (spvc_compiler_msl_needs_swizzle_buffer(compiler) || spvc_compiler_msl_needs_buffer_size_buffer(compiler)
                            || spvc_compiler_msl_needs_output_buffer(compiler) || spvc_compiler_msl_needs_patch_output_buffer(compiler)
                            || spvc_compiler_msl_needs_input_threadgroup_mem(compiler) || spvc_compiler_msl_is_rasterization_disabled(compiler)) {
                        throw new UnsupportedOperationException("Shader requires unimplemented auxiliary Metal resources");
                    }
                    for (Resource resource : reflected) verifyMapping(compiler, resource, bindings);
                    String function = spvc_compiler_get_cleansed_entry_point_name(compiler, entryPoint, model);
                    if (function == null || function.isEmpty()) throw new IllegalStateException("Missing MSL entry point");
                    return new Translation(label, stage, function, source, options, bindings, inputs, outputs);
                } finally { spvc_context_destroy(context); }
            }
        } finally { MemoryUtil.memFree(owned); }
    }

    private static void validateWords(ByteBuffer bytes) {
        if (bytes.getInt(0) != 0x07230203 || bytes.getInt(16) != 0 || bytes.getInt(12) < 1 || bytes.getInt(12) > 1_000_000) {
            throw new IllegalArgumentException("Invalid little-endian SPIR-V header or ID bound");
        }
        int version = bytes.getInt(4);
        if ((version & 0xffff00ff) != 0x00010000 || ((version >>> 8) & 255) > 6) throw new IllegalArgumentException("Unsupported SPIR-V version");
        for (int offset = 20; offset < bytes.limit();) {
            int words = bytes.getInt(offset) >>> 16;
            if (words == 0 || words > (bytes.limit() - offset) / 4) throw new IllegalArgumentException("Truncated or zero-length SPIR-V instruction");
            offset += words * 4;
        }
    }
    private static void check(long context, int result, String operation) {
        if (result != SPVC_SUCCESS) throw new IllegalArgumentException(operation + " failed (" + result + "): "
                + (context == 0 ? "no context" : spvc_context_get_last_error_string(context)));
    }
    private static void selectEntry(long context, long compiler, String entry, int model, MemoryStack stack) {
        PointerBuffer pointer = stack.callocPointer(1), count = stack.callocPointer(1);
        check(context, spvc_compiler_get_entry_points(compiler, pointer, count), "list entry points");
        int size = boundedCount(count.get(0));
        var entries = SpvcEntryPoint.create(pointer.get(0), size);
        boolean found = false;
        for (int i = 0; i < size; i++) if (entries.get(i).execution_model() == model && entry.equals(entries.get(i).nameString())) found = true;
        if (!found) throw new IllegalArgumentException("SPIR-V has no requested entry point/stage: " + entry);
        check(context, spvc_compiler_set_entry_point(compiler, entry, model), "select entry point");
    }
    private static int boundedCount(long size) {
        if (size < 0 || size > 256) throw new IllegalArgumentException("Shader reflection list exceeds initial 256-entry limit");
        return (int)size;
    }
    private static SpvcReflectedResource.Buffer list(long context, long resources, int kind, MemoryStack stack) {
        PointerBuffer pointer = stack.callocPointer(1), count = stack.callocPointer(1);
        check(context, spvc_resources_get_resource_list_for_type(resources, kind, pointer, count), "reflect resource list");
        int size = boundedCount(count.get(0));
        return size == 0 ? null : SpvcReflectedResource.create(pointer.get(0), size);
    }
    private static void rejectUnsupported(long context, long resources, MemoryStack stack) {
        int[] kinds = {SPVC_RESOURCE_TYPE_STORAGE_BUFFER, SPVC_RESOURCE_TYPE_STORAGE_IMAGE, SPVC_RESOURCE_TYPE_SUBPASS_INPUT,
                SPVC_RESOURCE_TYPE_ATOMIC_COUNTER, SPVC_RESOURCE_TYPE_ACCELERATION_STRUCTURE,
                SPVC_RESOURCE_TYPE_SHADER_RECORD_BUFFER, SPVC_RESOURCE_TYPE_GL_PLAIN_UNIFORM, SPVC_RESOURCE_TYPE_TENSOR};
        // RAY_QUERY is an enum value but not a queryable resource list in the C API.
        // Ray queries depend on acceleration structures, rejected above.
        for (int kind : kinds) if (list(context, resources, kind, stack) != null) throw new UnsupportedOperationException("Unsupported shader resource category: " + kind);
    }
    private static void reflect(long context, long compiler, long resources, int category, ResourceKind kind,
            List<Resource> destination, MemoryStack stack) {
        var list = list(context, resources, category, stack);
        if (list == null) return;
        if (kind == ResourceKind.PUSH_CONSTANT && list.remaining() > 1) throw new UnsupportedOperationException("Multiple push constant blocks are pending");
        for (int i = 0; i < list.remaining(); i++) {
            var resource = list.get(i);
            long type = spvc_compiler_get_type_handle(compiler, resource.type_id());
            if (spvc_type_get_num_array_dimensions(type) != 0) throw new UnsupportedOperationException("Descriptor arrays are pending");
            int set = SPVC_MSL_PUSH_CONSTANT_DESC_SET, binding = SPVC_MSL_PUSH_CONSTANT_BINDING;
            if (kind != ResourceKind.PUSH_CONSTANT) {
                if (!spvc_compiler_has_decoration(compiler, resource.id(), SpvDecorationDescriptorSet)
                        || !spvc_compiler_has_decoration(compiler, resource.id(), SpvDecorationBinding)) throw new IllegalArgumentException("Descriptor lacks set/binding decorations");
                set = spvc_compiler_get_decoration(compiler, resource.id(), SpvDecorationDescriptorSet);
                binding = spvc_compiler_get_decoration(compiler, resource.id(), SpvDecorationBinding);
                if (set < 0 || binding < 0) throw new IllegalArgumentException("Descriptor set/binding exceeds signed integer range");
            }
            long bytes = 0; List<Member> members = List.of(); boolean arrayed = false;
            if (kind == ResourceKind.UNIFORM_BUFFER || kind == ResourceKind.PUSH_CONSTANT) {
                long block = spvc_compiler_get_type_handle(compiler, resource.base_type_id());
                fixedType(compiler, block, 0);
                PointerBuffer size = stack.callocPointer(1);
                check(context, spvc_compiler_get_declared_struct_size(compiler, block, size), "reflect block size");
                bytes = size.get(0);
                if (bytes < 1 || bytes > (kind == ResourceKind.PUSH_CONSTANT ? 4096 : 65536)) throw new UnsupportedOperationException("Shader block exceeds initial 4 KiB push / 64 KiB uniform limit");
                members = members(context, compiler, block, stack);
            } else if (kind != ResourceKind.SAMPLER) {
                if (spvc_type_get_image_dimension(type) != SpvDim2D || spvc_type_get_image_is_depth(type)
                        || spvc_type_get_image_multisampled(type)) throw new UnsupportedOperationException("Initial shader images require non-depth, non-MS 2D/2D-array textures");
                long sampled = spvc_compiler_get_type_handle(compiler, spvc_type_get_image_sampled_type(type));
                if (spvc_type_get_basetype(sampled) != SPVC_BASETYPE_FP32) throw new UnsupportedOperationException("Initial shader images require float sampled components");
                arrayed = spvc_type_get_image_arrayed(type);
            }
            destination.add(new Resource(resource.id(), resource.type_id(), resource.nameString(), kind, set, binding, bytes, members, arrayed));
        }
    }
    private static void fixedType(long compiler, long type, int depth) {
        if (depth > 32) throw new UnsupportedOperationException("Shader struct nesting exceeds 32");
        for (int i = 0; i < spvc_type_get_num_array_dimensions(type); i++) {
            if (!spvc_type_array_dimension_is_literal(type, i) || spvc_type_get_array_dimension(type, i) < 1) throw new UnsupportedOperationException("Runtime/specialized arrays are pending");
        }
        int count = boundedCount(spvc_type_get_num_member_types(type));
        for (int i = 0; i < count; i++) fixedType(compiler, spvc_compiler_get_type_handle(compiler, spvc_type_get_member_type(type, i)), depth + 1);
    }
    private static List<Member> members(long context, long compiler, long type, MemoryStack stack) {
        var result = new ArrayList<Member>();
        int count = boundedCount(spvc_type_get_num_member_types(type));
        var number = stack.callocInt(1); var size = stack.callocPointer(1);
        for (int i = 0; i < count; i++) {
            long member = spvc_compiler_get_type_handle(compiler, spvc_type_get_member_type(type, i));
            check(context, spvc_compiler_type_struct_member_offset(compiler, type, i, number), "reflect member offset");
            long offset = Integer.toUnsignedLong(number.get(0));
            check(context, spvc_compiler_get_declared_struct_member_size(compiler, type, i, size), "reflect member size");
            long bytes = size.get(0); int arrayStride = 0, matrixStride = 0;
            if (spvc_type_get_num_array_dimensions(member) != 0) {
                check(context, spvc_compiler_type_struct_member_array_stride(compiler, type, i, number), "reflect array stride"); arrayStride = number.get(0);
            }
            if (spvc_type_get_columns(member) > 1) {
                check(context, spvc_compiler_type_struct_member_matrix_stride(compiler, type, i, number), "reflect matrix stride"); matrixStride = number.get(0);
            }
            boolean rowMajor = spvc_compiler_has_member_decoration(compiler, spvc_type_get_base_type_id(type), i, SpvDecorationRowMajor);
            result.add(new Member(spvc_compiler_get_member_name(compiler, spvc_type_get_base_type_id(type), i), offset, bytes, arrayStride, matrixStride, rowMajor));
        }
        return result;
    }
    private static List<Binding> remap(long context, long compiler, int model, List<Resource> resources, MemoryStack stack) {
        var result = new ArrayList<Binding>(); var keys = new HashSet<Long>();
        int buffer = FIRST_UNIFORM_BUFFER, texture = 0, sampler = 0;
        for (var resource : resources) {
            long key = ((long)resource.set() << 32) | Integer.toUnsignedLong(resource.binding());
            if (!keys.add(key)) throw new IllegalArgumentException("Duplicate descriptor set/binding");
            int b = -1, t = -1, s = -1;
            switch (resource.kind()) {
                case UNIFORM_BUFFER -> { if (buffer >= PUSH_CONSTANT_BUFFER) throw new UnsupportedOperationException("More than 14 shader uniform buffers"); b = buffer++; }
                case PUSH_CONSTANT -> b = PUSH_CONSTANT_BUFFER;
                case IMAGE -> t = texture++;
                case SAMPLER -> s = sampler++;
                case COMBINED_IMAGE_SAMPLER -> { t = texture++; s = sampler++; }
            }
            if (texture > 16 || sampler > 16) throw new UnsupportedOperationException("More than 16 shader textures/samplers");
            var mapping = SpvcMslResourceBinding2.calloc(stack);
            spvc_msl_resource_binding_init_2(mapping);
            mapping.stage(model).desc_set(resource.set()).binding(resource.binding()).count(1);
            if (b >= 0) mapping.msl_buffer(b);
            if (t >= 0) mapping.msl_texture(t);
            if (s >= 0) mapping.msl_sampler(s);
            check(context, spvc_compiler_msl_add_resource_binding_2(compiler, mapping), "remap Metal resource");
            result.add(new Binding(resource.name(), resource.kind(), resource.set(), resource.binding(), b, t, s, resource.byteSize(), resource.members(), resource.imageArrayed()));
        }
        return result;
    }
    private static void verifyMapping(long compiler, Resource resource, List<Binding> bindings) {
        Binding binding = bindings.stream().filter(b -> b.descriptorSet() == resource.set() && b.binding() == resource.binding()).findFirst().orElseThrow();
        int primary = binding.metalBuffer() >= 0 ? binding.metalBuffer() : binding.metalTexture() >= 0 ? binding.metalTexture() : binding.metalSampler();
        if (spvc_compiler_msl_get_automatic_resource_binding(compiler, resource.id()) != primary
                || (resource.kind() == ResourceKind.COMBINED_IMAGE_SAMPLER
                && spvc_compiler_msl_get_automatic_resource_binding_secondary(compiler, resource.id()) != binding.metalSampler())) {
            throw new IllegalStateException("SPIRV-Cross emitted unexpected Metal binding for " + resource.name());
        }
    }
    private static List<InterfaceVariable> interfaces(long context, long compiler, long resources, int kind, MemoryStack stack) {
        var list = list(context, resources, kind, stack); var result = new ArrayList<InterfaceVariable>();
        if (list == null) return result;
        var locations = new HashSet<Integer>();
        for (int i = 0; i < list.remaining(); i++) {
            var resource = list.get(i); long type = spvc_compiler_get_type_handle(compiler, resource.type_id());
            if (!spvc_compiler_has_decoration(compiler, resource.id(), SpvDecorationLocation)
                    || spvc_type_get_num_array_dimensions(type) != 0 || spvc_type_get_columns(type) != 1
                    || spvc_type_get_bit_width(type) != 32 || spvc_type_get_num_member_types(type) != 0
                    || spvc_compiler_has_decoration(compiler, resource.id(), SpvDecorationComponent)
                    || spvc_compiler_has_decoration(compiler, resource.id(), SpvDecorationIndex)) {
                throw new UnsupportedOperationException("Initial stage interfaces require located 32-bit scalar/vector variables");
            }
            int location = spvc_compiler_get_decoration(compiler, resource.id(), SpvDecorationLocation);
            if (location < 0 || location >= 32 || !locations.add(location)) throw new IllegalArgumentException("Invalid/duplicate stage location");
            result.add(new InterfaceVariable(resource.nameString(), location, spvc_type_get_basetype(type), 32, spvc_type_get_vector_size(type)));
        }
        result.sort(Comparator.comparingInt(InterfaceVariable::location));
        return result;
    }
}
