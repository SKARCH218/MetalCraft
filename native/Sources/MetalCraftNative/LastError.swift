import Darwin

// Caller threads are created by the JVM, so use a raw pthread key instead of Foundation's Thread.
private final class ErrorBox {
    let bytes: [UInt8]
    init(_ bytes: [UInt8]) { self.bytes = bytes }
}

private let lastErrorKey: pthread_key_t = {
    var key = pthread_key_t()
    let rc = pthread_key_create(&key) { raw in
        Unmanaged<ErrorBox>.fromOpaque(raw).release()
    }
    return rc == 0 ? key : pthread_key_t.max
}()

/// Records `message` as the calling thread's last error. Best effort: silently skipped if no key.
func recordError(_ message: String) {
    guard lastErrorKey != pthread_key_t.max else { return }
    let box = Unmanaged.passRetained(ErrorBox(Array(message.utf8)))
    let old = pthread_getspecific(lastErrorKey)
    // Release the previous box only once the slot no longer points at it.
    guard pthread_setspecific(lastErrorKey, box.toOpaque()) == 0 else {
        box.release()
        return
    }
    if let old = old {
        Unmanaged<ErrorBox>.fromOpaque(old).release()
    }
}

func currentErrorBytes() -> [UInt8] {
    guard lastErrorKey != pthread_key_t.max, let raw = pthread_getspecific(lastErrorKey) else { return [] }
    return Unmanaged<ErrorBox>.fromOpaque(raw).takeUnretainedValue().bytes
}

/// Copies `bytes` plus a NUL into `buf`, truncating to fit. Caller guarantees `capacity > 0`.
func copyTruncated(_ bytes: [UInt8], to buf: UnsafeMutablePointer<CChar>, capacity: Int) {
    let n = min(bytes.count, capacity - 1)
    bytes.withUnsafeBufferPointer { src in
        for i in 0..<n { buf[i] = CChar(bitPattern: src[i]) }
    }
    buf[n] = 0
}
