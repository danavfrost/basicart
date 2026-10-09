import Compression
import Foundation

/// Minimal ZIP reader/writer (Store + Deflate, ZIP64-aware) on Apple's Compression
/// framework. No third-party code. Used for project packages (FORMAT.md §13).
enum ZipError: Error, Equatable {
    case corrupt(String)
    case limit(String)   // "tooLarge" / "compressionRatio"
    case io(String)
}

// MARK: - CRC-32

enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 { c = (c & 1) != 0 ? (0xEDB8_8320 ^ (c >> 1)) : (c >> 1) }
        return c
    }

    static func update(_ crc: UInt32, _ buf: UnsafeRawBufferPointer) -> UInt32 {
        var c = ~crc
        for b in buf { c = table[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
        return ~c
    }

    static func checksum(_ data: Data) -> UInt32 { data.withUnsafeBytes { update(0, $0) } }
}

// MARK: - Reader

struct ZipEntry {
    var name: String
    var rawName: [UInt8]
    var flags: UInt16
    var method: UInt16
    var crc: UInt32
    var compressedSize: UInt64
    var uncompressedSize: UInt64
    var localHeaderOffset: UInt64
    var externalAttributes: UInt32
    var versionMadeBy: UInt16

    var isDirectory: Bool { name.hasSuffix("/") }
    var isEncrypted: Bool { flags & 1 != 0 }
    /// Unix mode from the external attributes (when made on Unix).
    var unixMode: UInt32 { externalAttributes >> 16 }
    var isSymlink: Bool { (unixMode & 0o170000) == 0o120000 }
}

final class ZipReader {
    let url: URL
    private let handle: FileHandle
    let fileSize: UInt64
    private(set) var entries: [ZipEntry] = []

    init(url: URL) throws {
        self.url = url
        do { handle = try FileHandle(forReadingFrom: url) } catch { throw ZipError.io("open") }
        fileSize = (try? handle.seekToEnd()) ?? 0
        try readCentralDirectory()
    }

    deinit { try? handle.close() }

    private func read(at offset: UInt64, count: Int) throws -> Data {
        guard offset + UInt64(count) <= fileSize else { throw ZipError.corrupt("read past end") }
        try handle.seek(toOffset: offset)
        let d = handle.readData(ofLength: count)
        guard d.count == count else { throw ZipError.corrupt("short read") }
        return d
    }

    private func readCentralDirectory() throws {
        // End of central directory: search the last 64 KiB + 22 bytes.
        let tailLen = Int(min(fileSize, 65_557))
        guard tailLen >= 22 else { throw ZipError.corrupt("too small") }
        let tailStart = fileSize - UInt64(tailLen)
        let tail = try read(at: tailStart, count: tailLen)
        var eocd: Int?
        var i = tail.count - 22
        while i >= 0 {
            if tail.u32(i) == 0x0605_4B50 { eocd = i; break }
            i -= 1
        }
        guard let e = eocd else { throw ZipError.corrupt("no end record") }
        var count = UInt64(tail.u16(e + 10))
        var cdSize = UInt64(tail.u32(e + 12))
        var cdOffset = UInt64(tail.u32(e + 16))
        // ZIP64 end-of-central-directory locator just before the EOCD.
        if e >= 20, tail.u32(e - 20) == 0x0706_4B50 {
            let recOffset = tail.u64(e - 20 + 8)
            let rec = try read(at: recOffset, count: 56)
            guard rec.u32(0) == 0x0606_4B50 else { throw ZipError.corrupt("bad zip64 record") }
            count = rec.u64(32)
            cdSize = rec.u64(40)
            cdOffset = rec.u64(48)
        }
        guard cdOffset + cdSize <= fileSize, count < 1_000_000 else { throw ZipError.corrupt("bad directory") }
        let cd = try read(at: cdOffset, count: Int(cdSize))
        var p = 0
        var out: [ZipEntry] = []
        out.reserveCapacity(Int(count))
        for _ in 0..<count {
            guard p + 46 <= cd.count, cd.u32(p) == 0x0201_4B50 else { throw ZipError.corrupt("bad directory entry") }
            let madeBy = cd.u16(p + 4)
            let flags = cd.u16(p + 8)
            let method = cd.u16(p + 10)
            let crc = cd.u32(p + 16)
            var csize = UInt64(cd.u32(p + 20))
            var usize = UInt64(cd.u32(p + 24))
            let nameLen = Int(cd.u16(p + 28)), extraLen = Int(cd.u16(p + 30)), commentLen = Int(cd.u16(p + 32))
            let ext = cd.u32(p + 38)
            var offset = UInt64(cd.u32(p + 42))
            guard p + 46 + nameLen + extraLen + commentLen <= cd.count else { throw ZipError.corrupt("truncated entry") }
            let rawName = [UInt8](cd[(cd.startIndex + p + 46)..<(cd.startIndex + p + 46 + nameLen)])
            // ZIP64 extra field (0x0001): values present only for fields that are 0xFFFFFFFF.
            var x = p + 46 + nameLen
            let xEnd = x + extraLen
            while x + 4 <= xEnd {
                let id = cd.u16(x), len = Int(cd.u16(x + 2))
                if id == 0x0001 {
                    var q = x + 4
                    if usize == 0xFFFF_FFFF, q + 8 <= x + 4 + len { usize = cd.u64(q); q += 8 }
                    if csize == 0xFFFF_FFFF, q + 8 <= x + 4 + len { csize = cd.u64(q); q += 8 }
                    if offset == 0xFFFF_FFFF, q + 8 <= x + 4 + len { offset = cd.u64(q); q += 8 }
                }
                x += 4 + len
            }
            let name = String(bytes: rawName, encoding: .utf8) ?? String(decoding: rawName, as: UTF8.self)
            out.append(ZipEntry(name: name, rawName: rawName, flags: flags, method: method, crc: crc,
                                compressedSize: csize, uncompressedSize: usize, localHeaderOffset: offset,
                                externalAttributes: ext, versionMadeBy: madeBy))
            p += 46 + nameLen + extraLen + commentLen
        }
        entries = out
    }

    private func dataOffset(_ e: ZipEntry) throws -> UInt64 {
        let h = try read(at: e.localHeaderOffset, count: 30)
        guard h.u32(0) == 0x0403_4B50 else { throw ZipError.corrupt("bad local header") }
        return e.localHeaderOffset + 30 + UInt64(h.u16(26)) + UInt64(h.u16(28))
    }

    /// Streams an entry's uncompressed bytes to `sink`, counting real output bytes and
    /// enforcing `maxBytes` and the expansion ratio (declared sizes are not trusted).
    func extract(_ e: ZipEntry, maxBytes: UInt64, ratioMinBytes: UInt64, maxRatio: UInt64,
                 sink: (Data) throws -> Void) throws {
        guard !e.isEncrypted else { throw ZipError.corrupt("encrypted") }
        let start = try dataOffset(e)
        guard start + e.compressedSize <= fileSize else { throw ZipError.corrupt("entry past end") }
        var produced: UInt64 = 0
        var crc: UInt32 = 0
        func emit(_ d: Data) throws {
            produced += UInt64(d.count)
            if produced > maxBytes { throw ZipError.limit("tooLarge") }
            if produced >= ratioMinBytes && produced > maxRatio * max(e.compressedSize, 1) { throw ZipError.limit("compressionRatio") }
            crc = d.withUnsafeBytes { CRC32.update(crc, $0) }
            try sink(d)
        }
        try handle.seek(toOffset: start)
        var remaining = e.compressedSize
        let chunk = 1 << 16
        switch e.method {
        case 0:
            while remaining > 0 {
                let n = Int(min(UInt64(chunk), remaining))
                let d = handle.readData(ofLength: n)
                guard d.count == n else { throw ZipError.corrupt("short read") }
                remaining -= UInt64(n)
                try emit(d)
            }
        case 8:
            let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
            defer { stream.deallocate() }
            guard compression_stream_init(stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else {
                throw ZipError.io("inflate init")
            }
            defer { compression_stream_destroy(stream) }
            let outCap = 1 << 17
            let outBuf = UnsafeMutablePointer<UInt8>.allocate(capacity: outCap)
            defer { outBuf.deallocate() }
            let inBuf = UnsafeMutablePointer<UInt8>.allocate(capacity: chunk)
            defer { inBuf.deallocate() }
            stream.pointee.src_size = 0
            while true {
                if stream.pointee.src_size == 0 && remaining > 0 {
                    let n = Int(min(UInt64(chunk), remaining))
                    let d = handle.readData(ofLength: n)
                    guard d.count == n else { throw ZipError.corrupt("short read") }
                    d.copyBytes(to: inBuf, count: n)
                    remaining -= UInt64(n)
                    stream.pointee.src_ptr = UnsafePointer(inBuf)
                    stream.pointee.src_size = n
                }
                let flags: Int32 = remaining == 0 ? Int32(COMPRESSION_STREAM_FINALIZE.rawValue) : 0
                stream.pointee.dst_ptr = outBuf
                stream.pointee.dst_size = outCap
                let status = compression_stream_process(stream, flags)
                if status == COMPRESSION_STATUS_ERROR { throw ZipError.corrupt("inflate") }
                let n = outCap - stream.pointee.dst_size
                if n > 0 { try emit(Data(bytes: outBuf, count: n)) }
                if status == COMPRESSION_STATUS_END { break }
                if remaining == 0 && stream.pointee.src_size == 0 && n == 0 { break }
            }
        default:
            throw ZipError.corrupt("unsupported method \(e.method)")
        }
        guard crc == e.crc else { throw ZipError.corrupt("crc mismatch") }
    }

    func data(_ e: ZipEntry, max: UInt64 = 64 << 20) throws -> Data {
        var out = Data()
        try extract(e, maxBytes: max, ratioMinBytes: 1 << 20, maxRatio: 100) { out.append($0) }
        return out
    }
}

// MARK: - Writer

final class ZipWriter {
    private let handle: FileHandle
    private var offset: UInt64 = 0
    private var central = Data()
    private var count = 0

    init(url: URL) throws {
        FileManager.default.createFile(atPath: url.path, contents: nil)
        do { handle = try FileHandle(forWritingTo: url) } catch { throw ZipError.io("create") }
    }

    private func write(_ d: Data) throws {
        try handle.write(contentsOf: d)
        offset += UInt64(d.count)
    }

    /// Adds a file (Deflate when it helps, else Store). UTF-8 names (flag bit 11).
    func add(name: String, data: Data, modified: Date = Date()) throws {
        let nameData = Data(name.utf8)
        let crc = CRC32.checksum(data)
        var method: UInt16 = 0
        var payload = data
        if data.count > 64, let deflated = ZipWriter.deflate(data), deflated.count < data.count {
            method = 8
            payload = deflated
        }
        let (dosTime, dosDate) = ZipWriter.dosDateTime(modified)
        let needs64 = data.count >= 0xFFFF_FFFF || payload.count >= 0xFFFF_FFFF || offset >= 0xFFFF_FFFF
        let localOffset = offset
        var local = Data()
        local.le32(0x0403_4B50); local.le16(needs64 ? 45 : 20); local.le16(0x0800); local.le16(method)
        local.le16(dosTime); local.le16(dosDate); local.le32(crc)
        local.le32(needs64 ? 0xFFFF_FFFF : UInt32(payload.count)); local.le32(needs64 ? 0xFFFF_FFFF : UInt32(data.count))
        local.le16(UInt16(nameData.count)); local.le16(needs64 ? 20 : 0)
        local.append(nameData)
        if needs64 { local.le16(1); local.le16(16); local.le64(UInt64(data.count)); local.le64(UInt64(payload.count)) }
        try write(local)
        try write(payload)

        var c = Data()
        c.le32(0x0201_4B50); c.le16(0x0314); c.le16(needs64 ? 45 : 20); c.le16(0x0800); c.le16(method)
        c.le16(dosTime); c.le16(dosDate); c.le32(crc)
        c.le32(needs64 ? 0xFFFF_FFFF : UInt32(payload.count)); c.le32(needs64 ? 0xFFFF_FFFF : UInt32(data.count))
        c.le16(UInt16(nameData.count)); c.le16(needs64 ? 28 : 0); c.le16(0)
        c.le16(0); c.le16(0); c.le32(UInt32(0o100644) << 16)
        c.le32(needs64 ? 0xFFFF_FFFF : UInt32(localOffset))
        c.append(nameData)
        if needs64 { c.le16(1); c.le16(24); c.le64(UInt64(data.count)); c.le64(UInt64(payload.count)); c.le64(localOffset) }
        central.append(c)
        count += 1
    }

    func finish() throws {
        let cdOffset = offset
        try write(central)
        let cdSize = UInt64(central.count)
        let needs64 = cdOffset >= 0xFFFF_FFFF || count >= 0xFFFF
        if needs64 {
            let recOffset = offset
            var r = Data()
            r.le32(0x0606_4B50); r.le64(44); r.le16(45); r.le16(45); r.le32(0); r.le32(0)
            r.le64(UInt64(count)); r.le64(UInt64(count)); r.le64(cdSize); r.le64(cdOffset)
            r.le32(0x0706_4B50); r.le32(0); r.le64(recOffset); r.le32(1)
            try write(r)
        }
        var e = Data()
        e.le32(0x0605_4B50); e.le16(0); e.le16(0)
        e.le16(needs64 ? 0xFFFF : UInt16(count)); e.le16(needs64 ? 0xFFFF : UInt16(count))
        e.le32(needs64 ? 0xFFFF_FFFF : UInt32(cdSize)); e.le32(needs64 ? 0xFFFF_FFFF : UInt32(cdOffset)); e.le16(0)
        try write(e)
        try handle.synchronize()
        try handle.close()
    }

    static func deflate(_ data: Data) -> Data? {
        let cap = data.count + data.count / 10 + 1024
        var out = Data(count: cap)
        let n = out.withUnsafeMutableBytes { dst in
            data.withUnsafeBytes { src in
                compression_encode_buffer(dst.bindMemory(to: UInt8.self).baseAddress!, cap,
                                          src.bindMemory(to: UInt8.self).baseAddress!, data.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard n > 0 else { return nil }
        out.count = n
        return out
    }

    static func dosDateTime(_ d: Date) -> (UInt16, UInt16) {
        let c = Calendar(identifier: .gregorian).dateComponents(in: TimeZone(identifier: "UTC")!, from: d)
        let time = UInt16((c.hour ?? 0) << 11 | (c.minute ?? 0) << 5 | (c.second ?? 0) / 2)
        let date = UInt16(max(0, (c.year ?? 1980) - 1980) << 9 | (c.month ?? 1) << 5 | (c.day ?? 1))
        return (time, date)
    }
}

// MARK: - Little-endian helpers

private extension Data {
    func u16(_ i: Int) -> UInt16 { UInt16(self[startIndex + i]) | UInt16(self[startIndex + i + 1]) << 8 }
    func u32(_ i: Int) -> UInt32 { UInt32(u16(i)) | UInt32(u16(i + 2)) << 16 }
    func u64(_ i: Int) -> UInt64 { UInt64(u32(i)) | UInt64(u32(i + 4)) << 32 }
}

extension Data {
    mutating func le16(_ v: UInt16) { append(UInt8(v & 0xFF)); append(UInt8(v >> 8)) }
    mutating func le32(_ v: UInt32) { le16(UInt16(v & 0xFFFF)); le16(UInt16(v >> 16)) }
    mutating func le64(_ v: UInt64) { le32(UInt32(v & 0xFFFF_FFFF)); le32(UInt32(v >> 32)) }
}
