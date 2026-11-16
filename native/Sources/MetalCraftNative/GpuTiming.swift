import Foundation
import Metal

/// Per-pass GPU times for finding which passes are expensive. Each render pass samples the GPU clock where
/// its vertex and fragment stages start and end; the sums per pass name are printed every few seconds.
/// A debugging aid: on with METALCRAFT_GPU_TIMING=1, absent otherwise.
final class GpuTiming {
    /// The samples of the passes in one command buffer.
    final class Batch {
        fileprivate let buffer: MTLCounterSampleBuffer
        fileprivate var names: [String] = []

        fileprivate init(buffer: MTLCounterSampleBuffer) {
            self.buffer = buffer
        }

        func attach(_ descriptor: MTLRenderPassDescriptor, name: String) {
            guard names.count < GpuTiming.passesPerBatch else { return }
            let first = names.count * 4
            let attachment = descriptor.sampleBufferAttachments[0]!
            attachment.sampleBuffer = buffer
            attachment.startOfVertexSampleIndex = first
            attachment.endOfVertexSampleIndex = first + 1
            attachment.startOfFragmentSampleIndex = first + 2
            attachment.endOfFragmentSampleIndex = first + 3
            names.append(name)
        }
    }

    private struct Total {
        var vertex: UInt64 = 0
        var fragment: UInt64 = 0
        var passes = 0
    }

    fileprivate static let passesPerBatch = 512
    private static let reportSeconds = 5.0

    private let device: MTLDevice
    private let counters: MTLCounterSet
    private let lock = NSLock()
    private var free: [MTLCounterSampleBuffer] = []
    private var totals: [String: Total] = [:]
    private var order: [String] = []
    private var frames = 0
    private var lastReport = Date()

    init?(device: MTLDevice) {
        guard ProcessInfo.processInfo.environment["METALCRAFT_GPU_TIMING"] == "1",
              device.supportsCounterSampling(.atStageBoundary),
              let counters = device.counterSets?.first(where: { $0.name == MTLCommonCounterSet.timestamp.rawValue })
        else { return nil }
        self.device = device
        self.counters = counters
    }

    func batch() -> Batch? {
        lock.lock()
        let reused = free.popLast()
        lock.unlock()
        if let reused { return Batch(buffer: reused) }
        let descriptor = MTLCounterSampleBufferDescriptor()
        descriptor.counterSet = counters
        descriptor.storageMode = .shared
        descriptor.sampleCount = GpuTiming.passesPerBatch * 4
        guard let buffer = try? device.makeCounterSampleBuffer(descriptor: descriptor) else { return nil }
        return Batch(buffer: buffer)
    }

    func frameShown() {
        lock.lock()
        frames += 1
        lock.unlock()
    }

    /// Called once the GPU has finished the batch's command buffer.
    func finish(_ batch: Batch) {
        let count = batch.names.count
        let data = count > 0 ? (try? batch.buffer.resolveCounterRange(0..<count * 4)) ?? nil : nil
        lock.lock()
        defer { lock.unlock() }
        if let data {
            data.withUnsafeBytes { raw in
                let samples = raw.bindMemory(to: MTLCounterResultTimestamp.self)
                for index in 0..<count {
                    let name = batch.names[index]
                    if totals[name] == nil { order.append(name) }
                    var total = totals[name] ?? Total()
                    total.vertex += GpuTiming.span(samples[index * 4].timestamp, samples[index * 4 + 1].timestamp)
                    total.fragment += GpuTiming.span(samples[index * 4 + 2].timestamp, samples[index * 4 + 3].timestamp)
                    total.passes += 1
                    totals[name] = total
                }
            }
        }
        free.append(batch.buffer)
        if Date().timeIntervalSince(lastReport) >= GpuTiming.reportSeconds { report() }
    }

    private static func span(_ start: UInt64, _ end: UInt64) -> UInt64 {
        if start == MTLCounterErrorValue || end == MTLCounterErrorValue || end < start { return 0 }
        return end - start
    }

    private func report() {
        // On Apple GPUs the resolved timestamps are already nanoseconds.
        let scale = 1.0
        let shown = max(frames, 1)
        var lines = "[MetalCraft GPU timing] \(frames) frames; ms per frame (vertex + fragment), passes per frame\n"
        var sum = 0.0
        for name in order {
            guard let total = totals[name] else { continue }
            let vertex = Double(total.vertex) * scale / 1e6 / Double(shown)
            let fragment = Double(total.fragment) * scale / 1e6 / Double(shown)
            sum += vertex + fragment
            if vertex + fragment < 0.02 { continue }
            let per = Double(total.passes) / Double(shown)
            lines += String(format: "  %7.3f = %6.3f + %7.3f  x%-5.1f %@\n", vertex + fragment, vertex, fragment, per, name)
        }
        lines += String(format: "  %7.3f total\n", sum)
        FileHandle.standardError.write(lines.data(using: .utf8)!)
        totals.removeAll(keepingCapacity: true)
        order.removeAll(keepingCapacity: true)
        frames = 0
        lastReport = Date()
    }
}
