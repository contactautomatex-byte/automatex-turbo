package com.jonathan.phonexboxcontroller

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-latency sender. Input events wake the sender immediately; a 50 ms heartbeat
 * keeps the receiver alive and guarantees that a release eventually reaches the PC.
 */
class UdpControllerClient(
    private val hostProvider: () -> String,
    private val portProvider: () -> Int,
    private val stateProvider: () -> GamepadState,
) {
    private val running = AtomicBoolean(false)
    private val sequence = AtomicInteger(0)
    private val signal = Object()
    @Volatile private var dirty = true
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread {
            DatagramSocket().use { socket ->
                socket.sendBufferSize = 1 shl 16
                try { socket.trafficClass = 0x10 } catch (_: Throwable) { }

                var cachedHost = ""
                var cachedAddress: InetAddress? = null
                var cachedPort = 0

                while (running.get()) {
                    synchronized(signal) {
                        if (!dirty && running.get()) {
                            try { signal.wait(50L) } catch (_: InterruptedException) { }
                        }
                        dirty = false
                    }
                    if (!running.get()) break

                    try {
                        val host = hostProvider().trim()
                        val port = portProvider()
                        if (host.isBlank() || port !in 1..65535) continue

                        if (host != cachedHost || cachedAddress == null) {
                            cachedAddress = InetAddress.getByName(host)
                            cachedHost = host
                        }
                        cachedPort = port

                        val bytes = stateProvider().toBinary(sequence.incrementAndGet())
                        socket.send(DatagramPacket(bytes, bytes.size, cachedAddress, cachedPort))
                    } catch (_: Throwable) {
                        // Receiver watchdog neutralizes the virtual pad if connectivity is lost.
                    }
                }
            }
        }.apply {
            name = "XboxPhoneControllerUdpV2"
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    /** Call after every meaningful touch/sensor state change. */
    fun sendNow() {
        dirty = true
        synchronized(signal) { signal.notifyAll() }
    }

    fun stop() {
        running.set(false)
        synchronized(signal) { signal.notifyAll() }
        thread?.interrupt()
        thread = null
    }
}
