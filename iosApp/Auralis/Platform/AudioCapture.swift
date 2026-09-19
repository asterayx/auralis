import AVFoundation

/// REC-1 / REC-2 / REC-3: microphone capture, background audio session,
/// and crash-safe file writer. PCM 16 kHz mono is forwarded to the KMP pipeline.
final class AppleAudioCapture: NSObject {
    private let engine = AVAudioEngine()
    private var file: AVAudioFile?
    var onChunk: ((Data, Int64) -> Void)?

    func start(sessionId: String, keepFile: Bool) throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .defaultToSpeaker])
        try session.setActive(true)
        let input = engine.inputNode
        let format = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16_000, channels: 1, interleaved: true)
            ?? input.outputFormat(forBus: 0)
        if keepFile {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(sessionId).caf")
            file = try AVAudioFile(forWriting: url, settings: format.settings)
        }
        var offset: Int64 = 0
        input.installTap(onBus: 0, bufferSize: 1600, format: format) { [weak self] buffer, _ in
            if let file = self?.file { try? file.write(from: buffer) }
            guard let channels = buffer.int16ChannelData else { return }
            let frames = Int(buffer.frameLength)
            let data = Data(bytes: channels[0], count: frames * 2)
            self?.onChunk?(data, offset)
            offset += Int64(frames * 1000 / 16_000)
        }
        try engine.start()
    }

    func stop() {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false)
        file = nil
    }
}
