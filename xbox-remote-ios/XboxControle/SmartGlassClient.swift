import Foundation
import CryptoKit
import Security
import Darwin

final class SmartGlassClient: ObservableObject {
    @Published var status = "Desconectado"
    @Published var connected = false
    @Published var consoleName = "Xbox"
    @Published var consoleIP = ""

    static let btnNexus: UInt16 = 0x0002
    static let btnMenu: UInt16  = 0x0004
    static let btnView: UInt16  = 0x0008
    static let btnA: UInt16     = 0x0010
    static let btnB: UInt16     = 0x0020
    static let btnX: UInt16     = 0x0040
    static let btnY: UInt16     = 0x0080
    static let btnUp: UInt16    = 0x0100
    static let btnDown: UInt16  = 0x0200
    static let btnLeft: UInt16  = 0x0400
    static let btnRight: UInt16 = 0x0800

    private static let port: UInt16 = 5050
    private static let pktConnectRequest: UInt16 = 0xCC00
    private static let pktConnectResponse: UInt16 = 0xCC01
    private static let pktDiscoveryRequest: UInt16 = 0xDD00
    private static let pktDiscoveryResponse: UInt16 = 0xDD01
    private static let pktMessage: UInt16 = 0xD00D

    private static let msgAck = 0x001
    private static let msgLocalJoin = 0x003
    private static let msgConsoleStatus = 0x01E
    private static let msgStartChannelRequest = 0x026
    private static let msgStartChannelResponse = 0x027
    private static let msgDisconnect = 0x02A
    private static let msgGamepad = 0xF0A
    private static let systemInputUUID = UUID(uuidString: "fa20b8ca-66fb-46e0-adb6-0b978a59d35f")!

    private var socketFD: Int32 = -1
    private var crypto: CryptoContext?
    private var sourceParticipantID: UInt32 = 0
    private var systemInputChannel: UInt64 = 0
    private var sequence: UInt32 = 0
    private var lowWatermark: UInt32 = 0
    private var receiverTask: Task<Void, Never>?
    private var heartbeatTask: Task<Void, Never>?
    private let ioQueue = DispatchQueue(label: "XboxSmartGlassIO")
    private var targetIP = ""

    deinit {
        receiverTask?.cancel()
        heartbeatTask?.cancel()
        if socketFD >= 0 { Darwin.close(socketFD) }
    }

    func connect(ip: String) {
        let trimmed = ip.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            status = "Informe o IP do Xbox."
            return
        }
        status = "Procurando Xbox…"
        connected = false

        Task.detached(priority: .userInitiated) { [weak self] in
            guard let self else { return }
            do {
                try await self.connectBlocking(ip: trimmed)
            } catch {
                await MainActor.run {
                    self.connected = false
                    self.status = error.localizedDescription
                }
            }
        }
    }

    func disconnect() {
        receiverTask?.cancel()
        heartbeatTask?.cancel()
        receiverTask = nil
        heartbeatTask = nil
        connected = false
        systemInputChannel = 0
        crypto = nil
        if socketFD >= 0 {
            Darwin.close(socketFD)
            socketFD = -1
        }
        status = "Desconectado"
    }

    func sendButton(_ mask: UInt16) {
        guard connected, systemInputChannel != 0 else { return }
        Task.detached(priority: .userInitiated) { [weak self] in
            guard let self else { return }
            do {
                try self.ioQueue.sync { try self.sendGamepad(buttons: mask) }
                try await Task.sleep(nanoseconds: 90_000_000)
                try self.ioQueue.sync { try self.sendGamepad(buttons: 0) }
            } catch {
                await MainActor.run { self.status = "Falha ao enviar comando: \(error.localizedDescription)" }
            }
        }
    }

    private func connectBlocking(ip: String) async throws {
        try ioQueue.sync {
            try setupSocket()
            targetIP = ip
        }
        await MainActor.run { status = "Xbox \(ip) • conectando…" }

        let info = try ioQueue.sync { try discover(ip: ip) }
        await MainActor.run {
            consoleName = info.name.isEmpty ? "Xbox" : info.name
            consoleIP = info.ip
            status = "Xbox encontrado • criando sessão…"
        }

        let context = try CryptoContext(certificateDER: info.certificate)
        crypto = context
        let result = try ioQueue.sync { try connectAnonymous() }
        guard result.result == 0 else { throw SGError.message(connectionError(result.result)) }
        sourceParticipantID = result.participantID

        await MainActor.run { status = "Sessão criada • ativando controle…" }
        let joinSeq = try ioQueue.sync { try sendLocalJoin() }
        guard try ioQueue.sync(execute: { try waitForAck(joinSeq, timeout: 5.0) }) else {
            throw SGError.message("O Xbox aceitou a sessão, mas não confirmou o controle local.")
        }

        let channelSeq = try ioQueue.sync { try sendStartSystemInputChannel() }
        _ = try ioQueue.sync { try waitForAck(channelSeq, timeout: 5.0) }
        if systemInputChannel == 0 {
            guard try ioQueue.sync(execute: { try waitForSystemInputChannel(timeout: 5.0) }) else {
                throw SGError.message("Conectado ao Xbox, mas o canal de controle não foi liberado.")
            }
        }

        await MainActor.run {
            connected = true
            status = "● \(consoleName) • \(consoleIP)"
        }
        startReceiverAndHeartbeat()
    }

    private func setupSocket() throws {
        if socketFD >= 0 { Darwin.close(socketFD) }
        socketFD = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard socketFD >= 0 else { throw SGError.message("Não foi possível abrir a rede local.") }
        var one: Int32 = 1
        setsockopt(socketFD, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(socketFD, SOL_SOCKET, SO_BROADCAST, &one, socklen_t(MemoryLayout<Int32>.size))
        var timeout = timeval(tv_sec: 0, tv_usec: 450_000)
        setsockopt(socketFD, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        sequence = 0
        lowWatermark = 0
        sourceParticipantID = 0
        systemInputChannel = 0
    }

    private func discover(ip: String) throws -> DiscoveryInfo {
        let request = discoveryPacket()
        let deadline = Date().addingTimeInterval(5.0)
        while Date() < deadline {
            try sendRaw(request, ip: ip)
            let round = Date().addingTimeInterval(0.7)
            while Date() < round {
                do {
                    let packet = try receiveDatagram()
                    if let info = parseDiscovery(packet.data, from: packet.ip), info.ip == ip { return info }
                } catch SGError.timeout { break }
            }
        }
        throw SGError.message("Xbox não encontrado em \(ip). Confirme se iPhone e Xbox estão na mesma rede Wi‑Fi.")
    }

    private func connectAnonymous() throws -> ConnectResult {
        guard let crypto else { throw SGError.message("Criptografia não inicializada.") }
        var iv = Data(count: 16)
        _ = iv.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }

        var protected = Data()
        protected.appendSGString("")
        protected.appendSGString("")
        protected.appendBE(UInt32(0))
        protected.appendBE(UInt32(0))
        protected.appendBE(UInt32(1))

        var unprotected = Data()
        unprotected.appendUUID(UUID())
        unprotected.appendBE(crypto.publicKeyType)
        unprotected.append(crypto.publicKeyBytes)
        unprotected.append(iv)

        let encrypted = try crypto.encrypt(iv: iv, data: protected.pad16())
        var header = Data()
        header.appendBE(Self.pktConnectRequest)
        header.appendBE(UInt16(unprotected.count))
        header.appendBE(UInt16(protected.count))
        header.appendBE(UInt16(2))
        let signed = header + unprotected + encrypted
        let packet = signed + crypto.hmac(signed)
        try sendRaw(packet, ip: targetIP)

        let deadline = Date().addingTimeInterval(6.0)
        var resent = false
        while Date() < deadline {
            do {
                let response = try receiveDatagram()
                guard response.ip == targetIP, response.data.count >= 2 else { continue }
                if response.data.readBEUInt16(at: 0) == Self.pktConnectResponse {
                    return try parseConnectResponse(response.data)
                }
            } catch SGError.timeout {
                if !resent { resent = true; try sendRaw(packet, ip: targetIP) }
            }
        }
        throw SGError.message("O Xbox foi localizado, mas não respondeu ao pedido de conexão.")
    }

    private func parseConnectResponse(_ data: Data) throws -> ConnectResult {
        guard let crypto, data.count >= 72 else { throw SGError.message("Resposta de conexão incompleta.") }
        guard data.readBEUInt16(at: 0) == Self.pktConnectResponse else { throw SGError.message("Resposta inesperada.") }
        let unprotectedLen = Int(data.readBEUInt16(at: 2))
        let protectedLen = Int(data.readBEUInt16(at: 4))
        guard unprotectedLen >= 16, 8 + unprotectedLen + 32 <= data.count else { throw SGError.message("Resposta inválida.") }

        let signed = data.prefix(data.count - 32)
        let supplied = data.suffix(32)
        guard Data(supplied) == crypto.hmac(Data(signed)) else { throw SGError.message("Assinatura da resposta inválida.") }

        let iv = data.subdata(in: 8..<24)
        let cipherStart = 8 + unprotectedLen
        let cipher = data.subdata(in: cipherStart..<(data.count - 32))
        let plain = try crypto.decrypt(iv: iv, data: cipher)
        guard protectedLen <= plain.count, protectedLen >= 8 else { throw SGError.message("Payload de conexão inválido.") }
        return ConnectResult(result: plain.readBEUInt16(at: 0), participantID: plain.readBEUInt32(at: 4))
    }

    private func sendLocalJoin() throws -> UInt32 {
        var p = Data()
        p.appendBE(UInt16(4)); p.appendBE(UInt16(1080)); p.appendBE(UInt16(1920)); p.appendBE(UInt16(96)); p.appendBE(UInt16(96))
        p.appendBE(UInt64.max); p.appendBE(UInt32(39)); p.appendBE(UInt32(6)); p.appendBE(UInt32(2)); p.appendSGString("SmartGlass-iPhone")
        let packet = try buildMessage(type: Self.msgLocalJoin, needAck: true, channel: 0, payload: p)
        try sendRaw(packet.data, ip: targetIP)
        return packet.sequence
    }

    private func sendStartSystemInputChannel() throws -> UInt32 {
        var p = Data()
        p.appendBE(UInt32(1)); p.appendBE(UInt32(0)); p.appendUUID(Self.systemInputUUID); p.appendBE(UInt32(0))
        let packet = try buildMessage(type: Self.msgStartChannelRequest, needAck: true, channel: 0, payload: p)
        try sendRaw(packet.data, ip: targetIP)
        return packet.sequence
    }

    private func sendGamepad(buttons: UInt16) throws {
        guard systemInputChannel != 0 else { return }
        var p = Data()
        p.appendBE(UInt64(Date().timeIntervalSince1970))
        p.appendBE(buttons)
        for _ in 0..<6 { p.appendBE(Float32(0).bitPattern) }
        let packet = try buildMessage(type: Self.msgGamepad, needAck: false, channel: systemInputChannel, payload: p)
        try sendRaw(packet.data, ip: targetIP)
    }

    private func sendAck(processed: [UInt32], rejected: [UInt32], needAck: Bool) throws {
        var p = Data()
        p.appendBE(lowWatermark)
        p.appendBE(UInt32(processed.count)); processed.forEach { p.appendBE($0) }
        p.appendBE(UInt32(rejected.count)); rejected.forEach { p.appendBE($0) }
        let packet = try buildMessage(type: Self.msgAck, needAck: needAck, channel: 0, payload: p)
        try sendRaw(packet.data, ip: targetIP)
    }

    private func buildMessage(type: Int, needAck: Bool, channel: UInt64, payload: Data) throws -> (sequence: UInt32, data: Data) {
        guard let crypto else { throw SGError.message("Criptografia não inicializada.") }
        sequence &+= 1
        let flags = UInt16(0x8000 | (needAck ? 0x2000 : 0) | (type & 0x0FFF))
        var header = Data()
        header.appendBE(Self.pktMessage)
        header.appendBE(UInt16(payload.count))
        header.appendBE(sequence)
        header.appendBE(UInt32(0))
        header.appendBE(sourceParticipantID)
        header.appendBE(flags)
        header.appendBE(channel)
        let iv = try crypto.messageIV(header.prefix(16))
        let encrypted = try crypto.encrypt(iv: iv, data: payload.pad16())
        let signed = header + encrypted
        return (sequence, signed + crypto.hmac(signed))
    }

    private func waitForAck(_ wanted: UInt32, timeout: TimeInterval) throws -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        var processed = Set<UInt32>()
        while Date() < deadline {
            do {
                if let m = try receiveAndHandle(), m.type == Self.msgAck {
                    parseAck(m.payload, into: &processed)
                    if processed.contains(wanted) { return true }
                }
            } catch SGError.timeout { }
        }
        return false
    }

    private func waitForSystemInputChannel(timeout: TimeInterval) throws -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if systemInputChannel != 0 { return true }
            do { _ = try receiveAndHandle() } catch SGError.timeout { }
        }
        return systemInputChannel != 0
    }

    private func startReceiverAndHeartbeat() {
        receiverTask?.cancel(); heartbeatTask?.cancel()
        receiverTask = Task.detached { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                do { _ = try self.ioQueue.sync { try self.receiveAndHandle() } }
                catch SGError.timeout { }
                catch { try? await Task.sleep(nanoseconds: 100_000_000) }
            }
        }
        heartbeatTask = Task.detached { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                guard self.connected else { continue }
                try? self.ioQueue.sync { try self.sendAck(processed: [], rejected: [], needAck: true) }
            }
        }
    }

    private func receiveAndHandle() throws -> DecodedMessage? {
        let packet = try receiveDatagram()
        guard packet.ip == targetIP, packet.data.count >= 2, packet.data.readBEUInt16(at: 0) == Self.pktMessage else { return nil }
        guard let m = try decodeMessage(packet.data) else { return nil }
        if (m.flags & 0x2000) != 0 {
            lowWatermark = max(lowWatermark, m.sequence)
            try sendAck(processed: [m.sequence], rejected: [], needAck: false)
        }
        if m.type == Self.msgStartChannelResponse, m.payload.count >= 16 {
            let requestID = m.payload.readBEUInt32(at: 0)
            let channel = m.payload.readBEUInt64(at: 4)
            let result = m.payload.readBEUInt32(at: 12)
            if requestID == 1 && result == 0 { systemInputChannel = channel }
        } else if m.type == Self.msgDisconnect {
            DispatchQueue.main.async {
                self.connected = false
                self.status = "Conexão com o Xbox encerrada."
            }
        }
        return m
    }

    private func decodeMessage(_ data: Data) throws -> DecodedMessage? {
        guard let crypto, data.count >= 74 else { return nil }
        let signed = data.prefix(data.count - 32)
        guard Data(data.suffix(32)) == crypto.hmac(Data(signed)) else { return nil }
        let plainLen = Int(data.readBEUInt16(at: 2))
        let seq = data.readBEUInt32(at: 4)
        let flags = data.readBEUInt16(at: 16)
        let channel = data.readBEUInt64(at: 18)
        let cipher = data.subdata(in: 26..<(data.count - 32))
        guard cipher.count > 0, cipher.count % 16 == 0 else { return nil }
        let iv = try crypto.messageIV(data.prefix(16))
        let padded = try crypto.decrypt(iv: iv, data: cipher)
        guard plainLen <= padded.count else { return nil }
        return DecodedMessage(sequence: seq, flags: flags, type: Int(flags & 0x0FFF), channel: channel, payload: padded.prefixData(plainLen))
    }

    private func parseAck(_ payload: Data, into processed: inout Set<UInt32>) {
        guard payload.count >= 8 else { return }
        var offset = 4
        let count = Int(payload.readBEUInt32(at: offset)); offset += 4
        guard count <= 1024 else { return }
        for _ in 0..<count where offset + 4 <= payload.count { processed.insert(payload.readBEUInt32(at: offset)); offset += 4 }
    }

    private func discoveryPacket() -> Data {
        var d = Data()
        d.appendBE(Self.pktDiscoveryRequest); d.appendBE(UInt16(10)); d.appendBE(UInt16(0)); d.appendBE(UInt32(0)); d.appendBE(UInt16(8)); d.appendBE(UInt16(0)); d.appendBE(UInt16(2))
        return d
    }

    private func parseDiscovery(_ data: Data, from ip: String) -> DiscoveryInfo? {
        guard data.count >= 16, data.readBEUInt16(at: 0) == Self.pktDiscoveryResponse else { return nil }
        var offset = 6
        guard offset + 6 <= data.count else { return nil }
        offset += 6
        guard let name = data.readSGString(offset: &offset), data.readSGString(offset: &offset) != nil else { return nil }
        guard offset + 6 <= data.count else { return nil }
        offset += 4
        let certLen = Int(data.readBEUInt16(at: offset)); offset += 2
        guard certLen > 0, offset + certLen <= data.count else { return nil }
        return DiscoveryInfo(ip: ip, name: name, certificate: data.subdata(in: offset..<(offset + certLen)))
    }

    private func sendRaw(_ data: Data, ip: String) throws {
        guard socketFD >= 0 else { throw SGError.message("Socket fechado.") }
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = Self.port.bigEndian
        let ok = ip.withCString { inet_pton(AF_INET, $0, &addr.sin_addr) }
        guard ok == 1 else { throw SGError.message("IP inválido.") }
        let sent = data.withUnsafeBytes { ptr -> Int in
            var copy = addr
            return withUnsafePointer(to: &copy) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    Darwin.sendto(socketFD, ptr.baseAddress, data.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
        guard sent == data.count else { throw SGError.message("Falha ao enviar dados ao Xbox.") }
    }

    private func receiveDatagram() throws -> (data: Data, ip: String) {
        var buffer = [UInt8](repeating: 0, count: 4096)
        var addr = sockaddr_in(); var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let n: Int = withUnsafeMutablePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                Darwin.recvfrom(socketFD, &buffer, buffer.count, 0, sa, &len)
            }
        }
        if n < 0 {
            if errno == EAGAIN || errno == EWOULDBLOCK { throw SGError.timeout }
            throw SGError.message(String(cString: strerror(errno)))
        }
        var a = addr.sin_addr
        var ipBuf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        inet_ntop(AF_INET, &a, &ipBuf, socklen_t(INET_ADDRSTRLEN))
        return (Data(buffer.prefix(n)), String(cString: ipBuf))
    }

    private func connectionError(_ code: UInt16) -> String {
        switch code {
        case 3: return "O Xbox está com conexões anônimas desativadas."
        case 4: return "O Xbox atingiu o limite de dispositivos conectados."
        case 5: return "O controle remoto/SmartGlass está desativado no Xbox."
        case 9: return "O Xbox exige uma conta autenticada para aceitar este dispositivo."
        default: return "O Xbox recusou a conexão (código \(code))."
        }
    }
}

private enum SGError: LocalizedError {
    case timeout
    case message(String)
    var errorDescription: String? {
        switch self { case .timeout: return "Tempo de rede esgotado."; case .message(let s): return s }
    }
}

private struct DiscoveryInfo { let ip: String; let name: String; let certificate: Data }
private struct ConnectResult { let result: UInt16; let participantID: UInt32 }
private struct DecodedMessage { let sequence: UInt32; let flags: UInt16; let type: Int; let channel: UInt64; let payload: Data }

private struct CryptoContext {
    let encryptKey: Data
    let ivKey: Data
    let hashKey: Data
    let publicKeyType: UInt16
    let publicKeyBytes: Data

    init(certificateDER: Data) throws {
        guard let cert = SecCertificateCreateWithData(nil, certificateDER as CFData), let secKey = SecCertificateCopyKey(cert) else {
            throw SGError.message("Certificado do Xbox inválido.")
        }
        var error: Unmanaged<CFError>?
        guard let ext = SecKeyCopyExternalRepresentation(secKey, &error) as Data? else { throw SGError.message("Não foi possível ler a chave do Xbox.") }
        let attrs = SecKeyCopyAttributes(secKey) as NSDictionary? ?? [:]
        let bits = (attrs[kSecAttrKeySizeInBits] as? NSNumber)?.intValue ?? 256
        let pre = Data([0xD6,0x37,0xF1,0xAA,0xE2,0xF0,0x41,0x8C])
        let post = Data([0xA8,0xF8,0x1A,0x57,0x4E,0x22,0x8A,0xB7])
        let shared: Data
        let pub: Data
        if bits <= 256 {
            let foreign = try P256.KeyAgreement.PublicKey(x963Representation: ext)
            let own = P256.KeyAgreement.PrivateKey()
            let secret = try own.sharedSecretFromKeyAgreement(with: foreign)
            shared = secret.withUnsafeBytes { Data($0) }
            pub = Data(own.publicKey.x963Representation.dropFirst())
            publicKeyType = 0
        } else if bits <= 384 {
            let foreign = try P384.KeyAgreement.PublicKey(x963Representation: ext)
            let own = P384.KeyAgreement.PrivateKey()
            let secret = try own.sharedSecretFromKeyAgreement(with: foreign)
            shared = secret.withUnsafeBytes { Data($0) }
            pub = Data(own.publicKey.x963Representation.dropFirst())
            publicKeyType = 1
        } else {
            throw SGError.message("Curva EC do Xbox ainda não suportada no iPhone.")
        }
        publicKeyBytes = pub
        let digest = Data(SHA512.hash(data: pre + shared + post))
        encryptKey = digest.subdata(in: 0..<16)
        ivKey = digest.subdata(in: 16..<32)
        hashKey = digest.subdata(in: 32..<64)
    }

    func hmac(_ data: Data) -> Data {
        var result = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        hashKey.withUnsafeBytes { key in
            data.withUnsafeBytes { bytes in
                CCHmac(CCHmacAlgorithm(kCCHmacAlgSHA256), key.baseAddress, hashKey.count, bytes.baseAddress, data.count, &result)
            }
        }
        return Data(result)
    }

    func messageIV(_ first16: Data.SubSequence) throws -> Data { try aesCBC(key: ivKey, iv: Data(count: 16), data: Data(first16), operation: CCOperation(kCCEncrypt)) }
    func encrypt(iv: Data, data: Data) throws -> Data { try aesCBC(key: encryptKey, iv: iv, data: data, operation: CCOperation(kCCEncrypt)) }
    func decrypt(iv: Data, data: Data) throws -> Data { try aesCBC(key: encryptKey, iv: iv, data: data, operation: CCOperation(kCCDecrypt)) }

    private func aesCBC(key: Data, iv: Data, data: Data, operation: CCOperation) throws -> Data {
        var out = [UInt8](repeating: 0, count: data.count + kCCBlockSizeAES128)
        var moved = 0
        let status = key.withUnsafeBytes { k in iv.withUnsafeBytes { i in data.withUnsafeBytes { d in
            CCCrypt(operation, CCAlgorithm(kCCAlgorithmAES), CCOptions(0), k.baseAddress, key.count, i.baseAddress, d.baseAddress, data.count, &out, out.count, &moved)
        }}}
        guard status == kCCSuccess else { throw SGError.message("Falha criptográfica (\(status)).") }
        return Data(out.prefix(moved))
    }
}

private extension Data {
    mutating func appendBE<T: FixedWidthInteger>(_ value: T) { var v = value.bigEndian; Swift.withUnsafeBytes(of: &v) { append(contentsOf: $0) } }
    mutating func appendUUID(_ uuid: UUID) { var u = uuid.uuid; Swift.withUnsafeBytes(of: &u) { append(contentsOf: $0) } }
    mutating func appendSGString(_ value: String) { let b = Data(value.utf8); appendBE(UInt16(b.count)); append(b); append(0) }
    func readBEUInt16(at o: Int) -> UInt16 { (UInt16(self[o]) << 8) | UInt16(self[o+1]) }
    func readBEUInt32(at o: Int) -> UInt32 { (UInt32(self[o]) << 24) | (UInt32(self[o+1]) << 16) | (UInt32(self[o+2]) << 8) | UInt32(self[o+3]) }
    func readBEUInt64(at o: Int) -> UInt64 { var r: UInt64 = 0; for i in 0..<8 { r = (r << 8) | UInt64(self[o+i]) }; return r }
    func prefixData(_ n: Int) -> Data { Data(prefix(n)) }
    func pad16() -> Data { let rem = count % 16; if rem == 0 { return self }; let pad = 16 - rem; return self + Data(repeating: UInt8(pad), count: pad) }
    func readSGString(offset: inout Int) -> String? {
        guard offset + 2 <= count else { return nil }
        let len = Int(readBEUInt16(at: offset)); offset += 2
        guard offset + len <= count else { return nil }
        let s = String(data: subdata(in: offset..<(offset+len)), encoding: .utf8) ?? ""; offset += len
        if offset < count { offset += 1 }
        return s
    }
}
