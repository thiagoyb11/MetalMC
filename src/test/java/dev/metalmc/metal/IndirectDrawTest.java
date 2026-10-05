package dev.metalmc.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** GPU execution-time indirect arguments, ordered transfers, static validation and retirement. */
final class IndirectDrawTest {
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
    private IndirectDrawTest() {}
    static void run() throws Exception {
        verifyLayoutsAndOrderedTransfers();
        verifyTransientInstancesAndResources();
        verifyStaticBoundariesAndCancellation();
        verifyAtomicOperationCapAndCountLimit();
        verifyNativeBoundaries();
        verifyAsyncRetirement();
        System.out.println("PASS: GPU indirect 16/20-byte arguments, ordered uploads/copies, UInt16/UInt32 signed-base pixels, firstInstance/transient resources, static preflight, cancellation and retained argument buffers.");
    }

    private static SPIRVModule module(String source,ShaderType type) {
        byte[] bytes=ShaderTranslationTest.compile(source,type==ShaderType.VERTEX?shaderc_vertex_shader:shaderc_fragment_shader);
        var owned=MemoryUtil.memAlloc(bytes.length).order(ByteOrder.LITTLE_ENDIAN); owned.put(bytes).flip();
        return new SPIRVModule(owned,type);
    }
    private static BackendRenderPipeline.CreateInfo info(SPIRVModule vertex,SPIRVModule fragment,
            List<BackendRenderPipeline.CreateInfo.VertexBuffer> layouts,List<BackendRenderPipeline.CreateInfo.AttribBinding> attributes,
            List<BindGroupLayout.UniformDescription> uniforms,int push) {
        return new BackendRenderPipeline.CreateInfo("indirect fixture",List.of(new BackendRenderPipeline.CreateInfo.Shader("vertex","main",vertex),
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
        return RenderPassDescriptor.builder(() -> "indirect pixels").withColorAttachment(view,Optional.of(TextureCommandTest.color(0,0,1,1))).build();
    }
    private static void waitFor(MetalCommandEncoder encoder) {
        try(var fence=encoder.createFence()){encoder.submit();check(fence.awaitCompletion(GpuFence.NO_TIMEOUT),"Indirect submission did not complete");}
    }
    private static void pixels(byte[] bytes,int width,int height,int[] left,int[] right) {
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)for(int c=0;c<4;c++) {
            int expected=(right!=null&&x>=width/2?right:left)[c];int actual=Byte.toUnsignedInt(bytes[(y*width+x)*4+c]);
            check(Math.abs(actual-expected)<=1,"Indirect pixel " + x + "," + y + " channel " + c + ": " + actual + " vs " + expected);
        }
    }
    private static ByteBuffer arguments(int prefix,int[]... rows) {
        int length=prefix;for(var row:rows)length+=row.length*4;
        var data=ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);data.position(prefix);
        for(var row:rows)for(int value:row)data.putInt(value);return data.flip();
    }
    private static void indirect(RenderPassBackend pass,boolean indexed,GpuBufferSlice slice,int count) {
        if(indexed)pass.drawIndexedIndirect(slice,count);else pass.drawIndirect(slice,count);
    }
    private static void verifyLayoutsAndOrderedTransfers() {
        // Offset four and indexed stride twenty exercise valid four-byte alignment.
        for(int kind=0;kind<3;kind++) {
            boolean indexed=kind!=0;var type=kind==1?IndexType.SHORT:IndexType.INT;
            int stride=indexed?20:16;
            int[] left=indexed?new int[]{3,1,1,3,0}:new int[]{3,1,3,0};
            int[] right=indexed?new int[]{3,1,4,-3,0}:new int[]{3,1,0,0};
            int[] zeroCount=indexed?new int[]{0,1,0,0,0}:new int[]{0,1,0,0};
            int[] zeroInstances=indexed?new int[]{3,0,1,0,0}:new int[]{3,0,0,0};
            var data=arguments(4,left,right,zeroCount,zeroInstances);
            try(var context=MetalContext.create();var encoder=context.createCommandEncoder();
                    var target=context.createTexture(null,1|2|8,GpuFormat.RGBA8_UNORM,12,8,1,1);var tv=context.createTextureView(target,0,1)) {
                var p=pipeline(context);var v=context.createBuffer(GpuBuffer.USAGE_VERTEX,vertices(16,new int[]{0,255,0,255},new int[]{255,0,0,255}));
                var index=context.createBuffer(GpuBuffer.USAGE_INDEX,indices(type,99,0,1,2,3,4,5));
                var source=context.createBuffer(GpuBuffer.USAGE_COPY_SRC|GpuBuffer.USAGE_COPY_DST,data.remaining());
                var args=context.createBuffer(GpuBuffer.USAGE_INDIRECT_PARAMETERS|GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,data.remaining());
                encoder.writeToBuffer(source.slice(),data);Arrays.fill(data.array(),(byte)0);
                check(data.position()==0,"Indirect upload advanced source position");
                encoder.copyToBuffer(source.slice(),args.slice());source.close();
                var pass=encoder.createRenderPass(descriptor(tv));pass.setPipeline(p);pass.setVertexBuffer(3,v.slice(16,96));
                if(indexed)pass.setIndexBuffer(index,type);
                pass.enableScissor(0,0,6,8);indirect(pass,indexed,args.slice(4,stride),1);
                pass.enableScissor(6,0,6,8);indirect(pass,indexed,args.slice(4+stride,stride),1);
                pass.disableScissor();indirect(pass,indexed,args.slice(4+2L*stride,2L*stride),2);
                expect(IllegalStateException.class,() -> args.map(true,false));
                args.close();p.close();v.close();index.close();
                encoder.submitRenderPass();waitFor(encoder);
                pixels(target.readDiagnostic(0,0),12,8,new int[]{255,0,0,255},new int[]{0,255,0,255});
                check(context.pipelineCount()==0&&context.commandStats()[4]==0,"Indirect argument/index/vertex allocations did not retire");
            }
        }
    }
    private static void verifyTransientInstancesAndResources() {
        String vertex="""
                #version 450
                layout(location=0) in vec3 position;
                layout(location=2) in vec4 instanceColor;
                layout(location=0) out vec4 tint;
                void main(){gl_Position=vec4(position,1);tint=instanceColor;}
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
                var other=context.createCommandEncoder();
                var target=context.createTexture(null,1|2|8,GpuFormat.RGBA8_UNORM,8,6,1,1);var tv=context.createTextureView(target,0,1);
                var vs=module(vertex,ShaderType.VERTEX);var fs=module(fragment,ShaderType.FRAGMENT);
                var index=context.createBuffer(GpuBuffer.USAGE_INDEX,indices(IndexType.INT,0,1,2))) {
            var layouts=List.of(LAYOUTS.getFirst(),new BackendRenderPipeline.CreateInfo.VertexBuffer(7,16,1));
            var attrs=List.of(ATTRIBUTES.getFirst(),new BackendRenderPipeline.CreateInfo.AttribBinding(7,2,0,GpuFormat.RGBA32_FLOAT));
            try(var p=(MetalRenderPipeline)context.compilePipeline(info(vs,fs,layouts,attrs,
                    List.of(new BindGroupLayout.UniformDescription("Gain",UniformType.UNIFORM_BUFFER)),16)).finishCompile()) {
                var memory=encoder.transientMemory();
                var v=memory.uploadGpu(vertices(0,new int[]{0,0,0,0}),16,GpuBuffer.USAGE_VERTEX);
                var instances=memory.uploadGpu(floats(1,0,0,1, 0,1,1,1),16,GpuBuffer.USAGE_VERTEX);
                var gain=memory.uploadGpu(floats(1,0.5f,1,1),16,GpuBuffer.USAGE_UNIFORM);
                memory.uploadGpu(arguments(0,new int[]{0,0,0,0}),4,GpuBuffer.USAGE_INDIRECT_PARAMETERS);
                var raw=arguments(0,new int[]{3,1,0,0,1},new int[]{3,0,0,0,0});
                var indexed=memory.uploadGpu(raw,4,GpuBuffer.USAGE_INDIRECT_PARAMETERS);Arrays.fill(raw.array(),(byte)0);
                var direct=memory.uploadGpu(arguments(0,new int[]{3,1,0,1},new int[]{0,1,0,0}),4,GpuBuffer.USAGE_INDIRECT_PARAMETERS);
                var foreign=other.transientMemory().uploadGpu(arguments(0,new int[]{3,1,0,0}),4,GpuBuffer.USAGE_INDIRECT_PARAMETERS);
                check(indexed.offset()>0&&direct.offset()>indexed.offset(),"Indirect transient fixture lacks nonzero ranges");
                var pass=encoder.createRenderPass(descriptor(tv));pass.setPipeline(p);
                expect(IllegalArgumentException.class,() -> pass.drawIndirect(foreign,1));
                pass.setVertexBuffer(3,v);pass.setVertexBuffer(7,instances);
                expect(IllegalStateException.class,() -> pass.drawIndirect(direct,1));
                pass.setUniform(0,gain);expect(IllegalStateException.class,() -> pass.drawIndirect(direct,1));
                pass.pushConstants(floats(1,1,1,1));
                expect(IllegalStateException.class,() -> pass.drawIndexedIndirect(indexed,1));
                pass.setIndexBuffer(index,IndexType.INT);
                pass.enableScissor(0,0,4,6);pass.drawIndexedIndirect(indexed,2);
                pass.enableScissor(4,0,4,6);pass.drawIndirect(direct,2);
                // Pipeline switches invalidate shader descriptors/constants for indirect draws too.
                pass.setPipeline(p);expect(IllegalStateException.class,() -> pass.drawIndirect(direct,1));
                encoder.submitRenderPass();waitFor(encoder);
                pixels(target.readDiagnostic(0,0),8,6,new int[]{0,128,255,255},null);
                var next=encoder.createRenderPass(descriptor(tv));next.setPipeline(p);
                expect(IllegalStateException.class,() -> next.drawIndirect(direct,1));
                expect(IllegalStateException.class,() -> next.drawIndexedIndirect(indexed,1));
                encoder.submitRenderPass();waitFor(encoder);
            }
        }
    }
    @SuppressWarnings("try") // Intentional early close checks recorded retention and cancellation.
    private static void verifyStaticBoundariesAndCancellation() throws Exception {
        try(var context=MetalContext.create();var foreign=MetalContext.create();var encoder=context.createCommandEncoder();
                var target=context.createTexture(null,1|2|8,GpuFormat.RGBA8_UNORM,4,4,1,1);var tv=context.createTextureView(target,0,1);
                var p=pipeline(context);var v=context.createBuffer(GpuBuffer.USAGE_VERTEX,vertices(0,new int[]{255,0,0,255}));
                var args=context.createBuffer(GpuBuffer.USAGE_INDIRECT_PARAMETERS|GpuBuffer.USAGE_MAP_READ,arguments(0,new int[]{3,1,0,0}));
                var wrongUsage=context.createBuffer(GpuBuffer.USAGE_VERTEX,16);
                var other=foreign.createBuffer(GpuBuffer.USAGE_INDIRECT_PARAMETERS,16)) {
            var original=target.readDiagnostic(0,0);
            var pass=encoder.createRenderPass(descriptor(tv));
            expect(IllegalStateException.class,() -> pass.drawIndirect(args.slice(),0));pass.setPipeline(p);
            pass.drawIndirect(args.slice(0,0),0);pass.drawIndexedIndirect(args.slice(0,0),0);
            expect(IllegalStateException.class,() -> pass.drawIndirect(args.slice(),1));
            expect(IllegalArgumentException.class,() -> pass.drawIndirect(args.slice(),-1));
            expect(IllegalArgumentException.class,() -> pass.drawIndirect(args.slice(),1025));
            expect(IllegalArgumentException.class,() -> pass.drawIndirect(new GpuBufferSlice(args,2,14),1));
            expect(IllegalArgumentException.class,() -> pass.drawIndirect(new GpuBufferSlice(args,-4,16),1));
            expect(IllegalArgumentException.class,() -> pass.drawIndirect(args.slice(0,12),1));
            expect(IllegalArgumentException.class,() -> pass.drawIndirect(new GpuBufferSlice(args,4,16),1));
            expect(IllegalArgumentException.class,() -> pass.drawIndexedIndirect(args.slice(),1));
            expect(NullPointerException.class,() -> pass.drawIndirect(null,1));
            expect(IllegalStateException.class,() -> pass.drawIndirect(wrongUsage.slice(),1));
            expect(IllegalArgumentException.class,() -> pass.drawIndirect(other.slice(),1));
            try(var mapped=args.map(true,false)) {
                check(mapped.data().remaining()==16,"Mapped indirect argument length changed");
                expect(IllegalStateException.class,() -> pass.drawIndirect(args.slice(),1));
            }
            pass.setVertexBuffer(3,v.slice(0,8));
            expect(IllegalArgumentException.class,() -> pass.drawIndirect(args.slice(),1));
            pass.setVertexBuffer(3,v.slice());
            var failure=new AtomicReference<Throwable>();
            var thread=new Thread(() -> {try{pass.drawIndirect(args.slice(),1);}catch(Throwable error){failure.set(error);}});
            thread.start();thread.join();check(failure.get() instanceof IllegalStateException,"Indirect draw accepted foreign thread");
            pass.drawIndirect(args.slice(),1);
            // No queued argument upload/copy retains this allocation: the indirect
            // draw alone must establish the CPU hazard and native retirement.
            expect(IllegalStateException.class,() -> args.map(true,false));
            long retained=context.commandStats()[4];
            var stale=args.slice();args.close();expect(IllegalStateException.class,() -> pass.drawIndirect(stale,1));
            check(context.commandStats()[4]==retained,"Argument allocation retired before cancellation");
            encoder.close();check(Arrays.equals(original,target.readDiagnostic(0,0)),"Cancelled indirect draw changed pixels");
            expect(IllegalStateException.class,() -> pass.drawIndirect(stale,1));
        }
    }
    private static void verifyAtomicOperationCapAndCountLimit() {
        try(var context=MetalContext.create();var encoder=context.createCommandEncoder();
                var target=context.createTexture(null,1|2|8,GpuFormat.RGBA8_UNORM,4,4,1,1);var tv=context.createTextureView(target,0,1);
                var p=pipeline(context);var v=context.createBuffer(GpuBuffer.USAGE_VERTEX,vertices(0,new int[]{255,0,0,255}));
                var args=context.createBuffer(GpuBuffer.USAGE_INDIRECT_PARAMETERS,arguments(0,new int[]{3,1,0,0},new int[]{3,1,0,0},new int[]{3,1,0,0}));
                var zeros=context.createBuffer(GpuBuffer.USAGE_INDIRECT_PARAMETERS,ByteBuffer.allocate(1024*20));
                var index=context.createBuffer(GpuBuffer.USAGE_INDEX,indices(IndexType.INT,0,1,2))) {
            var pass=encoder.createRenderPass(descriptor(tv));pass.setPipeline(p);pass.setVertexBuffer(3,v.slice());pass.setIndexBuffer(index,IndexType.INT);
            pass.drawIndirect(zeros.slice(0,1024*16),1024);pass.drawIndexedIndirect(zeros.slice(),1024);
            encoder.submitRenderPass();waitFor(encoder);pixels(target.readDiagnostic(0,0),4,4,new int[]{0,0,255,255},null);
            var capped=encoder.createRenderPass(descriptor(tv));capped.setPipeline(p);capped.setVertexBuffer(3,v.slice());
            // Three initial operations, then 4091 scissor operations leave only two.
            for(int i=0;i<4091;i++)capped.disableScissor();
            expect(IllegalStateException.class,() -> capped.drawIndirect(args.slice(),3));
            encoder.submitRenderPass();waitFor(encoder);pixels(target.readDiagnostic(0,0),4,4,new int[]{0,0,255,255},null);
            // Indexed 3*20 bytes exceed this slice: reject the whole call before encoding.
            var shortPass=encoder.createRenderPass(descriptor(tv));shortPass.setPipeline(p);shortPass.setVertexBuffer(3,v.slice());shortPass.setIndexBuffer(index,IndexType.INT);
            expect(IllegalArgumentException.class,() -> shortPass.drawIndexedIndirect(args.slice(),3));
            encoder.submitRenderPass();waitFor(encoder);pixels(target.readDiagnostic(0,0),4,4,new int[]{0,0,255,255},null);
        }
    }
    private static void verifyNativeBoundaries() {
        try(var context=MetalContext.create();var foreign=MetalContext.create();
                var target=context.createTexture(null,1|2|8,GpuFormat.RGBA8_UNORM,4,4,1,1);var tv=context.createTextureView(target,0,1);
                var p=pipeline(context);var args=context.createBuffer(GpuBuffer.USAGE_INDIRECT_PARAMETERS,64);
                var other=foreign.createBuffer(GpuBuffer.USAGE_INDIRECT_PARAMETERS,64)) {
            long batch=NativeMetal.createBatch(context.nativeHandle());
            long handle=args.commandHandle(context,0,64,GpuBuffer.USAGE_INDIRECT_PARAMETERS);
            try {
                expect(IllegalStateException.class,() -> NativeMetal.renderPassDrawIndirect(batch,false,handle,0,64,1));
                NativeMetal.beginRenderPass(batch,new long[]{tv.attachmentHandle(context)},new int[]{0},new boolean[]{false},new float[4],0,0,false,0,4,4,null);
                NativeMetal.renderPassPipeline(batch,p.commandHandle(context));
                expect(IllegalArgumentException.class,() -> NativeMetal.renderPassDrawIndirect(batch,false,handle,2,16,1));
                expect(IllegalArgumentException.class,() -> NativeMetal.renderPassDrawIndirect(batch,false,handle,-4,16,1));
                expect(IllegalArgumentException.class,() -> NativeMetal.renderPassDrawIndirect(batch,false,handle,Long.MAX_VALUE-3,16,1));
                expect(IllegalArgumentException.class,() -> NativeMetal.renderPassDrawIndirect(batch,false,handle,0,-1,1));
                expect(IllegalArgumentException.class,() -> NativeMetal.renderPassDrawIndirect(batch,false,handle,0,64,-1));
                expect(IllegalArgumentException.class,() -> NativeMetal.renderPassDrawIndirect(batch,true,handle,0,16,1));
                expect(IllegalArgumentException.class,() -> NativeMetal.renderPassDrawIndirect(batch,false,handle,0,64,1025));
                expect(IllegalArgumentException.class,() -> NativeMetal.renderPassDrawIndirect(batch,false,other.commandHandle(foreign,0,64,GpuBuffer.USAGE_INDIRECT_PARAMETERS),0,64,1));
                expect(IllegalStateException.class,() -> NativeMetal.renderPassDrawIndirect(batch,false,handle,0,64,1));
                NativeMetal.renderPassDrawIndirect(batch,true,handle,64,0,0);
            } finally {NativeMetal.cancelBatch(batch);NativeMetal.releaseBatch(batch);}
            expect(IllegalStateException.class,() -> NativeMetal.renderPassDrawIndirect(batch,false,handle,0,64,1));
            expect(IllegalStateException.class,() -> NativeMetal.renderPassDrawIndirect(0,false,handle,0,64,1));
        }
    }
    private static void verifyAsyncRetirement() {
        try(var context=MetalContext.create();var encoder=context.createCommandEncoder();
                var target=context.createTexture(null,1|2|8,GpuFormat.RGBA8_UNORM,6,4,1,1);var tv=context.createTextureView(target,0,1)) {
            for(int i=0;i<16;i++) {
                boolean indexed=(i&1)!=0;
                var p=pipeline(context);var data=vertices(0,new int[]{i,64,128,255});
                var v=context.createBuffer(GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_COPY_DST,data.remaining());
                var index=context.createBuffer(GpuBuffer.USAGE_INDEX|GpuBuffer.USAGE_COPY_DST,8);
                var raw=indexed?arguments(4,new int[]{3,1,1,0,0}):arguments(4,new int[]{3,1,0,0});
                var args=context.createBuffer(GpuBuffer.USAGE_INDIRECT_PARAMETERS|GpuBuffer.USAGE_COPY_DST,raw.remaining());
                encoder.writeToBuffer(v.slice(),data);encoder.writeToBuffer(index.slice(),indices(IndexType.SHORT,99,0,1,2));encoder.writeToBuffer(args.slice(),raw);
                var pass=encoder.createRenderPass(descriptor(tv));pass.setPipeline(p);pass.setVertexBuffer(3,v.slice());if(indexed)pass.setIndexBuffer(index,IndexType.SHORT);
                indirect(pass,indexed,args.slice(4,indexed?20:16),1);args.close();p.close();v.close();index.close();
                encoder.submitRenderPass();encoder.submit();
            }
            waitFor(encoder);pixels(target.readDiagnostic(0,0),6,4,new int[]{15,64,128,255},null);
            check(context.pipelineCount()==0&&context.commandStats()[4]==0&&context.commandStats()[2]==0,"Async indirect resources leaked");
        }
    }
    private static void expect(Class<? extends Throwable> type,Runnable action) {
        try{action.run();}catch(Throwable error){if(type.isInstance(error))return;throw new AssertionError("Expected " + type.getSimpleName() + ", received " + error,error);}
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
