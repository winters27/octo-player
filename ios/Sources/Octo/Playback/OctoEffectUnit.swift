import AVFoundation
import AudioToolbox
import OctoKit

/// What one of Octo's own effect units does on the render thread: told the frames about to be
/// pulled from its input (before anything upstream renders), then given those frames to change in
/// place, one non-interleaved float buffer per channel. Told its format on the main thread, while
/// the engine is not rendering.
protocol EffectRenderer: AnyObject, Sendable {
    func prepare(sampleRate: Double, channels: Int)
    func willPull(frames: Int)
    func process(_ buffers: UnsafeMutableAudioBufferListPointer, frames: Int)
}

/// The render side of a unit: the renderer and the buffer its input is pulled into. Kept apart
/// from the unit so the render block holds no reference to the unit itself.
final class EffectRenderBox: @unchecked Sendable {
    var renderer: EffectRenderer?
    var input: UnsafeMutablePointer<AudioBufferList>?
    /// The input buffer's own memory, put back before every pull.
    var owned: [UnsafeMutableRawPointer?] = []
}

/// An in-process audio unit that passes its input through Octo's own processing: the limiter on
/// the sum after the main mixer, and each deck's level after its time-pitch stage. Any rate, any
/// channel count, in place.
final class OctoEffectUnit: AUAudioUnit {
    private var inputBusArray: AUAudioUnitBusArray!
    private var outputBusArray: AUAudioUnitBusArray!
    private let box = EffectRenderBox()
    private var inputBuffer: AVAudioPCMBuffer?

    var renderer: EffectRenderer? {
        get { box.renderer }
        set { box.renderer = newValue }
    }

    override init(componentDescription: AudioComponentDescription, options: AudioComponentInstantiationOptions = []) throws {
        try super.init(componentDescription: componentDescription, options: options)
        let format = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 2)!
        let input = try AUAudioUnitBus(format: format)
        let output = try AUAudioUnitBus(format: format)
        input.maximumChannelCount = 16
        output.maximumChannelCount = 16
        inputBusArray = AUAudioUnitBusArray(audioUnit: self, busType: .input, busses: [input])
        outputBusArray = AUAudioUnitBusArray(audioUnit: self, busType: .output, busses: [output])
        maximumFramesToRender = 4_096
    }

    override var inputBusses: AUAudioUnitBusArray { inputBusArray }
    override var outputBusses: AUAudioUnitBusArray { outputBusArray }
    override var canProcessInPlace: Bool { true }

    override func allocateRenderResources() throws {
        try super.allocateRenderResources()
        let format = outputBusArray[0].format
        guard let buffer = AVAudioPCMBuffer(pcmFormat: inputBusArray[0].format, frameCapacity: maximumFramesToRender) else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(kAudioUnitErr_FailedInitialization))
        }
        inputBuffer = buffer
        let list = UnsafeMutableAudioBufferListPointer(buffer.mutableAudioBufferList)
        box.owned = list.map(\.mData)
        box.input = buffer.mutableAudioBufferList
        box.renderer?.prepare(sampleRate: format.sampleRate, channels: Int(format.channelCount))
    }

    override func deallocateRenderResources() {
        box.input = nil
        box.owned = []
        inputBuffer = nil
        super.deallocateRenderResources()
    }

    override var internalRenderBlock: AUInternalRenderBlock {
        let box = self.box
        return { _, timestamp, frameCount, _, outputData, _, pullInputBlock in
            guard let pull = pullInputBlock, let input = box.input else { return kAudioUnitErr_NoConnection }
            let inList = UnsafeMutableAudioBufferListPointer(input)
            let bytes = frameCount * UInt32(MemoryLayout<Float>.size)
            for i in 0..<inList.count {
                if i < box.owned.count { inList[i].mData = box.owned[i] }
                inList[i].mDataByteSize = bytes
            }
            box.renderer?.willPull(frames: Int(frameCount))
            var flags = AudioUnitRenderActionFlags()
            let status = pull(&flags, timestamp, frameCount, 0, input)
            guard status == noErr else { return status }
            let outList = UnsafeMutableAudioBufferListPointer(outputData)
            for i in 0..<min(outList.count, inList.count) {
                if outList[i].mData == nil {
                    outList[i].mData = inList[i].mData
                } else if outList[i].mData != inList[i].mData, let to = outList[i].mData, let from = inList[i].mData {
                    to.copyMemory(from: from, byteCount: Int(bytes))
                }
                outList[i].mDataByteSize = bytes
            }
            box.renderer?.process(outList, frames: Int(frameCount))
            return noErr
        }
    }

    // MARK: Making one

    private static let manufacturer = fourCharacterCode("Octo")

    /// The mix limiter's and a deck level's descriptions, registered once in this process.
    static let mixLimiter = octoComponent("Oclm")
    static let deckLevel = octoComponent("Ocdl")

    private static func octoComponent(_ subtype: String) -> AudioComponentDescription {
        AudioComponentDescription(componentType: kAudioUnitType_Effect, componentSubType: fourCharacterCode(subtype),
                                  componentManufacturer: manufacturer, componentFlags: 0, componentFlagsMask: 0)
    }

    private nonisolated(unsafe) static var registered = false

    /// A node running `renderer`, or nil when the unit could not be made (the engine then plays
    /// without it, as before).
    @MainActor
    static func node(_ description: AudioComponentDescription, name: String, renderer: EffectRenderer) -> AVAudioUnitEffect? {
        if !registered {
            AUAudioUnit.registerSubclass(OctoEffectUnit.self, as: mixLimiter, name: "Octo: mix limiter", version: 1)
            AUAudioUnit.registerSubclass(OctoEffectUnit.self, as: deckLevel, name: "Octo: deck level", version: 1)
            registered = true
        }
        let node = AVAudioUnitEffect(audioComponentDescription: description)
        guard let unit = node.auAudioUnit as? OctoEffectUnit else {
            playbackLog.info("mix: the \(name, privacy: .public) unit could not be made")
            return nil
        }
        unit.renderer = renderer
        return node
    }
}

private func fourCharacterCode(_ text: String) -> FourCharCode {
    text.utf8.prefix(4).reduce(0) { ($0 << 8) | FourCharCode($1) }
}
