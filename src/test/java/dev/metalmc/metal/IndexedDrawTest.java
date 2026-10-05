package dev.metalmc.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Real GPU index offsets/base vertex/instances and both frontend multi-draw parameter layouts. */
final class IndexedDrawTest {
    private static final String VERTEX = """
            #version 450
            layout(location=0) in vec3 position;
            layout(location=1) in vec4 vertexColor;
            layout(location=0) out vec4 tint;
            void main() { gl_Position=vec4(position,1); tint=vertexColor; }
            """;
    private static final String FRAGMENT = """
            #version 450
            layout(location=0) in vec4 tint;
            layout(location=0) out vec4 color;
            void main() { color=tint; }
            """;
    private static final List<BackendRenderPipeline.CreateInfo.VertexBuffer> LAYOUTS = List.of(new BackendRenderPipeline.CreateInfo.VertexBuffer(3,16,0));
    private static final List<BackendRenderPipeline.CreateInfo.AttribBinding> ATTRIBUTES = List.of(
            new BackendRenderPipeline.CreateInfo.AttribBinding(3,0,0,GpuFormat.RGB32_FLOAT),
            new BackendRenderPipeline.CreateInfo.AttribBinding(3,1,12,GpuFormat.RGBA8_UNORM));
    private IndexedDrawTest() {}
    static void run() throws Exception {
        verifyIndexTypesAndOffsets();
        verifyInstancesAndResources();
        verifyMultiDrawFormatsAndAtomicity();
        verifyCancellationAndBoundaries();
        verifyAsyncRetirement();
        System.out.println("PASS: UInt16/UInt32 indexed pixels, firstIndex/signed baseVertex/instances, all direct multi-draw layouts, parameter snapshots, atomic preflight, cancellation and retained index resources.");
    }
    private static SPIRVModule module(String source,ShaderType type) {
        byte[] bytes=ShaderTranslationTest.compile(source,type==ShaderType.VERTEX?shaderc_vertex_shader:shaderc_fragment_shader);
        var owned=MemoryUtil.memAlloc(bytes.length).order(ByteOrder.LITTLE_ENDIAN); owned.put(bytes).flip();
        return new SPIRVModule(owned,type);
    }
    private static BackendRenderPipeline.CreateInfo info(SPIRVModule vertex,SPIRVModule fragment,
            List<BackendRenderPipeline.CreateInfo.VertexBuffer> layouts,List<BackendRenderPipeline.CreateInfo.AttribBinding> attributes,
            List<BindGroupLayout.UniformDescription> uniforms,int push) {
        return new BackendRenderPipeline.CreateInfo("indexed fixture",List.of(new BackendRenderPipeline.CreateInfo.Shader("vertex","main",vertex),
                new BackendRenderPipeline.CreateInfo.Shader("fragment","main",fragment)),layouts,attributes,uniforms,push,
                new DepthStencilState(CompareOp.ALWAYS_PASS,false),PolygonMode.FILL,false,
                List.of(new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL)),PrimitiveTopology.TRIANGLES);
    }
    private static MetalRenderPipeline pipeline(MetalContext context) {
        try (var vertex=module(VERTEX,ShaderType.VERTEX);var fragment=module(FRAGMENT,ShaderType.FRAGMENT)) {
            return (MetalRenderPipeline)context.compilePipeline(info(vertex,fragment,LAYOUTS,ATTRIBUTES,List.of(),0)).finishCompile();
        }
    }
    private static ByteBuffer vertices(int prefix,int[]... colors) {
        var data=ByteBuffer.allocate(prefix+colors.length*48).order(ByteOrder.LITTLE_ENDIAN);data.position(prefix);
        for (var color:colors) for (var xy:new float[][]{{-1,-1},{3,-1},{-1,3}}) {
            data.putFloat(xy[0]).putFloat(xy[1]).putFloat(0.5f);
            for (int c:color) data.put((byte)c);
        }
        return data.flip();
    }
    private static ByteBuffer indices(IndexType type,int... values) {
        var data=ByteBuffer.allocate((values.length*type.bytes+3)&~3).order(ByteOrder.LITTLE_ENDIAN);
        for (int value:values) { if (type==IndexType.SHORT) data.putShort((short)value); else data.putInt(value); }
        data.position(data.capacity()); return data.flip();
    }
    private static ByteBuffer floats(float... values) {
        var data=ByteBuffer.allocate(values.length*4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value:values) data.putFloat(value);return data.flip();
    }
    private static RenderPassDescriptor descriptor(MetalGpuTextureView view) {
        return RenderPassDescriptor.builder(() -> "indexed pixels").withColorAttachment(view,Optional.of(TextureCommandTest.color(0,0,1,1))).build();
    }
    private static void waitFor(MetalCommandEncoder encoder) {
        try(var fence=encoder.createFence()){encoder.submit();check(fence.awaitCompletion(GpuFence.NO_TIMEOUT),"Indexed submission did not complete");}
    }
    private static void pixels(byte[] bytes,int width,int height,int[] left,int[] right) {
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)for(int c=0;c<4;c++) {
            int expected=(right!=null&&x>=width/2?right:left)[c];int actual=Byte.toUnsignedInt(bytes[(y*width+x)*4+c]);
            check(Math.abs(actual-expected)<=1,"Indexed pixel " + x + "," + y + " channel " + c + ": " + actual + " vs " + expected);
        }
    }
    private static void verifyIndexTypesAndOffsets() {
        for(var type:List.of(IndexType.SHORT,IndexType.INT)) {
            try(var context=MetalContext.create();var encoder=context.createCommandEncoder();
                    var target=context.createTexture(null,1|2|8,GpuFormat.RGBA8_UNORM,12,8,1,1);var tv=context.createTextureView(target,0,1)) {
                var p=pipeline(context);var v=context.createBuffer(GpuBuffer.USAGE_VERTEX,vertices(16,new int[]{0,255,0,255},new int[]{255,0,0,255}));
                var data=indices(type,99,0,1,2,3,4,5);
                var index=context.createBuffer(GpuBuffer.USAGE_INDEX|GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,data.remaining());
                encoder.writeToBuffer(index.slice(),data);Arrays.fill(data.array(),(byte)0);
                var pass=encoder.createRenderPass(descriptor(tv));pass.setPipeline(p);pass.setVertexBuffer(3,v.slice(16,96));pass.setIndexBuffer(index,type);
                expect(IllegalArgumentException.class,() -> pass.drawIndexed(3,1,Integer.MAX_VALUE,0,0));
                expect(IllegalArgumentException.class,() -> pass.drawIndexed(3,1,1,Integer.MAX_VALUE,0));
                expect(IllegalStateException.class,() -> index.map(true,false));
                p.close();v.close();index.close();
                expect(IllegalStateException.class,() -> pass.setIndexBuffer(index,type));
                pass.enableScissor(0,0,6,8);pass.drawIndexed(3,1,1,3,0);
                pass.enableScissor(6,0,6,8);pass.drawIndexed(3,1,4,-3,0);
                encoder.submitRenderPass();waitFor(encoder);
                pixels(target.readDiagnostic(0,0),12,8,new int[]{255,0,0,255},new int[]{0,255,0,255});
                check(context.pipelineCount()==0&&context.commandStats()[4]==0,"Closed index resources did not retire");
            }
        }
    }
    private static void verifyInstancesAndResources() {
        String vertex="""
                #version 450
                layout(location=0) in vec3 position;
                layout(location=1) in vec4 vertexColor;
                layout(location=2) in vec4 instanceColor;
                layout(location=0) out vec4 tint;
                void main(){gl_Position=vec4(position,1);tint=instanceColor+vertexColor*0.0;}
                """;
        String fragment="""
                #version 450
                layout(set=0,binding=0) uniform Gain {vec4 value;} gain;
                layout(push_constant) uniform Push {vec4 value;} pc;
                layout(location=0) in vec4 tint;
                layout(location=0) out vec4 color;
                void main(){color=tint*gain.value*pc.value;}
                """;
        try(var context=MetalContext.create();var encoder=context.createCommandEncoder();
                var target=context.createTexture(null,1|2|8,GpuFormat.RGBA8_UNORM,8,6,1,1);var tv=context.createTextureView(target,0,1);
                var vs=module(vertex,ShaderType.VERTEX);var fs=module(fragment,ShaderType.FRAGMENT);
                var index=context.createBuffer(GpuBuffer.USAGE_INDEX,indices(IndexType.INT,0,1,2));
                var gain=context.createBuffer(GpuBuffer.USAGE_UNIFORM,floats(1,0.5f,1,1))) {
            var layouts=List.of(LAYOUTS.getFirst(),new BackendRenderPipeline.CreateInfo.VertexBuffer(7,16,1));
            var attrs=List.of(ATTRIBUTES.get(0),ATTRIBUTES.get(1),new BackendRenderPipeline.CreateInfo.AttribBinding(7,2,0,GpuFormat.RGBA32_FLOAT));
            try(var p=(MetalRenderPipeline)context.compilePipeline(info(vs,fs,layouts,attrs,
                    List.of(new BindGroupLayout.UniformDescription("Gain",UniformType.UNIFORM_BUFFER)),16)).finishCompile()) {
                var memory=encoder.transientMemory();var v=memory.uploadGpu(vertices(0,new int[]{0,0,0,0}),16,GpuBuffer.USAGE_VERTEX);
                var instances=memory.uploadGpu(floats(1,0,0,1, 0,1,1,1),16,GpuBuffer.USAGE_VERTEX);
                var pass=encoder.createRenderPass(descriptor(tv));pass.setPipeline(p);pass.setVertexBuffer(3,v);pass.setVertexBuffer(7,instances);pass.setIndexBuffer(index,IndexType.INT);
                expect(IllegalStateException.class,() -> pass.drawIndexed(3,1,0,0,1));
                pass.setUniform(0,gain.slice());expect(IllegalStateException.class,() -> pass.drawIndexed(3,1,0,0,1));
                pass.pushConstants(floats(1,1,1,1));expect(IllegalArgumentException.class,() -> pass.drawIndexed(3,2,0,0,1));
                pass.enableScissor(0,0,2,6);pass.drawIndexed(3,1,0,0,1);
                pass.enableScissor(2,0,3,6);pass.multiDrawIndexed(IntBuffer.wrap(new int[]{0,3,0}),1,1,1);
                pass.enableScissor(5,0,3,6);pass.multiDraw(IntBuffer.wrap(new int[]{0,3}),1,1,1);
                encoder.submitRenderPass();waitFor(encoder);
                pixels(target.readDiagnostic(0,0),8,6,new int[]{0,128,255,255},null);
                var next=encoder.createRenderPass(descriptor(tv));next.setPipeline(p);
                expect(IllegalStateException.class,() -> next.setVertexBuffer(3,v));
                next.setUniform(0,gain.slice());next.pushConstants(floats(1,1,1,1));
                expect(IllegalStateException.class,() -> next.drawIndexed(3,1,0,0,0));
                encoder.submitRenderPass();waitFor(encoder);
            }
        }
    }
    private static void verifyMultiDrawFormatsAndAtomicity() {
        try(var context=MetalContext.create();var encoder=context.createCommandEncoder();
                var target=context.createTexture(null,1|2|8,GpuFormat.RGBA8_UNORM,8,6,1,1);var tv=context.createTextureView(target,0,1);
                var p=pipeline(context);var v=context.createBuffer(GpuBuffer.USAGE_VERTEX,vertices(0,new int[]{255,0,0,255},new int[]{0,255,0,255}));
                var index=context.createBuffer(GpuBuffer.USAGE_INDEX,indices(IndexType.SHORT,99,0,1,2))) {
            for(int kind=0;kind<4;kind++) {
                var pass=encoder.createRenderPass(descriptor(tv));pass.setPipeline(p);pass.setVertexBuffer(3,v.slice());pass.setIndexBuffer(index,IndexType.SHORT);
                if(kind==0) {
                    var rows=IntBuffer.wrap(new int[]{99,1,3,0,1,3,3,99});rows.position(1);rows.limit(7);
                    pass.multiDrawIndexed(rows.asReadOnlyBuffer(),1,0,2);check(rows.position()==1&&rows.limit()==7,"Indexed multi changed range");
                    Arrays.fill(rows.array(),0);
                } else if(kind==1) {
                    try(var stack=MemoryStack.stackPush()) {
                        var offsets=stack.mallocPointer(4);offsets.put(0,99).put(1,2).put(2,2).put(3,99);offsets.position(1);offsets.limit(3);
                        var counts=IntBuffer.wrap(new int[]{99,3,3,99});counts.position(1);counts.limit(3);
                        var bases=IntBuffer.wrap(new int[]{99,0,3,99});bases.position(1);bases.limit(3);
                        pass.multiDrawIndexed(offsets,counts.asReadOnlyBuffer(),bases.asReadOnlyBuffer(),2);
                        check(offsets.position()==1&&counts.position()==1&&bases.position()==1,"Separate indexed multi changed positions");
                        offsets.put(1,0);counts.put(1,0);bases.put(1,99);
                    }
                } else if(kind==2) {
                    var rows=ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder()).asIntBuffer();rows.put(new int[]{99,0,3,3,3,99});rows.position(1);rows.limit(5);
                    pass.multiDraw(rows.asReadOnlyBuffer(),1,0,2);check(rows.position()==1&&rows.limit()==5,"Direct multi changed range");rows.put(1,99);
                } else {
                    var starts=IntBuffer.wrap(new int[]{99,0,3,99});starts.position(1);starts.limit(3);
                    var counts=IntBuffer.wrap(new int[]{99,3,3,99});counts.position(1);counts.limit(3);
                    pass.multiDraw(starts.asReadOnlyBuffer(),counts.asReadOnlyBuffer(),2);
                    check(starts.position()==1&&counts.position()==1,"Separate direct multi changed positions");starts.put(1,99);
                }
                encoder.submitRenderPass();waitFor(encoder);pixels(target.readDiagnostic(0,0),8,6,new int[]{0,255,0,255},null);
            }
            var pass=encoder.createRenderPass(descriptor(tv));pass.setPipeline(p);pass.setVertexBuffer(3,v.slice());pass.setIndexBuffer(index,IndexType.SHORT);
            // Good first rows would draw red; a later invalid row must prevent the whole call.
            expect(IllegalArgumentException.class,() -> pass.multiDraw(IntBuffer.wrap(new int[]{0,3,3,4}),1,0,2));
            expect(IllegalArgumentException.class,() -> pass.multiDrawIndexed(IntBuffer.wrap(new int[]{1,3,0,3,3,0}),1,0,2));
            pass.multiDraw(IntBuffer.allocate(2048),1,0,1024);pass.multiDrawIndexed(IntBuffer.allocate(0),0,0,0);
            encoder.submitRenderPass();waitFor(encoder);pixels(target.readDiagnostic(0,0),8,6,new int[]{0,0,255,255},null);
            var capped=encoder.createRenderPass(descriptor(tv));capped.setPipeline(p);capped.setVertexBuffer(3,v.slice());
            // Pass, pipeline, vertex = 3 operations; leave only two slots for a three-draw call.
            for(int i=0;i<4091;i++) capped.disableScissor();
            expect(IllegalStateException.class,() -> capped.multiDraw(IntBuffer.wrap(new int[]{0,3,0,3,0,3}),1,0,3));
            encoder.submitRenderPass();waitFor(encoder);pixels(target.readDiagnostic(0,0),8,6,new int[]{0,0,255,255},null);
        }
    }
    private static void verifyCancellationAndBoundaries() throws InterruptedException {
        try(var context=MetalContext.create();var other=MetalContext.create();
                var target=context.createTexture(null,1|2|8,GpuFormat.RGBA8_UNORM,4,4,1,1);var tv=context.createTextureView(target,0,1);
                var p=pipeline(context);var v=context.createBuffer(GpuBuffer.USAGE_VERTEX,vertices(0,new int[]{255,0,0,255}));
                var index=context.createBuffer(GpuBuffer.USAGE_INDEX|GpuBuffer.USAGE_MAP_READ,indices(IndexType.SHORT,99,0,1,2));
                var wrongUsage=context.createBuffer(GpuBuffer.USAGE_VERTEX,8);var small=context.createBuffer(GpuBuffer.USAGE_INDEX,1);
                var foreign=other.createBuffer(GpuBuffer.USAGE_INDEX,8)) {
            byte[] original=new byte[64];Arrays.fill(original,(byte)37);target.uploadDiagnostic(0,0,ByteBuffer.wrap(original));
            var encoder=context.createCommandEncoder();var transientIndex=encoder.transientMemory().uploadGpu(indices(IndexType.SHORT,0,1,2),4,GpuBuffer.USAGE_INDEX);
            var pass=encoder.createRenderPass(descriptor(tv));
            expect(IllegalStateException.class,() -> pass.drawIndexed(3,1,0,0,0));pass.setPipeline(p);
            expect(IllegalStateException.class,() -> pass.drawIndexed(3,1,0,0,0));
            expect(IllegalArgumentException.class,() -> pass.setIndexBuffer(foreign,IndexType.SHORT));
            expect(IllegalStateException.class,() -> pass.setIndexBuffer(wrongUsage,IndexType.SHORT));
            expect(IllegalArgumentException.class,() -> pass.setIndexBuffer(small,IndexType.SHORT));
            expect(UnsupportedOperationException.class,() -> pass.setIndexBuffer(transientIndex.buffer(),IndexType.SHORT));
            expect(NullPointerException.class,() -> pass.setIndexBuffer(index,null));pass.setIndexBuffer(index,IndexType.SHORT);
            expect(IllegalStateException.class,() -> pass.drawIndexed(3,1,1,0,0));pass.setVertexBuffer(3,v.slice());
            expect(IllegalArgumentException.class,() -> pass.drawIndexed(-1,1,1,0,0));
            expect(IllegalArgumentException.class,() -> pass.drawIndexed(3,-1,1,0,0));
            expect(IllegalArgumentException.class,() -> pass.drawIndexed(3,1,-1,0,0));
            expect(IllegalArgumentException.class,() -> pass.drawIndexed(3,1,1,0,-1));
            expect(IllegalArgumentException.class,() -> pass.multiDraw(IntBuffer.allocate(0),1,0,1));
            expect(IllegalArgumentException.class,() -> pass.multiDraw(IntBuffer.allocate(0),1,0,1025));
            expect(IllegalArgumentException.class,() -> pass.multiDrawIndexed(IntBuffer.wrap(new int[]{1,3,0}),-1,0,1));
            try(var stack=MemoryStack.stackPush()) {
                var offsets=stack.mallocPointer(1);offsets.put(0,1);
                expect(IllegalArgumentException.class,() -> pass.multiDrawIndexed(offsets,IntBuffer.wrap(new int[]{3}),IntBuffer.wrap(new int[]{0}),1));
                offsets.put(0,Long.MAX_VALUE);
                expect(IllegalArgumentException.class,() -> pass.multiDrawIndexed(offsets,IntBuffer.wrap(new int[]{3}),IntBuffer.wrap(new int[]{0}),1));
            }
            var failure=new AtomicReference<Throwable>();var thread=new Thread(() -> {try{pass.drawIndexed(3,1,1,0,0);}catch(Throwable error){failure.set(error);}});
            thread.start();thread.join();check(failure.get() instanceof IllegalStateException,"Indexed draw accepted foreign thread");
            pass.drawIndexed(0,1,Integer.MAX_VALUE,0,0);pass.drawIndexed(3,0,1,0,0);pass.drawIndexed(3,1,1,0,0);
            encoder.close();check(Arrays.equals(original,target.readDiagnostic(0,0)),"Cancelled indexed draw changed pixels");
            expect(IllegalStateException.class,() -> pass.drawIndexed(3,1,1,0,0));
            try(var nativeEncoder=context.createCommandEncoder()) {
                long batch=NativeMetal.createBatch(context.nativeHandle());
                try {
                    NativeMetal.beginRenderPass(batch,new long[]{tv.attachmentHandle(context)},new int[]{0},new boolean[]{false},new float[4],0,0,false,0,4,4,null);
                    long buffer=index.commandHandle(context,0,index.size(),GpuBuffer.USAGE_INDEX);
                    expect(IllegalArgumentException.class,() -> NativeMetal.renderPassIndexBuffer(batch,buffer,3));
                    NativeMetal.renderPassPipeline(batch,p.commandHandle(context));NativeMetal.renderPassIndexBuffer(batch,buffer,2);
                    expect(IllegalArgumentException.class,() -> NativeMetal.renderPassMultiDraw(batch,true,new int[1]));
                    expect(IllegalArgumentException.class,() -> NativeMetal.renderPassMultiDraw(batch,false,new int[4100]));
                    expect(IllegalArgumentException.class,() -> NativeMetal.renderPassDrawIndexed(batch,3,1,Integer.MAX_VALUE,0,0));
                } finally {NativeMetal.cancelBatch(batch);NativeMetal.releaseBatch(batch);}
                var finished=nativeEncoder.createRenderPass(descriptor(tv));nativeEncoder.submitRenderPass();waitFor(nativeEncoder);
                expect(IllegalStateException.class,() -> finished.multiDraw(IntBuffer.allocate(0),1,0,0));
            }
        }
        try(var context=MetalContext.create();var vs=module("""
                #version 450
                #extension GL_ARB_shader_draw_parameters : require
                layout(location=0) out vec4 tint;
                void main(){gl_Position=vec4(0,0,0,1);tint=vec4(float(gl_DrawIDARB));}
                """,ShaderType.VERTEX);var fs=module(FRAGMENT,ShaderType.FRAGMENT)) {
            expect(UnsupportedOperationException.class,() -> context.compilePipeline(info(vs,fs,List.of(),List.of(),List.of(),0)));
            check(context.pipelineCount()==0,"DrawIndex rejection leaked native state");
        }
        expect(IllegalStateException.class,() -> NativeMetal.renderPassDrawIndexed(0,3,1,0,0,0));
        expect(IllegalStateException.class,() -> NativeMetal.renderPassIndexBuffer(0,0,2));
        expect(IllegalStateException.class,() -> NativeMetal.renderPassMultiDraw(0,false,new int[0]));
    }
    private static void verifyAsyncRetirement() {
        try(var context=MetalContext.create();var encoder=context.createCommandEncoder();
                var target=context.createTexture(null,1|2|8,GpuFormat.RGBA8_UNORM,6,4,1,1);var tv=context.createTextureView(target,0,1)) {
            for(int i=0;i<16;i++) {
                var p=pipeline(context);var data=vertices(0,new int[]{i,64,128,255});
                var v=context.createBuffer(GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_COPY_DST,data.remaining());
                var index=context.createBuffer(GpuBuffer.USAGE_INDEX|GpuBuffer.USAGE_COPY_DST,8);
                encoder.writeToBuffer(v.slice(),data);encoder.writeToBuffer(index.slice(),indices(IndexType.SHORT,99,0,1,2));
                var pass=encoder.createRenderPass(descriptor(tv));pass.setPipeline(p);pass.setVertexBuffer(3,v.slice());pass.setIndexBuffer(index,IndexType.SHORT);
                p.close();v.close();index.close();pass.drawIndexed(3,1,1,0,0);encoder.submitRenderPass();encoder.submit();
            }
            waitFor(encoder);pixels(target.readDiagnostic(0,0),6,4,new int[]{15,64,128,255},null);
            check(context.pipelineCount()==0&&context.commandStats()[4]==0&&context.commandStats()[2]==0,"Async indexed resources leaked");
        }
    }
    private static void expect(Class<? extends Throwable> type,Runnable action) {
        try{action.run();}catch(Throwable error){if(type.isInstance(error))return;throw new AssertionError("Expected " + type.getSimpleName() + ", received " + error,error);}
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
