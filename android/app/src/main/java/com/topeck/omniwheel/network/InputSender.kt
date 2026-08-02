package com.topeck.omniwheel.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.*
import kotlin.concurrent.thread

/**
 * Optimized input sender with buffer reuse and delta-based sending.
 *
 * Key optimizations:
 * - Pre-allocated send buffer (no per-packet allocation)
 * - Delta detection: skips sending if state unchanged
 * - Configurable send rate
 * - Connection handshake with ACK
 * - Socket error recovery
 * - WiFi lock for reliable UDP
 */
class InputSender(private val context: Context) {
    companion object {
        private const val TAG = "InputSender"
        private const val PROBE_COUNT = 5
        private const val PROBE_INTERVAL_MS = 150L
        private const val ACK_TIMEOUT_MS = 3000L
        // Max packet size: header(8) + payload(13) + crc(2) = 23
        private const val MAX_PACKET_SIZE = 32
    }

    private var udpSocket: DatagramSocket? = null
    private var running = false
    private var sendThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sendPacket: DatagramPacket? = null // reused every send

    var targetIp: String = ""
    var targetPort: Int = Protocol.INPUT_PORT
    var sendRateHz: Int = 240

    // Current input state (written by UI/gyro thread, read by send thread)
    @Volatile var steering: Short = 0
    @Volatile var throttle: Byte = 0
    @Volatile var brake: Byte = 0
    @Volatile var clutch: Byte = 0
    @Volatile var gyroX: Short = 0
    @Volatile var gyroY: Short = 0
    @Volatile var gyroZ: Short = 0
    @Volatile var activeButtons: Set<Int> = emptySet()
    @Volatile var gyroActive: Boolean = false

    // Connection state
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }
    @Volatile var state: State = State.DISCONNECTED; private set

    var onLog: ((String) -> Unit)? = null
    var onStateChanged: ((State) -> Unit)? = null

    private var _sendCount = 0
    private var _skipCount = 0
    private var _errorCount = 0
    val sendCount: Int get() = _sendCount
    val skipCount: Int get() = _skipCount
    val errorCount: Int get() = _errorCount

    private fun setState(s: State) {
        state = s
        onStateChanged?.invoke(s)
    }

    fun connect(ip: String, onReady: () -> Unit, onError: ((String) -> Unit)? = null) {
        targetIp = ip
        stopInternal()

        try {
            // WiFi lock
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "omniwheel:input")?.also {
                    it.setReferenceCounted(false)
                    it.acquire()
                }
            } catch (e: Exception) {
                Log.w(TAG, "WiFi lock failed: ${e.message}")
            }

            udpSocket = DatagramSocket().also { sock ->
                sock.sendBufferSize = 64 * 1024
                sock.soTimeout = 0
            }

            running = true
            _sendCount = 0
            _skipCount = 0
            _errorCount = 0
            setState(State.CONNECTING)
            onLog?.invoke("Connecting to $targetIp...")

            thread(name = "Connector") {
                try {
                    // Phase 1: Send CONNECT probes (use v1 protocol for discovery compat)
                    repeat(PROBE_COUNT) { i ->
                        if (!running) return@thread
                        val probe = Protocol.buildPacket(Protocol.TYPE_CONNECT)
                        udpSocket?.send(DatagramPacket(probe, probe.size, InetSocketAddress(targetIp, targetPort)))
                        Thread.sleep(PROBE_INTERVAL_MS)
                    }

                    // Phase 2: Listen for CONNECT_ACK
                    udpSocket?.soTimeout = 2000
                    var gotAck = false
                    val deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS

                    while (running && !gotAck && System.currentTimeMillis() < deadline) {
                        try {
                            val buf = ByteArray(256)
                            val pkt = DatagramPacket(buf, buf.size)
                            udpSocket?.receive(pkt)
                            val data = pkt.data.copyOfRange(0, pkt.length)
                            val header = Protocol.parseHeader(data)
                            if (header != null && header.type == Protocol.TYPE_CONNECT_ACK) {
                                gotAck = true
                                onLog?.invoke("PC responded (ACK received)")
                            }
                        } catch (_: SocketTimeoutException) { /* normal */ }
                        catch (e: SocketException) {
                            if (running) onLog?.invoke("ACK wait error: ${e.message}")
                            break
                        }
                    }

                    udpSocket?.soTimeout = 0

                    if (!gotAck) {
                        onLog?.invoke("No ACK from PC — check firewall on port $targetPort")
                    }

                    setState(State.CONNECTED)
                    onLog?.invoke("Sending input at ${sendRateHz}Hz (delta-optimized)")

                    // Pre-allocate reusable send packet
                    val addr = InetSocketAddress(targetIp, targetPort)
                    sendPacket = DatagramPacket(ByteArray(MAX_PACKET_SIZE), MAX_PACKET_SIZE, addr)

                    startSendLoop()
                    startHeartbeat()
                    onReady()

                } catch (_: InterruptedException) { /* cancelled */ }
                catch (e: Exception) {
                    val msg = "Connection failed: ${e.message}"
                    onLog?.invoke(msg)
                    Log.e(TAG, msg, e)
                    setState(State.DISCONNECTED)
                    onError?.invoke(e.message ?: "Unknown error")
                }
            }

        } catch (e: Exception) {
            val msg = "Socket creation failed: ${e.message}"
            onLog?.invoke(msg)
            setState(State.DISCONNECTED)
            onError?.invoke(msg)
            cleanup()
        }
    }

    private fun startSendLoop() {
        sendThread = thread(name = "InputSender") {
            val intervalNs = 1_000_000_000L / sendRateHz
            var nextTime = System.nanoTime()

            // Delta tracking: cache previous sent values
            var prevSteering: Short = Short.MIN_VALUE
            var prevThrottle: Byte = -1
            var prevBrake: Byte = -1
            var prevClutch: Byte = -1
            var prevGyroX: Short = Short.MIN_VALUE
            var prevGyroY: Short = Short.MIN_VALUE
                var prevGyroZ: Short = Short.MIN_VALUE
            var prevButtons: Set<Int> = emptySet()

            while (running) {
                try {
                    val now = System.nanoTime()
                    if (now >= nextTime) {
                        // Read current volatile values atomically
                        val curSteering = steering
                        val curThrottle = throttle
                        val curBrake = brake
                        val curClutch = clutch
                        val curGyroX = gyroX
                        val curGyroY = gyroY
                        val curGyroZ = gyroZ
                        val curButtons = activeButtons
                        val curGyro = gyroActive

                        // DELTA CHECK: only send if something changed
                        val changed = curSteering != prevSteering ||
                            curThrottle != prevThrottle ||
                            curBrake != prevBrake ||
                            curClutch != prevClutch ||
                            curButtons != prevButtons ||
                            (curGyro && (curGyroX != prevGyroX || curGyroY != prevGyroY || curGyroZ != prevGyroZ))

                        if (changed) {
                            val packet = Protocol.buildInputPacket(
                                curSteering, curThrottle, curBrake, curClutch,
                                curGyroX, curGyroY, curGyroZ,
                                curButtons, includeGyro = curGyro
                            )
                            // Reuse DatagramPacket
                            sendPacket?.let { sp ->
                                System.arraycopy(packet, 0, sp.data, 0, packet.size)
                                sp.length = packet.size
                                udpSocket?.send(sp)
                            }
                            _sendCount++

                            // Update previous values
                            prevSteering = curSteering
                            prevThrottle = curThrottle
                            prevBrake = curBrake
                            prevClutch = curClutch
                            prevGyroX = curGyroX
                            prevGyroY = curGyroY
                            prevGyroZ = curGyroZ
                            prevButtons = curButtons
                        } else {
                            _skipCount++
                        }

                        nextTime = now + intervalNs
                    } else {
                        val sleepNs = nextTime - now
                        if (sleepNs > 2_000_000L) {
                            Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                        } else {
                            Thread.yield()
                        }
                    }
                } catch (_: InterruptedException) { break }
                catch (e: SocketException) {
                    if (running) {
                        _errorCount++
                        Log.w(TAG, "Socket error on send #$_sendCount: ${e.message}")
                        if (_errorCount <= 3) onLog?.invoke("Socket error: ${e.message}")
                        try {
                            udpSocket?.close()
                            udpSocket = DatagramSocket().also { it.sendBufferSize = 64 * 1024 }
                            // Recreate the send packet with new socket's address
                            val addr = InetSocketAddress(targetIp, targetPort)
                            sendPacket = DatagramPacket(ByteArray(MAX_PACKET_SIZE), MAX_PACKET_SIZE, addr)
                            onLog?.invoke("Socket recreated after error")
                        } catch (re: Exception) {
                            onLog?.invoke("Cannot recreate socket: ${re.message}")
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (running) {
                        _errorCount++
                        if (_errorCount <= 3) onLog?.invoke("Send error: ${e.message}")
                    }
                }
            }
            Log.d(TAG, "Send loop ended: $_sendCount sent, $_skipCount skipped (delta), $_errorCount errors")
        }
    }

    private fun startHeartbeat() {
        heartbeatThread = thread(name = "Heartbeat") {
            while (running) {
                try {
                    Thread.sleep(3000) // Reduced from 1s to 3s
                    if (running && udpSocket != null) {
                        val hb = Protocol.buildHeartbeatPacket()
                        val addr = InetSocketAddress(targetIp, Protocol.INPUT_PORT)
                        udpSocket?.send(DatagramPacket(hb, hb.size, addr))
                    }
                } catch (_: InterruptedException) { break }
                catch (e: Exception) {
                    if (running) onLog?.invoke("Heartbeat error: ${e.message}")
                }
            }
        }
    }

    fun connectDirect(ip: String, onReady: () -> Unit, onError: ((String) -> Unit)? = null) {
        targetIp = ip
        stopInternal()

        try {
            // WiFi lock
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "omniwheel:input")?.also {
                    it.setReferenceCounted(false)
                    it.acquire()
                }
            } catch (e: Exception) {
                Log.w(TAG, "WiFi lock failed: ${e.message}")
            }

            udpSocket = DatagramSocket().also { sock ->
                sock.sendBufferSize = 64 * 1024
                sock.soTimeout = 0
            }

            running = true
            _sendCount = 0
            _skipCount = 0
            _errorCount = 0
            setState(State.CONNECTED)
            onLog?.invoke("Direct connect to $targetIp (no handshake)")

            val addr = InetSocketAddress(targetIp, targetPort)
            sendPacket = DatagramPacket(ByteArray(MAX_PACKET_SIZE), MAX_PACKET_SIZE, addr)

            startSendLoop()
            startHeartbeat()
            onReady()

        } catch (e: Exception) {
            val msg = "Direct connect failed: ${e.message}"
            onLog?.invoke(msg)
            setState(State.DISCONNECTED)
            onError?.invoke(msg)
            cleanup()
        }
    }

    fun disconnect() {
        stopInternal()
        setState(State.DISCONNECTED)
        onLog?.invoke("Disconnected")
    }

    private fun stopInternal() {
        running = false
        sendThread?.interrupt()
        heartbeatThread?.interrupt()
        sendThread = null
        heartbeatThread = null
        sendPacket = null
        cleanup()
    }

    private fun cleanup() {
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    fun isConnected(): Boolean = state == State.CONNECTED && running
}
