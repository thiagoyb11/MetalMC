#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <dispatch/dispatch.h>
#include <mach/mach_time.h>
#include <array>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>

// Standalone, user-run hardware diagnosis; no renderer state or JNI changes.
namespace {
constexpr NSUInteger kSamples = 32, kPasses = 6;
static_assert(sizeof(MTLCounterResultTimestamp) == sizeof(uint64_t));
void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}
std::string describe(NSError *error) {
    return error ? error.localizedDescription.UTF8String : "unknown Metal error";
}
id<MTLBlitCommandEncoder> transferEncoder(id<MTLCommandBuffer> commands, bool explicitDescriptor) {
    if (!explicitDescriptor) return [commands blitCommandEncoder];
    auto pass = [MTLBlitPassDescriptor blitPassDescriptor];
    pass.sampleBufferAttachments[0].sampleBuffer = nil;
    pass.sampleBufferAttachments[0].startOfEncoderSampleIndex = MTLCounterDontSample;
    pass.sampleBufferAttachments[0].endOfEncoderSampleIndex = MTLCounterDontSample;
    return [commands blitCommandEncoderWithDescriptor:pass];
}
bool runCase(id<MTLDevice> device, id<MTLCommandQueue> queue, id<MTLCounterSet> set,
        id<MTLComputePipelineState> pipeline, bool compute, bool shared, unsigned mode,
        NSUInteger sampleCount = kSamples, bool productionSequence = false, bool interleave = false,
        unsigned strategy = 0) {
    const char *boundary = mode == 0 ? "start" : mode == 1 ? "end" : "both";
    std::printf("\nCASE %s/%s/%s\n", compute ? "compute" : "blit-copy", shared ? "shared" : "private", boundary);
    if (productionSequence) std::printf("PRODUCTION SEQUENCE samples=%lu slots=2,3 interleaved-transfers=%d\n",
            static_cast<unsigned long>(sampleCount), interleave);
    if (strategy) std::printf("CORRECTION STRATEGY=%s\n", strategy == 1 ? "explicit-unsampled-transfer-descriptors"
            : strategy == 2 ? "compute-marker" : "event-boundary-before-second-marker");
    NSError *error = nil;
    auto descriptor = [MTLCounterSampleBufferDescriptor new];
    descriptor.counterSet = set;
    descriptor.storageMode = shared ? MTLStorageModeShared : MTLStorageModePrivate;
    descriptor.sampleCount = sampleCount;
    auto samples = [device newCounterSampleBufferWithDescriptor:descriptor error:&error];
    require(samples != nil, "Counter allocation: " + describe(error));
    NSUInteger markerWords = productionSequence ? sampleCount : kPasses;
    auto input = [device newBufferWithLength:markerWords * 4 options:MTLResourceStorageModeShared];
    auto output = [device newBufferWithLength:markerWords * 4 options:MTLResourceStorageModePrivate];
    auto readback = [device newBufferWithLength:markerWords * 4 options:MTLResourceStorageModeShared];
    auto resolved = [device newBufferWithLength:sampleCount * 8 options:MTLResourceStorageModeShared];
    auto event = [device newSharedEvent];
    require(input && output && readback && resolved && event, "Probe buffer/event allocation failed");
    id<MTLBuffer> transferInput = nil, transferFrom = nil, transferTo = nil, transferReadback = nil;
    if (interleave) {
        transferInput = [device newBufferWithLength:65536 options:MTLResourceStorageModeShared];
        transferFrom = [device newBufferWithLength:65536 options:MTLResourceStorageModeShared];
        transferTo = [device newBufferWithLength:65536 options:MTLResourceStorageModeShared];
        transferReadback = [device newBufferWithLength:65536 options:MTLResourceStorageModeShared];
        require(transferInput && transferFrom && transferTo && transferReadback, "Transfer buffer allocation failed");
        auto bytes = static_cast<uint8_t *>(transferInput.contents);
        for (NSUInteger i = 0; i < 65536; ++i) bytes[i] = static_cast<uint8_t>(i * 31 + 7);
    }
    auto values = static_cast<uint32_t *>(input.contents);
    for (NSUInteger i = 0; i < markerWords; ++i) values[i] = static_cast<uint32_t>(i + 1);
    std::memset(resolved.contents, 0, resolved.length);
    auto commands = [queue commandBuffer];
    require(commands != nil, "Probe command buffer allocation failed");
    std::array<bool, kSamples> written{};
    NSUInteger passCount = productionSequence ? 2 : kPasses;
    for (NSUInteger i = 0; i < passCount; ++i) {
        if (strategy == 3 && i == 1) {
            [commands encodeSignalEvent:event value:1];
            [commands encodeWaitForEvent:event value:1];
        }
        NSUInteger word = productionSequence ? i + 2 : i;
        NSUInteger start = mode == 1 ? MTLCounterDontSample : (mode == 2 ? i * 2 : word);
        NSUInteger end = mode == 0 ? MTLCounterDontSample : (mode == 2 ? i * 2 + 1 : word);
        if (start != MTLCounterDontSample) written[start] = true;
        if (end != MTLCounterDontSample) written[end] = true;
        // Match JNI's per-call autorelease-pool lifetime for encoder/descriptor objects.
        @autoreleasepool {
        if (compute) {
            auto pass = [MTLComputePassDescriptor computePassDescriptor];
            pass.dispatchType = MTLDispatchTypeSerial;
            pass.sampleBufferAttachments[0].sampleBuffer = samples;
            pass.sampleBufferAttachments[0].startOfEncoderSampleIndex = start;
            pass.sampleBufferAttachments[0].endOfEncoderSampleIndex = end;
            auto encoder = [commands computeCommandEncoderWithDescriptor:pass];
            require(encoder != nil, "Compute encoder creation failed");
            [encoder setComputePipelineState:pipeline];
            [encoder setBuffer:output offset:word * 4 atIndex:0];
            uint32_t value = static_cast<uint32_t>(word + 1);
            [encoder setBytes:&value length:sizeof(value) atIndex:1];
            [encoder dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
            [encoder endEncoding];
        } else {
            auto pass = [MTLBlitPassDescriptor blitPassDescriptor];
            pass.sampleBufferAttachments[0].sampleBuffer = samples;
            pass.sampleBufferAttachments[0].startOfEncoderSampleIndex = start;
            pass.sampleBufferAttachments[0].endOfEncoderSampleIndex = end;
            auto encoder = [commands blitCommandEncoderWithDescriptor:pass];
            require(encoder != nil, "Blit encoder creation failed");
            [encoder copyFromBuffer:input sourceOffset:word * 4 toBuffer:output destinationOffset:word * 4 size:4];
            [encoder endEncoding];
        }
        }
        if (interleave && i == 0) {
            @autoreleasepool {
            auto upload = transferEncoder(commands, strategy == 1);
            require(upload != nil, "Interleaved upload encoder creation failed");
            [upload copyFromBuffer:transferInput sourceOffset:0 toBuffer:transferFrom destinationOffset:0 size:65536];
            [upload endEncoding];
            }
            @autoreleasepool {
            auto copy = transferEncoder(commands, strategy == 1);
            require(copy != nil, "Interleaved copy encoder creation failed");
            [copy copyFromBuffer:transferFrom sourceOffset:0 toBuffer:transferTo destinationOffset:0 size:65536];
            [copy endEncoding];
            }
        }
    }
    uint64_t resolveEventValue = strategy == 3 ? 2 : 1;
    [commands encodeSignalEvent:event value:resolveEventValue];
    [commands encodeWaitForEvent:event value:resolveEventValue];
    auto resolve = transferEncoder(commands, strategy == 1);
    require(resolve != nil, "Resolution encoder creation failed");
    [resolve resolveCounters:samples inRange:NSMakeRange(0, sampleCount) destinationBuffer:resolved destinationOffset:0];
    [resolve copyFromBuffer:output sourceOffset:0 toBuffer:readback destinationOffset:0 size:markerWords * 4];
    if (interleave) [resolve copyFromBuffer:transferTo sourceOffset:0 toBuffer:transferReadback destinationOffset:0 size:65536];
    [resolve endEncoding];
    __block NSData *callbackValues = nil;
    auto callbackDone = dispatch_semaphore_create(0);
    [commands addCompletedHandler:^(id<MTLCommandBuffer> completed) {
        @autoreleasepool {
            if (completed.status == MTLCommandBufferStatusCompleted) {
                callbackValues = [NSData dataWithBytes:resolved.contents length:resolved.length];
            }
            dispatch_semaphore_signal(callbackDone);
        }
    }];
    // These blocking waits are exclusively in this explicit user-run diagnostic.
    [commands commit];
    [commands waitUntilCompleted];
    require(commands.status == MTLCommandBufferStatusCompleted, "GPU submission: " + describe(commands.error));
    require(dispatch_semaphore_wait(callbackDone, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC)) == 0,
            "Completion callback did not finish in five seconds");
    bool callbackValid = callbackValues && callbackValues.length == resolved.length
            && std::memcmp(callbackValues.bytes, resolved.contents, resolved.length) == 0;
    std::printf("Completion-handler snapshot versus post-wait values: %s\n", callbackValid ? "IDENTICAL" : "DIFFERENT");
    bool bytesValid = true;
    for (NSUInteger i = 0; i < passCount; ++i) {
        NSUInteger word = productionSequence ? i + 2 : i;
        bytesValid &= static_cast<const uint32_t *>(readback.contents)[word] == word + 1;
    }
    if (interleave) bytesValid &= std::memcmp(transferInput.contents, transferReadback.contents, 65536) == 0;
    std::printf("Workload bytes: %s (%lu distinct marker words%s)\n", bytesValid ? "VALID" : "INVALID",
            static_cast<unsigned long>(passCount), interleave ? " and 64 KiB ordered transfers" : "");
    NSData *cpu = shared ? [samples resolveCounterRange:NSMakeRange(0, sampleCount)] : nil;
    bool cpuAvailable = cpu && cpu.length == sampleCount * 8;
    bool gpuValid = bytesValid && callbackValid, cpuValid = bytesValid && cpuAvailable;
    uint64_t previousGpu = 0, previousCpu = 0;
    for (NSUInteger i = 0; i < sampleCount; ++i) {
        uint64_t gpu = 0, full = 0;
        std::memcpy(&gpu, static_cast<const uint8_t *>(resolved.contents) + i * 8, 8);
        if (cpuAvailable) std::memcpy(&full, static_cast<const uint8_t *>(cpu.bytes) + i * 8, 8);
        if (written[i]) {
            gpuValid &= gpu > 0 && gpu != MTLCounterErrorValue && gpu <= INT64_MAX && gpu >= previousGpu;
            cpuValid &= full > 0 && full != MTLCounterErrorValue && full <= INT64_MAX && full >= previousCpu;
            previousGpu = gpu; previousCpu = full;
        }
        // All slots show whether the driver placed a value at a different index.
        std::printf("slot=%lu written=%d GPU=%llu", static_cast<unsigned long>(i), written[i], static_cast<unsigned long long>(gpu));
        if (shared) {
            NSData *single = [samples resolveCounterRange:NSMakeRange(i, 1)];
            uint64_t one = 0;
            bool singleAvailable = single && single.length == 8;
            if (singleAvailable) std::memcpy(&one, single.bytes, 8);
            std::printf(" CPU-full=%llu CPU-single=%llu fullAvailable=%d singleAvailable=%d",
                    static_cast<unsigned long long>(full), static_cast<unsigned long long>(one), cpuAvailable, singleAvailable);
        }
        std::printf("\n");
    }
    std::printf("RESULT GPU=%s", gpuValid ? "VALID" : "INVALID");
    if (shared) std::printf(" CPU-full=%s", cpuValid ? "VALID" : "INVALID");
    std::printf("\n");
    return gpuValid;
}
}
int main(int argc, const char *argv[]) {
    std::setvbuf(stdout, nullptr, _IOLBF, 0);
    @autoreleasepool {
        try {
            bool productionSequence = argc == 2 && std::strcmp(argv[1], "--production-sequence") == 0;
            bool strategies = argc == 2 && std::strcmp(argv[1], "--interleaved-strategies") == 0;
            require(argc == 1 || productionSequence || strategies,
                    "Usage: timestamp-probe [--production-sequence | --interleaved-strategies]");
            auto device = MTLCreateSystemDefaultDevice();
            require(device != nil, "No Metal device");
            std::printf("Counter probe device=%s OS=%s\n", device.name.UTF8String,
                    NSProcessInfo.processInfo.operatingSystemVersionString.UTF8String);
            mach_timebase_info_data_t timebase{};
            mach_timebase_info(&timebase);
            MTLTimestamp cpu = 0, gpu = 0;
            [device sampleTimestamps:&cpu gpuTimestamp:&gpu];
            std::printf("Calibration CPU=%llu GPU=%llu mach-timebase=%u/%u\n",
                    static_cast<unsigned long long>(cpu), static_cast<unsigned long long>(gpu), timebase.numer, timebase.denom);
            if (@available(macOS 26.0, *)) {
                std::printf("GPU timestamp frequency=%llu ticks/sec\n", static_cast<unsigned long long>([device queryTimestampFrequency]));
            }
            bool supported = [device supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary];
            std::printf("Stage sampling=%d\n", supported);
            id<MTLCounterSet> timestamps = nil;
            for (id<MTLCounterSet> set in device.counterSets) {
                std::printf("Counter set=%s\n", set.name.UTF8String);
                for (id<MTLCounter> counter in set.counters) {
                    std::printf("  counter=%s\n", counter.name.UTF8String);
                    if ([set.name isEqualToString:MTLCommonCounterSetTimestamp]
                            && [counter.name isEqualToString:MTLCommonCounterTimestamp]) timestamps = set;
                }
            }
            require(supported && timestamps, "Required legacy timestamp set/stage sampling is unavailable");
            auto queue = [device newCommandQueue];
            require(queue != nil, "Queue allocation failed");
            NSError *error = nil;
            NSString *source = @"#include <metal_stdlib>\nusing namespace metal;\n"
                    "kernel void marker(device uint *out [[buffer(0)]], constant uint &value [[buffer(1)]]) { out[0] = value; }\n";
            auto library = [device newLibraryWithSource:source options:nil error:&error];
            require(library != nil, "Probe MSL compilation: " + describe(error));
            auto function = [library newFunctionWithName:@"marker"];
            auto pipeline = [device newComputePipelineStateWithFunction:function error:&error];
            require(pipeline != nil, "Probe pipeline compilation: " + describe(error));
            unsigned working = 0;
            if (strategies) {
                for (unsigned strategy = 0; strategy < 4; ++strategy) {
                    @autoreleasepool {
                        if (!strategy) std::printf("\nCONTROL: original interleaved blit markers\n");
                        working += runCase(device, queue, timestamps, pipeline, strategy == 2, false, 1, 8, true, true, strategy);
                    }
                }
            } else if (productionSequence) {
                for (NSUInteger count : {8UL, 32UL}) for (bool interleave : {false, true}) {
                    @autoreleasepool { working += runCase(device, queue, timestamps, pipeline, false, false, 1, count, true, interleave); }
                }
            } else {
                for (bool compute : {false, true}) for (bool shared : {true, false}) for (unsigned mode = 0; mode < 3; ++mode) {
                    @autoreleasepool { working += runCase(device, queue, timestamps, pipeline, compute, shared, mode); }
                }
            }
            std::printf("\nDIAGNOSTIC COMPLETE: %u/%u GPU configurations produced positive ordered written samples.\n",
                    working, productionSequence || strategies ? 4 : 12);
            std::printf("This diagnostic is not the ABI 16 acceptance suite or a renderer support claim.\n");
            return working == 0 ? 1 : 0;
        } catch (const std::exception &error) {
            std::fprintf(stderr, "Counter probe error: %s\n", error.what());
            return 1;
        }
    }
}
