package com.topeck.omniwheel.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.*
import kotlin.concurrent.thread

/**
 * Ultra-aggressive input sender optimized for worst-case networks.
 *
 * Key strategies for fighting congestion/old routers:
 * - 500Hz send rate (every 2ms)
 * - IPTOS_LOWDELAY socket flag (QoS priority)
 * - 256KB send buffer
 * - Redundant sends: critical changes (steering/pedals) sent 2x
 * - Forced periodic sends every 50ms even if unchanged (keeps NAT/routing warm)
 * - Pre-allocated reusable buffers (zero GC pressure)
 * - WiFi HIGH_PERF lock
 * - Fast connect: 3 probes @ 80ms + 2s ACK wait
 */
class InputSender(private val context: Context) {
    companion object {
        private const val TAG = "InputSender"
        private const val PROBE_COUNT = 3
        private const val PROBE_INTERVAL_MS = 80L
        private const val ACK_TIMEOUT_MS = 2000L
        private const val MAX_PACKET_SIZE = 32
        private const val FORCE_SEND_INTERVAL = 25 // ms: send even if unchanged
    }

    private var udpSocket: DatagramSocket? = null
    private var running = false
    private var sendThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private var receiveThread: Thread? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sendPacket: DatagramPacket? = null
    private var dupPacket: DatagramPacket? = null // duplicate for redundancy

    /** Invoked on the receiver thread when the PC asks us to resend the layout. */
    @Volatile var onResyncRequested: (() -> Unit)? = null

    var targetIp: String = ""
    var targetPort: Int = Protocol.INPUT_PORT
    var sendRateHz: Int = 500 // aggressive default

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

    // Device info to advertise (battery, steering range, screen, name)
    @Volatile var metaBatteryPercent: Int = 255
    @Volatile var metaMaxAngle: Int = 900
    @Volatile var metaScreenWidthPx: Int = 0
    @Volatile var metaScreenHeightPx: Int = 0
    @Volatile var metaDeviceType: String = "Android"
    @Volatile var metaClutchEnabled: Boolean = true

    private val metaLock = Any()
    private val sentWidgetJson = HashMap<String, String>()

    // Live layout-sync status surfaced in the controller status bar so the
    // user can see the phone actually transmitting HUD data to the PC.
    @Volatile var layoutSyncInfo: String = "layout not sent yet"
    @Volatile var widgetPacketsSent: Int = 0
    @Volatile var fullLayoutBurstsSent: Int = 0
    private val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    private fun updateLayoutLabel() {
        layoutSyncInfo = timeFmt.format(System.currentTimeMillis()) +
            " · ${widgetPacketsSent} widgets · FULL ${fullLayoutBurstsSent}x"
    }

    fun sendMetaPacket() {
        val sock = udpSocket ?: return
        val addr = try { InetSocketAddress(targetIp, Protocol.INPUT_PORT) } catch (e: Exception) { return }
        try {
            val pkt = Protocol.buildMetaPacket(
                metaBatteryPercent, metaMaxAngle,
                metaScreenWidthPx, metaScreenHeightPx,
                metaDeviceType, metaClutchEnabled
            )
            sock.send(DatagramPacket(pkt, pkt.size, addr))
        } catch (e: Exception) {
            layoutSyncInfo = "META error: ${e.message}"
            if (_errorCount <= 3) Log.w(TAG, "Meta send: ${e.message}")
        }
    }

    /**
     * Send HUD layout widgets to the receiver. EVERY widget is sent on every
     * call so a single lost UDP packet can never leave the PC preview stale or
     * missing an element. Widgets that no longer exist are announced as
     * removals so the PC drops them too.
     */
    fun syncLayout(widgetJsons: List<String>) {
        val sock = udpSocket ?: return
        val addr = try { InetSocketAddress(targetIp, Protocol.INPUT_PORT) } catch (e: Exception) { return }
        val currentIds = HashSet<String>()
        synchronized(metaLock) {
            for (json in widgetJsons) {
                // Extract the widget id without pulling in JSON parsing.
                val id = extractJsonId(json) ?: continue
                currentIds.add(id)
                try {
                    val pkt = Protocol.buildPacket(Protocol.TYPE_HUD_WIDGET, json.toByteArray(Charsets.UTF_8))
                    sock.send(DatagramPacket(pkt, pkt.size, addr))
                } catch (e: Exception) {
                    layoutSyncInfo = "Widget error: ${e.message}"
                    Log.w(TAG, "Widget send: ${e.message}")
                }
                sentWidgetJson[id] = json
            }
            // Notify server of deletions as an EMPTY replace
            val removed = sentWidgetJson.keys.toList() - currentIds
            for (id in removed) {
                try {
                    val json = "{\"id\":\"$id\",\"remove\":true}"
                    val pkt = Protocol.buildPacket(Protocol.TYPE_HUD_WIDGET, json.toByteArray(Charsets.UTF_8))
                    sock.send(DatagramPacket(pkt, pkt.size, addr))
                } catch (e: Exception) { }
                sentWidgetJson.remove(id)
            }
            widgetPacketsSent = currentIds.size
            updateLayoutLabel()
        }
    }

    /**
     * Send the entire layout as chained chunks. The receiver replaces its whole
     * widget list, which authoritatively handles deletions and any dropped
     * per-widget packets.
     */
    fun sendFullLayout(fullJson: String) {
        val sock = udpSocket ?: return
        val addr = try { InetSocketAddress(targetIp, Protocol.INPUT_PORT) } catch (e: Exception) { return }
        try {
            for (pkt in Protocol.buildFullLayoutPackets(fullJson)) {
                sock.send(DatagramPacket(pkt, pkt.size, addr))
            }
            fullLayoutBurstsSent++
            updateLayoutLabel()
        } catch (e: Exception) {
            layoutSyncInfo = "FULL error: ${e.message}"
            Log.w(TAG, "Full layout send: ${e.message}")
        }
    }

    /** Pull the `"id":"..."` value from a widget JSON string (cheap, no parser). */
    private fun extractJsonId(json: String): String? {
        val marker = "\"id\""
        val idx = json.indexOf(marker)
        if (idx < 0) return null
        val q = json.indexOf('"', idx + marker.length)
        if (q < 0) return null
        val end = json.indexOf('"', q + 1)
        if (end < 0) return null
        return json.substring(q + 1, end)
    }

    // Connection state
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }
    @Volatile var state: State = State.DISCONNECTED; private set

    var onLog: ((String) -> Unit)? = null
    var onStateChanged: ((State) -> Unit)? = null

    private var _sendCount = 0
    private var _skipCount = 0
    private var _dupCount = 0
    private var _errorCount = 0
    val sendCount: Int get() = _sendCount
    val skipCount: Int get() = _skipCount
    val errorCount: Int get() = _errorCount

    private fun setState(s: State) {
        state = s
        onStateChanged?.invoke(s)
    }

    private fun configureSocket(sock: DatagramSocket) {
        try {
            sock.sendBufferSize = 256 * 1024
            sock.soTimeout = 0
            // QoS: IPTOS_LOWDELAY (0x10) - tells routers to prioritize these packets
            sock.trafficClass = 0x10
        } catch (e: Exception) {
            Log.w(TAG, "Socket config: ${e.message}")
        }
    }

    fun connect(ip: String, onReady: () -> Unit, onError: ((String) -> Unit)? = null) {
        targetIp = ip
        stopInternal()

        try {
            // WiFi lock - HIGH_PERF prevents power saving latency
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "omniwheel:input")?.also {
                    it.setReferenceCounted(false)
                    it.acquire()
                }
            } catch (e: Exception) {
                Log.w(TAG, "WiFi lock failed: ${e.message}")
            }

            udpSocket = DatagramSocket().also { configureSocket(it) }

            running = true
            _sendCount = 0
            _skipCount = 0
            _dupCount = 0
            _errorCount = 0
            synchronized(metaLock) { sentWidgetJson.clear() }
            setState(State.CONNECTING)
            onLog?.invoke("Connecting to $targetIp...")

            thread(name = "Connector") {
                try {
                    // Phase 1: Fast CONNECT probes
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
                    onLog?.invoke("Sending at ${sendRateHz}Hz (aggressive + redundant)")

                    val addr = InetSocketAddress(targetIp, targetPort)
                    sendPacket = DatagramPacket(ByteArray(MAX_PACKET_SIZE), MAX_PACKET_SIZE, addr)
                    dupPacket = DatagramPacket(ByteArray(MAX_PACKET_SIZE), MAX_PACKET_SIZE, addr)

                    startSendLoop()
                    startHeartbeat()
                    startReceiveLoop()
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
            var lastForceSendTime = 0L

            // Delta tracking
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
                        val curSteering = steering
                        val curThrottle = throttle
                        val curBrake = brake
                        val curClutch = clutch
                        val curGyroX = gyroX
                        val curGyroY = gyroY
                        val curGyroZ = gyroZ
                        val curButtons = activeButtons
                        val curGyro = gyroActive

                        val changed = curSteering != prevSteering ||
                            curThrottle != prevThrottle ||
                            curBrake != prevBrake ||
                            curClutch != prevClutch ||
                            curButtons != prevButtons ||
                            (curGyro && (curGyroX != prevGyroX || curGyroY != prevGyroY || curGyroZ != prevGyroZ))

                        val nowMs = System.currentTimeMillis()
                        val forceSend = (nowMs - lastForceSendTime) >= FORCE_SEND_INTERVAL

                        if (changed || forceSend) {
                            val packet = Protocol.buildInputPacket(
                                curSteering, curThrottle, curBrake, curClutch,
                                curGyroX, curGyroY, curGyroZ,
                                curButtons, includeGyro = curGyro
                            )
                            // Primary send
                            sendPacket?.let { sp ->
                                System.arraycopy(packet, 0, sp.data, 0, packet.size)
                                sp.length = packet.size
                                udpSocket?.send(sp)
                            }
                            _sendCount++

                            // REDUNDANT SEND on EVERY packet, not just on change.
                            // Each state update goes out twice back-to-back so a
                            // single dropped UDP datagram can never delay the
                            // steering — even on a congested/lossy router.
                            dupPacket?.let { dp ->
                                System.arraycopy(packet, 0, dp.data, 0, packet.size)
                                dp.length = packet.size
                                udpSocket?.send(dp)
                            }
                            _dupCount++

                            if (forceSend) lastForceSendTime = nowMs

                            // Update previous
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
                            udpSocket = DatagramSocket().also { configureSocket(it) }
                            val addr = InetSocketAddress(targetIp, targetPort)
                            sendPacket = DatagramPacket(ByteArray(MAX_PACKET_SIZE), MAX_PACKET_SIZE, addr)
                            dupPacket = DatagramPacket(ByteArray(MAX_PACKET_SIZE), MAX_PACKET_SIZE, addr)
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
            Log.d(TAG, "Send loop ended: $_sendCount sent, $_dupCount redundant, $_skipCount skipped, $_errorCount errors")
        }
    }

    private fun startHeartbeat() {
        heartbeatThread = thread(name = "Heartbeat") {
            while (running) {
                try {
                    Thread.sleep(3000)
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

    /**
     * Persistent listener on the same socket. Reacts to PC requests so the
     * receiver never depends on a specific connect/disconnect order:
     * - PING (0x09): echo it back as PONG with the payload untouched so the PC
     *   can measure a clock-skew-proof RTT latency.
     * - CONNECT (0x03): the PC just opened/needs the layout — resend it now.
     */
    private fun startReceiveLoop() {
        receiveThread = thread(name = "InputReceiver") {
            val buf = ByteArray(512)
            while (running) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(pkt)
                    val data = buf.copyOfRange(0, pkt.length)
                    val header = Protocol.parseHeader(data) ?: continue
                    when (header.type) {
                        Protocol.TYPE_PING -> {
                            if (data.size > Protocol.HEADER_SIZE + Protocol.CRC_SIZE) {
                                val echo = data.copyOfRange(Protocol.HEADER_SIZE, data.size - Protocol.CRC_SIZE)
                                val pong = Protocol.buildPacket(Protocol.TYPE_PONG, echo)
                                val sender = InetSocketAddress(pkt.address, pkt.port)
                                udpSocket?.send(DatagramPacket(pong, pong.size, sender))
                            }
                        }
                        Protocol.TYPE_CONNECT -> {
                            onLog?.invoke("PC requested layout resync")
                            onResyncRequested?.invoke()
                        }
                    }
                } catch (_: InterruptedException) { break }
                catch (_: SocketException) { if (!running) break }
                catch (e: Exception) {
                    if (running) onLog?.invoke("Receive error: ${e.message}")
                }
            }
        }
    }

    fun connectDirect(ip: String, onReady: () -> Unit, onError: ((String) -> Unit)? = null) {
        targetIp = ip
        stopInternal()

        try {
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "omniwheel:input")?.also {
                    it.setReferenceCounted(false)
                    it.acquire()
                }
            } catch (e: Exception) {
                Log.w(TAG, "WiFi lock failed: ${e.message}")
            }

            udpSocket = DatagramSocket().also { configureSocket(it) }

            running = true
            _sendCount = 0
            _skipCount = 0
            _dupCount = 0
            _errorCount = 0
            synchronized(metaLock) { sentWidgetJson.clear() }
            setState(State.CONNECTED)
            onLog?.invoke("Direct connect to $targetIp (aggressive, no handshake)")

            val addr = InetSocketAddress(targetIp, targetPort)
            sendPacket = DatagramPacket(ByteArray(MAX_PACKET_SIZE), MAX_PACKET_SIZE, addr)
            dupPacket = DatagramPacket(ByteArray(MAX_PACKET_SIZE), MAX_PACKET_SIZE, addr)

            startSendLoop()
            startHeartbeat()
            startReceiveLoop()
            onReady()

        } catch (e: Exception) {
            val msg = "Direct connect failed: ${e.message}"
            onLog?.invoke(msg)
            setState(State.DISCONNECTED)
            onError?.invoke(e.message ?: "Unknown error")
            cleanup()
        }
    }

    fun disconnect() {
        if (running && udpSocket != null) {
            try {
                val zeroPacket = Protocol.buildInputPacket(
                    steering = 0, throttle = 0, brake = 0, clutch = 0,
                    activeButtons = emptySet()
                )
                val addr = InetSocketAddress(targetIp, targetPort)
                repeat(5) { // send 5 times to guarantee arrival
                    udpSocket?.send(DatagramPacket(zeroPacket, zeroPacket.size, addr))
                }
            } catch (_: Exception) {}
        }
        stopInternal()
        setState(State.DISCONNECTED)
        onLog?.invoke("Disconnected")
    }

    private fun stopInternal() {
        running = false
        sendThread?.interrupt()
        heartbeatThread?.interrupt()
        receiveThread?.interrupt()
        sendThread = null
        heartbeatThread = null
        receiveThread = null
        sendPacket = null
        dupPacket = null
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