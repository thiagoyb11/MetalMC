package dev.metalmc.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Exact frontend resource values -> stage-specific Metal bindings -> actual rasterized pixels. */
final class ShaderResourceTest {
    private static final String FULLSCREEN = """
            #version 450
            void main() {
                vec2 points[3] = vec2[3](vec2(-1,-1),vec2(3,-1),vec2(-1,3));
                gl_Position = vec4(points[gl_VertexIndex],0.5,1);
            }
            """;
    private static final String VERTEX = """
            #version 450
            layout(set=0,binding=1) uniform Scene { vec4 multiplier; } scene;
            layout(set=0,binding=3) uniform Shared { vec4 factor; } sharedBlock;
            layout(set=0,binding=2) uniform sampler2D secondary;
            layout(push_constant) uniform Push { vec4 vertexGain; vec4 fragmentGain; } pc;
            layout(location=0) out vec4 tint;
            void main() {
                vec2 points[3] = vec2[3](vec2(-1,-1),vec2(3,-1),vec2(-1,3));
                gl_Position = vec4(points[gl_VertexIndex],0.5,1);
                tint = scene.multiplier * sharedBlock.factor * textureLod(secondary,vec2(0.5),0) * pc.vertexGain;
            }
            """;
    private static final String FRAGMENT = """
            #version 450
            layout(set=0,binding=0) uniform sampler2D primary;
            layout(set=0,binding=2) uniform sampler2D secondary;
            layout(set=0,binding=3) uniform Shared { vec4 factor; } sharedBlock;
            layout(push_constant) uniform Push { vec4 vertexGain; vec4 fragmentGain; } pc;
            layout(location=0) in vec4 tint;
            layout(location=0) out vec4 color;
            void main() { color = tint * sharedBlock.factor * textureLod(primary,vec2(0.5),0)
                * textureLod(secondary,vec2(0.5),0) * pc.fragmentGain; }
            """;
    private static final List<BindGroupLayout.UniformDescription> UNIFORMS = List.of(
            new BindGroupLayout.UniformDescription("primary",UniformType.COMBINED_IMAGE_SAMPLER),
            new BindGroupLayout.UniformDescription("Scene",UniformType.UNIFORM_BUFFER),
            new BindGroupLayout.UniformDescription("secondary",UniformType.COMBINED_IMAGE_SAMPLER),
            new BindGroupLayout.UniformDescription("Shared",UniformType.UNIFORM_BUFFER));
    private ShaderResourceTest() {}

    static void run() throws Exception {
        verifyRemapsSnapshotsAndRetirement();
        verifyArrayAndFormats();
        verifyTransientAndAsync();
        verifyBoundariesAndCancellation();
        verifyPipelineDeclarationsAndJni();
        System.out.println("PASS: per-stage uniform/texture/sampler bindings, push snapshots, mip views/array layers, format pixels, transient uniforms, rebinding, cancellation and retained shader resources.");
    }
    private static SPIRVModule module(String source, ShaderType stage) {
        byte[] bytes = ShaderTranslationTest.compile(source,stage == ShaderType.VERTEX ? shaderc_vertex_shader : shaderc_fragment_shader);
        var owned = MemoryUtil.memAlloc(bytes.length).order(ByteOrder.LITTLE_ENDIAN); owned.put(bytes).flip();
        return new SPIRVModule(owned,stage);
    }
    private static BackendRenderPipeline.CreateInfo info(SPIRVModule vertex, SPIRVModule fragment,
            List<BindGroupLayout.UniformDescription> uniforms, int push) {
        return new BackendRenderPipeline.CreateInfo("shader resource fixture",List.of(
                new BackendRenderPipeline.CreateInfo.Shader("vertex","main",vertex),
                new BackendRenderPipeline.CreateInfo.Shader("fragment","main",fragment)),List.of(),List.of(),uniforms,push,
                new DepthStencilState(CompareOp.ALWAYS_PASS,false),PolygonMode.FILL,false,
                List.of(new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL)),PrimitiveTopology.TRIANGLES);
    }
    private static MetalRenderPipeline pipeline(MetalContext context,String vs,String fs,List<BindGroupLayout.UniformDescription> uniforms,int push) {
        try (var vertex = module(vs,ShaderType.VERTEX); var fragment = module(fs,ShaderType.FRAGMENT)) {
            return (MetalRenderPipeline)context.compilePipeline(info(vertex,fragment,uniforms,push)).finishCompile();
        }
    }
    private static MetalGpuSampler sampler(MetalContext context) {
        return context.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.NEAREST,FilterMode.NEAREST,1,OptionalDouble.empty());
    }
    private static ByteBuffer floats(float... values) {
        var data = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) data.putFloat(value);
        return data.flip();
    }
    private static RenderPassDescriptor descriptor(MetalGpuTextureView target) {
        return RenderPassDescriptor.builder(() -> "shader resources").withColorAttachment(target,Optional.of(TextureCommandTest.color(0,0,0,0))).build();
    }
    private static void waitFor(MetalCommandEncoder encoder) {
        try (var fence = encoder.createFence()) { encoder.submit(); check(fence.awaitCompletion(GpuFence.NO_TIMEOUT),"Resource draw did not complete"); }
    }
    private static void pixels(byte[] bytes,int width,int height,int[] left,int[] right) {
        for (int y=0;y<height;y++) for (int x=0;x<width;x++) for (int c=0;c<4;c++) {
            int expected = (right != null && x >= width/2 ? right : left)[c];
            int actual = Byte.toUnsignedInt(bytes[(y*width+x)*4+c]);
            check(Math.abs(actual-expected)<=1,"Shader resource pixel at " + x + "," + y + " channel " + c + ": " + actual + " vs " + expected);
        }
    }
    private static MetalGpuTexture floatTexture(MetalContext context,MetalCommandEncoder encoder,int layers,int mips,float... value) {
        var texture = context.createTexture("shader sample",1|2|4,GpuFormat.RGBA32_FLOAT,4,4,layers,mips);
        for (int layer=0;layer<layers;layer++) for (int mip=0;mip<mips;mip++) {
            int count = texture.getWidth(mip)*texture.getHeight(mip);
            var data = ByteBuffer.allocate(count*16).order(ByteOrder.LITTLE_ENDIAN);
            for (int i=0;i<count;i++) for (float component : value) data.putFloat(component);
            encoder.writeToTexture(texture,data.flip(),mip,layer,0,0,texture.getWidth(mip),texture.getHeight(mip));
        }
        return texture;
    }
    private static void bind(com.mojang.renderpearl.backend.api.RenderPassBackend pass,GpuBufferSlice scene,GpuBufferSlice shared,
            MetalGpuTextureView primary,MetalGpuTextureView secondary,MetalGpuSampler sampler) {
        pass.setUniform(0,new TextureViewAndSampler(primary,sampler)); pass.setUniform(1,scene);
        pass.setUniform(2,new TextureViewAndSampler(secondary,sampler)); pass.setUniform(3,shared);
    }
    private static void verifyRemapsSnapshotsAndRetirement() {
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder();
                var target = context.createTexture(null,1|2|8,GpuFormat.RGBA8_UNORM,12,8,1,1); var tv = context.createTextureView(target,0,1)) {
            long targetBytes = context.commandStats()[5];
            var p = pipeline(context,VERTEX,FRAGMENT,UNIFORMS,32);
            // Shared uses vertex buffer slot 17 and fragment slot 16. Secondary uses texture/sampler 0 and 1.
            var primary = floatTexture(context,encoder,1,3,0,0,0,0);
            var secondary = floatTexture(context,encoder,1,2,1,0.5f,0.25f,1);
            var pv = context.createTextureView(primary,1,2); var sv = context.createTextureView(secondary,1,1);
            encoder.writeToTexture(primary,floats(0.5f,0.25f,1,1, 0.5f,0.25f,1,1, 0.5f,0.25f,1,1, 0.5f,0.25f,1,1),1,0,0,0,2,2);
            var sampler = sampler(context);
            var scene = context.createBuffer(GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_MAP_READ,floats(9,9,9,9,1,1,0.5f,1));
            var shared = context.createBuffer(GpuBuffer.USAGE_UNIFORM,floats(0.5f,1,1,1));
            var shared2 = context.createBuffer(GpuBuffer.USAGE_UNIFORM,floats(1,1,1,1));
            var pass = encoder.createRenderPass(descriptor(tv)); pass.setPipeline(p);
            bind(pass,scene.slice(16,16),shared.slice(),pv,sv,sampler);
            expect(IllegalStateException.class,() -> pass.draw(3,1,0,0));
            expect(IllegalStateException.class,() -> scene.map(true,false));
            expect(IllegalStateException.class,() -> primary.readDiagnostic(1,0));
            var push = floats(7,7,7,7,1,1,1,1,1,1,1,1,7,7,7,7); push.position(16); push.limit(48);
            pass.pushConstants(push.asReadOnlyBuffer()); check(push.position()==16 && push.limit()==48,"Push changed source range");
            for (int i=16;i<48;i++) push.put(i,(byte)0); // Recorded snapshot must survive source mutation.
            pass.enableScissor(0,0,6,8); pass.draw(3,1,0,0);
            pass.setUniform(3,shared2.slice());
            pass.pushConstants(floats(0.5f,1,1,1,1,0.5f,1,1)); pass.enableScissor(6,0,6,8);
            p.close(); pv.close(); primary.close(); sv.close(); secondary.close(); sampler.close(); scene.close(); shared.close(); shared2.close();
            check(context.pipelineCount()==1 && context.commandStats()[4]>0 && context.commandStats()[5]>targetBytes,"Closed shader resources were not retained");
            pass.draw(3,1,0,0); encoder.submitRenderPass(); waitFor(encoder);
            pixels(target.readDiagnostic(0,0),12,8,new int[]{32,16,8,255},new int[]{64,8,8,255});
            check(context.pipelineCount()==0 && context.commandStats()[4]==0 && context.commandStats()[5]==targetBytes,"Shader resource retirement leaked allocations");
        }
    }
    private static void verifyArrayAndFormats() {
        String fs = """
                #version 450
                layout(set=0,binding=0) uniform sampler2DArray image;
                layout(location=0) out vec4 color;
                void main() { color = textureLod(image,vec3(0.5,0.5,1),0); }
                """;
        var declarations = List.of(new BindGroupLayout.UniformDescription("image",UniformType.COMBINED_IMAGE_SAMPLER));
        try (var context = MetalContext.create(); var encoder = context.createCommandEncoder();
                var target = context.createTexture(null,1|2|8,GpuFormat.RGBA8_UNORM,5,3,1,1); var tv = context.createTextureView(target,0,1);
                var p = pipeline(context,FULLSCREEN,fs,declarations,0); var sampler = sampler(context)) {
            var formats = List.of(GpuFormat.R8_UNORM,GpuFormat.RG8_UNORM,GpuFormat.RGBA8_UNORM,GpuFormat.R32_FLOAT,
                    GpuFormat.RG32_FLOAT,GpuFormat.RGBA16_FLOAT,GpuFormat.RGBA32_FLOAT);
            for (var format : formats) {
                try (var image = context.createTexture(null,1|2|4,format,4,4,2,2); var iv = context.createTextureView(image,1,1)) {
                    // Adjacent layers/mips are zero; only layer one at view-relative mip zero is colored.
                    for (int layer=0;layer<2;layer++) for (int mip=0;mip<2;mip++) {
                        var data = ByteBuffer.allocate(image.getWidth(mip)*image.getHeight(mip)*format.blockSize()).order(ByteOrder.LITTLE_ENDIAN);
                        boolean colored = layer==1 && mip==1;
                        while (data.hasRemaining()) {
                            if (format==GpuFormat.R8_UNORM || format==GpuFormat.RG8_UNORM || format==GpuFormat.RGBA8_UNORM) {
                                for (int c=0;c<format.blockSize();c++) data.put((byte)(colored ? new int[]{128,64,192,255}[c] : 0));
                            } else if (format==GpuFormat.RGBA16_FLOAT) {
                                for (short value : new short[]{0x3800,0x3400,0x3a00,0x3c00}) data.putShort(colored ? value : (short)0);
                            } else {
                                for (int c=0;c<format.blockSize()/4;c++) data.putFloat(colored ? new float[]{0.5f,0.25f,0.75f,1}[c] : 0);
                            }
                        }
                        encoder.writeToTexture(image,data.flip(),mip,layer,0,0,image.getWidth(mip),image.getHeight(mip));
                    }
                    var pass = encoder.createRenderPass(descriptor(tv)); pass.setPipeline(p); pass.setUniform(0,new TextureViewAndSampler(iv,sampler));
                    pass.draw(3,1,0,0); encoder.submitRenderPass(); waitFor(encoder);
                    int channels = format==GpuFormat.R8_UNORM || format==GpuFormat.R32_FLOAT ? 1 : format==GpuFormat.RG8_UNORM || format==GpuFormat.RG32_FLOAT ? 2 : 4;
                    pixels(target.readDiagnostic(0,0),5,3,new int[]{128,channels>1?64:0,channels>2?192:0,255},null);
                }
            }
        }
    }
    private static void verifyTransientAndAsync() {
        String fs = """
                #version 450
                layout(set=0,binding=0) uniform Color { vec4 value; } tint;
                layout(push_constant) uniform Push { vec4 gain; } pc;
                layout(location=0) out vec4 color;
                void main() { color = tint.value * pc.gain; }
                """;
        var uniforms = List.of(new BindGroupLayout.UniformDescription("Color",UniformType.UNIFORM_BUFFER));
        try (var context = MetalContext.create();
                var target = context.createTexture(null,1|2|8,GpuFormat.RGBA8_UNORM,4,4,1,1); var tv = context.createTextureView(target,0,1)) {
            var encoder = context.createCommandEncoder();
            var p = pipeline(context,FULLSCREEN,fs,uniforms,16);
            var transientSlice = encoder.transientMemory().uploadGpu(floats(1,0.5f,0.25f,1),16,GpuBuffer.USAGE_UNIFORM);
            var pass = encoder.createRenderPass(descriptor(tv)); pass.setPipeline(p); pass.setUniform(0,transientSlice); pass.pushConstants(floats(1,1,1,1));
            pass.draw(3,1,0,0); encoder.submitRenderPass(); waitFor(encoder);
            pixels(target.readDiagnostic(0,0),4,4,new int[]{255,128,64,255},null);
            var next = encoder.createRenderPass(descriptor(tv)); next.setPipeline(p);
            expect(IllegalStateException.class,() -> next.setUniform(0,transientSlice));
            encoder.submitRenderPass(); waitFor(encoder); p.close();
            // Finish transient pool ownership so allocation counters can prove no stress-run leaks.
            encoder.close();
            try (var async = context.createCommandEncoder()) {
                for (int i=0;i<16;i++) {
                    var pipeline = pipeline(context,FULLSCREEN,fs,uniforms,16);
                    var buffer = context.createBuffer(GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,16);
                    async.writeToBuffer(buffer.slice(),floats(i/255.0f,0.5f,1,1));
                    var draw = async.createRenderPass(descriptor(tv)); draw.setPipeline(pipeline); draw.setUniform(0,buffer.slice());
                    draw.pushConstants(floats(1,1,1,1)); draw.draw(3,1,0,0); pipeline.close(); buffer.close();
                    async.submitRenderPass(); async.submit();
                }
                waitFor(async); pixels(target.readDiagnostic(0,0),4,4,new int[]{15,128,255,255},null);
                check(context.pipelineCount()==0 && context.commandStats()[4]==0 && context.commandStats()[2]==0,"Async shader bindings/staging did not retire");
                String sampled = """
                        #version 450
                        layout(set=0,binding=0) uniform sampler2D image;
                        layout(location=0) out vec4 color;
                        void main() { color=textureLod(image,vec2(0.5),0); }
                        """;
                var images = List.of(new BindGroupLayout.UniformDescription("image",UniformType.COMBINED_IMAGE_SAMPLER));
                long targetBytes = context.commandStats()[5];
                for (int i=0;i<16;i++) {
                    var pipeline = pipeline(context,FULLSCREEN,sampled,images,0);
                    var texture = floatTexture(context,async,1,1,i/255.0f,0.25f,0.5f,1);
                    var view = context.createTextureView(texture,0,1); var sample = sampler(context);
                    var draw = async.createRenderPass(descriptor(tv)); draw.setPipeline(pipeline); draw.setUniform(0,new TextureViewAndSampler(view,sample));
                    draw.draw(3,1,0,0); pipeline.close(); texture.close(); sample.close(); async.submitRenderPass(); async.submit();
                }
                waitFor(async); pixels(target.readDiagnostic(0,0),4,4,new int[]{15,64,128,255},null);
                check(context.pipelineCount()==0 && context.commandStats()[4]==0 && context.commandStats()[5]==targetBytes && context.commandStats()[2]==0,
                        "Async sampled textures/views/samplers did not retire");
            }
        }
    }
    private static void verifyBoundariesAndCancellation() throws InterruptedException {
        try (var context = MetalContext.create(); var other = MetalContext.create();
                var target = context.createTexture(null,1|2|4|8,GpuFormat.RGBA8_UNORM,4,4,1,1); var tv = context.createTextureView(target,0,1);
                var p = pipeline(context,VERTEX,FRAGMENT,UNIFORMS,32); var sampler = sampler(context); var foreignSampler = sampler(other);
                var scene = context.createBuffer(GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_MAP_WRITE,floats(1,1,1,1));
                var shortBuffer = context.createBuffer(GpuBuffer.USAGE_UNIFORM,4);
                var wrongUsage = context.createBuffer(GpuBuffer.USAGE_VERTEX,16);
                var foreignBuffer = other.createBuffer(GpuBuffer.USAGE_UNIFORM,16)) {
            byte[] original = new byte[64]; Arrays.fill(original,(byte)37); target.uploadDiagnostic(0,0,ByteBuffer.wrap(original));
            var encoder = context.createCommandEncoder();
            var primary = floatTexture(context,encoder,1,1,1,1,1,1); var pv = context.createTextureView(primary,0,1);
            var array = floatTexture(context,encoder,2,1,1,1,1,1); var av = context.createTextureView(array,0,1);
            var depth = context.createTexture(null,4,GpuFormat.D32_FLOAT,1,1,1,1); var dv = context.createTextureView(depth,0,1);
            var missingUsage = context.createTexture(null,1,GpuFormat.RGBA8_UNORM,1,1,1,1); var mv = context.createTextureView(missingUsage,0,1);
            var pass = encoder.createRenderPass(descriptor(tv));
            expect(IllegalStateException.class,() -> pass.setUniform(1,scene.slice()));
            expect(IllegalStateException.class,() -> pass.pushConstants(floats(1,1,1,1,1,1,1,1)));
            pass.setPipeline(p);
            expect(IllegalArgumentException.class,() -> pass.setUniform(-1,scene.slice()));
            expect(IllegalArgumentException.class,() -> pass.setUniform(4,null));
            expect(IllegalArgumentException.class,() -> pass.setUniform(0,scene.slice()));
            expect(IllegalArgumentException.class,() -> pass.setUniform(1,new TextureViewAndSampler(pv,sampler)));
            expect(IllegalArgumentException.class,() -> pass.setUniform(1,"wrong type"));
            expect(IllegalArgumentException.class,() -> pass.setUniform(1,shortBuffer.slice()));
            expect(IllegalArgumentException.class,() -> pass.setUniform(1,new GpuBufferSlice(scene,1,4)));
            expect(IllegalArgumentException.class,() -> pass.setUniform(1,new GpuBufferSlice(scene,0,Long.MAX_VALUE)));
            expect(IllegalStateException.class,() -> pass.setUniform(1,wrongUsage.slice()));
            expect(IllegalArgumentException.class,() -> pass.setUniform(1,foreignBuffer.slice()));
            try (var mapping = scene.map(false,true)) { expect(IllegalStateException.class,() -> pass.setUniform(1,scene.slice())); mapping.data(); }
            expect(IllegalArgumentException.class,() -> pass.setUniform(0,new TextureViewAndSampler(pv,foreignSampler)));
            expect(IllegalArgumentException.class,() -> pass.setUniform(0,new TextureViewAndSampler(av,sampler)));
            expect(IllegalArgumentException.class,() -> pass.setUniform(0,new TextureViewAndSampler(dv,sampler)));
            expect(IllegalStateException.class,() -> pass.setUniform(0,new TextureViewAndSampler(mv,sampler)));
            expect(IllegalArgumentException.class,() -> pass.setUniform(0,new TextureViewAndSampler(tv,sampler)));
            expect(IllegalArgumentException.class,() -> pass.pushConstants(floats(1)));
            bind(pass,scene.slice(),scene.slice(),pv,pv,sampler); pass.pushConstants(floats(1,1,1,1,1,1,1,1));
            pass.setUniform(0,null); expect(IllegalStateException.class,() -> pass.draw(3,1,0,0));
            pass.setUniform(0,new TextureViewAndSampler(pv,sampler)); pass.draw(3,1,0,0);
            pass.setPipeline(p); expect(IllegalStateException.class,() -> pass.draw(3,1,0,0));
            bind(pass,scene.slice(),scene.slice(),pv,pv,sampler); pass.pushConstants(floats(1,1,1,1,1,1,1,1));
            var failure = new AtomicReference<Throwable>();
            var foreign = new Thread(() -> { try { pass.setUniform(1,scene.slice()); } catch (Throwable error) { failure.set(error); } });
            foreign.start(); foreign.join(); check(failure.get() instanceof IllegalStateException,"Resource binding accepted foreign thread");
            primary.close(); expect(IllegalStateException.class,() -> pass.setUniform(0,new TextureViewAndSampler(pv,sampler)));
            pass.draw(3,1,0,0); array.close(); depth.close(); missingUsage.close(); encoder.close();
            check(Arrays.equals(original,target.readDiagnostic(0,0)),"Cancelled resource draw changed target pixels");
            check(context.commandStats()[4]==36,"Cancellation leaked push snapshots/staging");
            check(context.commandStats()[5]>0,"Cancellation released live target unexpectedly");
            expect(IllegalStateException.class,() -> pass.pushConstants(floats(1,1,1,1,1,1,1,1)));
        }
    }
    private static void verifyPipelineDeclarationsAndJni() {
        try (var context = MetalContext.create(); var vertex = module(VERTEX,ShaderType.VERTEX); var fragment = module(FRAGMENT,ShaderType.FRAGMENT)) {
            expect(IllegalArgumentException.class,() -> context.compilePipeline(info(vertex,fragment,UNIFORMS,16)));
            expect(IllegalArgumentException.class,() -> context.compilePipeline(info(vertex,fragment,UNIFORMS,31)));
            expect(IllegalArgumentException.class,() -> context.compilePipeline(info(vertex,fragment,UNIFORMS,4100)));
            expect(IllegalArgumentException.class,() -> context.compilePipeline(info(vertex,fragment,UNIFORMS.subList(0,3),32)));
            var wrongTypes = List.of(UNIFORMS.get(0),new BindGroupLayout.UniformDescription("Scene",UniformType.COMBINED_IMAGE_SAMPLER),UNIFORMS.get(2),UNIFORMS.get(3));
            expect(IllegalArgumentException.class,() -> context.compilePipeline(info(vertex,fragment,wrongTypes,32)));
            var duplicate = List.of(UNIFORMS.get(0),UNIFORMS.get(0),UNIFORMS.get(2),UNIFORMS.get(3));
            expect(IllegalArgumentException.class,() -> context.compilePipeline(info(vertex,fragment,duplicate,32)));
            var texel = List.of(new BindGroupLayout.UniformDescription("texel",GpuFormat.R32_FLOAT));
            expect(UnsupportedOperationException.class,() -> context.compilePipeline(info(vertex,fragment,texel,32)));
            try (var wrongSet = module(VERTEX.replace("set=0,binding=1","set=1,binding=1"),ShaderType.VERTEX)) {
                expect(IllegalArgumentException.class,() -> context.compilePipeline(info(wrongSet,fragment,UNIFORMS,32)));
            }
            try (var wrongDimension = module(FRAGMENT.replace("sampler2D secondary","sampler2DArray secondary")
                    .replace("textureLod(secondary,vec2(0.5),0)","textureLod(secondary,vec3(0.5,0.5,0),0)"),ShaderType.FRAGMENT)) {
                expect(IllegalArgumentException.class,() -> context.compilePipeline(info(vertex,wrongDimension,UNIFORMS,32)));
            }
            try (var integerImage = module("""
                    #version 450
                    layout(set=0,binding=0) uniform usampler2D image;
                    layout(location=0) out vec4 color;
                    void main() { color=vec4(textureLod(image,vec2(0.5),0)); }
                    """,ShaderType.FRAGMENT)) {
                expect(UnsupportedOperationException.class,() -> context.compilePipeline(info(vertex,integerImage,UNIFORMS,32)));
            }
            try (var separate = module("""
                    #version 450
                    layout(set=0,binding=0) uniform texture2D image;
                    layout(set=0,binding=1) uniform sampler sampling;
                    layout(location=0) out vec4 color;
                    void main() { color=texture(sampler2D(image,sampling),vec2(0.5)); }
                    """,ShaderType.FRAGMENT)) {
                expect(UnsupportedOperationException.class,() -> context.compilePipeline(info(vertex,separate,UNIFORMS,32)));
            }
            check(context.pipelineCount()==0,"Invalid descriptor declarations registered a pipeline");
            // Raw JNI validates metadata before compiling shader sources.
            expect(IllegalArgumentException.class,() -> NativeMetal.createRenderPipeline(context.nativeHandle(),null,null,null,null,null,
                    new int[0],new int[0],new int[0],new int[]{7,0,0,0,3},0,0,new int[]{0},new int[]{0,0,0,15,-1,-1,16,0},0));
            expect(IllegalArgumentException.class,() -> NativeMetal.createRenderPipeline(context.nativeHandle(),null,null,null,null,null,
                    new int[0],new int[0],new int[0],new int[]{7,0,0,0,3},0,0,new int[]{0},new int[1],0));
            try (var encoder = context.createCommandEncoder(); var target = context.createTexture(null,1|2|8,GpuFormat.RGBA8_UNORM,2,2,1,1);
                    var tv = context.createTextureView(target,0,1); var p = pipeline(context,VERTEX,FRAGMENT,UNIFORMS,32)) {
                long batch = NativeMetal.createBatch(context.nativeHandle());
                NativeMetal.beginRenderPass(batch,new long[]{tv.attachmentHandle(context)},new int[]{0},new boolean[]{true},new float[4],0,0,false,0,2,2,null);
                NativeMetal.renderPassPipeline(batch,p.commandHandle(context));
                expect(IllegalArgumentException.class,() -> NativeMetal.renderPassPushConstants(batch,ByteBuffer.allocate(32),32));
                expect(IllegalArgumentException.class,() -> NativeMetal.renderPassPushConstants(batch,ByteBuffer.allocateDirect(16),32));
                expect(IllegalArgumentException.class,() -> NativeMetal.renderPassPushConstants(batch,ByteBuffer.allocateDirect(32),16));
                expect(IllegalArgumentException.class,() -> NativeMetal.renderPassClearUniform(batch,64));
                NativeMetal.cancelBatch(batch); NativeMetal.releaseBatch(batch);
                var finished = encoder.createRenderPass(descriptor(tv)); finished.setPipeline(p); encoder.submitRenderPass(); waitFor(encoder);
                expect(IllegalStateException.class,() -> finished.setUniform(0,null));
            }
        }
        expect(IllegalStateException.class,() -> NativeMetal.renderPassClearUniform(0,0));
        expect(IllegalStateException.class,() -> NativeMetal.renderPassUniformTexture(0,0,0,0));
        expect(IllegalStateException.class,() -> NativeMetal.renderPassUniformBuffer(0,0,0,0,16));
        expect(IllegalStateException.class,() -> NativeMetal.renderPassPushConstants(0,ByteBuffer.allocateDirect(16),16));
    }
    private static void expect(Class<? extends Throwable> type,Runnable action) {
        try { action.run(); } catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", received " + error,error);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
