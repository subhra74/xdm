// What macOS's own player stack (AVFoundation: QuickTime Player, Safari, Photos; the same on iOS) makes of
// an MP4: whether it opens and plays, and how smooth the decoded timeline is. Every video frame is
// decoded through AVAssetReader (VideoToolbox), every audio frame to PCM.
//
//   swiftc -O -o avf_check avf_check.swift && ./avf_check file.mp4      -> one JSON object on stdout
//
// Smoothness measures: frames decoded vs samples, presentation-time gaps longer than 1.5 frames (a frame
// held: stutter), duplicate or backward presentation times (frames dropped or shown out of order).
import AVFoundation
import Foundation

func seconds(_ t: CMTime) -> Double { t.isNumeric ? CMTimeGetSeconds(t) : -1 }

func fourcc(_ d: CMFormatDescription) -> String {
    let c = CMFormatDescriptionGetMediaSubType(d)
    return String(bytes: [UInt8((c >> 24) & 0xFF), UInt8((c >> 16) & 0xFF), UInt8((c >> 8) & 0xFF), UInt8(c & 0xFF)],
                  encoding: .ascii) ?? "?"
}

struct VideoResult: Codable {
    var codec = "", width = 0, height = 0, nominalFps = 0.0, edits = 0
    var frames = 0, firstPts = -1.0, lastPts = -1.0
    var heldFrames = 0, longestGapMs = 0.0, duplicateOrBackward = 0
    var error: String? = nil
}
struct AudioResult: Codable {
    var codec = "", frames = 0, firstPts = -1.0, durationDecoded = 0.0, error: String? = nil
}
struct Result: Codable {
    var file = "", playable = false, readable = false, duration = 0.0
    var video: VideoResult? = nil, audio: AudioResult? = nil
    var avStartOffsetMs: Double? = nil
    var error: String? = nil
}

func decodeVideo(_ asset: AVAsset, _ track: AVAssetTrack) -> VideoResult {
    var r = VideoResult()
    if let d = (track.formatDescriptions as? [CMFormatDescription])?.first {
        r.codec = fourcc(d)
        let dim = CMVideoFormatDescriptionGetDimensions(d)
        r.width = Int(dim.width); r.height = Int(dim.height)
    }
    r.nominalFps = Double(track.nominalFrameRate)
    r.edits = track.segments.count
    guard let reader = try? AVAssetReader(asset: asset) else { r.error = "no reader"; return r }
    let out = AVAssetReaderTrackOutput(track: track, outputSettings: [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange])
    out.alwaysCopiesSampleData = false
    reader.add(out)
    guard reader.startReading() else { r.error = reader.error?.localizedDescription; return r }
    var times: [Double] = []
    while let sb = out.copyNextSampleBuffer() {
        if CMSampleBufferGetImageBuffer(sb) != nil {
            times.append(seconds(CMSampleBufferGetPresentationTimeStamp(sb)))
        }
    }
    if reader.status == .failed { r.error = reader.error?.localizedDescription }
    r.frames = times.count
    guard times.count > 1 else { return r }
    r.firstPts = times.first!; r.lastPts = times.last!
    // Frames come out in presentation order: steps of zero or less are duplicates or backward jumps.
    var steps: [Double] = []
    for i in 1..<times.count {
        let s = times[i] - times[i - 1]
        if s <= 0 { r.duplicateOrBackward += 1 } else { steps.append(s) }
    }
    let typical = steps.sorted()[steps.count / 2]
    for s in steps where s > typical * 1.5 { r.heldFrames += 1 }
    r.longestGapMs = (steps.max() ?? 0) * 1000
    return r
}

func decodeAudio(_ asset: AVAsset, _ track: AVAssetTrack) -> AudioResult {
    var r = AudioResult()
    if let d = (track.formatDescriptions as? [CMFormatDescription])?.first { r.codec = fourcc(d) }
    guard let reader = try? AVAssetReader(asset: asset) else { r.error = "no reader"; return r }
    let out = AVAssetReaderTrackOutput(track: track, outputSettings: [AVFormatIDKey: kAudioFormatLinearPCM])
    reader.add(out)
    guard reader.startReading() else { r.error = reader.error?.localizedDescription; return r }
    var total = 0.0
    while let sb = out.copyNextSampleBuffer() {
        if r.frames == 0 { r.firstPts = seconds(CMSampleBufferGetPresentationTimeStamp(sb)) }
        r.frames += CMSampleBufferGetNumSamples(sb)
        total += seconds(CMSampleBufferGetDuration(sb))
    }
    if reader.status == .failed { r.error = reader.error?.localizedDescription }
    r.durationDecoded = total
    return r
}

let path = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : ""
var res = Result(file: path)
let asset = AVURLAsset(url: URL(fileURLWithPath: path))
res.playable = asset.isPlayable
res.readable = asset.isReadable
res.duration = seconds(asset.duration)
if let v = asset.tracks(withMediaType: .video).first { res.video = decodeVideo(asset, v) }
if let a = asset.tracks(withMediaType: .audio).first { res.audio = decodeAudio(asset, a) }
if let v = res.video, let a = res.audio, v.frames > 0, a.frames > 0 {
    res.avStartOffsetMs = (v.firstPts - a.firstPts) * 1000
}
let enc = JSONEncoder()
enc.outputFormatting = [.sortedKeys]
print(String(data: try! enc.encode(res), encoding: .utf8)!)
