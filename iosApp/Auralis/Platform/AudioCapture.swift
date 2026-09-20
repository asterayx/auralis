import AVFoundation

enum AppleAudioCaptureError: Error, LocalizedError {
    case invalidInputFormat
    case converterUnavailable
    case tapFailed(String)

    var errorDescription: String? {
        switch self {
        case .invalidInputFormat:
            return "Microphone format is not ready."
        case .converterUnavailable:
            return "Could not convert microphone audio to 16 kHz PCM."
        case .tapFailed(let reason):
            return reason
        }
    }
}

/// REC-1 / REC-2 / REC-3: microphone capture, background audio session,
/// and crash-safe file writer. PCM 16 kHz mono Int16 is forwarded to the KMP pipeline.
///
/// Start order: session category → activate → new engine → input/output nodes →
/// tap → prepare → start. `prepare`/`start` before the nodes exist throws
/// `inputNode != nullptr || outputNode != nullptr` (NSException, not Swift Error).
final class AppleAudioCapture: NSObject {
    private static let targetRate: Double = 16_000

    private var engine = AVAudioEngine()
    private var file: AVAudioFile?
    private var converter: AVAudioConverter?
    private var converterOutputFormat: AVAudioFormat?
    private var converterSourceFormat: AVAudioFormat?
    private var pcm16Format: AVAudioFormat?
    private var tapInstalled = false
    private var streamOffsetMs: Int64 = 0
    private(set) var lastFilePath: String?
    var onChunk: ((Data, Int64) -> Void)?
    /// 0…1 peak/RMS from the same Int16 PCM sent to `onChunk`. Audio-thread callback.
    var onLevel: ((Float) -> Void)?

    func start(sessionId: String, keepFile: Bool) throws {
        teardownEngine(deactivateSession: false)
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .defaultToSpeaker])
        try session.setActive(true)

        engine = AVAudioEngine()
        let input: AVAudioInputNode
        let nodeFormat: AVAudioFormat
        do {
            var resolvedInput: AVAudioInputNode?
            var resolvedFormat: AVAudioFormat?
            try runEngine {
                // Access both I/O nodes before prepare/start so the graph exists.
                let node = self.engine.inputNode
                _ = self.engine.outputNode
                let format = node.outputFormat(forBus: 0)
                if format.sampleRate > 0, format.channelCount > 0 {
                    self.engine.connect(node, to: self.engine.mainMixerNode, format: format)
                    self.engine.mainMixerNode.outputVolume = 0
                }
                resolvedInput = node
                resolvedFormat = format
            }
            guard let readyInput = resolvedInput, let format = resolvedFormat else {
                throw AppleAudioCaptureError.invalidInputFormat
            }
            input = readyInput
            nodeFormat = format
        } catch {
            throw AppleAudioCaptureError.tapFailed(error.localizedDescription)
        }
        guard nodeFormat.sampleRate > 0, nodeFormat.channelCount > 0 else {
            throw AppleAudioCaptureError.invalidInputFormat
        }
        guard let pcm16 = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: Self.targetRate,
            channels: 1,
            interleaved: true
        ) else {
            throw AppleAudioCaptureError.converterUnavailable
        }
        pcm16Format = pcm16
        rebuildConverter(from: nodeFormat)
        if converter == nil && !Self.isTargetPcm16(nodeFormat) {
            throw AppleAudioCaptureError.converterUnavailable
        }
        streamOffsetMs = 0

        if keepFile {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(sessionId).caf")
            lastFilePath = url.path
            file = try AVAudioFile(forWriting: url, settings: pcm16.settings)
        } else {
            lastFilePath = nil
            file = nil
        }

        let bufferSize = AVAudioFrameCount(max(512, min(8_192, nodeFormat.sampleRate * 0.1)))
        do {
            try runEngine {
                // nil = node's current format. Never pass 16 kHz Int16 here.
                input.installTap(onBus: 0, bufferSize: bufferSize, format: nil) { [weak self] buffer, _ in
                    self?.handleTap(buffer)
                }
            }
        } catch {
            throw AppleAudioCaptureError.tapFailed(error.localizedDescription)
        }
        tapInstalled = true

        do {
            try runEngine {
                self.engine.prepare()
                try self.engine.start()
            }
        } catch {
            removeTapSafely()
            throw AppleAudioCaptureError.tapFailed(error.localizedDescription)
        }
    }

    func stop() {
        teardownEngine(deactivateSession: true)
        file = nil
        converter = nil
        converterOutputFormat = nil
        converterSourceFormat = nil
        pcm16Format = nil
        streamOffsetMs = 0
    }

    /// NSException from AVAudioEngine is not a Swift Error. Catch both.
    private func runEngine(_ body: () throws -> Void) throws {
        var thrown: Error?
        try AuralisExceptionCatcher.run {
            do {
                try body()
            } catch {
                thrown = error
            }
        }
        if let thrown { throw thrown }
    }

    private func teardownEngine(deactivateSession: Bool) {
        let old = engine
        let hadTap = tapInstalled
        tapInstalled = false
        try? runEngine {
            if hadTap {
                old.inputNode.removeTap(onBus: 0)
            }
            if old.isRunning {
                old.stop()
            }
            old.reset()
        }
        if deactivateSession {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    private func handleTap(_ buffer: AVAudioPCMBuffer) {
        guard buffer.frameLength > 0 else { return }
        guard let pcm16 = toPcm16(buffer) else { return }
        if let file {
            try? file.write(from: pcm16)
        }
        guard let channels = pcm16.int16ChannelData else { return }
        let frames = Int(pcm16.frameLength)
        let data = Data(bytes: channels[0], count: frames * MemoryLayout<Int16>.size)
        let offset = streamOffsetMs
        streamOffsetMs += Int64(frames) * 1_000 / Int64(Self.targetRate)
        onLevel?(Self.meterLevel(channels[0], frames: frames))
        onChunk?(data, offset)
    }

    private static func meterLevel(_ samples: UnsafePointer<Int16>, frames: Int) -> Float {
        guard frames > 0 else { return 0 }
        var peak: Float = 0
        var sum: Float = 0
        for i in 0..<frames {
            let sample = Float(samples[i]) / 32_768
            let absSample = abs(sample)
            if absSample > peak { peak = absSample }
            sum += sample * sample
        }
        let rms = sqrt(sum / Float(frames))
        return min(1, max(peak, rms * 3))
    }

    private func toPcm16(_ buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        if Self.isTargetPcm16(buffer.format), buffer.int16ChannelData != nil {
            return buffer
        }
        if converterSourceFormat.map({ !Self.sameFormat($0, buffer.format) }) ?? true {
            rebuildConverter(from: buffer.format)
        }
        guard let converter, let outFormat = converterOutputFormat else { return nil }
        let ratio = outFormat.sampleRate / max(buffer.format.sampleRate, 1)
        let capacity = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up) + 32)
        guard let converted = AVAudioPCMBuffer(pcmFormat: outFormat, frameCapacity: capacity) else { return nil }
        var error: NSError?
        var delivered = false
        let status = converter.convert(to: converted, error: &error) { _, outStatus in
            if delivered {
                outStatus.pointee = .noDataNow
                return nil
            }
            delivered = true
            outStatus.pointee = .haveData
            return buffer
        }
        guard status != .error, error == nil, converted.frameLength > 0 else { return nil }
        return packInt16(converted)
    }

    private func packInt16(_ buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        if buffer.format.commonFormat == .pcmFormatInt16, buffer.int16ChannelData != nil {
            return buffer
        }
        guard let pcm16 = pcm16Format
            ?? AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: Self.targetRate, channels: 1, interleaved: true),
            let floats = buffer.floatChannelData,
            let out = AVAudioPCMBuffer(pcmFormat: pcm16, frameCapacity: buffer.frameLength),
            let dest = out.int16ChannelData else { return nil }
        out.frameLength = buffer.frameLength
        let frames = Int(buffer.frameLength)
        let src = floats[0]
        let dst = dest[0]
        for i in 0..<frames {
            let sample = max(-1, min(1, src[i]))
            dst[i] = Int16(sample * Float(Int16.max))
        }
        return out
    }

    private func rebuildConverter(from source: AVAudioFormat) {
        converter = nil
        converterOutputFormat = nil
        converterSourceFormat = source
        if Self.isTargetPcm16(source) {
            return
        }
        if let pcm16 = pcm16Format ?? AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: Self.targetRate,
            channels: 1,
            interleaved: true
        ), let toInt16 = AVAudioConverter(from: source, to: pcm16) {
            converter = toInt16
            converterOutputFormat = pcm16
            return
        }
        if let float32 = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Self.targetRate,
            channels: 1,
            interleaved: false
        ), let toFloat = AVAudioConverter(from: source, to: float32) {
            converter = toFloat
            converterOutputFormat = float32
        }
    }

    private func removeTapSafely() {
        guard tapInstalled else { return }
        tapInstalled = false
        try? runEngine {
            self.engine.inputNode.removeTap(onBus: 0)
        }
    }

    private static func isTargetPcm16(_ format: AVAudioFormat) -> Bool {
        format.commonFormat == .pcmFormatInt16 &&
            format.sampleRate == targetRate &&
            format.channelCount == 1
    }

    private static func sameFormat(_ a: AVAudioFormat, _ b: AVAudioFormat) -> Bool {
        a.sampleRate == b.sampleRate &&
            a.channelCount == b.channelCount &&
            a.commonFormat == b.commonFormat &&
            a.isInterleaved == b.isInterleaved
    }
}
