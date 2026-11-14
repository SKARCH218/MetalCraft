import Foundation
import Metal

private let libraryVersion = "0.1.0"

@_cdecl("mcn_abi_version")
public func mcn_abi_version() -> Int32 {
    return 1
}

@_cdecl("mcn_describe")
public func mcn_describe(_ buf: UnsafeMutablePointer<CChar>?, _ capacity: Int32) -> Int32 {
    guard let buf = buf, capacity > 0 else {
        recordError("mcn_describe: buffer is null or capacity <= 0")
        return MCN_ERR_INVALID_ARGUMENT
    }
    guard let device = MTLCreateSystemDefaultDevice() else {
        recordError("No Metal device available")
        return MCN_ERR_METAL_UNAVAILABLE
    }
    let os = ProcessInfo.processInfo.operatingSystemVersion
    let text = "MetalCraftNative \(libraryVersion) | Metal device: \(device.name) | "
        + "macOS \(os.majorVersion).\(os.minorVersion).\(os.patchVersion)"
    let bytes = Array(text.utf8)
    let cap = Int(capacity)
    if bytes.count + 1 > cap {
        recordError("mcn_describe: buffer too small (need \(bytes.count + 1) bytes, have \(cap))")
        return MCN_ERR_BUFFER_TOO_SMALL
    }
    copyTruncated(bytes, to: buf, capacity: cap)
    return MCN_OK
}

@_cdecl("mcn_last_error")
public func mcn_last_error(_ buf: UnsafeMutablePointer<CChar>?, _ capacity: Int32) -> Int32 {
    let bytes = currentErrorBytes()
    if let buf = buf, capacity > 0 {
        copyTruncated(bytes, to: buf, capacity: Int(capacity))
    }
    return Int32(clamping: bytes.count)
}
