#include "dev_metalmc_metal_NativeMetal.h"
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <condition_variable>
#include <chrono>
#include <cmath>
#include <cfloat>
#include <cstddef>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
// Exact packed frontend/Vulkan layouts; signed baseVertex is preserved by Metal.
static_assert(sizeof(MTLDrawPrimitivesIndirectArguments) == 16);
static_assert(offsetof(MTLDrawPrimitivesIndirectArguments, vertexCount) == 0);
static_assert(offsetof(MTLDrawPrimitivesIndirectArguments, instanceCount) == 4);
static_assert(offsetof(MTLDrawPrimitivesIndirectArguments, vertexStart) == 8);
static_assert(offsetof(MTLDrawPrimitivesIndirectArguments, baseInstance) == 12);
static_assert(sizeof(MTLDrawIndexedPrimitivesIndirectArguments) == 20);
static_assert(offsetof(MTLDrawIndexedPrimitivesIndirectArguments, indexCount) == 0);
static_assert(offsetof(MTLDrawIndexedPrimitivesIndirectArguments, instanceCount) == 4);
static_assert(offsetof(MTLDrawIndexedPrimitivesIndirectArguments, indexStart) == 8);
static_assert(offsetof(MTLDrawIndexedPrimitivesIndirectArguments, baseVertex) == 12);
static_assert(offsetof(MTLDrawIndexedPrimitivesIndirectArguments, baseInstance) == 16);
struct Context {
    id<MTLDevice> device;
    id<MTLCommandQueue> queue;
    id<MTLRenderPipelineState> pipeline;
    id<MTLComputePipelineState> samplePipeline;
    id<MTLLibrary> clearLibrary;
    id<MTLDepthStencilState> clearDepthState;
    // Internal clear PSOs: at most one for each of the seven supported color formats.
    std::unordered_map<NSUInteger, id<MTLRenderPipelineState>> clearPipelines;
    std::mutex encoding;
    std::mutex submissionState;
    std::condition_variable submissionIdle;
    uint64_t submitted = 0, completed = 0, inFlight = 0, peakInFlight = 0;
    std::string commandError;
    std::atomic<uint64_t> pipelineCount{0}, queryPoolCount{0};
    std::atomic<uint64_t> bufferBytes{0}, stagingBytes{0}, textureBytes{0};
};

struct Surface {
    std::shared_ptr<Context> context;
    CAMetalLayer *layer;
    id<MTLRenderPipelineState> pipeline;
    std::mutex state;
    std::condition_variable idle;
    uint64_t submitted = 0, completed = 0, skipped = 0;
    uint64_t inFlight = 0, peakInFlight = 0;
    jint width = 0, height = 0;
    std::string gpuError;
};

struct Buffer {
    std::shared_ptr<Context> context;
    id<MTLBuffer> metal;
    std::atomic<uint64_t> recordedUses{0};
    uint64_t accountedBytes = 0;
    ~Buffer() { context->bufferBytes.fetch_sub(accountedBytes); }
};

struct Texture {
    std::shared_ptr<Context> context;
    id<MTLTexture> metal;
    jint usage;
    NSUInteger bytesPerPixel;
    std::atomic<uint64_t> recordedUses{0};
    uint64_t accountedBytes = 0;
    ~Texture() { context->textureBytes.fetch_sub(accountedBytes); }
};


struct ShaderBinding {
    jint stage, index, kind, buffer, texture, sampler, bytes, arrayed;
};
struct TextureView;
struct Sampler;
struct RenderPipeline {
    std::shared_ptr<Context> context;
    id<MTLRenderPipelineState> withDepth, withoutDepth;
    id<MTLDepthStencilState> depthState, noDepthState;
    std::array<MTLPixelFormat, 8> colors{};
    std::array<NSUInteger, 16> strides{}, steps{}, extents{};
    MTLPrimitiveType primitive;
    MTLTriangleFillMode fill;
    MTLCullMode cull;
    float depthBias, slopeBias;
    bool counted = false;
    std::vector<jint> uniformTypes;
    std::vector<ShaderBinding> shaderBindings;
    jint pushConstantsSize = 0;
    ~RenderPipeline() { if (counted) context->pipelineCount.fetch_sub(1); }
};
struct VertexBinding {
    std::shared_ptr<Buffer> buffer;
    NSUInteger offset = 0, length = 0;
};

std::atomic<uint64_t> processQueryPoolCount{0};
enum class QueryState { Empty, Recorded, Submitted, Available, Failed };
struct QuerySlot { QueryState state = QueryState::Empty; uint64_t value = 0; };
struct QueryPool {
    std::shared_ptr<Context> context;
    id<MTLCounterSampleBuffer> samples;
    id<MTLBuffer> markerSource;
    id<MTLBuffer> marker;
    std::mutex state;
    std::vector<QuerySlot> slots;
    uint64_t accountedBytes = 0;
    bool counted = false;
    ~QueryPool() {
        context->bufferBytes.fetch_sub(accountedBytes);
        if (counted) { context->queryPoolCount.fetch_sub(1); processQueryPoolCount.fetch_sub(1); }
    }
};
struct QueryWrite { std::shared_ptr<QueryPool> pool; NSUInteger index; };
struct QuerySnapshot { std::shared_ptr<QueryPool> pool; id<MTLBuffer> values; };

struct Batch {
    std::shared_ptr<Context> context;
    id<MTLCommandBuffer> commands;
    id<MTLRenderCommandEncoder> render;
    NSUInteger renderWidth = 0, renderHeight = 0;
    unsigned debugGroups = 0;
    std::array<MTLPixelFormat, 8> renderColors{};
    bool renderHasDepth = false;
    std::shared_ptr<RenderPipeline> currentPipeline;
    std::array<VertexBinding, 16> vertexBindings{};
    std::shared_ptr<Buffer> indexBuffer;
    NSUInteger indexBytes = 0;
    std::vector<std::shared_ptr<RenderPipeline>> pipelineResources;
    std::vector<std::shared_ptr<Buffer>> resources;
    std::vector<std::shared_ptr<Texture>> textureResources;
    std::vector<std::shared_ptr<Texture>> renderAttachments;
    std::vector<std::shared_ptr<TextureView>> viewResources;
    std::vector<std::shared_ptr<Sampler>> samplerResources;
    std::array<bool, 64> uniformBound{};
    bool constantsBound = false;
    std::vector<id<MTLBuffer>> staging;
    std::vector<QueryWrite> queryWrites;
    std::vector<QuerySnapshot> querySnapshots;
    id<MTLSharedEvent> queryResolveEvent;
    uint64_t queryEventValue = 0;
    uint64_t stagingBytes = 0, operations = 0;
    std::mutex state;
    std::condition_variable ready;
    bool submitted = false, completed = false, cancelled = false;
    std::string error;
    void finishRender() {
        if (!render) return;
        while (debugGroups) { [render popDebugGroup]; --debugGroups; }
        [render endEncoding];
        render = nil;
        currentPipeline.reset();
        vertexBindings = {};
        indexBuffer.reset(); indexBytes = 0;
        renderAttachments.clear(); uniformBound = {}; constantsBound = false;
    }
    void retireRecording() {
        for (const auto &buffer : resources) buffer->recordedUses.fetch_sub(1);
        for (const auto &texture : textureResources) texture->recordedUses.fetch_sub(1);
        for (const auto &write : queryWrites) {
            std::lock_guard<std::mutex> lock(write.pool->state);
            auto &slot = write.pool->slots[write.index];
            slot.state = submitted ? QueryState::Submitted : QueryState::Empty;
            slot.value = 0;
        }
    }
    // Called only after real GPU completion. No Java calls or additional GPU wait.
    void completeQueries(std::string &failure) {
        for (const auto &write : queryWrites) {
            std::lock_guard<std::mutex> lock(write.pool->state);
            auto &slot = write.pool->slots[write.index];
            if (!failure.empty()) { slot.state = QueryState::Failed; continue; }
            auto snapshot = std::find_if(querySnapshots.begin(), querySnapshots.end(),
                    [&](const auto &entry) { return entry.pool == write.pool; });
            NSUInteger offset = write.index * sizeof(MTLCounterResultTimestamp);
            if (snapshot == querySnapshots.end() || !snapshot->values.contents
                    || offset + sizeof(MTLCounterResultTimestamp) > snapshot->values.length) {
                failure = "Missing Metal timestamp resolution snapshot";
                slot.state = QueryState::Failed; continue;
            }
            MTLCounterResultTimestamp sample;
            std::memcpy(&sample, static_cast<const uint8_t *>(snapshot->values.contents) + offset, sizeof(sample));
            if (sample.timestamp == 0 || sample.timestamp == MTLCounterErrorValue
                    || sample.timestamp > static_cast<uint64_t>(INT64_MAX)) {
                failure = "Invalid Metal timestamp at slot " + std::to_string(write.index)
                        + ": " + std::to_string(sample.timestamp);
                slot.state = QueryState::Failed; continue;
            }
            slot.value = sample.timestamp;
            slot.state = QueryState::Available;
        }
    }
    void releaseResources() {
        currentPipeline.reset();
        vertexBindings = {};
        indexBuffer.reset(); indexBytes = 0;
        pipelineResources.clear();
        resources.clear();
        textureResources.clear();
        renderAttachments.clear(); viewResources.clear(); samplerResources.clear();
        staging.clear();
        queryWrites.clear();
        querySnapshots.clear();
        queryResolveEvent = nil;
        context->stagingBytes.fetch_sub(stagingBytes);
        stagingBytes = 0;
    }
    ~Batch() {
        finishRender();
        if (!submitted && !cancelled) retireRecording();
        releaseResources();
    }
};


struct TextureView {
    std::shared_ptr<Texture> texture;
    id<MTLTexture> metal;
};

struct Sampler {
    std::shared_ptr<Context> context;
    id<MTLSamplerState> metal;
};

std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<Context>> contexts;
std::unordered_map<jlong, std::shared_ptr<Surface>> surfaces;
std::unordered_map<jlong, std::shared_ptr<Buffer>> buffers;
std::unordered_map<jlong, std::shared_ptr<Texture>> textures;
std::unordered_map<jlong, std::shared_ptr<TextureView>> textureViews;
std::unordered_map<jlong, std::shared_ptr<Sampler>> samplers;
std::unordered_map<jlong, std::shared_ptr<Batch>> batches;
std::unordered_map<jlong, std::shared_ptr<RenderPipeline>> renderPipelines;
std::unordered_map<jlong, std::shared_ptr<QueryPool>> queryPools;
std::atomic<jlong> nextHandle{1};

void throwJava(JNIEnv *env, const char *type, const std::string &message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(type);
    if (cls) env->ThrowNew(cls, message.c_str());
}

template<typename T>
std::shared_ptr<T> findResource(JNIEnv *env, jlong handle,
        const std::unordered_map<jlong, std::shared_ptr<T>> &registry, const char *name) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto it = registry.find(handle);
    if (it == registry.end()) {
        throwJava(env, "java/lang/IllegalStateException", std::string("Invalid or closed native Metal ") + name);
        return nullptr;
    }
    return it->second;
}

uint64_t allocatedResources(const std::shared_ptr<Context> &context) {
    // Caller holds registryMutex. Views share storage and are not counted twice.
    uint64_t bytes = context->bufferBytes.load() + context->stagingBytes.load() + context->textureBytes.load();
    return bytes;
}

NSString *resourceLabel(JNIEnv *env, jstring label, NSString *fallback) {
    if (!label) return fallback;
    jsize length = env->GetStringLength(label);
    const jchar *characters = env->GetStringChars(label, nullptr);
    if (!characters) return nil;
    NSString *result = [NSString stringWithCharacters:reinterpret_cast<const unichar *>(characters) length:length];
    env->ReleaseStringChars(label, characters);
    return result;
}

std::shared_ptr<Context> findContext(JNIEnv *env, jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto it = contexts.find(handle);
    if (it == contexts.end()) {
        throwJava(env, "java/lang/IllegalStateException", "Invalid or closed native Metal context");
        return nullptr;
    }
    // Retains resources if another native caller closes the handle concurrently.
    return it->second;
}

std::string describe(NSError *error) {
    return error ? std::string(error.localizedDescription.UTF8String) : "unknown Metal error";
}

void drainCommands(const std::shared_ptr<Context> &context) {
    std::unique_lock<std::mutex> lock(context->submissionState);
    context->submissionIdle.wait(lock, [&] { return context->inFlight == 0; });
    if (!context->commandError.empty()) throw std::runtime_error(context->commandError);
}

bool cpuAccess(JNIEnv *env, const std::shared_ptr<Buffer> &buffer) {
    if (buffer->recordedUses.load()) {
        throwJava(env, "java/lang/IllegalStateException", "Submit or cancel recorded GPU commands before CPU buffer access");
        return false;
    }
    try { drainCommands(buffer->context); return true; }
    catch (const std::exception &error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
        return false;
    }
}

std::shared_ptr<Buffer> findBuffer(JNIEnv *env, jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto it = buffers.find(handle);
    if (it == buffers.end()) {
        throwJava(env, "java/lang/IllegalStateException", "Invalid or closed native Metal buffer");
        return nullptr;
    }
    return it->second;
}

bool bufferRange(JNIEnv *env, const std::shared_ptr<Buffer> &buffer, jlong offset, jlong length) {
    jlong size = static_cast<jlong>(buffer->metal.length);
    if (offset < 0 || length < 0 || offset > size || length > size - offset) {
        throwJava(env, "java/lang/IllegalArgumentException", "Buffer range exceeds allocation");
        return false;
    }
    return true;
}

bool diagnosticTextureAccess(JNIEnv *env, const std::shared_ptr<Texture> &texture) {
    if (texture->recordedUses.load()) {
        throwJava(env, "java/lang/IllegalStateException", "Submit or cancel recorded texture commands before diagnostics");
        return false;
    }
    try { drainCommands(texture->context); return true; }
    catch (const std::exception &error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
        return false;
    }
}

bool requireMainThread(JNIEnv *env) {
    if ([NSThread isMainThread]) return true;
    throwJava(env, "java/lang/IllegalStateException",
              "SDL Metal surfaces require the macOS main thread; use -XstartOnFirstThread");
    return false;
}

std::shared_ptr<Surface> findSurface(JNIEnv *env, jlong handle) {
    if (!requireMainThread(env)) return nullptr;
    std::lock_guard<std::mutex> lock(registryMutex);
    auto it = surfaces.find(handle);
    if (it == surfaces.end()) {
        throwJava(env, "java/lang/IllegalStateException", "Invalid or closed native Metal surface");
        return nullptr;
    }
    return it->second;
}

void drain(const std::shared_ptr<Surface> &surface) {
    std::unique_lock<std::mutex> lock(surface->state);
    surface->idle.wait(lock, [&] { return surface->inFlight == 0; });
}

NSString *const triangleSource = @R"MSL(
#include <metal_stdlib>
using namespace metal;
struct VertexOut { float4 position [[position]]; float3 color; };
vertex VertexOut diagnostic_vertex(uint index [[vertex_id]]) {
    const float2 positions[3] = {float2(-0.8, -0.8), float2(0.8, -0.8), float2(0.0, 0.8)};
    const float3 colors[3] = {float3(1,0,0), float3(0,1,0), float3(0,0,1)};
    VertexOut result;
    result.position = float4(positions[index], 0.0, 1.0);
    result.color = colors[index];
    return result;
}
fragment float4 diagnostic_fragment(VertexOut in [[stage_in]]) {
    return float4(in.color, 1.0);
}
)MSL";
}

JNIEXPORT jint JNICALL Java_dev_metalmc_metal_NativeMetal_abiVersion(JNIEnv *, jclass) {
    return 16;
}

JNIEXPORT jlong JNICALL Java_dev_metalmc_metal_NativeMetal_createContext(JNIEnv *env, jclass) {
    @autoreleasepool {
        try {
            auto ctx = std::make_shared<Context>();
            ctx->device = MTLCreateSystemDefaultDevice();
            if (!ctx->device) {
                throw std::runtime_error("No Metal device available to this process");
            }
            if (!ctx->device.hasUnifiedMemory) {
                throw std::runtime_error("MetalMC's initial target requires a unified-memory Metal device");
            }
            if (![ctx->device supportsFamily:MTLGPUFamilyApple7]) {
                throw std::runtime_error("MetalMC's initial target requires Apple7-or-later GPU family support");
            }
            ctx->queue = [ctx->device newCommandQueue];
            if (!ctx->queue) throw std::runtime_error("Could not create Metal command queue");
            ctx->queue.label = @"MetalMC diagnostic queue";
            NSError *error = nil;
            id<MTLLibrary> library = [ctx->device newLibraryWithSource:triangleSource options:nil error:&error];
            if (!library) throw std::runtime_error("MSL compilation failed: " + describe(error));
            MTLRenderPipelineDescriptor *descriptor = [MTLRenderPipelineDescriptor new];
            descriptor.label = @"MetalMC diagnostic triangle";
            descriptor.vertexFunction = [library newFunctionWithName:@"diagnostic_vertex"];
            descriptor.fragmentFunction = [library newFunctionWithName:@"diagnostic_fragment"];
            descriptor.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
            ctx->pipeline = [ctx->device newRenderPipelineStateWithDescriptor:descriptor error:&error];
            if (!ctx->pipeline) throw std::runtime_error("Metal pipeline creation failed: " + describe(error));
            jlong handle = nextHandle.fetch_add(1);
            if (handle <= 0) throw std::runtime_error("Metal handle space exhausted");
            std::lock_guard<std::mutex> lock(registryMutex);
            contexts.emplace(handle, std::move(ctx));
            return handle;
        } catch (const std::exception &error) {
            throwJava(env, "java/lang/IllegalStateException", error.what());
            return 0;
        }
    }
}

JNIEXPORT jstring JNICALL Java_dev_metalmc_metal_NativeMetal_deviceName(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        auto ctx = findContext(env, handle);
        return ctx ? env->NewStringUTF(ctx->device.name.UTF8String) : nullptr;
    }
}

JNIEXPORT jlongArray JNICALL Java_dev_metalmc_metal_NativeMetal_deviceProperties(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        auto ctx = findContext(env, handle);
        if (!ctx) return nullptr;
        // NSUInteger and uint64_t device properties must fit Java's signed contract.
        uint64_t bufferLength = ctx->device.maxBufferLength;
        uint64_t workingSet = ctx->device.recommendedMaxWorkingSetSize;
        if (bufferLength == 0 || bufferLength > static_cast<uint64_t>(INT64_MAX)
                || workingSet > static_cast<uint64_t>(INT64_MAX)) {
            throwJava(env, "java/lang/IllegalStateException", "Metal device limits cannot be represented by Java");
            return nullptr;
        }
        const jlong values[] = {static_cast<jlong>(bufferLength), static_cast<jlong>(workingSet),
            ctx->device.hasUnifiedMemory ? 1LL : 0LL, [ctx->device supportsFamily:MTLGPUFamilyApple7] ? 1LL : 0LL};
        jlongArray result = env->NewLongArray(4);
        if (result) env->SetLongArrayRegion(result, 0, 4, values);
        return result;
    }
}

JNIEXPORT jstring JNICALL Java_dev_metalmc_metal_NativeMetal_operatingSystemVersion(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        auto ctx = findContext(env, handle);
        if (!ctx) return nullptr;
        NSString *version = [@"macOS " stringByAppendingString:NSProcessInfo.processInfo.operatingSystemVersionString];
        return env->NewStringUTF(version.UTF8String);
    }
}

JNIEXPORT jbyteArray JNICALL Java_dev_metalmc_metal_NativeMetal_renderTriangle(
        JNIEnv *env, jclass, jlong handle, jint width, jint height) {
    @autoreleasepool {
        if (width < 1 || height < 1 || width > 4096 || height > 4096) {
            throwJava(env, "java/lang/IllegalArgumentException", "Diagnostic dimensions must be between 1 and 4096");
            return nullptr;
        }
        auto ctx = findContext(env, handle);
        if (!ctx) return nullptr;
        try {
            std::lock_guard<std::mutex> lock(ctx->encoding);
            const NSUInteger rowBytes = static_cast<NSUInteger>(width) * 4;
            const NSUInteger alignedRowBytes = (rowBytes + 255) & ~static_cast<NSUInteger>(255);
            MTLTextureDescriptor *textureDescriptor = [MTLTextureDescriptor
                texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
                width:width height:height mipmapped:NO];
            textureDescriptor.storageMode = MTLStorageModePrivate;
            textureDescriptor.usage = MTLTextureUsageRenderTarget;
            id<MTLTexture> target = [ctx->device newTextureWithDescriptor:textureDescriptor];
            id<MTLBuffer> readback = [ctx->device newBufferWithLength:alignedRowBytes * height
                                                           options:MTLResourceStorageModeShared];
            if (!target || !readback) throw std::runtime_error("Could not allocate Metal diagnostic resources");
            target.label = @"MetalMC offscreen RGBA8 target";
            readback.label = @"MetalMC diagnostic readback";

            MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
            pass.colorAttachments[0].texture = target;
            pass.colorAttachments[0].loadAction = MTLLoadActionClear;
            pass.colorAttachments[0].storeAction = MTLStoreActionStore;
            pass.colorAttachments[0].clearColor = MTLClearColorMake(0.025, 0.035, 0.06, 1.0);
            id<MTLCommandBuffer> commands = [ctx->queue commandBuffer];
            if (!commands) throw std::runtime_error("Could not allocate Metal command buffer");
            commands.label = @"MetalMC triangle and readback";
            id<MTLRenderCommandEncoder> render = [commands renderCommandEncoderWithDescriptor:pass];
            if (!render) throw std::runtime_error("Could not begin Metal render pass");
            [render setRenderPipelineState:ctx->pipeline];
            [render drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            [render endEncoding];
            id<MTLBlitCommandEncoder> blit = [commands blitCommandEncoder];
            if (!blit) throw std::runtime_error("Could not begin Metal readback blit");
            [blit copyFromTexture:target sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0)
                      sourceSize:MTLSizeMake(width, height, 1) toBuffer:readback destinationOffset:0
             destinationBytesPerRow:alignedRowBytes destinationBytesPerImage:alignedRowBytes * height];
            [blit endEncoding];
            [commands commit];
            // Diagnostic only. Gameplay will use asynchronous completion and frames in flight.
            [commands waitUntilCompleted];
            if (commands.status != MTLCommandBufferStatusCompleted) {
                throw std::runtime_error("Metal diagnostic command failed: " + describe(commands.error));
            }
            std::vector<jbyte> packed(rowBytes * height);
            const char *mapped = static_cast<const char *>(readback.contents);
            if (!mapped) throw std::runtime_error("Metal readback buffer could not be mapped");
            for (jint y = 0; y < height; ++y) {
                std::memcpy(packed.data() + y * rowBytes, mapped + y * alignedRowBytes, rowBytes);
            }
            jbyteArray result = env->NewByteArray(static_cast<jsize>(packed.size()));
            if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(packed.size()), packed.data());
            return result;
        } catch (const std::exception &error) {
            throwJava(env, "java/lang/IllegalStateException", error.what());
            return nullptr;
        }
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_destroyContext(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(registryMutex);
        auto context = contexts.find(handle);
        if (context != contexts.end()) {
            for (const auto &entry : batches) {
                if (entry.second->context == context->second) {
                    throwJava(env, "java/lang/IllegalStateException", "Close Metal commands/fences before their context");
                    return;
                }
            }
            {
                std::lock_guard<std::mutex> state(context->second->submissionState);
                if (context->second->inFlight) {
                    throwJava(env, "java/lang/IllegalStateException", "Drain Metal commands before their context");
                    return;
                }
            }
            for (const auto &entry : renderPipelines) {
                if (entry.second->context == context->second) {
                    throwJava(env, "java/lang/IllegalStateException", "Close Metal pipelines before their context");
                    return;
                }
            }
            for (const auto &entry : queryPools) {
                if (entry.second->context == context->second) {
                    throwJava(env, "java/lang/IllegalStateException", "Close Metal timestamp pools before their context");
                    return;
                }
            }
            for (const auto &entry : surfaces) {
                if (entry.second->context == context->second) {
                    throwJava(env, "java/lang/IllegalStateException", "Close Metal surfaces before their context");
                    return;
                }
            }
            for (const auto &entry : buffers) {
                if (entry.second->context == context->second) {
                    throwJava(env, "java/lang/IllegalStateException", "Close Metal buffers before their context");
                    return;
                }
            }
            for (const auto &entry : textures) {
                if (entry.second->context == context->second) {
                    throwJava(env, "java/lang/IllegalStateException", "Close Metal textures/views before their context");
                    return;
                }
            }
            for (const auto &entry : samplers) {
                if (entry.second->context == context->second) {
                    throwJava(env, "java/lang/IllegalStateException", "Close Metal samplers before their context");
                    return;
                }
            }
        }
        if (contexts.erase(handle) == 0) {
            throwJava(env, "java/lang/IllegalStateException", "Invalid or already closed native Metal context");
        }
    }
}

JNIEXPORT jlong JNICALL Java_dev_metalmc_metal_NativeMetal_createSurface(
        JNIEnv *env, jclass, jlong contextHandle, jlong layerPointer) {
    @autoreleasepool {
        if (!requireMainThread(env)) return 0;
        if (!layerPointer) {
            throwJava(env, "java/lang/IllegalArgumentException", "SDL Metal layer is null");
            return 0;
        }
        auto context = findContext(env, contextHandle);
        if (!context) return 0;
        try {
            // Trusted bootstrap pointer from SDL_Metal_GetLayer, never a registry ID.
            // SDL owns the view; ARC retains its layer until our work has drained.
            CAMetalLayer *layer = (__bridge CAMetalLayer *)(reinterpret_cast<void *>(layerPointer));
            if (![layer isKindOfClass:[CAMetalLayer class]]) {
                throw std::runtime_error("SDL did not supply a CAMetalLayer");
            }
            auto surface = std::make_shared<Surface>();
            surface->context = context;
            surface->layer = layer;
            NSError *error = nil;
            id<MTLLibrary> library = [context->device newLibraryWithSource:triangleSource options:nil error:&error];
            if (!library) throw std::runtime_error("Surface MSL compilation failed: " + describe(error));
            MTLRenderPipelineDescriptor *pipeline = [MTLRenderPipelineDescriptor new];
            pipeline.label = @"MetalMC drawable triangle (BGRA8 sRGB)";
            pipeline.vertexFunction = [library newFunctionWithName:@"diagnostic_vertex"];
            pipeline.fragmentFunction = [library newFunctionWithName:@"diagnostic_fragment"];
            pipeline.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm_sRGB;
            surface->pipeline = [context->device newRenderPipelineStateWithDescriptor:pipeline error:&error];
            if (!surface->pipeline) throw std::runtime_error("Surface pipeline creation failed: " + describe(error));
            std::lock_guard<std::mutex> lock(registryMutex);
            auto registeredContext = contexts.find(contextHandle);
            if (registeredContext == contexts.end() || registeredContext->second != context) {
                throw std::runtime_error("Metal context closed during surface creation");
            }
            for (const auto &entry : surfaces) {
                if (entry.second->layer == layer) throw std::runtime_error("SDL layer already has a Metal surface");
            }
            jlong handle = nextHandle.fetch_add(1);
            if (handle <= 0) throw std::runtime_error("Metal handle space exhausted");
            layer.device = context->device;
            layer.pixelFormat = MTLPixelFormatBGRA8Unorm_sRGB;
            layer.framebufferOnly = YES;
            layer.maximumDrawableCount = 3;
            layer.allowsNextDrawableTimeout = YES;
            layer.displaySyncEnabled = YES;
            surfaces.emplace(handle, std::move(surface));
            return handle;
        } catch (const std::exception &error) {
            throwJava(env, "java/lang/IllegalStateException", error.what());
            return 0;
        }
    }
}

JNIEXPORT jboolean JNICALL Java_dev_metalmc_metal_NativeMetal_presentTriangle(
        JNIEnv *env, jclass, jlong handle, jint width, jint height) {
    @autoreleasepool {
        auto surface = findSurface(env, handle);
        if (!surface) return JNI_FALSE;
        if (width < 0 || height < 0 || width > 16384 || height > 16384) {
            throwJava(env, "java/lang/IllegalArgumentException", "Surface dimensions must be between 0 and 16384");
            return JNI_FALSE;
        }
        bool reserved = false;
        try {
            {
                std::lock_guard<std::mutex> lock(surface->state);
                if (!surface->gpuError.empty()) throw std::runtime_error(surface->gpuError);
                if (!width || !height || surface->inFlight >= 3) {
                    ++surface->skipped;
                    return JNI_FALSE;
                }
                ++surface->inFlight;
                reserved = true;
            }
            if (surface->width != width || surface->height != height) {
                surface->layer.drawableSize = CGSizeMake(width, height);
                surface->width = width;
                surface->height = height;
            }
            id<CAMetalDrawable> drawable = [surface->layer nextDrawable];
            if (!drawable) {
                std::lock_guard<std::mutex> lock(surface->state);
                --surface->inFlight;
                ++surface->skipped;
                surface->idle.notify_all();
                return JNI_FALSE;
            }
            std::lock_guard<std::mutex> encodingLock(surface->context->encoding);
            MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
            pass.colorAttachments[0].texture = drawable.texture;
            pass.colorAttachments[0].loadAction = MTLLoadActionClear;
            pass.colorAttachments[0].storeAction = MTLStoreActionStore;
            pass.colorAttachments[0].clearColor = MTLClearColorMake(0.025, 0.035, 0.06, 1.0);
            id<MTLCommandBuffer> commands = [surface->context->queue commandBuffer];
            if (!commands) throw std::runtime_error("Could not allocate drawable command buffer");
            commands.label = @"MetalMC SDL triangle presentation";
            id<MTLRenderCommandEncoder> render = [commands renderCommandEncoderWithDescriptor:pass];
            if (!render) throw std::runtime_error("Could not begin drawable render pass");
            [render setRenderPipelineState:surface->pipeline];
            [render drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            [render endEncoding];
            [commands presentDrawable:drawable];
            // No JNI on the completion thread. Retain surface/context until retired.
            [commands addCompletedHandler:^(id<MTLCommandBuffer> completed) {
                @autoreleasepool {
                    std::lock_guard<std::mutex> lock(surface->state);
                    if (completed.status != MTLCommandBufferStatusCompleted) {
                        surface->gpuError = "Metal presentation failed: " + describe(completed.error);
                    }
                    ++surface->completed;
                    --surface->inFlight;
                    surface->idle.notify_all();
                }
            }];
            {
                std::lock_guard<std::mutex> lock(surface->state);
                ++surface->submitted;
                surface->peakInFlight = std::max(surface->peakInFlight, surface->inFlight);
            }
            [commands commit];
            reserved = false;
            return JNI_TRUE;
        } catch (const std::exception &error) {
            if (reserved) {
                std::lock_guard<std::mutex> lock(surface->state);
                --surface->inFlight;
                surface->idle.notify_all();
            }
            throwJava(env, "java/lang/IllegalStateException", error.what());
            return JNI_FALSE;
        }
    }
}

JNIEXPORT jlongArray JNICALL Java_dev_metalmc_metal_NativeMetal_surfaceStats(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        auto surface = findSurface(env, handle);
        if (!surface) return nullptr;
        std::lock_guard<std::mutex> lock(surface->state);
        if (!surface->gpuError.empty()) {
            throwJava(env, "java/lang/IllegalStateException", surface->gpuError);
            return nullptr;
        }
        jlong values[] = {static_cast<jlong>(surface->submitted), static_cast<jlong>(surface->completed),
            static_cast<jlong>(surface->skipped), static_cast<jlong>(surface->inFlight),
            static_cast<jlong>(surface->peakInFlight), surface->width, surface->height};
        jlongArray result = env->NewLongArray(7);
        if (result) env->SetLongArrayRegion(result, 0, 7, values);
        return result;
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_drainSurface(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        auto surface = findSurface(env, handle);
        if (!surface) return;
        drain(surface);
        std::lock_guard<std::mutex> lock(surface->state);
        if (!surface->gpuError.empty()) throwJava(env, "java/lang/IllegalStateException", surface->gpuError);
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_destroySurface(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        auto surface = findSurface(env, handle);
        if (!surface) return;
        drain(surface);
        surface->layer.device = nil;
        std::lock_guard<std::mutex> lock(registryMutex);
        surfaces.erase(handle);
    }
}

JNIEXPORT jlong JNICALL Java_dev_metalmc_metal_NativeMetal_createBuffer(
        JNIEnv *env, jclass, jlong contextHandle, jlong size, jstring label) {
    @autoreleasepool {
        if (size <= 0 || size > 256LL * 1024 * 1024) {
            throwJava(env, "java/lang/IllegalArgumentException", "Buffer size must be 1..256 MiB");
            return 0;
        }
        auto context = findContext(env, contextHandle);
        if (!context) return 0;
        try {
            std::lock_guard<std::mutex> lock(registryMutex);
            if (contexts.find(contextHandle) == contexts.end()) throw std::runtime_error("Metal context is closed");
            uint64_t allocated = allocatedResources(context);
            if (allocated + static_cast<uint64_t>(size) > 1024ULL * 1024 * 1024) {
                throw std::runtime_error("Initial Metal buffer/texture allocation budget (1 GiB) exceeded");
            }
            if (static_cast<uint64_t>(size) > context->device.maxBufferLength) {
                throw std::runtime_error("Buffer exceeds Metal device maxBufferLength");
            }
            auto buffer = std::make_shared<Buffer>();
            buffer->context = context;
            buffer->metal = [context->device newBufferWithLength:static_cast<NSUInteger>(size)
                                                        options:MTLResourceStorageModeShared];
            if (!buffer->metal || !buffer->metal.contents) throw std::runtime_error("Could not allocate shared Metal buffer");
            if (label) {
                const jchar *characters = env->GetStringChars(label, nullptr);
                if (!characters) return 0;
                buffer->metal.label = [NSString stringWithCharacters:reinterpret_cast<const unichar *>(characters)
                                                             length:env->GetStringLength(label)];
                env->ReleaseStringChars(label, characters);
            } else {
                buffer->metal.label = @"MetalMC shared buffer";
            }
            // Defined initial bytes; staged partial writes preserve untouched ranges.
            std::memset(buffer->metal.contents, 0, static_cast<size_t>(size));
            buffer->accountedBytes = static_cast<uint64_t>(size);
            context->bufferBytes.fetch_add(buffer->accountedBytes);
            jlong handle = nextHandle.fetch_add(1);
            if (handle <= 0) throw std::runtime_error("Metal handle space exhausted");
            buffers.emplace(handle, std::move(buffer));
            return handle;
        } catch (const std::exception &error) {
            throwJava(env, "java/lang/IllegalStateException", error.what());
            return 0;
        }
    }
}

namespace {
void transferBuffer(JNIEnv *env, jlong handle, jlong offset, jobject data, jint length, bool write) {
    auto buffer = findBuffer(env, handle);
    if (!buffer || !bufferRange(env, buffer, offset, length)) return;
    if (!data) {
        throwJava(env, "java/lang/IllegalArgumentException", "Transfer requires a direct ByteBuffer");
        return;
    }
    if (!write) {
        jclass cls = env->GetObjectClass(data);
        if (!cls) return;
        jmethodID readonly = env->GetMethodID(cls, "isReadOnly", "()Z");
        if (!readonly) return;
        jboolean protectedMemory = env->CallBooleanMethod(data, readonly);
        if (env->ExceptionCheck()) return;
        if (protectedMemory) {
            throwJava(env, "java/lang/IllegalArgumentException", "Readback destination is read-only");
            return;
        }
    }
    jlong capacity = env->GetDirectBufferCapacity(data);
    if (env->ExceptionCheck()) return;
    void *address = env->GetDirectBufferAddress(data);
    if (env->ExceptionCheck()) return;
    if (capacity < length || capacity < 0 || (!address && length)) {
        throwJava(env, "java/lang/IllegalArgumentException", "Transfer requires a sufficiently large direct ByteBuffer");
        return;
    }
    if (!length) return;
    std::lock_guard<std::mutex> lock(buffer->context->encoding);
    if (!cpuAccess(env, buffer)) return;
    char *metal = static_cast<char *>(buffer->metal.contents) + offset;
    if (write) std::memcpy(metal, address, length);
    else std::memcpy(address, metal, length);
}
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_readBuffer(
        JNIEnv *env, jclass, jlong handle, jlong offset, jobject destination, jint length) {
    @autoreleasepool { transferBuffer(env, handle, offset, destination, length, false); }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_writeBuffer(
        JNIEnv *env, jclass, jlong handle, jlong offset, jobject source, jint length) {
    @autoreleasepool { transferBuffer(env, handle, offset, source, length, true); }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_copyBufferDiagnostic(
        JNIEnv *env, jclass, jlong sourceHandle, jlong sourceOffset, jlong destinationHandle,
        jlong destinationOffset, jlong length) {
    @autoreleasepool {
        auto source = findBuffer(env, sourceHandle);
        if (!source) return;
        auto destination = findBuffer(env, destinationHandle);
        if (!destination || !bufferRange(env, source, sourceOffset, length)
                || !bufferRange(env, destination, destinationOffset, length)) return;
        if (source->context != destination->context || source == destination) {
            throwJava(env, "java/lang/IllegalArgumentException", "Diagnostic copies require distinct buffers on the same device");
            return;
        }
        if ((sourceOffset | destinationOffset | length) & 3) {
            throwJava(env, "java/lang/IllegalArgumentException", "Initial diagnostic GPU copies require 4-byte alignment");
            return;
        }
        if (!length) return;
        try {
            std::lock_guard<std::mutex> lock(source->context->encoding);
            if (!cpuAccess(env, source) || !cpuAccess(env, destination)) return;
            id<MTLCommandBuffer> commands = [source->context->queue commandBuffer];
            if (!commands) throw std::runtime_error("Could not allocate buffer copy commands");
            commands.label = @"MetalMC diagnostic buffer copy";
            id<MTLBlitCommandEncoder> blit = [commands blitCommandEncoder];
            if (!blit) throw std::runtime_error("Could not begin buffer blit");
            [blit copyFromBuffer:source->metal sourceOffset:static_cast<NSUInteger>(sourceOffset)
                       toBuffer:destination->metal destinationOffset:static_cast<NSUInteger>(destinationOffset)
                           size:static_cast<NSUInteger>(length)];
            [blit endEncoding];
            [commands commit];
            // Isolated diagnostic. Future game encoders use completion-based retirement.
            [commands waitUntilCompleted];
            if (commands.status != MTLCommandBufferStatusCompleted) {
                throw std::runtime_error("Metal buffer copy failed: " + describe(commands.error));
            }
        } catch (const std::exception &error) {
            throwJava(env, "java/lang/IllegalStateException", error.what());
        }
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_destroyBuffer(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(registryMutex);
        if (buffers.erase(handle) == 0) throwJava(env, "java/lang/IllegalStateException", "Invalid or closed native Metal buffer");
    }
}

namespace {
struct TextureFormat { MTLPixelFormat metal; NSUInteger bytes; };
TextureFormat textureFormat(jint format) {
    switch (format) {
        case 1: return {MTLPixelFormatR8Unorm, 1};
        case 2: return {MTLPixelFormatRG8Unorm, 2};
        case 3: return {MTLPixelFormatRGBA8Unorm, 4};
        case 4: return {MTLPixelFormatR32Float, 4};
        case 5: return {MTLPixelFormatRG32Float, 8};
        case 6: return {MTLPixelFormatRGBA16Float, 8};
        case 7: return {MTLPixelFormatRGBA32Float, 16};
        case 8: return {MTLPixelFormatDepth32Float, 4};
        default: return {MTLPixelFormatInvalid, 0};
    }
}

void *directTransferMemory(JNIEnv *env, jobject data, jint length, bool readback) {
    if (!data) {
        throwJava(env, "java/lang/IllegalArgumentException", "Transfer requires a direct ByteBuffer");
        return nullptr;
    }
    if (readback) {
        jclass cls = env->GetObjectClass(data);
        if (!cls) return nullptr;
        jmethodID readonly = env->GetMethodID(cls, "isReadOnly", "()Z");
        env->DeleteLocalRef(cls);
        if (!readonly) return nullptr;
        jboolean protectedMemory = env->CallBooleanMethod(data, readonly);
        if (env->ExceptionCheck()) return nullptr;
        if (protectedMemory) {
            throwJava(env, "java/lang/IllegalArgumentException", "Readback destination is read-only");
            return nullptr;
        }
    }
    jlong capacity = env->GetDirectBufferCapacity(data);
    if (env->ExceptionCheck()) return nullptr;
    void *address = env->GetDirectBufferAddress(data);
    if (env->ExceptionCheck()) return nullptr;
    if (length <= 0 || capacity < length || !address) {
        throwJava(env, "java/lang/IllegalArgumentException", "Transfer requires a sufficiently large direct ByteBuffer");
        return nullptr;
    }
    return address;
}

void completeDiagnostic(id<MTLCommandBuffer> commands) {
    [commands commit];
    // All calls to this helper are isolated diagnostics, never the game encoder.
    [commands waitUntilCompleted];
    if (commands.status != MTLCommandBufferStatusCompleted) {
        throw std::runtime_error("Metal resource diagnostic failed: " + describe(commands.error));
    }
}
}

JNIEXPORT jlong JNICALL Java_dev_metalmc_metal_NativeMetal_createTexture(
        JNIEnv *env, jclass, jlong contextHandle, jstring label, jint usage, jint formatId,
        jint width, jint height, jint layers, jint mips) {
    @autoreleasepool {
        const auto format = textureFormat(formatId);
        if (!format.bytes || usage <= 0 || (usage & ~15) != 0) {
            throwJava(env, "java/lang/UnsupportedOperationException", "Unsupported native texture format/usage");
            return 0;
        }
        if (width < 1 || height < 1 || width > 8192 || height > 8192 || layers < 1 || layers > 256) {
            throwJava(env, "java/lang/IllegalArgumentException", "Texture dimensions exceed initial limits");
            return 0;
        }
        jint maxMips = 0;
        for (jint size = std::max(width, height); size; size >>= 1) ++maxMips;
        if (mips < 1 || mips > maxMips) {
            throwJava(env, "java/lang/IllegalArgumentException", "Invalid native texture mip count");
            return 0;
        }
        uint64_t bytes = 0;
        for (jint mip = 0; mip < mips; ++mip) {
            bytes += static_cast<uint64_t>(std::max(1, width >> mip)) * std::max(1, height >> mip) * layers * format.bytes;
        }
        if (bytes > 256ULL * 1024 * 1024) {
            throwJava(env, "java/lang/IllegalArgumentException", "Texture exceeds initial 256 MiB cap");
            return 0;
        }
        auto context = findContext(env, contextHandle);
        if (!context) return 0;
        try {
            std::lock_guard<std::mutex> lock(registryMutex);
            if (contexts.find(contextHandle) == contexts.end()) throw std::runtime_error("Metal context is closed");
            uint64_t allocated = allocatedResources(context);
            if (allocated + bytes > 1024ULL * 1024 * 1024) throw std::runtime_error("Metal resource budget exceeded");
            MTLTextureDescriptor *descriptor = [MTLTextureDescriptor new];
            descriptor.textureType = layers == 1 ? MTLTextureType2D : MTLTextureType2DArray;
            descriptor.pixelFormat = format.metal;
            descriptor.width = width;
            descriptor.height = height;
            descriptor.depth = 1;
            descriptor.arrayLength = layers;
            descriptor.mipmapLevelCount = mips;
            descriptor.storageMode = MTLStorageModePrivate;
            descriptor.usage = MTLTextureUsagePixelFormatView;
            if (usage & 4) descriptor.usage |= MTLTextureUsageShaderRead;
            if (usage & 8) descriptor.usage |= MTLTextureUsageRenderTarget;
            auto texture = std::make_shared<Texture>();
            texture->context = context;
            texture->usage = usage;
            texture->bytesPerPixel = format.bytes;
            texture->metal = [context->device newTextureWithDescriptor:descriptor];
            if (!texture->metal) throw std::runtime_error("Could not allocate private Metal texture");
            if (texture->metal.allocatedSize > 256ULL * 1024 * 1024
                    || allocated + texture->metal.allocatedSize > 1024ULL * 1024 * 1024) {
                throw std::runtime_error("Actual Metal texture allocation exceeds initial resource budget");
            }
            texture->metal.label = resourceLabel(env, label, @"MetalMC private texture");
            if (env->ExceptionCheck()) return 0;
            jlong handle = nextHandle.fetch_add(1);
            if (handle <= 0) throw std::runtime_error("Metal handle space exhausted");
            texture->accountedBytes = texture->metal.allocatedSize;
            context->textureBytes.fetch_add(texture->accountedBytes);
            textures.emplace(handle, std::move(texture));
            return handle;
        } catch (const std::exception &error) {
            throwJava(env, "java/lang/IllegalStateException", error.what());
            return 0;
        }
    }
}

JNIEXPORT jlong JNICALL Java_dev_metalmc_metal_NativeMetal_createTextureView(
        JNIEnv *env, jclass, jlong textureHandle, jint baseMip, jint mips) {
    @autoreleasepool {
        auto texture = findResource(env, textureHandle, textures, "texture");
        if (!texture) return 0;
        if (baseMip < 0 || mips < 1 || static_cast<NSUInteger>(baseMip) >= texture->metal.mipmapLevelCount
                || static_cast<NSUInteger>(mips) > texture->metal.mipmapLevelCount - baseMip) {
            throwJava(env, "java/lang/IllegalArgumentException", "Texture view mip range outside texture");
            return 0;
        }
        try {
            std::lock_guard<std::mutex> lock(registryMutex);
            if (textures.find(textureHandle) == textures.end()) throw std::runtime_error("Texture is closed");
            auto view = std::make_shared<TextureView>();
            view->texture = texture;
            view->metal = [texture->metal newTextureViewWithPixelFormat:texture->metal.pixelFormat
                  textureType:texture->metal.textureType levels:NSMakeRange(baseMip, mips)
                       slices:NSMakeRange(0, texture->metal.arrayLength)];
            if (!view->metal) throw std::runtime_error("Could not create Metal mip texture view");
            jlong handle = nextHandle.fetch_add(1);
            if (handle <= 0) throw std::runtime_error("Metal handle space exhausted");
            textureViews.emplace(handle, std::move(view));
            return handle;
        } catch (const std::exception &error) {
            throwJava(env, "java/lang/IllegalStateException", error.what());
            return 0;
        }
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_destroyTextureView(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(registryMutex);
        if (!textureViews.erase(handle)) throwJava(env, "java/lang/IllegalStateException", "Invalid or closed Metal texture view");
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_destroyTexture(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(registryMutex);
        auto texture = textures.find(handle);
        if (texture == textures.end()) {
            throwJava(env, "java/lang/IllegalStateException", "Invalid or closed Metal texture");
            return;
        }
        for (const auto &entry : textureViews) {
            if (entry.second->texture == texture->second) {
                throwJava(env, "java/lang/IllegalStateException", "Close native texture views before their texture");
                return;
            }
        }
        textures.erase(texture);
    }
}

JNIEXPORT jlong JNICALL Java_dev_metalmc_metal_NativeMetal_createSampler(
        JNIEnv *env, jclass, jlong contextHandle, jboolean repeatU, jboolean repeatV,
        jboolean linearMin, jboolean linearMag, jint anisotropy, jdouble maxLod) {
    @autoreleasepool {
        if (anisotropy < 1 || anisotropy > 16 || !std::isfinite(maxLod) || maxLod < 0 || maxLod > FLT_MAX) {
            throwJava(env, "java/lang/IllegalArgumentException", "Invalid sampler anisotropy or LOD");
            return 0;
        }
        auto context = findContext(env, contextHandle);
        if (!context) return 0;
        try {
            std::lock_guard<std::mutex> lock(registryMutex);
            if (contexts.find(contextHandle) == contexts.end()) throw std::runtime_error("Metal context is closed");
            MTLSamplerDescriptor *descriptor = [MTLSamplerDescriptor new];
            descriptor.sAddressMode = repeatU ? MTLSamplerAddressModeRepeat : MTLSamplerAddressModeClampToEdge;
            descriptor.tAddressMode = repeatV ? MTLSamplerAddressModeRepeat : MTLSamplerAddressModeClampToEdge;
            descriptor.rAddressMode = MTLSamplerAddressModeClampToEdge;
            descriptor.minFilter = linearMin ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
            descriptor.magFilter = linearMag ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
            descriptor.mipFilter = MTLSamplerMipFilterLinear;
            descriptor.maxAnisotropy = anisotropy;
            descriptor.lodMinClamp = 0;
            descriptor.lodMaxClamp = static_cast<float>(maxLod);
            descriptor.label = @"MetalMC sampler";
            auto sampler = std::make_shared<Sampler>();
            sampler->context = context;
            sampler->metal = [context->device newSamplerStateWithDescriptor:descriptor];
            if (!sampler->metal) throw std::runtime_error("Could not create Metal sampler");
            jlong handle = nextHandle.fetch_add(1);
            if (handle <= 0) throw std::runtime_error("Metal handle space exhausted");
            samplers.emplace(handle, std::move(sampler));
            return handle;
        } catch (const std::exception &error) {
            throwJava(env, "java/lang/IllegalStateException", error.what());
            return 0;
        }
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_destroySampler(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(registryMutex);
        if (!samplers.erase(handle)) throwJava(env, "java/lang/IllegalStateException", "Invalid or closed Metal sampler");
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_transferTextureDiagnostic(
        JNIEnv *env, jclass, jlong handle, jint mip, jint layer, jobject data, jint length, jboolean upload) {
    @autoreleasepool {
        auto texture = findResource(env, handle, textures, "texture");
        if (!texture) return;
        if (mip < 0 || static_cast<NSUInteger>(mip) >= texture->metal.mipmapLevelCount
                || layer < 0 || static_cast<NSUInteger>(layer) >= texture->metal.arrayLength) {
            throwJava(env, "java/lang/IllegalArgumentException", "Mip/layer outside texture");
            return;
        }
        if (!(texture->usage & (upload ? 1 : 2))) {
            throwJava(env, "java/lang/IllegalStateException", "Native texture copy usage missing");
            return;
        }
        NSUInteger width = std::max(static_cast<NSUInteger>(1), texture->metal.width >> mip);
        NSUInteger height = std::max(static_cast<NSUInteger>(1), texture->metal.height >> mip);
        NSUInteger rowBytes = width * texture->bytesPerPixel;
        if (length < 0 || static_cast<NSUInteger>(length) != rowBytes * height) {
            throwJava(env, "java/lang/IllegalArgumentException", "Transfer must contain one complete mip/layer");
            return;
        }
        void *address = directTransferMemory(env, data, length, !upload);
        if (!address) return;
        try {
            std::lock_guard<std::mutex> lock(texture->context->encoding);
            if (!diagnosticTextureAccess(env, texture)) return;
            NSUInteger alignedRow = (rowBytes + 255) & ~static_cast<NSUInteger>(255);
            id<MTLBuffer> staging = [texture->context->device newBufferWithLength:alignedRow * height
                                                                       options:MTLResourceStorageModeShared];
            if (!staging || !staging.contents) throw std::runtime_error("Could not allocate texture diagnostic staging");
            if (upload) {
                std::memset(staging.contents, 0, alignedRow * height);
                for (NSUInteger y = 0; y < height; ++y) {
                    std::memcpy(static_cast<char *>(staging.contents) + y * alignedRow,
                                static_cast<char *>(address) + y * rowBytes, rowBytes);
                }
            }
            id<MTLCommandBuffer> commands = [texture->context->queue commandBuffer];
            if (!commands) throw std::runtime_error("Could not create texture diagnostic commands");
            commands.label = upload ? @"MetalMC diagnostic texture upload" : @"MetalMC diagnostic texture readback";
            id<MTLBlitCommandEncoder> blit = [commands blitCommandEncoder];
            if (!blit) throw std::runtime_error("Could not create texture diagnostic blit");
            if (upload) {
                [blit copyFromBuffer:staging sourceOffset:0 sourceBytesPerRow:alignedRow sourceBytesPerImage:alignedRow * height
                         sourceSize:MTLSizeMake(width, height, 1) toTexture:texture->metal destinationSlice:layer
                   destinationLevel:mip destinationOrigin:MTLOriginMake(0, 0, 0)];
            } else {
                [blit copyFromTexture:texture->metal sourceSlice:layer sourceLevel:mip sourceOrigin:MTLOriginMake(0, 0, 0)
                           sourceSize:MTLSizeMake(width, height, 1) toBuffer:staging destinationOffset:0
                  destinationBytesPerRow:alignedRow destinationBytesPerImage:alignedRow * height];
            }
            [blit endEncoding];
            completeDiagnostic(commands);
            if (!upload) {
                for (NSUInteger y = 0; y < height; ++y) {
                    std::memcpy(static_cast<char *>(address) + y * rowBytes,
                                static_cast<char *>(staging.contents) + y * alignedRow, rowBytes);
                }
            }
        } catch (const std::exception &error) {
            throwJava(env, "java/lang/IllegalStateException", error.what());
        }
    }
}

JNIEXPORT jfloatArray JNICALL Java_dev_metalmc_metal_NativeMetal_sampleTextureDiagnostic(
        JNIEnv *env, jclass, jlong viewHandle, jlong samplerHandle, jfloat u, jfloat v, jfloat lod) {
    @autoreleasepool {
        auto view = findResource(env, viewHandle, textureViews, "texture view");
        if (!view) return nullptr;
        auto sampler = findResource(env, samplerHandle, samplers, "sampler");
        if (!sampler) return nullptr;
        if (view->texture->context != sampler->context) {
            throwJava(env, "java/lang/IllegalArgumentException", "Sampler/view belong to different devices");
            return nullptr;
        }
        if (!(view->texture->usage & 4) || view->metal.textureType != MTLTextureType2D
                || view->metal.pixelFormat == MTLPixelFormatDepth32Float) {
            throwJava(env, "java/lang/UnsupportedOperationException", "Sampling diagnostic requires a bound color 2D view");
            return nullptr;
        }
        if (!std::isfinite(u) || !std::isfinite(v) || !std::isfinite(lod) || lod < 0) {
            throwJava(env, "java/lang/IllegalArgumentException", "Invalid diagnostic sample coordinates/LOD");
            return nullptr;
        }
        try {
            auto context = sampler->context;
            std::lock_guard<std::mutex> lock(context->encoding);
            if (!diagnosticTextureAccess(env, view->texture)) return nullptr;
            if (!context->samplePipeline) {
                NSString *source = @R"MSL(
#include <metal_stdlib>
using namespace metal;
kernel void sample_diagnostic(texture2d<float> image [[texture(0)]], sampler state [[sampler(0)]],
                              device float4 *output [[buffer(0)]], constant float4 &params [[buffer(1)]]) {
    output[0] = image.sample(state, params.xy, level(params.z));
}
)MSL";
                NSError *error = nil;
                id<MTLLibrary> library = [context->device newLibraryWithSource:source options:nil error:&error];
                if (!library) throw std::runtime_error("Diagnostic sampling shader failed: " + describe(error));
                context->samplePipeline = [context->device newComputePipelineStateWithFunction:
                        [library newFunctionWithName:@"sample_diagnostic"] error:&error];
                if (!context->samplePipeline) throw std::runtime_error("Sampling pipeline failed: " + describe(error));
            }
            id<MTLBuffer> output = [context->device newBufferWithLength:4 * sizeof(float) options:MTLResourceStorageModeShared];
            if (!output || !output.contents) throw std::runtime_error("Could not allocate sample readback");
            id<MTLCommandBuffer> commands = [context->queue commandBuffer];
            if (!commands) throw std::runtime_error("Could not create sampling commands");
            commands.label = @"MetalMC diagnostic texture view/sampler";
            id<MTLComputeCommandEncoder> compute = [commands computeCommandEncoder];
            if (!compute) throw std::runtime_error("Could not create sampling encoder");
            float params[4] = {u, v, lod, 0};
            [compute setComputePipelineState:context->samplePipeline];
            [compute setTexture:view->metal atIndex:0];
            [compute setSamplerState:sampler->metal atIndex:0];
            [compute setBuffer:output offset:0 atIndex:0];
            [compute setBytes:params length:sizeof(params) atIndex:1];
            [compute dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
            [compute endEncoding];
            completeDiagnostic(commands);
            jfloatArray result = env->NewFloatArray(4);
            if (result) env->SetFloatArrayRegion(result, 0, 4, static_cast<const jfloat *>(output.contents));
            return result;
        } catch (const std::exception &error) {
            throwJava(env, "java/lang/IllegalStateException", error.what());
            return nullptr;
        }
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_clearTextureDiagnostic(
        JNIEnv *env, jclass, jlong handle, jint mip, jint layer, jfloat red, jfloat green, jfloat blue, jfloat alpha) {
    @autoreleasepool {
        auto texture = findResource(env, handle, textures, "texture");
        if (!texture) return;
        if (mip < 0 || static_cast<NSUInteger>(mip) >= texture->metal.mipmapLevelCount
                || layer < 0 || static_cast<NSUInteger>(layer) >= texture->metal.arrayLength) {
            throwJava(env, "java/lang/IllegalArgumentException", "Clear mip/layer outside texture");
            return;
        }
        if (!(texture->usage & 8)) {
            throwJava(env, "java/lang/IllegalStateException", "Native texture render-attachment usage missing");
            return;
        }
        bool depth = texture->metal.pixelFormat == MTLPixelFormatDepth32Float;
        if (!std::isfinite(red) || !std::isfinite(green) || !std::isfinite(blue) || !std::isfinite(alpha)
                || (depth && (red < 0 || red > 1))) {
            throwJava(env, "java/lang/IllegalArgumentException", "Invalid texture clear value");
            return;
        }
        try {
            std::lock_guard<std::mutex> lock(texture->context->encoding);
            if (!diagnosticTextureAccess(env, texture)) return;
            MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
            MTLRenderPassAttachmentDescriptor *attachment;
            if (depth) {
                pass.depthAttachment.clearDepth = red;
                attachment = pass.depthAttachment;
            } else {
                pass.colorAttachments[0].clearColor = MTLClearColorMake(red, green, blue, alpha);
                attachment = pass.colorAttachments[0];
            }
            attachment.texture = texture->metal;
            attachment.level = mip;
            attachment.slice = layer;
            attachment.loadAction = MTLLoadActionClear;
            attachment.storeAction = MTLStoreActionStore;
            id<MTLCommandBuffer> commands = [texture->context->queue commandBuffer];
            if (!commands) throw std::runtime_error("Could not create diagnostic clear commands");
            commands.label = @"MetalMC diagnostic render-attachment clear";
            id<MTLRenderCommandEncoder> render = [commands renderCommandEncoderWithDescriptor:pass];
            if (!render) throw std::runtime_error("Could not create diagnostic clear pass");
            [render endEncoding];
            completeDiagnostic(commands);
        } catch (const std::exception &error) {
            throwJava(env, "java/lang/IllegalStateException", error.what());
        }
    }
}

namespace {
void requireRecording(const std::shared_ptr<Batch> &batch) {
    std::lock_guard<std::mutex> lock(batch->state);
    if (batch->submitted || batch->cancelled) throw std::runtime_error("Metal batch is not recording");
    if (batch->render) throw std::runtime_error("Finish the Metal render pass before recording commands");
    if (batch->operations >= 4096) throw std::runtime_error("Submit Metal batch before recording more than 4096 operations");
    std::lock_guard<std::mutex> state(batch->context->submissionState);
    if (!batch->context->commandError.empty()) throw std::runtime_error(batch->context->commandError);
}
void keepBuffer(const std::shared_ptr<Batch> &batch, const std::shared_ptr<Buffer> &buffer) {
    batch->resources.push_back(buffer);
    buffer->recordedUses.fetch_add(1);
}
void keepTexture(const std::shared_ptr<Batch> &batch, const std::shared_ptr<Texture> &texture) {
    batch->textureResources.push_back(texture);
    texture->recordedUses.fetch_add(1);
}

id<MTLBuffer> allocateStaging(const std::shared_ptr<Batch> &batch, NSUInteger length) {
    batch->staging.reserve(batch->staging.size() + 1);
    std::lock_guard<std::mutex> registry(registryMutex);
    if (batch->context->stagingBytes.load() + length > 64ULL * 1024 * 1024
            || allocatedResources(batch->context) + length > 1024ULL * 1024 * 1024) {
        throw std::runtime_error("Metal upload staging budget exceeded (64 MiB staging / 1 GiB resources)");
    }
    id<MTLBuffer> staging = [batch->context->device newBufferWithLength:length options:MTLResourceStorageModeShared];
    if (!staging || !staging.contents) throw std::runtime_error("Could not allocate Metal upload staging");
    batch->staging.push_back(staging);
    batch->context->stagingBytes.fetch_add(length);
    batch->stagingBytes += length;
    return staging;
}

void textureRegion(const std::shared_ptr<Texture> &texture, jint mip, jint layer,
        jint x, jint y, jint width, jint height) {
    if (mip < 0 || static_cast<NSUInteger>(mip) >= texture->metal.mipmapLevelCount
            || layer < 0 || static_cast<NSUInteger>(layer) >= texture->metal.arrayLength
            || x < 0 || y < 0 || width < 1 || height < 1) {
        throw std::invalid_argument("Invalid texture region/mip/layer");
    }
    NSUInteger mipWidth = std::max(static_cast<NSUInteger>(1), texture->metal.width >> mip);
    NSUInteger mipHeight = std::max(static_cast<NSUInteger>(1), texture->metal.height >> mip);
    if (static_cast<NSUInteger>(x) > mipWidth || static_cast<NSUInteger>(width) > mipWidth - x
            || static_cast<NSUInteger>(y) > mipHeight || static_cast<NSUInteger>(height) > mipHeight - y) {
        throw std::invalid_argument("Region exceeds texture mip dimensions");
    }
}

void textureDeviceUsage(const std::shared_ptr<Batch> &batch, const std::shared_ptr<Texture> &texture, jint usage) {
    if (texture->context != batch->context) throw std::invalid_argument("Texture belongs to another Metal device");
    if ((texture->usage & usage) != usage) throw std::runtime_error("Native texture command usage missing");
}

}

namespace {
id<MTLCounterSet> timestampCounterSet(const std::shared_ptr<Context> &context) {
    if (![context->device supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]) return nil;
    for (id<MTLCounterSet> set in context->device.counterSets) {
        if (![set.name isEqualToString:MTLCommonCounterSetTimestamp]) continue;
        for (id<MTLCounter> counter in set.counters) {
            if ([counter.name isEqualToString:MTLCommonCounterTimestamp]) return set;
        }
    }
    return nil;
}
}

JNIEXPORT jboolean JNICALL Java_dev_metalmc_metal_NativeMetal_timestampSupported(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        auto context = findContext(env, handle); if (!context) return JNI_FALSE;
        std::lock_guard<std::mutex> encoding(context->encoding);
        return timestampCounterSet(context) ? JNI_TRUE : JNI_FALSE;
    }
}
JNIEXPORT jlong JNICALL Java_dev_metalmc_metal_NativeMetal_sampleGpuTimestamp(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        auto context = findContext(env, handle); if (!context) return 0;
        std::lock_guard<std::mutex> encoding(context->encoding);
        if (!timestampCounterSet(context)) {
            throwJava(env, "java/lang/UnsupportedOperationException", "Metal timestamp counter/stage sampling is unavailable");
            return 0;
        }
        MTLTimestamp cpu = 0, gpu = 0;
        [context->device sampleTimestamps:&cpu gpuTimestamp:&gpu];
        // Apple Silicon's resolved/sample GPU timestamps use the CPU nanosecond domain.
        // The JVM origin is correlated by bracketed sampling on the Java owner thread.
        if (gpu == MTLCounterErrorValue || gpu > static_cast<uint64_t>(INT64_MAX)) {
            throwJava(env, "java/lang/IllegalStateException", "Invalid native Metal calibration timestamp");
            return 0;
        }
        return static_cast<jlong>(gpu);
    }
}
JNIEXPORT jlong JNICALL Java_dev_metalmc_metal_NativeMetal_createQueryPool(JNIEnv *env, jclass, jlong handle, jint count) {
    @autoreleasepool {
        auto context = findContext(env, handle); if (!context) return 0;
        try {
            if (count < 1 || count > 4096) throw std::invalid_argument("Timestamp pool size must be 1..4096");
            std::lock_guard<std::mutex> encoding(context->encoding);
            auto set = timestampCounterSet(context);
            if (!set) {
                throwJava(env, "java/lang/UnsupportedOperationException", "Metal timestamp counter/stage sampling is unavailable");
                return 0;
            }
            std::lock_guard<std::mutex> registry(registryMutex);
            if (context->queryPoolCount.load() >= 16 || processQueryPoolCount.load() >= 32) {
                throw std::runtime_error("Close or retire timestamp pools before exceeding 16/context or 32/process");
            }
            uint64_t markerBytes = static_cast<uint64_t>(count) * sizeof(uint32_t);
            if (allocatedResources(context) + markerBytes * 2 > 1024ULL * 1024 * 1024) {
                throw std::runtime_error("Timestamp marker exceeds Metal resource budget");
            }
            auto pool = std::make_shared<QueryPool>();
            pool->context = context; pool->slots.resize(count);
            MTLCounterSampleBufferDescriptor *descriptor = [MTLCounterSampleBufferDescriptor new];
            descriptor.counterSet = set;
            descriptor.storageMode = MTLStorageModePrivate;
            descriptor.sampleCount = count;
            descriptor.label = @"MetalMC timestamp query pool";
            NSError *error = nil;
            pool->samples = [context->device newCounterSampleBufferWithDescriptor:descriptor error:&error];
            if (!pool->samples) throw std::runtime_error("Could not allocate timestamp counter samples: " + describe(error));
            pool->markerSource = [context->device newBufferWithLength:markerBytes options:MTLResourceStorageModeShared];
            pool->marker = [context->device newBufferWithLength:markerBytes options:MTLResourceStorageModePrivate];
            if (!pool->markerSource || !pool->markerSource.contents || !pool->marker) {
                throw std::runtime_error("Could not allocate timestamp marker copy buffers");
            }
            pool->markerSource.label = @"MetalMC immutable timestamp marker source";
            pool->marker.label = @"MetalMC per-slot timestamp marker destination";
            auto markerValues = static_cast<uint32_t *>(pool->markerSource.contents);
            for (jint i = 0; i < count; ++i) markerValues[i] = static_cast<uint32_t>(i + 1);
            pool->accountedBytes = markerBytes * 2;
            context->bufferBytes.fetch_add(pool->accountedBytes);
            pool->counted = true;
            context->queryPoolCount.fetch_add(1); processQueryPoolCount.fetch_add(1);
            jlong id = nextHandle.fetch_add(1);
            queryPools.emplace(id, pool);
            return id;
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
        return 0;
    }
}
JNIEXPORT jlong JNICALL Java_dev_metalmc_metal_NativeMetal_queryPoolCount(JNIEnv *env, jclass, jlong handle) {
    auto context = findContext(env, handle);
    return context ? static_cast<jlong>(context->queryPoolCount.load()) : 0;
}
JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_destroyQueryPool(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        std::lock_guard<std::mutex> registry(registryMutex);
        if (!queryPools.erase(handle)) throwJava(env, "java/lang/IllegalStateException", "Invalid or closed Metal query pool");
    }
}
JNIEXPORT jlongArray JNICALL Java_dev_metalmc_metal_NativeMetal_queryValues(
        JNIEnv *env, jclass, jlong handle, jint first, jint count) {
    @autoreleasepool {
        auto pool = findResource(env, handle, queryPools, "timestamp query pool"); if (!pool) return nullptr;
        try {
            if (first < 0 || count < 0 || static_cast<size_t>(first) > pool->slots.size()
                    || static_cast<size_t>(count) > pool->slots.size() - first) {
                throwJava(env, "java/lang/IndexOutOfBoundsException", "Timestamp query range outside pool");
                return nullptr;
            }
            std::vector<jlong> values(static_cast<size_t>(count) * 2, 0);
            {
                std::lock_guard<std::mutex> state(pool->state);
                for (jint i = 0; i < count; ++i) {
                    const auto &slot = pool->slots[first + i];
                    if (slot.state == QueryState::Failed) throw std::runtime_error("Metal timestamp submission or counter resolution failed");
                    if (slot.state == QueryState::Available) {
                        values[i * 2] = 1; values[i * 2 + 1] = static_cast<jlong>(slot.value);
                    }
                }
            }
            jlongArray result = env->NewLongArray(count * 2);
            if (result && count) env->SetLongArrayRegion(result, 0, count * 2, values.data());
            return result;
        } catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
        return nullptr;
    }
}
JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_encodeTimestamp(
        JNIEnv *env, jclass, jlong batchHandle, jlong poolHandle, jint index) {
    @autoreleasepool {
        auto batch = findResource(env, batchHandle, batches, "command batch");
        auto pool = findResource(env, poolHandle, queryPools, "timestamp query pool");
        if (!batch || !pool || env->ExceptionCheck()) return;
        try {
            if (batch->context != pool->context) throw std::invalid_argument("Timestamp pool belongs to another Metal device");
            if (index < 0 || static_cast<size_t>(index) >= pool->slots.size()) {
                throwJava(env, "java/lang/IndexOutOfBoundsException", "Timestamp query index outside pool"); return;
            }
            std::lock_guard<std::mutex> encoding(batch->context->encoding);
            requireRecording(batch);
            std::lock_guard<std::mutex> state(pool->state);
            auto &slot = pool->slots[index];
            if (slot.state == QueryState::Recorded || slot.state == QueryState::Submitted) {
                throw std::runtime_error("Timestamp slot is still recorded or in flight");
            }
            if (slot.state == QueryState::Failed) throw std::runtime_error("Timestamp slot failed");
            batch->queryWrites.reserve(batch->queryWrites.size() + 1);
            auto snapshot = std::find_if(batch->querySnapshots.begin(), batch->querySnapshots.end(),
                    [&](const auto &entry) { return entry.pool == pool; });
            if (snapshot == batch->querySnapshots.end()) {
                batch->querySnapshots.reserve(batch->querySnapshots.size() + 1);
                if (!batch->queryResolveEvent) {
                    batch->queryResolveEvent = [batch->context->device newSharedEvent];
                    if (!batch->queryResolveEvent) throw std::runtime_error("Could not allocate timestamp resolution event");
                    batch->queryResolveEvent.label = @"MetalMC timestamp resolution ordering";
                }
                auto values = allocateStaging(batch, pool->slots.size() * sizeof(MTLCounterResultTimestamp));
                values.label = @"MetalMC timestamp resolution snapshot";
                // Keep unwritten values distinguishable from valid hardware samples.
                std::memset(values.contents, 0, values.length);
                batch->querySnapshots.push_back(QuerySnapshot{pool, values});
            }
            MTLBlitPassDescriptor *pass = [MTLBlitPassDescriptor blitPassDescriptor];
            pass.sampleBufferAttachments[0].sampleBuffer = pool->samples;
            pass.sampleBufferAttachments[0].startOfEncoderSampleIndex = MTLCounterDontSample;
            pass.sampleBufferAttachments[0].endOfEncoderSampleIndex = index;
            // The real-GPU strategy probe showed that an event boundary restores
            // sampling after unsampled blits. Order every marker, including the
            // first after preceding transfers; never reuse a signaled event value.
            uint64_t markerEventValue = ++batch->queryEventValue;
            [batch->commands encodeSignalEvent:batch->queryResolveEvent value:markerEventValue];
            [batch->commands encodeWaitForEvent:batch->queryResolveEvent value:markerEventValue];
            id<MTLBlitCommandEncoder> blit = [batch->commands blitCommandEncoderWithDescriptor:pass];
            if (!blit) throw std::runtime_error("Could not create timestamp sampling blit encoder");
            blit.label = @"MetalMC timestamp stage boundary";
            // The user-run counter matrix validates actual copies into distinct
            // per-slot words. A repeated four-byte fill did not produce all samples.
            NSUInteger markerOffset = static_cast<NSUInteger>(index) * sizeof(uint32_t);
            [blit copyFromBuffer:pool->markerSource sourceOffset:markerOffset
                    toBuffer:pool->marker destinationOffset:markerOffset size:sizeof(uint32_t)];
            [blit endEncoding];
            batch->queryWrites.push_back(QueryWrite{pool, static_cast<NSUInteger>(index)});
            slot.state = QueryState::Recorded; slot.value = 0;
            ++batch->operations;
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}

JNIEXPORT jlong JNICALL Java_dev_metalmc_metal_NativeMetal_createBatch(JNIEnv *env, jclass, jlong contextHandle) {
    @autoreleasepool {
        auto context = findContext(env, contextHandle);
        if (!context) return 0;
        try {
            std::lock_guard<std::mutex> lock(context->encoding);
            auto batch = std::make_shared<Batch>();
            batch->context = context;
            batch->commands = [context->queue commandBuffer];
            if (!batch->commands) throw std::runtime_error("Could not allocate Metal command batch");
            batch->commands.label = @"MetalMC resource submission";
            std::lock_guard<std::mutex> registry(registryMutex);
            if (!contexts.count(contextHandle)) throw std::runtime_error("Metal context is closed");
            // Includes fence IDs. Prevent unbounded development handle accumulation.
            size_t count = 0;
            for (const auto &entry : batches) if (entry.second->context == context) ++count;
            if (count >= 1024) throw std::runtime_error("Close Metal command/fence handles before creating more");
            jlong handle = nextHandle.fetch_add(1);
            batches.emplace(handle, std::move(batch));
            return handle;
        } catch (const std::exception &error) {
            throwJava(env, "java/lang/IllegalStateException", error.what());
            return 0;
        }
    }
}

JNIEXPORT jlong JNICALL Java_dev_metalmc_metal_NativeMetal_retainBatch(JNIEnv *env, jclass, jlong handle) {
    auto batch = findResource(env, handle, batches, "command batch");
    if (!batch) return 0;
    std::lock_guard<std::mutex> registry(registryMutex);
    size_t count = 0;
    for (const auto &entry : batches) if (entry.second->context == batch->context) ++count;
    if (count >= 1024) {
        throwJava(env, "java/lang/IllegalStateException", "Close Metal command/fence handles before creating more");
        return 0;
    }
    jlong retained = nextHandle.fetch_add(1);
    batches.emplace(retained, std::move(batch));
    return retained;
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_releaseBatch(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        std::lock_guard<std::mutex> registry(registryMutex);
        if (!batches.erase(handle)) throwJava(env, "java/lang/IllegalStateException", "Invalid or closed Metal command/fence handle");
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_cancelBatch(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch");
        if (!batch) return;
        std::lock_guard<std::mutex> encoding(batch->context->encoding);
        std::lock_guard<std::mutex> state(batch->state);
        if (batch->submitted) {
            throwJava(env, "java/lang/IllegalStateException", "Cannot cancel submitted Metal commands");
            return;
        }
        if (!batch->cancelled) {
            batch->finishRender();
            batch->cancelled = true;
            batch->retireRecording();
            batch->releaseResources();
            batch->commands = nil;
            batch->ready.notify_all();
        }
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_encodeBufferCopy(
        JNIEnv *env, jclass, jlong handle, jlong sourceHandle, jlong sourceOffset,
        jlong destinationHandle, jlong destinationOffset, jlong length) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch");
        auto source = findBuffer(env, sourceHandle);
        auto destination = findBuffer(env, destinationHandle);
        if (!batch || !source || !destination || !bufferRange(env, source, sourceOffset, length)
                || !bufferRange(env, destination, destinationOffset, length)) return;
        if (batch->context != source->context || batch->context != destination->context || source == destination
                || ((sourceOffset | destinationOffset | length) & 3)) {
            throwJava(env, "java/lang/IllegalArgumentException", "Buffer blits require distinct same-device buffers and 4-byte alignment");
            return;
        }
        try {
            std::lock_guard<std::mutex> encoding(batch->context->encoding);
            requireRecording(batch);
            if (!length) return;
            batch->resources.reserve(batch->resources.size() + 2);
            keepBuffer(batch, source);
            keepBuffer(batch, destination);
            id<MTLBlitCommandEncoder> blit = [batch->commands blitCommandEncoder];
            if (!blit) throw std::runtime_error("Could not create Metal blit encoder");
            [blit copyFromBuffer:source->metal sourceOffset:sourceOffset toBuffer:destination->metal
                  destinationOffset:destinationOffset size:length];
            [blit endEncoding];
            ++batch->operations;
        } catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_encodeBufferWrite(
        JNIEnv *env, jclass, jlong handle, jlong destinationHandle, jlong offset, jobject data, jint length) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch");
        auto destination = findBuffer(env, destinationHandle);
        if (!batch || !destination || !bufferRange(env, destination, offset, length)) return;
        if (batch->context != destination->context || ((offset | length) & 3)) {
            throwJava(env, "java/lang/IllegalArgumentException", "Buffer upload requires same device and 4-byte alignment");
            return;
        }
        void *address = directTransferMemory(env, data, length, false);
        if (env->ExceptionCheck()) return;
        try {
            std::lock_guard<std::mutex> encoding(batch->context->encoding);
            requireRecording(batch);
            if (!length) return;
            batch->resources.reserve(batch->resources.size() + 1);
            id<MTLBuffer> staging = allocateStaging(batch, static_cast<NSUInteger>(length));
            std::memcpy(staging.contents, address, length);
            keepBuffer(batch, destination);
            id<MTLBlitCommandEncoder> blit = [batch->commands blitCommandEncoder];
            if (!blit) throw std::runtime_error("Could not create Metal upload encoder");
            [blit copyFromBuffer:staging sourceOffset:0 toBuffer:destination->metal destinationOffset:offset size:length];
            [blit endEncoding];
            ++batch->operations;
        } catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_submitBatch(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch");
        if (!batch) return;
        try {
            std::lock_guard<std::mutex> encoding(batch->context->encoding);
            // Submitting exactly 4096 operations is legal.
            {
                std::lock_guard<std::mutex> state(batch->state);
                if (batch->submitted || batch->cancelled) throw std::runtime_error("Metal batch is not recording");
                if (batch->render) throw std::runtime_error("Finish the Metal render pass before submitting");
            }
            auto context = batch->context;
            if (!batch->querySnapshots.empty()) {
                // Shared-event ordering includes the counter writes at encoder ends,
                // without waiting on the CPU or relying on counter resource hazards.
                uint64_t resolveEventValue = ++batch->queryEventValue;
                [batch->commands encodeSignalEvent:batch->queryResolveEvent value:resolveEventValue];
                [batch->commands encodeWaitForEvent:batch->queryResolveEvent value:resolveEventValue];
                id<MTLBlitCommandEncoder> resolve = [batch->commands blitCommandEncoder];
                if (!resolve) throw std::runtime_error("Could not create timestamp resolution encoder");
                resolve.label = @"MetalMC timestamp resolution";
                for (const auto &snapshot : batch->querySnapshots) {
                    [resolve resolveCounters:snapshot.pool->samples inRange:NSMakeRange(0, snapshot.pool->slots.size())
                            destinationBuffer:snapshot.values destinationOffset:0];
                }
                [resolve endEncoding];
            }
            {
                std::unique_lock<std::mutex> state(context->submissionState);
                // Only saturation imposes backpressure; normal submit never waits for its own GPU work.
                context->submissionIdle.wait(state, [&] { return context->inFlight < 3; });
                if (!context->commandError.empty()) throw std::runtime_error(context->commandError);
                ++context->submitted;
                ++context->inFlight;
                context->peakInFlight = std::max(context->peakInFlight, context->inFlight);
            }
            {
                std::lock_guard<std::mutex> state(batch->state);
                batch->submitted = true;
                batch->retireRecording();
            }
            [batch->commands addCompletedHandler:^(id<MTLCommandBuffer> completed) {
                @autoreleasepool {
                    std::string error;
                    if (completed.status != MTLCommandBufferStatusCompleted) {
                        error = "Metal resource submission failed: " + describe(completed.error);
                    }
                    {
                        std::lock_guard<std::mutex> state(batch->state);
                        batch->completeQueries(error);
                        batch->error = error;
                        batch->releaseResources();
                        batch->commands = nil; // Break command-buffer/completion-handler ownership cycle.
                        batch->completed = true;
                    }
                    {
                        std::lock_guard<std::mutex> state(context->submissionState);
                        if (!error.empty()) context->commandError = error;
                        ++context->completed;
                        --context->inFlight;
                    }
                    batch->ready.notify_all();
                    context->submissionIdle.notify_all();
                }
            }];
            [batch->commands commit];
        } catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}

JNIEXPORT jboolean JNICALL Java_dev_metalmc_metal_NativeMetal_awaitBatch(JNIEnv *env, jclass, jlong handle, jlong timeout) {
    auto batch = findResource(env, handle, batches, "command fence");
    if (!batch) return JNI_FALSE;
    if (timeout < -1) {
        throwJava(env, "java/lang/IllegalArgumentException", "Invalid fence timeout");
        return JNI_FALSE;
    }
    std::unique_lock<std::mutex> state(batch->state);
    if (!batch->submitted && !batch->cancelled) {
        if (timeout == -1) throwJava(env, "java/lang/IllegalStateException", "Submit commands before waiting indefinitely on their fence");
        return JNI_FALSE;
    }
    if (!batch->completed && !batch->cancelled) {
        if (timeout == -1) batch->ready.wait(state, [&] { return batch->completed; });
        else {
            auto now = std::chrono::steady_clock::now();
            auto available = std::chrono::duration_cast<std::chrono::nanoseconds>(
                    std::chrono::steady_clock::time_point::max() - now).count();
            auto deadline = now + std::chrono::nanoseconds(std::min(timeout, static_cast<jlong>(available)));
            if (!batch->ready.wait_until(state, deadline, [&] { return batch->completed; })) return JNI_FALSE;
        }
    }
    if (batch->cancelled || !batch->error.empty()) {
        throwJava(env, "java/lang/IllegalStateException", batch->cancelled ? "Metal command batch was cancelled" : batch->error);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_drainCommands(JNIEnv *env, jclass, jlong handle) {
    auto context = findContext(env, handle);
    if (!context) return;
    try { drainCommands(context); }
    catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
}

JNIEXPORT jlongArray JNICALL Java_dev_metalmc_metal_NativeMetal_commandStats(JNIEnv *env, jclass, jlong handle) {
    auto context = findContext(env, handle);
    if (!context) return nullptr;
    jlong values[6];
    {
        std::lock_guard<std::mutex> state(context->submissionState);
        values[0] = context->submitted; values[1] = context->completed;
        values[2] = context->inFlight; values[3] = context->peakInFlight;
        values[4] = context->bufferBytes.load() + context->stagingBytes.load();
        values[5] = context->textureBytes.load();
        if (!context->commandError.empty()) {
            throwJava(env, "java/lang/IllegalStateException", context->commandError);
            return nullptr;
        }
    }
    jlongArray result = env->NewLongArray(6);
    if (result) env->SetLongArrayRegion(result, 0, 6, values);
    return result;
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_encodeTextureWrite(
        JNIEnv *env, jclass, jlong batchHandle, jlong textureHandle, jobject data, jint length,
        jint mip, jint layer, jint x, jint y, jint width, jint height) {
    @autoreleasepool {
        auto batch = findResource(env, batchHandle, batches, "command batch");
        auto texture = findResource(env, textureHandle, textures, "texture");
        if (!batch || !texture) return;
        try {
            textureDeviceUsage(batch, texture, 1);
            textureRegion(texture, mip, layer, x, y, width, height);
            NSUInteger row = static_cast<NSUInteger>(width) * texture->bytesPerPixel;
            if (length < 0 || static_cast<NSUInteger>(length) != row * height) {
                throw std::invalid_argument("Upload must contain the complete texture region");
            }
            void *address = directTransferMemory(env, data, length, false);
            if (!address) return;
            std::lock_guard<std::mutex> encoding(batch->context->encoding);
            requireRecording(batch);
            batch->textureResources.reserve(batch->textureResources.size() + 1);
            id<MTLBuffer> staging = allocateStaging(batch, row * height);
            std::memcpy(staging.contents, address, length);
            keepTexture(batch, texture);
            id<MTLBlitCommandEncoder> blit = [batch->commands blitCommandEncoder];
            if (!blit) throw std::runtime_error("Could not create Metal texture upload encoder");
            [blit copyFromBuffer:staging sourceOffset:0 sourceBytesPerRow:row sourceBytesPerImage:0
                     sourceSize:MTLSizeMake(width, height, 1) toTexture:texture->metal destinationSlice:layer
                     destinationLevel:mip destinationOrigin:MTLOriginMake(x, y, 0)];
            [blit endEncoding];
            ++batch->operations;
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_encodeBufferTexture(
        JNIEnv *env, jclass, jlong batchHandle, jlong bufferHandle, jlong offset, jint rowPixels,
        jlong textureHandle, jint mip, jint layer, jint x, jint y, jint width, jint height, jboolean upload) {
    @autoreleasepool {
        auto batch = findResource(env, batchHandle, batches, "command batch");
        auto buffer = findBuffer(env, bufferHandle);
        auto texture = findResource(env, textureHandle, textures, "texture");
        if (!batch || !buffer || !texture) return;
        try {
            textureDeviceUsage(batch, texture, upload ? 1 : 2);
            textureRegion(texture, mip, layer, x, y, width, height);
            if (buffer->context != batch->context || offset < 0 || offset % texture->bytesPerPixel != 0
                    || rowPixels < width || rowPixels > 8192) {
                throw std::invalid_argument("Invalid buffer/texture copy device, pixel offset alignment or row stride");
            }
            NSUInteger row = static_cast<NSUInteger>(rowPixels) * texture->bytesPerPixel;
            NSUInteger span = row * (height - 1) + static_cast<NSUInteger>(width) * texture->bytesPerPixel;
            if (!bufferRange(env, buffer, offset, static_cast<jlong>(span))) return;
            std::lock_guard<std::mutex> encoding(batch->context->encoding);
            requireRecording(batch);
            batch->resources.reserve(batch->resources.size() + 1);
            batch->textureResources.reserve(batch->textureResources.size() + 1);
            keepBuffer(batch, buffer);
            keepTexture(batch, texture);
            id<MTLBlitCommandEncoder> blit = [batch->commands blitCommandEncoder];
            if (!blit) throw std::runtime_error("Could not create Metal buffer/texture copy encoder");
            if (upload) {
                [blit copyFromBuffer:buffer->metal sourceOffset:offset sourceBytesPerRow:row sourceBytesPerImage:0
                     sourceSize:MTLSizeMake(width, height, 1) toTexture:texture->metal destinationSlice:layer
                     destinationLevel:mip destinationOrigin:MTLOriginMake(x, y, 0)];
            } else {
                [blit copyFromTexture:texture->metal sourceSlice:layer sourceLevel:mip sourceOrigin:MTLOriginMake(x, y, 0)
                      sourceSize:MTLSizeMake(width, height, 1) toBuffer:buffer->metal destinationOffset:offset
                      destinationBytesPerRow:row destinationBytesPerImage:0];
            }
            [blit endEncoding];
            ++batch->operations;
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_encodeTextureCopy(
        JNIEnv *env, jclass, jlong batchHandle, jlong sourceHandle, jlong targetHandle, jint mip,
        jint targetX, jint targetY, jint sourceX, jint sourceY, jint width, jint height) {
    @autoreleasepool {
        auto batch = findResource(env, batchHandle, batches, "command batch");
        auto source = findResource(env, sourceHandle, textures, "texture");
        auto target = findResource(env, targetHandle, textures, "texture");
        if (!batch || !source || !target) return;
        try {
            textureDeviceUsage(batch, source, 2);
            textureDeviceUsage(batch, target, 1);
            textureRegion(source, mip, 0, sourceX, sourceY, width, height);
            textureRegion(target, mip, 0, targetX, targetY, width, height);
            if (source == target || source->metal.pixelFormat != target->metal.pixelFormat) {
                throw std::invalid_argument("Texture copies require distinct textures with identical pixel formats");
            }
            std::lock_guard<std::mutex> encoding(batch->context->encoding);
            requireRecording(batch);
            batch->textureResources.reserve(batch->textureResources.size() + 2);
            keepTexture(batch, source); keepTexture(batch, target);
            id<MTLBlitCommandEncoder> blit = [batch->commands blitCommandEncoder];
            if (!blit) throw std::runtime_error("Could not create Metal texture copy encoder");
            [blit copyFromTexture:source->metal sourceSlice:0 sourceLevel:mip sourceOrigin:MTLOriginMake(sourceX, sourceY, 0)
                     sourceSize:MTLSizeMake(width, height, 1) toTexture:target->metal destinationSlice:0
                     destinationLevel:mip destinationOrigin:MTLOriginMake(targetX, targetY, 0)];
            [blit endEncoding];
            ++batch->operations;
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}

namespace {
void encodeClear(const std::shared_ptr<Batch> &batch, const std::shared_ptr<Texture> &texture,
        jfloat red, jfloat green, jfloat blue, jfloat alpha, jdouble depth) {
    if (!texture) return;
    keepTexture(batch, texture);
    for (NSUInteger mip = 0; mip < texture->metal.mipmapLevelCount; ++mip) {
        MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
        MTLRenderPassAttachmentDescriptor *attachment;
        if (texture->metal.pixelFormat == MTLPixelFormatDepth32Float) {
            pass.depthAttachment.clearDepth = depth;
            attachment = pass.depthAttachment;
        } else {
            pass.colorAttachments[0].clearColor = MTLClearColorMake(red, green, blue, alpha);
            attachment = pass.colorAttachments[0];
        }
        attachment.texture = texture->metal;
        attachment.level = mip;
        attachment.slice = 0;
        attachment.loadAction = MTLLoadActionClear;
        attachment.storeAction = MTLStoreActionStore;
        id<MTLRenderCommandEncoder> render = [batch->commands renderCommandEncoderWithDescriptor:pass];
        if (!render) throw std::runtime_error("Could not create Metal texture clear pass");
        [render endEncoding];
        ++batch->operations;
    }
}
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_encodeTextureClear(
        JNIEnv *env, jclass, jlong batchHandle, jlong colorHandle, jfloat red, jfloat green,
        jfloat blue, jfloat alpha, jlong depthHandle, jdouble depth) {
    @autoreleasepool {
        auto batch = findResource(env, batchHandle, batches, "command batch");
        auto color = colorHandle ? findResource(env, colorHandle, textures, "texture") : nullptr;
        auto depthTexture = depthHandle ? findResource(env, depthHandle, textures, "texture") : nullptr;
        if (!batch || env->ExceptionCheck()) return;
        try {
            if (!color && !depthTexture) throw std::invalid_argument("Clear requires a texture");
            if (!std::isfinite(red) || !std::isfinite(green) || !std::isfinite(blue) || !std::isfinite(alpha)
                    || !std::isfinite(depth) || depth < 0 || depth > 1) throw std::invalid_argument("Invalid texture clear values");
            if (color) {
                textureDeviceUsage(batch, color, 1 | 8);
                if (color->metal.arrayLength != 1 || color->metal.pixelFormat == MTLPixelFormatDepth32Float) {
                    throw std::invalid_argument("Color clear requires a single-layer color texture");
                }
            }
            if (depthTexture) {
                textureDeviceUsage(batch, depthTexture, 1 | 8);
                if (depthTexture->metal.arrayLength != 1 || depthTexture->metal.pixelFormat != MTLPixelFormatDepth32Float) {
                    throw std::invalid_argument("Depth clear requires a single-layer depth texture");
                }
            }
            if (color && depthTexture && color->metal.mipmapLevelCount != depthTexture->metal.mipmapLevelCount) {
                throw std::invalid_argument("Color/depth clear mip counts differ");
            }
            std::lock_guard<std::mutex> encoding(batch->context->encoding);
            requireRecording(batch);
            NSUInteger operations = (color ? color->metal.mipmapLevelCount : 0)
                    + (depthTexture ? depthTexture->metal.mipmapLevelCount : 0);
            if (batch->operations + operations > 4096) throw std::runtime_error("Texture clear exceeds batch operation limit");
            batch->textureResources.reserve(batch->textureResources.size() + 2);
            encodeClear(batch, color, red, green, blue, alpha, 0);
            encodeClear(batch, depthTexture, 0, 0, 0, 0, depth);
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}

namespace {
id<MTLRenderPipelineState> scissoredClearPipeline(const std::shared_ptr<Context> &context, MTLPixelFormat format) {
    switch (format) {
        case MTLPixelFormatR8Unorm: case MTLPixelFormatRG8Unorm: case MTLPixelFormatRGBA8Unorm:
        case MTLPixelFormatR32Float: case MTLPixelFormatRG32Float:
        case MTLPixelFormatRGBA16Float: case MTLPixelFormatRGBA32Float: break;
        default: throw std::invalid_argument("Unsupported scissored clear color format");
    }
    auto found = context->clearPipelines.find(format);
    if (found != context->clearPipelines.end()) return found->second;
    NSError *error = nil;
    if (!context->clearLibrary) {
        NSString *source = @R"MSL(
#include <metal_stdlib>
using namespace metal;
struct ClearParams { float4 color; float4 depth; };
struct ClearOutput { float4 color [[color(0)]]; float depth [[depth(any)]]; };
vertex float4 scissored_clear_vertex(uint index [[vertex_id]]) {
    const float2 positions[3] = {float2(-1, -1), float2(3, -1), float2(-1, 3)};
    return float4(positions[index], 0, 1);
}
fragment ClearOutput scissored_clear_fragment(constant ClearParams &params [[buffer(0)]]) {
    return {params.color, params.depth.x};
}
)MSL";
        MTLCompileOptions *options = [MTLCompileOptions new];
        options.languageVersion = MTLLanguageVersion2_3;
        options.fastMathEnabled = NO;
        context->clearLibrary = [context->device newLibraryWithSource:source options:options error:&error];
        if (!context->clearLibrary) throw std::runtime_error("Scissored clear MSL compilation failed: " + describe(error));
    }
    if (!context->clearDepthState) {
        MTLDepthStencilDescriptor *depth = [MTLDepthStencilDescriptor new];
        depth.depthCompareFunction = MTLCompareFunctionAlways;
        depth.depthWriteEnabled = YES;
        context->clearDepthState = [context->device newDepthStencilStateWithDescriptor:depth];
        if (!context->clearDepthState) throw std::runtime_error("Could not create scissored clear depth state");
    }
    MTLRenderPipelineDescriptor *descriptor = [MTLRenderPipelineDescriptor new];
    descriptor.label = @"MetalMC scissored color/depth clear";
    descriptor.vertexFunction = [context->clearLibrary newFunctionWithName:@"scissored_clear_vertex"];
    descriptor.fragmentFunction = [context->clearLibrary newFunctionWithName:@"scissored_clear_fragment"];
    descriptor.colorAttachments[0].pixelFormat = format;
    descriptor.colorAttachments[0].blendingEnabled = NO;
    descriptor.colorAttachments[0].writeMask = MTLColorWriteMaskAll;
    descriptor.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    id<MTLRenderPipelineState> pipeline = [context->device newRenderPipelineStateWithDescriptor:descriptor error:&error];
    if (!pipeline) throw std::runtime_error("Scissored clear pipeline failed: " + describe(error));
    context->clearPipelines.emplace(format, pipeline);
    return pipeline;
}
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_encodeScissoredClear(
        JNIEnv *env, jclass, jlong batchHandle, jlong colorHandle, jfloat red, jfloat green,
        jfloat blue, jfloat alpha, jlong depthHandle, jdouble depth,
        jint x, jint y, jint width, jint height, jint mip) {
    @autoreleasepool {
        auto batch = findResource(env, batchHandle, batches, "command batch");
        auto color = findResource(env, colorHandle, textures, "texture");
        auto depthTexture = findResource(env, depthHandle, textures, "texture");
        if (!batch || !color || !depthTexture || env->ExceptionCheck()) return;
        try {
            textureDeviceUsage(batch, color, 1 | 8);
            textureDeviceUsage(batch, depthTexture, 1 | 8);
            if (color->metal.arrayLength != 1 || depthTexture->metal.arrayLength != 1
                    || color->metal.pixelFormat == MTLPixelFormatDepth32Float
                    || depthTexture->metal.pixelFormat != MTLPixelFormatDepth32Float) {
                throw std::invalid_argument("Scissored clear requires single-layer color and D32 textures");
            }
            if (!std::isfinite(red) || !std::isfinite(green) || !std::isfinite(blue) || !std::isfinite(alpha)
                    || !std::isfinite(depth) || depth < 0 || depth > 1) throw std::invalid_argument("Invalid scissored clear values");
            if (color->metal.mipmapLevelCount != depthTexture->metal.mipmapLevelCount) {
                throw std::invalid_argument("Scissored clear mip counts differ");
            }
            textureRegion(color, mip, 0, x, y, width, height);
            textureRegion(depthTexture, mip, 0, x, y, width, height);
            NSUInteger mipWidth = std::max(static_cast<NSUInteger>(1), color->metal.width >> mip);
            NSUInteger mipHeight = std::max(static_cast<NSUInteger>(1), color->metal.height >> mip);
            if (mipWidth != std::max(static_cast<NSUInteger>(1), depthTexture->metal.width >> mip)
                    || mipHeight != std::max(static_cast<NSUInteger>(1), depthTexture->metal.height >> mip)) {
                throw std::invalid_argument("Scissored clear attachment dimensions differ");
            }
            std::lock_guard<std::mutex> encoding(batch->context->encoding);
            requireRecording(batch);
            auto pipeline = scissoredClearPipeline(batch->context, color->metal.pixelFormat);
            batch->textureResources.reserve(batch->textureResources.size() + 2);
            // Immutable per-command parameters stay budgeted and alive through completion.
            id<MTLBuffer> parameters = allocateStaging(batch, 8 * sizeof(float));
            float values[8] = {red, green, blue, alpha, static_cast<float>(depth), 0, 0, 0};
            std::memcpy(parameters.contents, values, sizeof(values));
            MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
            pass.colorAttachments[0].texture = color->metal;
            pass.colorAttachments[0].level = mip;
            pass.colorAttachments[0].loadAction = MTLLoadActionLoad;
            pass.colorAttachments[0].storeAction = MTLStoreActionStore;
            pass.depthAttachment.texture = depthTexture->metal;
            pass.depthAttachment.level = mip;
            pass.depthAttachment.loadAction = MTLLoadActionLoad;
            pass.depthAttachment.storeAction = MTLStoreActionStore;
            id<MTLRenderCommandEncoder> render = [batch->commands renderCommandEncoderWithDescriptor:pass];
            if (!render) {
                batch->staging.pop_back();
                batch->stagingBytes -= sizeof(values);
                batch->context->stagingBytes.fetch_sub(sizeof(values));
                throw std::runtime_error("Could not create scissored clear render encoder");
            }
            keepTexture(batch, color);
            keepTexture(batch, depthTexture);
            render.label = @"MetalMC scissored color/depth clear";
            [render setRenderPipelineState:pipeline];
            [render setDepthStencilState:batch->context->clearDepthState];
            [render setViewport:MTLViewport{0, 0, static_cast<double>(mipWidth), static_cast<double>(mipHeight), 0, 1}];
            [render setScissorRect:MTLScissorRect{static_cast<NSUInteger>(x), static_cast<NSUInteger>(y),
                static_cast<NSUInteger>(width), static_cast<NSUInteger>(height)}];
            [render setCullMode:MTLCullModeNone];
            [render setTriangleFillMode:MTLTriangleFillModeFill];
            [render setFragmentBuffer:parameters offset:0 atIndex:0];
            [render drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            [render endEncoding];
            ++batch->operations;
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}

namespace {
void requireRender(const std::shared_ptr<Batch> &batch) {
    if (batch->submitted || batch->cancelled || !batch->render) throw std::runtime_error("No open Metal render pass");
}
void attachmentDimensions(const std::shared_ptr<Batch> &batch, const std::shared_ptr<Texture> &texture,
        jint mip, jint width, jint height, bool depth) {
    textureDeviceUsage(batch, texture, 8);
    textureRegion(texture, mip, 0, 0, 0, width, height);
    if (texture->metal.arrayLength != 1
            || (texture->metal.pixelFormat == MTLPixelFormatDepth32Float) != depth
            || static_cast<NSUInteger>(width) != std::max(static_cast<NSUInteger>(1), texture->metal.width >> mip)
            || static_cast<NSUInteger>(height) != std::max(static_cast<NSUInteger>(1), texture->metal.height >> mip)) {
        throw std::invalid_argument("Render attachment aspect/layers/dimensions mismatch");
    }
}
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_beginRenderPass(
        JNIEnv *env, jclass, jlong handle, jlongArray colorArray, jintArray mipArray, jbooleanArray clearArray,
        jfloatArray valueArray, jlong depthHandle, jint depthMip, jboolean clearDepth, jdouble depthValue,
        jint width, jint height, jstring label) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch");
        if (!batch) return;
        try {
            if (!colorArray || !mipArray || !clearArray || !valueArray) throw std::invalid_argument("Missing render attachment arrays");
            jsize count = env->GetArrayLength(colorArray);
            if (count > 8 || env->GetArrayLength(mipArray) != count || env->GetArrayLength(clearArray) != count
                    || env->GetArrayLength(valueArray) != count * 4 || width < 1 || width > 8192 || height < 1 || height > 8192
                    || !std::isfinite(depthValue) || depthValue < 0 || depthValue > 1) {
                throw std::invalid_argument("Invalid render attachment arrays/area/depth");
            }
            jlong handles[8] = {}; jint mips[8] = {}; jboolean clears[8] = {}; jfloat values[32] = {};
            if (count) {
                env->GetLongArrayRegion(colorArray, 0, count, handles);
                if (env->ExceptionCheck()) return;
                env->GetIntArrayRegion(mipArray, 0, count, mips);
                if (env->ExceptionCheck()) return;
                env->GetBooleanArrayRegion(clearArray, 0, count, clears);
                if (env->ExceptionCheck()) return;
                env->GetFloatArrayRegion(valueArray, 0, count * 4, values);
                if (env->ExceptionCheck()) return;
            }
            std::vector<std::shared_ptr<Texture>> retained;
            retained.reserve(count + 1);
            MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
            NSUInteger colorBytes = 0;
            for (jsize i = 0; i < count; ++i) {
                for (unsigned c = 0; c < 4; ++c) if (!std::isfinite(values[i * 4 + c])) throw std::invalid_argument("Invalid clear color");
                if (!handles[i]) {
                    if (clears[i]) throw std::invalid_argument("Cannot clear an unused color slot");
                    continue;
                }
                for (jsize j = 0; j < i; ++j) if (handles[j] == handles[i] && mips[j] == mips[i]) {
                    throw std::invalid_argument("Duplicate color attachment subresource");
                }
                auto texture = findResource(env, handles[i], textures, "texture");
                if (!texture) return;
                attachmentDimensions(batch, texture, mips[i], width, height, false);
                colorBytes += texture->bytesPerPixel;
                if (colorBytes > 32) throw std::invalid_argument("Initial render color attachment limit is 32 bytes per pixel");
                auto attachment = pass.colorAttachments[i];
                attachment.texture = texture->metal;
                attachment.level = mips[i];
                attachment.slice = 0;
                attachment.loadAction = clears[i] ? MTLLoadActionClear : MTLLoadActionLoad;
                attachment.storeAction = MTLStoreActionStore;
                attachment.clearColor = MTLClearColorMake(values[i * 4], values[i * 4 + 1], values[i * 4 + 2], values[i * 4 + 3]);
                retained.push_back(texture);
            }
            if (depthHandle) {
                auto texture = findResource(env, depthHandle, textures, "texture");
                if (!texture) return;
                attachmentDimensions(batch, texture, depthMip, width, height, true);
                pass.depthAttachment.texture = texture->metal;
                pass.depthAttachment.level = depthMip;
                pass.depthAttachment.slice = 0;
                pass.depthAttachment.loadAction = clearDepth ? MTLLoadActionClear : MTLLoadActionLoad;
                pass.depthAttachment.storeAction = MTLStoreActionStore;
                pass.depthAttachment.clearDepth = depthValue;
                retained.push_back(texture);
            } else if (clearDepth) throw std::invalid_argument("Cannot clear a missing depth attachment");
            if (retained.empty()) throw std::invalid_argument("Render pass requires an attachment");
            NSString *debugLabel = resourceLabel(env, label, @"MetalMC render pass");
            if (env->ExceptionCheck()) return;
            std::lock_guard<std::mutex> encoding(batch->context->encoding);
            requireRecording(batch);
            batch->textureResources.reserve(batch->textureResources.size() + retained.size());
            id<MTLRenderCommandEncoder> render = [batch->commands renderCommandEncoderWithDescriptor:pass];
            if (!render) throw std::runtime_error("Could not begin Metal render pass");
            batch->renderColors = {};
            for (jsize i = 0; i < count; ++i) batch->renderColors[i] = pass.colorAttachments[i].texture ? pass.colorAttachments[i].texture.pixelFormat : MTLPixelFormatInvalid;
            batch->renderHasDepth = depthHandle != 0;
            batch->render = render;
            batch->renderWidth = width; batch->renderHeight = height;
            render.label = debugLabel;
            [render setViewport:MTLViewport{0, 0, static_cast<double>(width), static_cast<double>(height), 0, 1}];
            for (const auto &texture : retained) keepTexture(batch, texture);
            batch->renderAttachments = std::move(retained);
            ++batch->operations;
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_endRenderPass(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch");
        if (!batch) return;
        try {
            std::lock_guard<std::mutex> encoding(batch->context->encoding);
            requireRender(batch);
            if (batch->debugGroups) throw std::runtime_error("Pop debug groups before ending Metal render pass");
            batch->finishRender();
        } catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_renderPassDebugGroup(
        JNIEnv *env, jclass, jlong handle, jstring label, jboolean push) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch");
        if (!batch) return;
        try {
            NSString *value = push ? resourceLabel(env, label, @"MetalMC debug group") : nil;
            if (env->ExceptionCheck()) return;
            std::lock_guard<std::mutex> encoding(batch->context->encoding);
            requireRender(batch);
            if ((push && batch->debugGroups == 64) || (!push && !batch->debugGroups)) throw std::runtime_error("Invalid Metal debug group nesting");
            // Count pushes, but allow pops and pass end at the operation cap.
            if (push) {
                if (batch->operations >= 4096) throw std::runtime_error("Render pass exceeds batch operation limit");
                [batch->render pushDebugGroup:value]; ++batch->debugGroups; ++batch->operations;
            } else { [batch->render popDebugGroup]; --batch->debugGroups; }
        } catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_renderPassScissor(
        JNIEnv *env, jclass, jlong handle, jint x, jint y, jint width, jint height) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch");
        if (!batch) return;
        try {
            std::lock_guard<std::mutex> encoding(batch->context->encoding);
            requireRender(batch);
            if (x < 0 || y < 0 || width < 1 || height < 1 || static_cast<NSUInteger>(x) > batch->renderWidth
                    || static_cast<NSUInteger>(width) > batch->renderWidth - x || static_cast<NSUInteger>(y) > batch->renderHeight
                    || static_cast<NSUInteger>(height) > batch->renderHeight - y) throw std::invalid_argument("Scissor outside render area");
            if (batch->operations >= 4096) throw std::runtime_error("Render pass exceeds batch operation limit");
            [batch->render setScissorRect:MTLScissorRect{static_cast<NSUInteger>(x), static_cast<NSUInteger>(y),
                    static_cast<NSUInteger>(width), static_cast<NSUInteger>(height)}];
            ++batch->operations;
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_validateShaderDiagnostic(
        JNIEnv *env, jclass, jlong handle, jstring source, jstring functionName, jint stage) {
    @autoreleasepool {
        auto context = findContext(env, handle);
        if (!context) return;
        try {
            if (!source || !functionName || env->GetStringLength(source) < 1
                    || env->GetStringLength(source) > 8 * 1024 * 1024
                    || env->GetStringLength(functionName) < 1 || env->GetStringLength(functionName) > 1024
                    || (stage != 0 && stage != 1)) throw std::invalid_argument("Invalid diagnostic shader source/name/stage");
            NSString *text = resourceLabel(env, source, nil);
            if (env->ExceptionCheck()) return;
            NSString *name = resourceLabel(env, functionName, nil);
            if (env->ExceptionCheck()) return;
            std::lock_guard<std::mutex> encoding(context->encoding);
            MTLCompileOptions *options = [MTLCompileOptions new];
            options.languageVersion = MTLLanguageVersion2_3;
            // Preserve macOS 14 support; newer SDKs deprecate the legacy spelling.
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
            options.fastMathEnabled = NO;
#pragma clang diagnostic pop
            NSError *error = nil;
            id<MTLLibrary> library = [context->device newLibraryWithSource:text options:options error:&error];
            if (!library) throw std::runtime_error("Translated MSL compilation failed: " + describe(error));
            id<MTLFunction> function = [library newFunctionWithName:name];
            if (!function) throw std::invalid_argument("Compiled MSL does not contain the requested function");
            MTLFunctionType expected = stage == 0 ? MTLFunctionTypeVertex : MTLFunctionTypeFragment;
            if (function.functionType != expected) throw std::invalid_argument("Compiled MSL function stage mismatch");
            // ARC releases library/options/function here. No queue wait, registry ID or draw.
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}

namespace {
std::vector<jint> pipelineArray(JNIEnv *env, jintArray array, jsize quantum, jsize maxCount) {
    if (!array) throw std::invalid_argument("Missing pipeline state array");
    jsize size = env->GetArrayLength(array);
    if (size % quantum || size > maxCount * quantum) throw std::invalid_argument("Invalid pipeline state array size");
    std::vector<jint> values(size);
    if (size) env->GetIntArrayRegion(array, 0, size, values.data());
    return values;
}
id<MTLFunction> pipelineFunction(JNIEnv *env, const std::shared_ptr<Context> &context,
        jstring source, jstring entry, MTLFunctionType stage) {
    if (!source || !entry || env->GetStringLength(source) < 1 || env->GetStringLength(source) > 8 * 1024 * 1024
            || env->GetStringLength(entry) < 1 || env->GetStringLength(entry) > 1024) throw std::invalid_argument("Invalid pipeline shader source/entry");
    NSString *text = resourceLabel(env, source, nil);
    if (env->ExceptionCheck()) return nil;
    NSString *name = resourceLabel(env, entry, nil);
    if (env->ExceptionCheck()) return nil;
    MTLCompileOptions *options = [MTLCompileOptions new];
    options.languageVersion = MTLLanguageVersion2_3;
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    options.fastMathEnabled = NO;
#pragma clang diagnostic pop
    NSError *error = nil;
    id<MTLLibrary> library = [context->device newLibraryWithSource:text options:options error:&error];
    if (!library) throw std::runtime_error("Pipeline MSL compilation failed: " + describe(error));
    id<MTLFunction> function = [library newFunctionWithName:name];
    if (!function || function.functionType != stage) throw std::invalid_argument("Pipeline entry point/stage mismatch");
    return function;
}
MTLBlendFactor pipelineBlendFactor(jint factor) {
    switch (factor) {
        case 0: return MTLBlendFactorZero; case 1: return MTLBlendFactorOne;
        case 2: return MTLBlendFactorSourceColor; case 3: return MTLBlendFactorOneMinusSourceColor;
        case 4: return MTLBlendFactorSourceAlpha; case 5: return MTLBlendFactorOneMinusSourceAlpha;
        case 6: return MTLBlendFactorDestinationColor; case 7: return MTLBlendFactorOneMinusDestinationColor;
        case 8: return MTLBlendFactorDestinationAlpha; case 9: return MTLBlendFactorOneMinusDestinationAlpha;
        case 10: return MTLBlendFactorSourceAlphaSaturated;
        default: throw std::invalid_argument("Unsupported pipeline blend factor");
    }
}
MTLBlendOperation pipelineBlendOp(jint op) {
    switch (op) {
        case 0: return MTLBlendOperationAdd; case 1: return MTLBlendOperationSubtract;
        case 2: return MTLBlendOperationReverseSubtract; case 3: return MTLBlendOperationMin; case 4: return MTLBlendOperationMax;
        default: throw std::invalid_argument("Unsupported blend operation");
    }
}
void renderOperation(const std::shared_ptr<Batch> &batch) {
    requireRender(batch);
    if (batch->operations >= 4096) throw std::runtime_error("Render batch operation limit exceeded");
}
void uniformIndex(const std::shared_ptr<Batch> &batch, jint index, jint kind) {
    renderOperation(batch);
    if (!batch->currentPipeline) throw std::runtime_error("Set pipeline before binding shader resources");
    const auto &types = batch->currentPipeline->uniformTypes;
    if (index < 0 || static_cast<size_t>(index) >= types.size() || (kind >= 0 && types[index] != kind)) {
        throw std::invalid_argument("Uniform index/type does not match pipeline");
    }
}
void bindShaderBuffer(const std::shared_ptr<Batch> &batch, const ShaderBinding &binding, id<MTLBuffer> buffer, NSUInteger offset) {
    if (binding.stage == 0) [batch->render setVertexBuffer:buffer offset:offset atIndex:binding.buffer];
    else [batch->render setFragmentBuffer:buffer offset:offset atIndex:binding.buffer];
}
void bindShaderTexture(const std::shared_ptr<Batch> &batch, const ShaderBinding &binding, id<MTLTexture> texture, id<MTLSamplerState> sampler) {
    if (binding.stage == 0) {
        [batch->render setVertexTexture:texture atIndex:binding.texture];
        [batch->render setVertexSamplerState:sampler atIndex:binding.sampler];
    } else {
        [batch->render setFragmentTexture:texture atIndex:binding.texture];
        [batch->render setFragmentSamplerState:sampler atIndex:binding.sampler];
    }
}
// Counts/ranges are checked before encoding. Index contents remain a caller invariant;
// they can be produced by earlier GPU transfers, so inspecting CPU contents here is unsafe.
bool validateDraw(const std::shared_ptr<Batch> &batch, bool indexed, jint count, jint instances,
        jint first, jint baseVertex, jint firstInstance) {
    auto pipeline = batch->currentPipeline;
    if (!pipeline) throw std::runtime_error("Set pipeline before drawing");
    if (count < 0 || instances < 0 || first < 0 || firstInstance < 0) throw std::invalid_argument("Negative draw range");
    if (!count || !instances) return false;
    for (const auto &binding : pipeline->shaderBindings) {
        if (binding.kind != 2 && !batch->uniformBound[binding.index]) throw std::runtime_error("Missing active shader uniform");
    }
    if (pipeline->pushConstantsSize && !batch->constantsBound) throw std::runtime_error("Push constants required before drawing");
    if (indexed) {
        if (!batch->indexBuffer) throw std::runtime_error("Set index buffer before indexed drawing");
        uint64_t end = static_cast<uint64_t>(first) + count;
        if (end > batch->indexBuffer->metal.length / batch->indexBytes) throw std::invalid_argument("Indexed draw exceeds index buffer");
    }
    for (unsigned slot = 0; slot < 16; ++slot) {
        if (!pipeline->strides[slot]) continue;
        const auto &binding = batch->vertexBindings[slot];
        if (!binding.buffer) throw std::runtime_error("Missing required vertex binding");
        uint64_t last;
        if (pipeline->steps[slot]) last = static_cast<uint64_t>(firstInstance) + instances - 1;
        else if (!indexed) last = static_cast<uint64_t>(first) + count - 1;
        else last = baseVertex > 0 ? static_cast<uint64_t>(baseVertex) : 0;
        uint64_t span = last * pipeline->strides[slot] + pipeline->extents[slot];
        if (span > binding.length) throw std::invalid_argument("Draw exceeds vertex/instance slice bounds known at encoding");
    }
    return true;
}
void encodeDraw(const std::shared_ptr<Batch> &batch, bool indexed, jint count, jint instances,
        jint first, jint baseVertex, jint firstInstance) {
    auto pipeline = batch->currentPipeline;
    if (indexed) {
        [batch->render drawIndexedPrimitives:pipeline->primitive indexCount:count
                indexType:batch->indexBytes == 2 ? MTLIndexTypeUInt16 : MTLIndexTypeUInt32
                indexBuffer:batch->indexBuffer->metal indexBufferOffset:static_cast<NSUInteger>(first) * batch->indexBytes
                instanceCount:instances baseVertex:baseVertex baseInstance:firstInstance];
    } else {
        [batch->render drawPrimitives:pipeline->primitive vertexStart:first vertexCount:count
                instanceCount:instances baseInstance:firstInstance];
    }
    ++batch->operations;
}
}

JNIEXPORT jlong JNICALL Java_dev_metalmc_metal_NativeMetal_createRenderPipeline(
        JNIEnv *env, jclass, jlong handle, jstring label, jstring vertexSource, jstring vertexEntry,
        jstring fragmentSource, jstring fragmentEntry, jintArray layoutArray, jintArray attributeArray,
        jintArray colorArray, jintArray stateArray, jfloat depthBias, jfloat slopeBias,
        jintArray uniformArray, jintArray resourceArray, jint pushConstantsSize) {
    @autoreleasepool {
        auto context = findContext(env, handle);
        if (!context) return 0;
        try {
            auto layouts = pipelineArray(env, layoutArray, 3, 16); if (env->ExceptionCheck()) return 0;
            auto attributes = pipelineArray(env, attributeArray, 4, 16); if (env->ExceptionCheck()) return 0;
            auto colors = pipelineArray(env, colorArray, 9, 8); if (env->ExceptionCheck()) return 0;
            auto state = pipelineArray(env, stateArray, 5, 1); if (env->ExceptionCheck()) return 0;
            auto uniformTypes = pipelineArray(env, uniformArray, 1, 64); if (env->ExceptionCheck()) return 0;
            auto resourceLayout = pipelineArray(env, resourceArray, 8, 62); if (env->ExceptionCheck()) return 0;
            if (state.size() != 5 || state[0] < 0 || state[0] > 7 || state[1] < 0 || state[1] > 1
                    || state[2] < 0 || state[2] > 1 || state[3] < 0 || state[3] > 1 || state[4] < 0 || state[4] > 4
                    || !std::isfinite(depthBias) || !std::isfinite(slopeBias)) throw std::invalid_argument("Invalid pipeline raster/depth state");
            auto pipeline = std::make_shared<RenderPipeline>(); pipeline->context = context;
            if (pushConstantsSize < 0 || pushConstantsSize > 4096 || pushConstantsSize % 4) throw std::invalid_argument("Invalid native push constant size");
            for (jint type : uniformTypes) if (type != 0 && type != 1) throw std::invalid_argument("Unsupported native uniform type");
            pipeline->uniformTypes = uniformTypes; pipeline->pushConstantsSize = pushConstantsSize;
            std::array<std::array<bool, 31>, 2> bufferSlots{};
            std::array<std::array<bool, 16>, 2> textureSlots{}, samplerSlots{};
            std::array<std::array<bool, 65>, 2> indices{};
            std::array<jint, 64> imageDimensions; imageDimensions.fill(-1);
            for (size_t i = 0; i < resourceLayout.size(); i += 8) {
                ShaderBinding b{resourceLayout[i], resourceLayout[i+1], resourceLayout[i+2], resourceLayout[i+3],
                        resourceLayout[i+4], resourceLayout[i+5], resourceLayout[i+6], resourceLayout[i+7]};
                if (b.stage < 0 || b.stage > 1 || b.kind < 0 || b.kind > 2 || b.arrayed < 0 || b.arrayed > 1) throw std::invalid_argument("Invalid native shader binding stage/kind/array");
                if (b.kind == 2) {
                    if (b.index != -1 || b.buffer != 30 || b.bytes < 1 || b.bytes > pushConstantsSize) throw std::invalid_argument("Invalid native push binding");
                } else if (b.index < 0 || static_cast<size_t>(b.index) >= uniformTypes.size() || uniformTypes[b.index] != b.kind) {
                    throw std::invalid_argument("Native descriptor index/type mismatch");
                }
                unsigned index = b.kind == 2 ? 64 : b.index;
                if (indices[b.stage][index]) throw std::invalid_argument("Duplicate native stage descriptor");
                indices[b.stage][index] = true;
                if (b.kind == 1) {
                    if (b.buffer != -1 || b.bytes != 0 || b.texture < 0 || b.texture > 15 || b.sampler < 0 || b.sampler > 15
                            || textureSlots[b.stage][b.texture] || samplerSlots[b.stage][b.sampler]) throw std::invalid_argument("Invalid/duplicate native texture/sampler slot");
                    textureSlots[b.stage][b.texture] = true; samplerSlots[b.stage][b.sampler] = true;
                    if (imageDimensions[b.index] >= 0 && imageDimensions[b.index] != b.arrayed) throw std::invalid_argument("Native shader stages disagree on texture dimension");
                    imageDimensions[b.index] = b.arrayed;
                } else {
                    if (b.texture != -1 || b.sampler != -1 || b.arrayed || b.buffer < 16 || b.buffer > 30
                            || (b.kind == 0 && (b.buffer == 30 || b.bytes < 1 || b.bytes > 65536))
                            || bufferSlots[b.stage][b.buffer]) throw std::invalid_argument("Invalid/duplicate native uniform slot");
                    bufferSlots[b.stage][b.buffer] = true;
                }
                pipeline->shaderBindings.push_back(b);
            }
            const MTLPrimitiveType primitives[] = {MTLPrimitiveTypePoint, MTLPrimitiveTypeLine, MTLPrimitiveTypeLineStrip, MTLPrimitiveTypeTriangle, MTLPrimitiveTypeTriangleStrip};
            pipeline->primitive = primitives[state[4]];
            pipeline->fill = state[2] ? MTLTriangleFillModeLines : MTLTriangleFillModeFill;
            pipeline->cull = state[3] ? MTLCullModeBack : MTLCullModeNone;
            pipeline->depthBias = depthBias; pipeline->slopeBias = slopeBias;
            MTLVertexDescriptor *vertices = [MTLVertexDescriptor vertexDescriptor];
            for (size_t i = 0; i < layouts.size(); i += 3) {
                jint slot = layouts[i], stride = layouts[i + 1], step = layouts[i + 2];
                if (slot < 0 || slot > 15 || stride < 4 || stride > 2048 || stride % 4 || step < 0 || step > 1 || pipeline->strides[slot]) {
                    throw std::invalid_argument("Invalid/duplicate native vertex layout");
                }
                pipeline->strides[slot] = stride; pipeline->steps[slot] = step;
                vertices.layouts[slot].stride = stride;
                vertices.layouts[slot].stepFunction = step ? MTLVertexStepFunctionPerInstance : MTLVertexStepFunctionPerVertex;
                vertices.layouts[slot].stepRate = 1;
            }
            std::array<bool, 16> locations{};
            const MTLVertexFormat formats[] = {MTLVertexFormatFloat, MTLVertexFormatFloat2, MTLVertexFormatFloat3,
                MTLVertexFormatFloat4, MTLVertexFormatUChar4Normalized, MTLVertexFormatShort2,
                MTLVertexFormatChar4Normalized, MTLVertexFormatInt3};
            const NSUInteger formatBytes[] = {4, 8, 12, 16, 4, 4, 4, 12};
            const NSUInteger formatAlignment[] = {4, 4, 4, 4, 1, 2, 1, 4};
            for (size_t i = 0; i < attributes.size(); i += 4) {
                jint slot = attributes[i], location = attributes[i + 1], offset = attributes[i + 2], format = attributes[i + 3];
                if (slot < 0 || slot > 15 || location < 0 || location > 15 || locations[location]
                        || offset < 0 || format < 0 || format > 7 || !pipeline->strides[slot]) throw std::invalid_argument("Invalid native vertex attribute");
                NSUInteger bytes = formatBytes[format];
                if (offset % formatAlignment[format] || static_cast<NSUInteger>(offset) + bytes > pipeline->strides[slot]) throw std::invalid_argument("Vertex attribute exceeds stride");
                locations[location] = true;
                pipeline->extents[slot] = std::max(pipeline->extents[slot], offset + bytes);
                vertices.attributes[location].bufferIndex = slot; vertices.attributes[location].offset = offset;
                vertices.attributes[location].format = formats[format];
            }
            for (unsigned slot = 0; slot < 16; ++slot) if (pipeline->strides[slot] && !pipeline->extents[slot]) throw std::invalid_argument("Unused native vertex layout");
            MTLRenderPipelineDescriptor *descriptor = [MTLRenderPipelineDescriptor new];
            NSUInteger colorBytes = 0;
            for (size_t i = 0; i < colors.size(); i += 9) {
                auto target = descriptor.colorAttachments[i / 9];
                if (colors[i] == -1) continue;
                auto format = textureFormat(colors[i]);
                if (!format.bytes || format.metal == MTLPixelFormatDepth32Float || colors[i + 1] < 0 || colors[i + 1] > 15
                        || colors[i + 2] < 0 || colors[i + 2] > 1) throw std::invalid_argument("Invalid native color target");
                colorBytes += format.bytes;
                if (colorBytes > 32) throw std::invalid_argument("Pipeline color targets exceed 32 bytes per pixel");
                if (colors[i + 2] && (format.metal == MTLPixelFormatR32Float || format.metal == MTLPixelFormatRG32Float || format.metal == MTLPixelFormatRGBA32Float)) {
                    throw std::invalid_argument("Initial pipeline does not blend 32-bit float targets");
                }
                pipeline->colors[i / 9] = format.metal; target.pixelFormat = format.metal;
                jint mask = colors[i + 1];
                target.writeMask = static_cast<MTLColorWriteMask>((mask & 1 ? MTLColorWriteMaskRed : 0)
                        | (mask & 2 ? MTLColorWriteMaskGreen : 0) | (mask & 4 ? MTLColorWriteMaskBlue : 0) | (mask & 8 ? MTLColorWriteMaskAlpha : 0));
                target.blendingEnabled = colors[i + 2];
                if (target.blendingEnabled) {
                    target.sourceRGBBlendFactor = pipelineBlendFactor(colors[i + 3]); target.destinationRGBBlendFactor = pipelineBlendFactor(colors[i + 4]);
                    target.rgbBlendOperation = pipelineBlendOp(colors[i + 5]); target.sourceAlphaBlendFactor = pipelineBlendFactor(colors[i + 6]);
                    target.destinationAlphaBlendFactor = pipelineBlendFactor(colors[i + 7]); target.alphaBlendOperation = pipelineBlendOp(colors[i + 8]);
                }
            }
            NSString *debugLabel = resourceLabel(env, label, @"MetalMC backend pipeline"); if (env->ExceptionCheck()) return 0;
            std::lock_guard<std::mutex> encoding(context->encoding);
            if (context->pipelineCount.load() >= 256) throw std::runtime_error("Initial context pipeline limit is 256 including retained pipelines");
            descriptor.label = debugLabel; descriptor.vertexDescriptor = vertices;
            descriptor.vertexFunction = pipelineFunction(env, context, vertexSource, vertexEntry, MTLFunctionTypeVertex); if (env->ExceptionCheck()) return 0;
            descriptor.fragmentFunction = pipelineFunction(env, context, fragmentSource, fragmentEntry, MTLFunctionTypeFragment); if (env->ExceptionCheck()) return 0;
            descriptor.inputPrimitiveTopology = state[4] == 0 ? MTLPrimitiveTopologyClassPoint : state[4] <= 2 ? MTLPrimitiveTopologyClassLine : MTLPrimitiveTopologyClassTriangle;
            NSError *error = nil;
            descriptor.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
            pipeline->withDepth = [context->device newRenderPipelineStateWithDescriptor:descriptor error:&error];
            if (!pipeline->withDepth) throw std::runtime_error("Metal pipeline (D32) compilation failed: " + describe(error));
            descriptor.depthAttachmentPixelFormat = MTLPixelFormatInvalid; error = nil;
            pipeline->withoutDepth = [context->device newRenderPipelineStateWithDescriptor:descriptor error:&error];
            if (!pipeline->withoutDepth) throw std::runtime_error("Metal pipeline (no depth) compilation failed: " + describe(error));
            MTLDepthStencilDescriptor *depth = [MTLDepthStencilDescriptor new];
            const MTLCompareFunction compares[] = {MTLCompareFunctionNever, MTLCompareFunctionLess, MTLCompareFunctionEqual,
                    MTLCompareFunctionLessEqual, MTLCompareFunctionGreater, MTLCompareFunctionNotEqual, MTLCompareFunctionGreaterEqual, MTLCompareFunctionAlways};
            depth.depthCompareFunction = compares[state[0]]; depth.depthWriteEnabled = state[1];
            pipeline->depthState = [context->device newDepthStencilStateWithDescriptor:depth];
            depth.depthCompareFunction = MTLCompareFunctionAlways; depth.depthWriteEnabled = NO;
            pipeline->noDepthState = [context->device newDepthStencilStateWithDescriptor:depth];
            if (!pipeline->depthState || !pipeline->noDepthState) throw std::runtime_error("Could not create Metal depth state");
            std::lock_guard<std::mutex> registry(registryMutex);
            jlong id = nextHandle.fetch_add(1);
            renderPipelines.emplace(id, pipeline);
            context->pipelineCount.fetch_add(1); pipeline->counted = true;
            return id;
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
        return 0;
    }
}
JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_destroyRenderPipeline(JNIEnv *env, jclass, jlong handle) {
    @autoreleasepool {
        std::lock_guard<std::mutex> registry(registryMutex);
        if (!renderPipelines.erase(handle)) throwJava(env, "java/lang/IllegalStateException", "Invalid or closed native Metal pipeline");
    }
}
JNIEXPORT jlong JNICALL Java_dev_metalmc_metal_NativeMetal_pipelineCount(JNIEnv *env, jclass, jlong handle) {
    auto context = findContext(env, handle);
    return context ? static_cast<jlong>(context->pipelineCount.load()) : 0;
}
JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_renderPassPipeline(JNIEnv *env, jclass, jlong handle, jlong pipelineHandle) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch"); if (!batch) return;
        auto pipeline = findResource(env, pipelineHandle, renderPipelines, "render pipeline"); if (!pipeline) return;
        try {
            std::lock_guard<std::mutex> encoding(batch->context->encoding); renderOperation(batch);
            if (pipeline->context != batch->context || pipeline->colors != batch->renderColors) throw std::invalid_argument("Pipeline device/color attachment formats mismatch");
            batch->pipelineResources.push_back(pipeline);
            [batch->render setRenderPipelineState:batch->renderHasDepth ? pipeline->withDepth : pipeline->withoutDepth];
            [batch->render setDepthStencilState:batch->renderHasDepth ? pipeline->depthState : pipeline->noDepthState];
            [batch->render setTriangleFillMode:pipeline->fill]; [batch->render setCullMode:pipeline->cull];
            [batch->render setFrontFacingWinding:MTLWindingCounterClockwise];
            [batch->render setDepthBias:pipeline->depthBias slopeScale:pipeline->slopeBias clamp:0];
            batch->currentPipeline = pipeline; ++batch->operations;
            // All shader resources must be rebound on a pipeline switch, including the same PSO.
            batch->uniformBound = {}; batch->constantsBound = false;
            for (const auto &binding : pipeline->shaderBindings) {
                if (binding.kind == 1) bindShaderTexture(batch, binding, nil, nil);
                else bindShaderBuffer(batch, binding, nil, 0);
            }
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}
JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_renderPassVertexBuffer(
        JNIEnv *env, jclass, jlong handle, jint slot, jlong bufferHandle, jlong offset, jlong length) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch"); if (!batch) return;
        auto buffer = findBuffer(env, bufferHandle); if (!buffer) return;
        try {
            std::lock_guard<std::mutex> encoding(batch->context->encoding); renderOperation(batch);
            if (slot < 0 || slot > 15 || offset < 0 || offset % 4 || length < 1 || buffer->context != batch->context) throw std::invalid_argument("Invalid vertex binding/device");
            if (!bufferRange(env, buffer, offset, length)) return;
            batch->resources.reserve(batch->resources.size() + 1);
            keepBuffer(batch, buffer);
            [batch->render setVertexBuffer:buffer->metal offset:offset atIndex:slot];
            batch->vertexBindings[slot] = VertexBinding{buffer, static_cast<NSUInteger>(offset), static_cast<NSUInteger>(length)};
            ++batch->operations;
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}
JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_renderPassDraw(
        JNIEnv *env, jclass, jlong handle, jint vertexCount, jint instanceCount, jint firstVertex, jint firstInstance) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch"); if (!batch) return;
        try {
            std::lock_guard<std::mutex> encoding(batch->context->encoding); renderOperation(batch);
            if (validateDraw(batch, false, vertexCount, instanceCount, firstVertex, 0, firstInstance)) {
                encodeDraw(batch, false, vertexCount, instanceCount, firstVertex, 0, firstInstance);
            }
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_renderPassClearUniform(JNIEnv *env, jclass, jlong handle, jint index) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch"); if (!batch) return;
        try {
            std::lock_guard<std::mutex> encoding(batch->context->encoding); uniformIndex(batch, index, -1);
            for (const auto &binding : batch->currentPipeline->shaderBindings) if (binding.index == index) {
                if (binding.kind == 1) bindShaderTexture(batch, binding, nil, nil);
                else bindShaderBuffer(batch, binding, nil, 0);
            }
            batch->uniformBound[index] = false; ++batch->operations;
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}
JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_renderPassUniformBuffer(
        JNIEnv *env, jclass, jlong handle, jint index, jlong bufferHandle, jlong offset, jlong length) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch"); if (!batch) return;
        auto buffer = findBuffer(env, bufferHandle); if (!buffer) return;
        try {
            std::lock_guard<std::mutex> encoding(batch->context->encoding); uniformIndex(batch, index, 0);
            if (offset < 0 || offset % 4 || length < 1 || buffer->context != batch->context) throw std::invalid_argument("Invalid uniform buffer device/range/alignment");
            if (!bufferRange(env, buffer, offset, length)) return;
            for (const auto &binding : batch->currentPipeline->shaderBindings) if (binding.index == index && length < binding.bytes) {
                throw std::invalid_argument("Uniform slice is smaller than an active shader block");
            }
            batch->resources.reserve(batch->resources.size() + 1); keepBuffer(batch, buffer);
            for (const auto &binding : batch->currentPipeline->shaderBindings) if (binding.index == index) bindShaderBuffer(batch, binding, buffer->metal, offset);
            batch->uniformBound[index] = true; ++batch->operations;
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}
JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_renderPassUniformTexture(
        JNIEnv *env, jclass, jlong handle, jint index, jlong viewHandle, jlong samplerHandle) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch"); if (!batch) return;
        auto view = findResource(env, viewHandle, textureViews, "texture view"); if (!view) return;
        auto sampler = findResource(env, samplerHandle, samplers, "sampler"); if (!sampler) return;
        try {
            std::lock_guard<std::mutex> encoding(batch->context->encoding); uniformIndex(batch, index, 1);
            if (view->texture->context != batch->context || sampler->context != batch->context || !(view->texture->usage & 4)
                    || view->metal.pixelFormat == MTLPixelFormatDepth32Float) throw std::invalid_argument("Invalid sampled texture/sampler device/usage/aspect");
            // Feedback needs explicit frontend/API semantics; conservatively reject any attached storage.
            for (const auto &attachment : batch->renderAttachments) if (attachment == view->texture) throw std::invalid_argument("Cannot sample render attachment storage in the same pass");
            bool arrayed = view->metal.textureType == MTLTextureType2DArray;
            for (const auto &binding : batch->currentPipeline->shaderBindings) if (binding.index == index && static_cast<bool>(binding.arrayed) != arrayed) {
                throw std::invalid_argument("Sampled texture dimension does not match active shader");
            }
            batch->textureResources.reserve(batch->textureResources.size() + 1);
            batch->viewResources.reserve(batch->viewResources.size() + 1);
            batch->samplerResources.reserve(batch->samplerResources.size() + 1);
            keepTexture(batch, view->texture); batch->viewResources.push_back(view); batch->samplerResources.push_back(sampler);
            for (const auto &binding : batch->currentPipeline->shaderBindings) if (binding.index == index) bindShaderTexture(batch, binding, view->metal, sampler->metal);
            batch->uniformBound[index] = true; ++batch->operations;
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}
JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_renderPassPushConstants(
        JNIEnv *env, jclass, jlong handle, jobject data, jint length) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch"); if (!batch) return;
        try {
            std::lock_guard<std::mutex> encoding(batch->context->encoding); renderOperation(batch);
            if (!batch->currentPipeline) throw std::runtime_error("Set pipeline before pushing constants");
            if (!data || length < 1 || length != batch->currentPipeline->pushConstantsSize) throw std::invalid_argument("Invalid push constant range");
            jlong capacity = env->GetDirectBufferCapacity(data);
            if (capacity < length) throw std::invalid_argument("Push constants require a sufficient direct byte buffer");
            void *source = env->GetDirectBufferAddress(data);
            if (!source) throw std::invalid_argument("Push constants require direct storage");
            // Owned shared staging snapshots are budgeted and retained until submission completion.
            id<MTLBuffer> snapshot = allocateStaging(batch, length);
            std::memcpy(snapshot.contents, source, length);
            for (const auto &binding : batch->currentPipeline->shaderBindings) if (binding.kind == 2) bindShaderBuffer(batch, binding, snapshot, 0);
            batch->constantsBound = true; ++batch->operations;
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}


JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_renderPassIndexBuffer(
        JNIEnv *env, jclass, jlong handle, jlong bufferHandle, jint indexBytes) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch"); if (!batch) return;
        auto buffer = findBuffer(env, bufferHandle); if (!buffer) return;
        try {
            std::lock_guard<std::mutex> encoding(batch->context->encoding); renderOperation(batch);
            if ((indexBytes != 2 && indexBytes != 4) || buffer->context != batch->context
                    || buffer->metal.length < static_cast<NSUInteger>(indexBytes)) throw std::invalid_argument("Invalid index buffer/device/type");
            batch->resources.reserve(batch->resources.size() + 1); keepBuffer(batch, buffer);
            batch->indexBuffer = buffer; batch->indexBytes = indexBytes; ++batch->operations;
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}
JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_renderPassDrawIndexed(
        JNIEnv *env, jclass, jlong handle, jint indexCount, jint instanceCount, jint firstIndex, jint baseVertex, jint firstInstance) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch"); if (!batch) return;
        try {
            std::lock_guard<std::mutex> encoding(batch->context->encoding); renderOperation(batch);
            if (validateDraw(batch, true, indexCount, instanceCount, firstIndex, baseVertex, firstInstance)) {
                encodeDraw(batch, true, indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
            }
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}
JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_renderPassMultiDraw(
        JNIEnv *env, jclass, jlong handle, jboolean indexed, jintArray commandArray) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch"); if (!batch) return;
        try {
            unsigned quantum = indexed ? 5 : 4;
            auto commands = pipelineArray(env, commandArray, quantum, 1024); if (env->ExceptionCheck()) return;
            std::lock_guard<std::mutex> encoding(batch->context->encoding); renderOperation(batch);
            if (!batch->currentPipeline) throw std::runtime_error("Set pipeline before multi-drawing");
            // Preflight all rows and their full operation cost before encoding even the first draw.
            unsigned operations = 0;
            for (size_t i = 0; i < commands.size(); i += quantum) {
                if (validateDraw(batch, indexed, commands[i], commands[i+1], commands[i+2],
                        indexed ? commands[i+3] : 0, commands[i+quantum-1])) ++operations;
            }
            if (batch->operations + operations > 4096) throw std::runtime_error("Multi-draw exceeds batch operation limit");
            for (size_t i = 0; i < commands.size(); i += quantum) {
                if (commands[i] && commands[i+1]) encodeDraw(batch, indexed, commands[i], commands[i+1], commands[i+2],
                        indexed ? commands[i+3] : 0, commands[i+quantum-1]);
            }
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}

JNIEXPORT void JNICALL Java_dev_metalmc_metal_NativeMetal_renderPassDrawIndirect(
        JNIEnv *env, jclass, jlong handle, jboolean indexed, jlong bufferHandle,
        jlong offset, jlong length, jint count) {
    @autoreleasepool {
        auto batch = findResource(env, handle, batches, "command batch"); if (!batch) return;
        auto buffer = findBuffer(env, bufferHandle); if (!buffer) return;
        try {
            std::lock_guard<std::mutex> encoding(batch->context->encoding); renderOperation(batch);
            const NSUInteger stride = indexed ? sizeof(MTLDrawIndexedPrimitivesIndirectArguments)
                    : sizeof(MTLDrawPrimitivesIndirectArguments);
            if (buffer->context != batch->context || (offset & 3) || count < 0 || count > 1024
                    || length < static_cast<jlong>(count) * static_cast<jlong>(stride)) {
                throw std::invalid_argument("Invalid indirect buffer/device/range/alignment");
            }
            if (!bufferRange(env, buffer, offset, length)) return;
            if (!batch->currentPipeline) throw std::runtime_error("Set pipeline before indirect drawing");
            if (!count) return;
            // GPU arguments are read at execution, after prior blits. Do not inspect CPU
            // contents or introduce a wait. Only fixed bindings/minimum extents are known;
            // all GPU-provided vertex/index/instance addresses remain caller invariants.
            validateDraw(batch, indexed, 1, 1, 0, 0, 0);
            if (batch->operations + static_cast<unsigned>(count) > 4096) {
                throw std::runtime_error("Indirect draws exceed batch operation limit");
            }
            batch->resources.reserve(batch->resources.size() + 1); keepBuffer(batch, buffer);
            auto pipeline = batch->currentPipeline;
            for (jint i = 0; i < count; ++i) {
                NSUInteger argumentOffset = static_cast<NSUInteger>(offset) + static_cast<NSUInteger>(i) * stride;
                if (indexed) {
                    [batch->render drawIndexedPrimitives:pipeline->primitive
                            indexType:batch->indexBytes == 2 ? MTLIndexTypeUInt16 : MTLIndexTypeUInt32
                            indexBuffer:batch->indexBuffer->metal indexBufferOffset:0
                            indirectBuffer:buffer->metal indirectBufferOffset:argumentOffset];
                } else {
                    [batch->render drawPrimitives:pipeline->primitive
                            indirectBuffer:buffer->metal indirectBufferOffset:argumentOffset];
                }
            }
            batch->operations += static_cast<unsigned>(count);
        } catch (const std::invalid_argument &error) { throwJava(env, "java/lang/IllegalArgumentException", error.what()); }
          catch (const std::exception &error) { throwJava(env, "java/lang/IllegalStateException", error.what()); }
    }
}
