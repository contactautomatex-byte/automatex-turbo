package com.jonathan.xboxremote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal native implementation of the Xbox SmartGlass core protocol.
 * It discovers an Xbox on UDP/5050, establishes an anonymous local session,
 * opens the SystemInput channel and sends gamepad-style navigation events.
 */
public final class SmartGlassClient {
    public interface Listener {
        void onStatus(String message);
        void onConnected(String consoleName, String ip);
        void onConnectionFailed(String reason);
        void onDisconnected(String reason);
    }

    public static final int BTN_NEXUS = 0x0002;
    public static final int BTN_MENU  = 0x0004;
    public static final int BTN_VIEW  = 0x0008;
    public static final int BTN_A     = 0x0010;
    public static final int BTN_B     = 0x0020;
    public static final int BTN_X     = 0x0040;
    public static final int BTN_Y     = 0x0080;
    public static final int BTN_UP    = 0x0100;
    public static final int BTN_DOWN  = 0x0200;
    public static final int BTN_LEFT  = 0x0400;
    public static final int BTN_RIGHT = 0x0800;

    private static final int PORT = 5050;
    private static final int PKT_CONNECT_REQUEST = 0xCC00;
    private static final int PKT_CONNECT_RESPONSE = 0xCC01;
    private static final int PKT_DISCOVERY_REQUEST = 0xDD00;
    private static final int PKT_DISCOVERY_RESPONSE = 0xDD01;
    private static final int PKT_MESSAGE = 0xD00D;

    private static final int MSG_ACK = 0x001;
    private static final int MSG_LOCAL_JOIN = 0x003;
    private static final int MSG_CONSOLE_STATUS = 0x01E;
    private static final int MSG_START_CHANNEL_REQUEST = 0x026;
    private static final int MSG_START_CHANNEL_RESPONSE = 0x027;
    private static final int MSG_DISCONNECT = 0x02A;
    private static final int MSG_GAMEPAD = 0xF0A;

    private static final byte[] SYSTEM_INPUT_UUID = uuidBytes("fa20b8ca-66fb-46e0-adb6-0b978a59d35f");

    private final Listener listener;
    private final ExecutorService connectionExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    private final Object sendLock = new Object();
    private final AtomicInteger sequence = new AtomicInteger(0);
    private final SecureRandom random = new SecureRandom();

    private volatile DatagramSocket socket;
    private volatile boolean connected;
    private volatile boolean shuttingDown;
    private volatile String consoleIp;
    private volatile String consoleName = "Xbox";
    private volatile CryptoContext crypto;
    private volatile long sourceParticipantId;
    private volatile long systemInputChannel;
    private volatile long lowWatermark;
    private Thread receiveThread;

    public SmartGlassClient(Listener listener) {
        this.listener = listener;
        heartbeat.scheduleAtFixedRate(() -> {
            if (connected && socket != null && !socket.isClosed()) {
                try { sendAck(new int[0], new int[0], true); } catch (Exception ignored) {}
            }
        }, 3, 3, TimeUnit.SECONDS);
    }

    public boolean isConnected() {
        return connected && systemInputChannel != 0;
    }

    public void connect(String manualIp) {
        connectionExecutor.execute(() -> {
            try {
                disconnectInternal(false, null);
                listener.onStatus("Procurando Xbox na rede…");
                setupSocket();
                DiscoveryInfo info = discover(manualIp == null ? "" : manualIp.trim());
                if (info == null) {
                    throw new FriendlyException("Xbox não encontrado. Confirme se celular e console estão na mesma rede Wi‑Fi. Se souber o IP do Xbox, informe no campo e tente novamente.");
                }

                consoleIp = info.ip;
                consoleName = info.name == null || info.name.isEmpty() ? "Xbox" : info.name;
                listener.onStatus("Xbox encontrado: " + consoleName + " • conectando…");

                crypto = CryptoContext.fromCertificate(info.certificate);
                ConnectResult result = connectAnonymous();
                if (result.result != 0) throw new FriendlyException(connectionError(result.result));
                sourceParticipantId = result.participantId & 0xffffffffL;

                listener.onStatus("Sessão criada. Ativando controle…");
                int localJoinSeq = sendLocalJoin();
                if (!waitForAck(localJoinSeq, 5000)) {
                    throw new FriendlyException("O Xbox aceitou a sessão, mas não confirmou o controle local. Tente novamente e confira as opções de conexão do app Xbox no console.");
                }

                int channelReqSeq = sendStartSystemInputChannel();
                waitForAck(channelReqSeq, 5000);
                if (systemInputChannel == 0 && !waitForSystemInputChannel(5000)) {
                    throw new FriendlyException("Conectado ao Xbox, mas o canal de controle não foi liberado. Verifique em Configurações do Xbox as opções de dispositivos/conexões e tente novamente.");
                }

                connected = true;
                startReceiver();
                listener.onConnected(consoleName, consoleIp);
            } catch (FriendlyException e) {
                disconnectInternal(false, null);
                listener.onConnectionFailed(e.getMessage());
            } catch (Exception e) {
                disconnectInternal(false, null);
                String detail = e.getMessage();
                if (detail == null || detail.trim().isEmpty()) detail = e.getClass().getSimpleName();
                listener.onConnectionFailed("Não foi possível conectar ao Xbox: " + detail);
            }
        });
    }

    public void sendButton(int buttonMask) {
        if (!isConnected()) return;
        commandExecutor.execute(() -> {
            try {
                sendGamepad(buttonMask);
                Thread.sleep(90);
                sendGamepad(0);
            } catch (Exception e) {
                if (connected) listener.onStatus("Falha ao enviar comando: " + safeMessage(e));
            }
        });
    }

    public void shutdown() {
        shuttingDown = true;
        disconnectInternal(false, null);
        heartbeat.shutdownNow();
        connectionExecutor.shutdownNow();
        commandExecutor.shutdownNow();
    }

    private void setupSocket() throws SocketException {
        DatagramSocket s = new DatagramSocket();
        s.setBroadcast(true);
        s.setReuseAddress(true);
        s.setSoTimeout(450);
        socket = s;
        sequence.set(0);
        lowWatermark = 0;
        sourceParticipantId = 0;
        systemInputChannel = 0;
        connected = false;
    }

    private DiscoveryInfo discover(String manualIp) throws Exception {
        byte[] request = discoveryPacket();
        long end = System.currentTimeMillis() + 4500;
        DiscoveryInfo first = null;

        while (System.currentTimeMillis() < end) {
            sendRaw(request, "255.255.255.255");
            sendRaw(request, "239.255.255.250");
            if (!manualIp.isEmpty()) sendRaw(request, manualIp);

            long roundEnd = Math.min(end, System.currentTimeMillis() + 650);
            while (System.currentTimeMillis() < roundEnd) {
                try {
                    DatagramPacket p = receiveDatagram();
                    DiscoveryInfo info = parseDiscovery(p);
                    if (info == null) continue;
                    if (!manualIp.isEmpty() && manualIp.equals(info.ip)) return info;
                    if (first == null) first = info;
                } catch (SocketTimeoutException ignored) { break; }
            }
            if (first != null && manualIp.isEmpty()) return first;
        }
        return first;
    }

    private ConnectResult connectAnonymous() throws Exception {
        byte[] iv = new byte[16];
        random.nextBytes(iv);

        ByteArrayOutputStream protectedOut = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(protectedOut);
        writeSGString(p, "");
        writeSGString(p, "");
        p.writeInt(0);
        p.writeInt(0);
        p.writeInt(1);
        p.flush();
        byte[] protectedPlain = protectedOut.toByteArray();

        ByteArrayOutputStream unprotectedOut = new ByteArrayOutputStream();
        DataOutputStream u = new DataOutputStream(unprotectedOut);
        UUID uuid = UUID.randomUUID();
        u.writeLong(uuid.getMostSignificantBits());
        u.writeLong(uuid.getLeastSignificantBits());
        u.writeShort(crypto.publicKeyType);
        u.write(crypto.publicKeyBytes);
        u.write(iv);
        u.flush();
        byte[] unprotected = unprotectedOut.toByteArray();

        byte[] encrypted = crypto.encrypt(iv, pad16(protectedPlain));
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        header.putShort((short) PKT_CONNECT_REQUEST);
        header.putShort((short) unprotected.length);
        header.putShort((short) protectedPlain.length);
        header.putShort((short) 2);

        byte[] withoutHash = concat(header.array(), unprotected, encrypted);
        byte[] packet = concat(withoutHash, crypto.hmac(withoutHash));
        sendRaw(packet, consoleIp);

        long deadline = System.currentTimeMillis() + 6000;
        boolean resent = false;
        while (System.currentTimeMillis() < deadline) {
            try {
                DatagramPacket dp = receiveDatagram();
                if (!dp.getAddress().getHostAddress().equals(consoleIp)) continue;
                int type = u16(dp.getData(), dp.getOffset());
                if (type == PKT_CONNECT_RESPONSE) return parseConnectResponse(copyPacket(dp));
            } catch (SocketTimeoutException ignored) {
                if (!resent) {
                    resent = true;
                    sendRaw(packet, consoleIp);
                }
            }
        }
        throw new FriendlyException("O Xbox foi localizado, mas não respondeu ao pedido de conexão.");
    }

    private ConnectResult parseConnectResponse(byte[] data) throws Exception {
        if (data.length < 8 + 16 + 16 + 32) throw new Exception("Resposta de conexão incompleta");
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int type = b.getShort() & 0xffff;
        if (type != PKT_CONNECT_RESPONSE) throw new Exception("Resposta inesperada");
        int unprotectedLen = b.getShort() & 0xffff;
        int protectedLen = b.getShort() & 0xffff;
        b.getShort();
        if (unprotectedLen < 16 || 8 + unprotectedLen + 32 > data.length) throw new Exception("Resposta inválida");

        byte[] signed = Arrays.copyOf(data, data.length - 32);
        byte[] hash = Arrays.copyOfRange(data, data.length - 32, data.length);
        if (!MessageDigest.isEqual(hash, crypto.hmac(signed))) throw new Exception("Assinatura da resposta inválida");

        byte[] iv = Arrays.copyOfRange(data, 8, 24);
        int cipherStart = 8 + unprotectedLen;
        byte[] ciphertext = Arrays.copyOfRange(data, cipherStart, data.length - 32);
        byte[] plainPadded = crypto.decrypt(iv, ciphertext);
        if (protectedLen > plainPadded.length || protectedLen < 8) throw new Exception("Payload de conexão inválido");
        ByteBuffer pp = ByteBuffer.wrap(plainPadded, 0, protectedLen).order(ByteOrder.BIG_ENDIAN);
        ConnectResult r = new ConnectResult();
        r.result = pp.getShort() & 0xffff;
        r.pairingState = pp.getShort() & 0xffff;
        r.participantId = pp.getInt();
        return r;
    }

    private int sendLocalJoin() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeShort(4);
        d.writeShort(1080);
        d.writeShort(1920);
        d.writeShort(96);
        d.writeShort(96);
        d.writeLong(-1L);
        d.writeInt(39);
        d.writeInt(6);
        d.writeInt(2);
        writeSGString(d, "SmartGlass-PC");
        d.flush();
        PacketWithSeq packet = buildMessage(MSG_LOCAL_JOIN, true, 0, out.toByteArray());
        sendRaw(packet.bytes, consoleIp);
        return packet.seq;
    }

    private int sendStartSystemInputChannel() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(1);
        d.writeInt(0);
        d.write(SYSTEM_INPUT_UUID);
        d.writeInt(0);
        d.flush();
        PacketWithSeq packet = buildMessage(MSG_START_CHANNEL_REQUEST, true, 0, out.toByteArray());
        sendRaw(packet.bytes, consoleIp);
        return packet.seq;
    }

    private void sendGamepad(int buttons) throws Exception {
        if (systemInputChannel == 0) return;
        ByteBuffer p = ByteBuffer.allocate(8 + 2 + 6 * 4).order(ByteOrder.BIG_ENDIAN);
        p.putLong(System.currentTimeMillis() / 1000L);
        p.putShort((short) buttons);
        for (int i = 0; i < 6; i++) p.putFloat(0f);
        PacketWithSeq packet = buildMessage(MSG_GAMEPAD, false, systemInputChannel, p.array());
        sendRaw(packet.bytes, consoleIp);
    }

    private void sendAck(int[] processed, int[] rejected, boolean needAck) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt((int) lowWatermark);
        d.writeInt(processed.length);
        for (int n : processed) d.writeInt(n);
        d.writeInt(rejected.length);
        for (int n : rejected) d.writeInt(n);
        d.flush();
        PacketWithSeq packet = buildMessage(MSG_ACK, needAck, 0, out.toByteArray());
        sendRaw(packet.bytes, consoleIp);
    }

    private PacketWithSeq buildMessage(int msgType, boolean needAck, long channelId, byte[] plain) throws Exception {
        int seq = sequence.incrementAndGet();
        int flags = 0x8000 | (needAck ? 0x2000 : 0) | (msgType & 0x0fff);
        ByteBuffer h = ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN);
        h.putShort((short) PKT_MESSAGE);
        h.putShort((short) plain.length);
        h.putInt(seq);
        h.putInt(0);
        h.putInt((int) sourceParticipantId);
        h.putShort((short) flags);
        h.putLong(channelId);
        byte[] header = h.array();
        byte[] dynamicIv = crypto.messageIv(Arrays.copyOfRange(header, 0, 16));
        byte[] encrypted = crypto.encrypt(dynamicIv, pad16(plain));
        byte[] withoutHash = concat(header, encrypted);
        return new PacketWithSeq(seq, concat(withoutHash, crypto.hmac(withoutHash)));
    }

    private boolean waitForAck(int wantedSeq, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Set<Integer> processed = new HashSet<>();
        while (System.currentTimeMillis() < deadline) {
            try {
                DecodedMessage m = receiveAndHandle();
                if (m == null) continue;
                if (m.type == MSG_ACK) {
                    parseAck(m.payload, processed);
                    if (processed.contains(wantedSeq)) return true;
                }
            } catch (SocketTimeoutException ignored) {}
        }
        return false;
    }

    private boolean waitForSystemInputChannel(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (systemInputChannel != 0) return true;
            try { receiveAndHandle(); } catch (SocketTimeoutException ignored) {}
        }
        return systemInputChannel != 0;
    }

    private void startReceiver() {
        receiveThread = new Thread(() -> {
            while (connected && socket != null && !socket.isClosed() && !shuttingDown) {
                try {
                    receiveAndHandle();
                } catch (SocketTimeoutException ignored) {
                } catch (SocketException e) {
                    break;
                } catch (Exception e) {
                    if (connected) listener.onStatus("Conectado • aviso de rede: " + safeMessage(e));
                }
            }
            if (connected && !shuttingDown) {
                connected = false;
                listener.onDisconnected("Conexão com o Xbox encerrada.");
            }
        }, "XboxSmartGlassReceiver");
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    private DecodedMessage receiveAndHandle() throws Exception {
        DatagramPacket packet = receiveDatagram();
        if (consoleIp != null && !consoleIp.equals(packet.getAddress().getHostAddress())) return null;
        byte[] data = copyPacket(packet);
        if (data.length < 2 || u16(data, 0) != PKT_MESSAGE) return null;
        DecodedMessage m = decodeMessage(data);
        if (m == null) return null;

        if ((m.flags & 0x2000) != 0) {
            lowWatermark = Math.max(lowWatermark, m.sequence & 0xffffffffL);
            sendAck(new int[]{m.sequence}, new int[0], false);
        }

        if (m.type == MSG_START_CHANNEL_RESPONSE && m.payload.length >= 16) {
            ByteBuffer p = ByteBuffer.wrap(m.payload).order(ByteOrder.BIG_ENDIAN);
            int requestId = p.getInt();
            long channel = p.getLong();
            long result = p.getInt() & 0xffffffffL;
            if (requestId == 1 && result == 0) systemInputChannel = channel;
        } else if (m.type == MSG_DISCONNECT) {
            connected = false;
        } else if (m.type == MSG_CONSOLE_STATUS) {
            // Receiving this confirms the local join succeeded.
        }
        return m;
    }

    private DecodedMessage decodeMessage(byte[] data) throws Exception {
        if (data.length < 26 + 16 + 32) return null;
        byte[] signed = Arrays.copyOf(data, data.length - 32);
        byte[] suppliedHash = Arrays.copyOfRange(data, data.length - 32, data.length);
        if (!MessageDigest.isEqual(suppliedHash, crypto.hmac(signed))) return null;

        ByteBuffer h = ByteBuffer.wrap(data, 0, 26).order(ByteOrder.BIG_ENDIAN);
        int pktType = h.getShort() & 0xffff;
        if (pktType != PKT_MESSAGE) return null;
        int plainLen = h.getShort() & 0xffff;
        int seq = h.getInt();
        h.getInt();
        h.getInt();
        int flags = h.getShort() & 0xffff;
        long channel = h.getLong();
        int cipherLen = data.length - 26 - 32;
        if (cipherLen <= 0 || cipherLen % 16 != 0) return null;
        byte[] ciphertext = Arrays.copyOfRange(data, 26, 26 + cipherLen);
        byte[] iv = crypto.messageIv(Arrays.copyOfRange(data, 0, 16));
        byte[] padded = crypto.decrypt(iv, ciphertext);
        if (plainLen > padded.length) return null;
        byte[] payload = Arrays.copyOf(padded, plainLen);

        DecodedMessage m = new DecodedMessage();
        m.sequence = seq;
        m.flags = flags;
        m.type = flags & 0x0fff;
        m.channelId = channel;
        m.payload = payload;
        return m;
    }

    private void parseAck(byte[] payload, Set<Integer> processedOut) {
        try {
            ByteBuffer p = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
            if (p.remaining() < 8) return;
            p.getInt();
            long processedCount = p.getInt() & 0xffffffffL;
            if (processedCount > 1024) return;
            for (int i = 0; i < processedCount && p.remaining() >= 4; i++) processedOut.add(p.getInt());
            if (p.remaining() < 4) return;
            long rejectedCount = p.getInt() & 0xffffffffL;
            for (int i = 0; i < rejectedCount && p.remaining() >= 4; i++) p.getInt();
        } catch (Exception ignored) {}
    }

    private DatagramPacket receiveDatagram() throws Exception {
        DatagramSocket s = socket;
        if (s == null || s.isClosed()) throw new SocketException("socket fechado");
        byte[] buf = new byte[4096];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        s.receive(p);
        return p;
    }

    private void sendRaw(byte[] data, String ip) throws Exception {
        DatagramSocket s = socket;
        if (s == null || s.isClosed()) throw new SocketException("socket fechado");
        InetAddress target = InetAddress.getByName(ip);
        DatagramPacket p = new DatagramPacket(data, data.length, target, PORT);
        synchronized (sendLock) { s.send(p); }
    }

    private byte[] discoveryPacket() {
        ByteBuffer b = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        b.putShort((short) PKT_DISCOVERY_REQUEST);
        b.putShort((short) 10);
        b.putShort((short) 0);
        b.putInt(0);
        b.putShort((short) 8);
        b.putShort((short) 0);
        b.putShort((short) 2);
        return b.array();
    }

    private DiscoveryInfo parseDiscovery(DatagramPacket packet) {
        try {
            byte[] data = copyPacket(packet);
            if (data.length < 16 || u16(data, 0) != PKT_DISCOVERY_RESPONSE) return null;
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            b.getShort();
            int payloadLen = b.getShort() & 0xffff;
            b.getShort();
            if (payloadLen > data.length - 6) return null;
            long flags = b.getInt() & 0xffffffffL;
            b.getShort();
            String name = readSGString(b);
            String uuid = readSGString(b);
            if (b.remaining() < 6) return null;
            b.getInt();
            int certLen = b.getShort() & 0xffff;
            if (certLen <= 0 || certLen > b.remaining()) return null;
            byte[] cert = new byte[certLen];
            b.get(cert);
            DiscoveryInfo info = new DiscoveryInfo();
            info.ip = packet.getAddress().getHostAddress();
            info.name = name;
            info.uuid = uuid;
            info.flags = flags;
            info.certificate = cert;
            return info;
        } catch (Exception ignored) { return null; }
    }

    private void disconnectInternal(boolean notify, String reason) {
        connected = false;
        systemInputChannel = 0;
        DatagramSocket s = socket;
        socket = null;
        if (s != null) s.close();
        if (receiveThread != null) {
            receiveThread.interrupt();
            receiveThread = null;
        }
        if (notify && !shuttingDown) listener.onDisconnected(reason == null ? "Desconectado" : reason);
    }

    private static String connectionError(int code) {
        switch (code) {
            case 1: return "O Xbox pediu uma nova tentativa de login. Tente conectar novamente.";
            case 2: return "O Xbox recusou a conexão por um erro interno.";
            case 3: return "O Xbox está com conexões anônimas desativadas. No console, habilite a conexão do app/SmartGlass para dispositivos da rede e tente novamente.";
            case 4: return "O Xbox atingiu o limite de dispositivos conectados. Desconecte outro dispositivo e tente novamente.";
            case 5: return "O controle remoto/SmartGlass está desativado nas configurações do Xbox.";
            case 6: return "O Xbox recusou a autenticação local.";
            case 7: return "Falha de entrada na conta do Xbox.";
            case 8: return "Tempo de entrada na conta do Xbox esgotado.";
            case 9: return "O Xbox exige uma conta autenticada para aceitar este dispositivo. Altere a preferência de conexão do app Xbox no console para permitir dispositivos da rede.";
            default: return "O Xbox recusou a conexão (código " + code + ").";
        }
    }

    private static void writeSGString(DataOutputStream d, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        d.writeShort(bytes.length);
        d.write(bytes);
        d.writeByte(0);
    }

    private static String readSGString(ByteBuffer b) {
        int len = b.getShort() & 0xffff;
        if (len > b.remaining()) throw new IllegalArgumentException("string inválida");
        byte[] value = new byte[len];
        b.get(value);
        if (b.remaining() > 0) b.get();
        return new String(value, StandardCharsets.UTF_8);
    }

    private static byte[] pad16(byte[] input) {
        int remainder = input.length % 16;
        if (remainder == 0) return input;
        int pad = 16 - remainder;
        byte[] out = Arrays.copyOf(input, input.length + pad);
        Arrays.fill(out, input.length, out.length, (byte) pad);
        return out;
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static byte[] copyPacket(DatagramPacket p) {
        return Arrays.copyOfRange(p.getData(), p.getOffset(), p.getOffset() + p.getLength());
    }

    private static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] a : arrays) length += a.length;
        byte[] out = new byte[length];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, pos, a.length);
            pos += a.length;
        }
        return out;
    }

    private static byte[] uuidBytes(String value) {
        UUID u = UUID.fromString(value);
        ByteBuffer b = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        b.putLong(u.getMostSignificantBits());
        b.putLong(u.getLeastSignificantBits());
        return b.array();
    }

    private static byte[] unsignedFixed(java.math.BigInteger value, int size) {
        byte[] src = value.toByteArray();
        if (src.length == size) return src;
        byte[] out = new byte[size];
        if (src.length > size) System.arraycopy(src, src.length - size, out, 0, size);
        else System.arraycopy(src, 0, out, size - src.length, src.length);
        return out;
    }

    private static String safeMessage(Exception e) {
        String s = e.getMessage();
        return s == null || s.isEmpty() ? e.getClass().getSimpleName() : s;
    }

    private static final class CryptoContext {
        final byte[] encryptKey;
        final byte[] ivKey;
        final byte[] hashKey;
        final int publicKeyType;
        final byte[] publicKeyBytes;

        private CryptoContext(byte[] encryptKey, byte[] ivKey, byte[] hashKey, int publicKeyType, byte[] publicKeyBytes) {
            this.encryptKey = encryptKey;
            this.ivKey = ivKey;
            this.hashKey = hashKey;
            this.publicKeyType = publicKeyType;
            this.publicKeyBytes = publicKeyBytes;
        }

        static CryptoContext fromCertificate(byte[] derCertificate) throws Exception {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(derCertificate));
            if (!(cert.getPublicKey() instanceof ECPublicKey)) throw new Exception("Certificado do Xbox não usa chave EC");
            ECPublicKey foreign = (ECPublicKey) cert.getPublicKey();
            int fieldBits = foreign.getParams().getCurve().getField().getFieldSize();
            String curveName;
            int publicType;
            int coordSize;
            if (fieldBits <= 256) { curveName = "secp256r1"; publicType = 0; coordSize = 32; }
            else if (fieldBits <= 384) { curveName = "secp384r1"; publicType = 1; coordSize = 48; }
            else { curveName = "secp521r1"; publicType = 2; coordSize = 66; }

            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec(curveName));
            KeyPair pair = kpg.generateKeyPair();
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(pair.getPrivate());
            ka.doPhase(foreign, true);
            byte[] shared = ka.generateSecret();
            if (shared.length != coordSize) {
                byte[] fixed = new byte[coordSize];
                System.arraycopy(shared, Math.max(0, shared.length - coordSize), fixed, Math.max(0, coordSize - shared.length), Math.min(shared.length, coordSize));
                shared = fixed;
            }

            byte[] pre = hex("D637F1AAE2F0418C");
            byte[] post = hex("A8F81A574E228AB7");
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            byte[] expanded = sha512.digest(concat(pre, shared, post));

            ECPublicKey own = (ECPublicKey) pair.getPublic();
            ECPoint w = own.getW();
            byte[] pub = concat(unsignedFixed(w.getAffineX(), coordSize), unsignedFixed(w.getAffineY(), coordSize));
            return new CryptoContext(
                    Arrays.copyOfRange(expanded, 0, 16),
                    Arrays.copyOfRange(expanded, 16, 32),
                    Arrays.copyOfRange(expanded, 32, 64),
                    publicType,
                    pub
            );
        }

        byte[] encrypt(byte[] iv, byte[] plain) throws Exception { return aes(encryptKey, iv, plain, Cipher.ENCRYPT_MODE); }
        byte[] decrypt(byte[] iv, byte[] cipher) throws Exception { return aes(encryptKey, iv, cipher, Cipher.DECRYPT_MODE); }
        byte[] messageIv(byte[] first16HeaderBytes) throws Exception { return aes(ivKey, new byte[16], first16HeaderBytes, Cipher.ENCRYPT_MODE); }

        byte[] hmac(byte[] data) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hashKey, "HmacSHA256"));
            return mac.doFinal(data);
        }

        private static byte[] aes(byte[] key, byte[] iv, byte[] data, int mode) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        }

        private static byte[] hex(String s) {
            byte[] out = new byte[s.length() / 2];
            for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
            return out;
        }
    }

    private static final class DiscoveryInfo {
        String ip;
        String name;
        String uuid;
        long flags;
        byte[] certificate;
    }

    private static final class ConnectResult {
        int result;
        int pairingState;
        int participantId;
    }

    private static final class DecodedMessage {
        int sequence;
        int flags;
        int type;
        long channelId;
        byte[] payload;
    }

    private static final class PacketWithSeq {
        final int seq;
        final byte[] bytes;
        PacketWithSeq(int seq, byte[] bytes) { this.seq = seq; this.bytes = bytes; }
    }

    private static final class FriendlyException extends Exception {
        FriendlyException(String message) { super(message); }
    }
}
