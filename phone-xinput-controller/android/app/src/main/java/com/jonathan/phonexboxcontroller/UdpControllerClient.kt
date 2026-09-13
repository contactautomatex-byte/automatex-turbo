package com.jonathan.phonexboxcontroller

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class UdpControllerClient(
    private val hostProvider: () -> String,
    private val portProvider: () -> Int,
    private val stateProvider: () -> GamepadState,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                while (running.get()) {
                    try {
                        val host = hostProvider().trim()
                        val port = portProvider()
                        if (host.isNotBlank() && port in 1..65535) {
                            val bytes = stateProvider().toJson().toByteArray(Charsets.UTF_8)
                            val packet = DatagramPacket(
                                bytes,
                                bytes.size,
                                InetAddress.getByName(host),
                                port,
                            )
                            socket.send(packet)
                        }
                    } catch (_: Throwable) {
                    }
                    SystemClock.sleep(8)
                }
            }
        }.apply {
            name = "XboxPhoneControllerUdp"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }
}
