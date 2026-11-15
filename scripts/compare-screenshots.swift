// Pixel comparison of two screenshot sets produced by the test harness (CLAUDE.md section 6-1).
//
// Usage: compare-screenshots <baseline-dir> <candidate-dir> <output-dir> [--tolerance N] [--max-diff-percent P]
//
// A pixel counts as different when any colour channel differs by more than the tolerance (0-255). A scene
// passes when the share of different pixels stays at or below the allowed percentage. For every scene a diff
// image is written: the baseline in dim greyscale with the different pixels painted from red (small
// difference) to yellow (large difference). Results go to <output-dir>/compare.json. Only scenes present in
// the candidate are compared. Exit code 1 means at least one scene differs, 2 means the tool itself failed.

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

struct Bitmap {
    let width: Int
    let height: Int
    var pixels: [UInt8] // RGBA, 8 bits per channel
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(2)
}

func loadBitmap(_ url: URL) -> Bitmap? {
    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
    let width = image.width
    let height = image.height
    var pixels = [UInt8](repeating: 0, count: width * height * 4)
    let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
        guard let context = CGContext(
            data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else { return false }
        context.interpolationQuality = .none
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return true
    }
    return drawn ? Bitmap(width: width, height: height, pixels: pixels) : nil
}

func savePNG(_ bitmap: Bitmap, to url: URL) -> Bool {
    var pixels = bitmap.pixels
    let image = pixels.withUnsafeMutableBytes { buffer -> CGImage? in
        CGContext(
            data: buffer.baseAddress, width: bitmap.width, height: bitmap.height, bitsPerComponent: 8,
            bytesPerRow: bitmap.width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        )?.makeImage()
    }
    guard let image,
          let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)
    else { return false }
    CGImageDestinationAddImage(destination, image, nil)
    return CGImageDestinationFinalize(destination)
}

var positional: [String] = []
var tolerance = 8
var maxDiffPercent = 0.5
var index = 1
let arguments = CommandLine.arguments
while index < arguments.count {
    switch arguments[index] {
    case "--tolerance":
        index += 1
        guard index < arguments.count, let value = Int(arguments[index]), (0...255).contains(value) else { fail("--tolerance needs 0-255") }
        tolerance = value
    case "--max-diff-percent":
        index += 1
        guard index < arguments.count, let value = Double(arguments[index]), value >= 0 else { fail("--max-diff-percent needs a number") }
        maxDiffPercent = value
    default:
        positional.append(arguments[index])
    }
    index += 1
}
guard positional.count == 3 else {
    fail("Usage: compare-screenshots <baseline-dir> <candidate-dir> <output-dir> [--tolerance N] [--max-diff-percent P]")
}

let fileManager = FileManager.default
let baselineDir = URL(fileURLWithPath: positional[0])
let candidateDir = URL(fileURLWithPath: positional[1])
let outputDir = URL(fileURLWithPath: positional[2])
do {
    try fileManager.createDirectory(at: outputDir, withIntermediateDirectories: true)
} catch {
    fail("Cannot create \(outputDir.path): \(error.localizedDescription)")
}

guard let baselineFiles = try? fileManager.contentsOfDirectory(atPath: baselineDir.path) else {
    fail("Cannot read baseline directory \(baselineDir.path)")
}
guard let candidateFiles = try? fileManager.contentsOfDirectory(atPath: candidateDir.path) else {
    fail("Cannot read candidate directory \(candidateDir.path)")
}
// The candidate decides what is compared, so a run of only some scenes is not a failure.
let baselineNames = Set(baselineFiles.filter { $0.hasSuffix(".png") })
let names = candidateFiles.filter { $0.hasSuffix(".png") }.sorted()
if names.isEmpty { fail("No PNG files in \(candidateDir.path)") }

// Scenes the harness marked as not repeatable (random particles, live FPS numbers) are reported but never fail.
var visualOnly = Set<String>()
if let data = try? Data(contentsOf: candidateDir.appendingPathComponent("metrics.json")),
   let metrics = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
   let scenes = metrics["scenes"] as? [[String: Any]] {
    for scene in scenes {
        if let name = scene["name"] as? String, scene["pixelCompare"] as? Bool == false { visualOnly.insert(name) }
    }
}

func pad(_ text: String, _ width: Int) -> String {
    text.count >= width ? text : text + String(repeating: " ", count: width - text.count)
}

var results: [[String: Any]] = []
var failed = false
print("\(pad("scene", 18)) \(pad("diff %", 10)) \(pad("mean diff", 10)) \(pad("max", 5)) result")

for name in names {
    let scene = String(name.dropLast(4))
    guard baselineNames.contains(name) else {
        results.append(["scene": scene, "pass": true, "reason": "no baseline"])
        print("\(pad(scene, 18)) no baseline yet (not compared)")
        continue
    }
    guard let baseline = loadBitmap(baselineDir.appendingPathComponent(name)) else {
        fail("Cannot decode \(baselineDir.appendingPathComponent(name).path)")
    }
    guard let candidate = loadBitmap(candidateDir.appendingPathComponent(name)) else {
        fail("Cannot decode \(candidateDir.appendingPathComponent(name).path)")
    }
    guard baseline.width == candidate.width, baseline.height == candidate.height else {
        failed = true
        let reason = "size \(candidate.width)x\(candidate.height) differs from baseline \(baseline.width)x\(baseline.height)"
        results.append(["scene": scene, "pass": false, "reason": reason])
        print("\(pad(scene, 18)) \(reason)")
        continue
    }

    let pixelCount = baseline.width * baseline.height
    var diff = Bitmap(width: baseline.width, height: baseline.height, pixels: [UInt8](repeating: 255, count: pixelCount * 4))
    var differentPixels = 0
    var maxChannelDiff = 0
    var totalChannelDiff = 0
    baseline.pixels.withUnsafeBufferPointer { a in
        candidate.pixels.withUnsafeBufferPointer { b in
            diff.pixels.withUnsafeMutableBufferPointer { out in
                for pixel in 0..<pixelCount {
                    let offset = pixel * 4
                    let dr = abs(Int(a[offset]) - Int(b[offset]))
                    let dg = abs(Int(a[offset + 1]) - Int(b[offset + 1]))
                    let db = abs(Int(a[offset + 2]) - Int(b[offset + 2]))
                    let worst = max(dr, max(dg, db))
                    totalChannelDiff += dr + dg + db
                    if worst > maxChannelDiff { maxChannelDiff = worst }
                    if worst > tolerance {
                        differentPixels += 1
                        out[offset] = 255
                        out[offset + 1] = UInt8(min(255, worst * 2))
                        out[offset + 2] = 0
                    } else {
                        let luma = (Int(a[offset]) * 54 + Int(a[offset + 1]) * 183 + Int(a[offset + 2]) * 19) >> 8
                        let dim = UInt8(luma * 35 / 100)
                        out[offset] = dim
                        out[offset + 1] = dim
                        out[offset + 2] = dim
                    }
                }
            }
        }
    }

    let diffPercent = Double(differentPixels) * 100.0 / Double(pixelCount)
    let meanDiff = Double(totalChannelDiff) / Double(pixelCount * 3)
    let compared = !visualOnly.contains(scene)
    let pass = !compared || diffPercent <= maxDiffPercent
    if !pass { failed = true }
    let diffURL = outputDir.appendingPathComponent("\(scene).diff.png")
    if !savePNG(diff, to: diffURL) { fail("Cannot write \(diffURL.path)") }

    results.append([
        "scene": scene,
        "pass": pass,
        "pixelCompare": compared,
        "diffPercent": (diffPercent * 10000).rounded() / 10000,
        "meanChannelDiff": (meanDiff * 1000).rounded() / 1000,
        "maxChannelDiff": maxChannelDiff,
        "diffImage": diffURL.lastPathComponent,
    ])
    let verdict = !compared ? "visual check only" : (pass ? "ok" : "DIFFERENT")
    print("\(pad(scene, 18)) \(pad(String(format: "%.4f", diffPercent), 10)) \(pad(String(format: "%.3f", meanDiff), 10)) \(pad(String(maxChannelDiff), 5)) \(verdict)")
}

let notRun = baselineNames.subtracting(names).map { String($0.dropLast(4)) }.sorted()
if !notRun.isEmpty { print("Not run this time: \(notRun.joined(separator: ", "))") }

let summary: [String: Any] = [
    "notRun": notRun,
    "baseline": baselineDir.path,
    "candidate": candidateDir.path,
    "tolerance": tolerance,
    "maxDiffPercent": maxDiffPercent,
    "pass": !failed,
    "scenes": results,
]
do {
    let data = try JSONSerialization.data(withJSONObject: summary, options: [.prettyPrinted, .sortedKeys])
    try data.write(to: outputDir.appendingPathComponent("compare.json"))
} catch {
    fail("Cannot write compare.json: \(error.localizedDescription)")
}
exit(failed ? 1 : 0)
