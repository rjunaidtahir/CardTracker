import AVFoundation
import CoreTransferable
import UIKit
import UniformTypeIdentifiers

/// Turns a screen recording of a Messages conversation into a series of still frames, so a whole bank thread can be
/// read in one pass instead of dozens of separate screenshots. Frames go through the same ScreenshotReader pipeline
/// as ordinary screenshots — this only supplies the images.
enum VideoFrameExtractor {
    /// - Parameters:
    ///   - sampleInterval: how often to look at the video, in seconds. Fine enough to not miss a message during a
    ///     normal scroll, coarse enough that a several-minute recording doesn't turn into an enormous frame count.
    ///   - maxFrames: a hard ceiling, so a very long or accidentally-static recording can't run away.
    static func frames(from url: URL, sampleInterval: Double = 0.5, maxFrames: Int = 240) async -> [UIImage] {
        let asset = AVURLAsset(url: url)
        guard let duration = try? await asset.load(.duration) else { return [] }
        let seconds = CMTimeGetSeconds(duration)
        guard seconds.isFinite, seconds > 0 else { return [] }

        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero

        var times: [CMTime] = []
        var t = 0.0
        while t < seconds, times.count < maxFrames {
            times.append(CMTime(seconds: t, preferredTimescale: 600))
            t += sampleInterval
        }
        guard !times.isEmpty else { return [] }

        var kept: [UIImage] = []
        var lastSignature: [UInt8]?
        do {
            for try await item in generator.images(for: times) {
                let cgImage = item.image
                let signature = thumbprint(of: cgImage)
                // Two frames that look the same (paused, or between scroll gestures) would just read the same
                // messages twice — the engine already skips messages it's seen, but skipping the OCR pass itself
                // here keeps a long recording fast.
                if let last = lastSignature, similar(signature, last) { continue }
                lastSignature = signature
                kept.append(UIImage(cgImage: cgImage))
            }
        } catch {
            // A mid-stream generation failure shouldn't discard the frames already collected.
        }
        return kept
    }

    /// A tiny grayscale downsample used only to compare one frame with the next — not for reading text.
    private static func thumbprint(of cgImage: CGImage) -> [UInt8] {
        let size = 24
        var pixels = [UInt8](repeating: 0, count: size * size)
        let colorSpace = CGColorSpaceCreateDeviceGray()
        guard let context = CGContext(data: &pixels, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size,
                                       space: colorSpace, bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return pixels }
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: size, height: size))
        return pixels
    }

    private static func similar(_ a: [UInt8], _ b: [UInt8], threshold: Int = 5) -> Bool {
        guard a.count == b.count, !a.isEmpty else { return false }
        var diff = 0
        for i in 0..<a.count { diff += abs(Int(a[i]) - Int(b[i])) }
        return diff / a.count < threshold
    }
}

/// A video picked with PhotosPicker, copied to a temporary file so AVFoundation can read it by URL.
struct PickedVideo: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { picked in
            SentTransferredFile(picked.url)
        } importing: { received in
            let copy = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".mov")
            try? FileManager.default.removeItem(at: copy)
            try FileManager.default.copyItem(at: received.file, to: copy)
            return Self(url: copy)
        }
    }
}
