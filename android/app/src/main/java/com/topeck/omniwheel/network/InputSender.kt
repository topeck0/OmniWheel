package com.topeck.omniwheel.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
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
 *
 * USB debugging mode (adb reverse): the same packets ride a persistent TCP
 * connection to 127.0.0.1:USB_BRIDGE_PORT as [4-byte BE length][data] frames.
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

    // USB (adb reverse) transport
    private var usbSocket: Socket? = null
    private var usbOut: DataOutputStream? = null
    private var usbIn: DataInputStream? = null
    private val usbLock = Any()

    // Auto-reconnect for the USB bridge: when a live USB session drops (cable
    // blip, adb reverse briefly out), retry dialing the bridge a few times
    // before giving up and returning to the connection screen. Keeps you
    // driving through transient drops instead of forcing a manual reconnect.
    private const val USB_RECONNECT_ATTEMPTS = 6
    private const val USB_RECONNECT_DELAY_MS = 1500L
    @Volatile private var usbReconnectEnabled = false
    @Volatile private var usbReconnectThread: Thread? = null

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
            " \u00b7 ${widgetPacketsSent} widgets \u00b7 FULL ${fullLayoutBurstsSent}x"
    }

    // ================= TRANSPORT ABSTRACTION =================

    /** True once connected over the USB debugging bridge instead of WiFi. */
    @Volatile var isUsbConnection: Boolean = false
        private set

    /** Send one full OW packet over whichever transport is active. */
    private fun transmit(pkt: ByteArray) {
        val sock = udpSocket
        if (sock != null) {
            try {
                val addr = InetSocketAddress(targetIp, targetPort)
                sock.send(DatagramPacket(pkt, pkt.size, addr))
            } catch (e: Exception) {
                if (running) {
                    layoutSyncInfo = "send error: ${e.message}"
                    Log.w(TAG, "send: ${e.message}")
                }
            }
            return
        }
        // USB path — write lock only. The receives never hold this lock, so a
        // blocked read can't starve the sender (see receivePacket()).
        synchronized(usbLock) {
            val out = usbOut ?: return
            try {
                out.writeInt(pkt.size) // big-endian
                out.write(pkt)
                out.flush()
            } catch (e: Exception) {
                if (running) {
                    layoutSyncInfo = "usb send error: ${e.message}"
                    Log.w(TAG, "usb send: ${e.message}")
                    if (e is java.io.IOException) failUsbTransport("USB write failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Give up on the USB transport. We are called from the sender or the
     * receiver thread when the adb-reverse bridge drops out from under us
     * (broken pipe). First try a short auto-reconnect loop (the adb reverse
     * mapping on the PC survives and the phone just needs to redial the local
     * bridge) before declaring the transport dead.
     */
    private fun failUsbTransport(reason: String) {
        if (!running) return
        running = false
        isUsbConnection = false
        try { usbSocket?.close() } catch (_: Exception) {}
        onLog?.invoke(reason)
        setState(State.CONNECTING)
        usbReconnectThread = thread(name = "UsbReconnect") {
            var attempt = 0
            while (attempt < USB_RECONNECT_ATTEMPTS) {
                attempt++
                try {
                    Thread.sleep(USB_RECONNECT_DELAY_MS)
                } catch (_: InterruptedException) { break }
                if (!usbReconnectEnabled) break
                if (state != State.CONNECTING) break
                try {
                    val socket = Socket()
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress("127.0.0.1", Protocol.USB_BRIDGE_PORT), 4000)
                    if (!usbReconnectEnabled) {
                        try { socket.close() } catch (_: Exception) {}
                        break
                    }
                    usbSocket = socket
                    usbOut = DataOutputStream(socket.getOutputStream())
                    usbIn = DataInputStream(socket.getInputStream())
                    isUsbConnection = true
                    running = true
                    onLog?.invoke("USB bridge reconnected (attempt ${attempt + 1})")
                    transmit(Protocol.buildPacket(Protocol.TYPE_CONNECT))
                    setState(State.CONNECTED)
                    startSendLoop()
                    startHeartbeat()
                    startReceiveLoop()
                    return@thread
                } catch (_: Exception) {
                    onLog?.invoke("USB reconnect attempt ${attempt + 1} failed")
                }
            }
            if (usbReconnectEnabled && state == State.CONNECTING) {
                setState(State.DISCONNECTED)
            }
        }
    }

    /** Receive one packet (UDP datagram or USB frame), null when shutting down. */
    private fun receivePacket(): ByteArray? {
        val sock = udpSocket
        if (sock != null) {
            return try {
                val buf = ByteArray(512)
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                buf.copyOfRange(0, pkt.length)
            } catch (e: Exception) { null }
        }
        val input = usbIn ?: return null
        // NOTE: deliberately NOT under usbLock. The header+payload read blocks
        // for the whole frame; if it held the shared lock, every transmit()
        // would stall behind it (the latency/deadlock bug the WiFi-only code
        // never hit). Only one thread ever reads, so it needs no lock at all.
        return try {
            val len = input.readInt() // big-endian
            if (len <= 0 || len > 65535) null
            else {
                val data = ByteArray(len)
                input.readFully(data)
                data
            }
        } catch (e: Exception) {
            if (e is java.io.IOException && running && usbSocket != null) {
                failUsbTransport("USB bridge lost: ${e.message}")
            }
            null
        }
    }

    // ================= META / HUD =================

    fun sendMetaPacket() {
        val sock = udpSocket
        val out = usbOut
        if (sock == null && out == null) return
        try {
            val pkt = Protocol.buildMetaPacket(
                metaBatteryPercent, metaMaxAngle,
                metaScreenWidthPx, metaScreenHeightPx,
                metaDeviceType, metaClutchEnabled
            )
            transmit(pkt)
        } catch (e: Exception) {
            layoutSyncInfo = "META error: ${e.message}"
        }
    }

    /**
     * Send HUD layout widgets to the receiver. EVERY widget is sent on every
     * call so a single lost UDP packet can never leave the client preview stale
     * or missing an element. Widgets that no longer exist are announced as
     * removals so the client drops them too.
     */
    fun syncLayout(widgetJsons: List<String>) {
        val sock = udpSocket
        val out = usbOut
        if (sock == null && out == null) return
        val currentIds = HashSet<String>()
        synchronized(metaLock) {
            for (json in widgetJsons) {
                // Extract the widget id without pulling in JSON parsing.
                val id = extractJsonId(json) ?: continue
                currentIds.add(id)
                try {
                    val pkt = Protocol.buildPacket(Protocol.TYPE_HUD_WIDGET, json.toByteArray(Charsets.UTF_8))
                    transmit(pkt)
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
                    transmit(pkt)
                } catch (e: Exception) { }
                sentWidgetJson.remove(id)
            }
            widgetPacketsSent = currentIds.size
            updateLayoutLabel()
        }
    }

    /**
     * Send the entire layout as chained chunks. The client replaces its whole
     * layout list, which authoritatively handles deletions and any dropped
     * per-widget packets.
     */
    fun sendFullLayout(fullJson: String) {
        val sock = udpSocket
        val out = usbOut
        if (sock == null && out == null) return
        try {
            for (pkt in Protocol.buildFullLayoutPackets(fullJson)) {
                transmit(pkt)
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
        usbReconnectEnabled = false
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

    /**
     * Connect over USB debugging (adb reverse). The PC that pressed "ENABLE USB"
     * runs `adb reverse tcp:19710 tcp:19710`, so connecting to 127.0.0.1 flows
     * straight into its TCP bridge. Each packet is exchanged as a length-prefixed
     * frame. No WiFi lock needed — USB is already low-latency and wired.
     */
    fun connectUsb(onReady: () -> Unit, onError: ((String) -> Unit)? = null) {
        stopInternal()
        targetIp = "127.0.0.1"

        running = true
        _sendCount = 0
        _skipCount = 0
        _dupCount = 0
        _errorCount = 0
        synchronized(metaLock) { sentWidgetJson.clear() }
        setState(State.CONNECTING)
        onLog?.invoke("USB connect: opening 127.0.0.1:${Protocol.USB_BRIDGE_PORT}...")

        usbReconnectEnabled = true

        thread(name = "UsbConnector") {
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress("127.0.0.1", Protocol.USB_BRIDGE_PORT), 5000)
                usbSocket = socket
                usbOut = DataOutputStream(socket.getOutputStream())
                usbIn = DataInputStream(socket.getInputStream())
                isUsbConnection = true

                // Send a CONNECT so the client sends back an ACK and knows our
                // layout resync request arrives immediately.
                transmit(Protocol.buildPacket(Protocol.TYPE_CONNECT))
                setState(State.CONNECTED)
                onLog?.invoke("USB bridge connected (adb reverse)")

                startSendLoop()
                startHeartbeat()
                startReceiveLoop()
                onReady()

            } catch (_: InterruptedException) { /* cancelled */ }
            catch (e: Exception) {
                val msg = "USB connect failed: ${e.message}"
                onLog?.invoke(msg)
                Log.e(TAG, msg, e)
                isUsbConnection = false
                stopInternal()
                setState(State.DISCONNECTED)
                onError?.invoke(e.message ?: "Unknown error")
            }
        }
    }

    private fun startSendLoop() {
        sendThread = thread(name = "InputSender") {
            val intervalNs = 1_000_000_000L / sendRateHz
            var nextTime = System.nanoTime()
            var lastForceSendTime = 0L

            // Congestion backoff: when a UDP send blocks for a while, the WiFi
            // driver/kernel buffer is wedged. Piling on 4x redundant copies
            // just keeps it wedged (the classic "pk/s collapses towards 1 for
            // a few seconds, then recovers" symptom). When detected, drop to a
            // single copy for a short window so the link drains instead of
            // blocking the send loop.
            var congestionUntilMs = 0L
            var lastCongestionLogMs = 0L

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
                            // CHANGE packets (steering sweep / pedal / clutch /
                            // button tap) are the ones that must not be lost —
                            // a dropped gear-button press is the difference
                            // between a shift and a destroyed gearbox. Send them
                            // 4x back-to-back on WiFi. Over the wired USB TCP
                            // bridge one copy is already reliable.
                            val congested = System.currentTimeMillis() < congestionUntilMs
                            val copies = if (usbSocket != null) 1
                                else if (congested) 1
                                else if (changed) 4
                                else 2

                            val sendStartNs = System.nanoTime()
                            for (c in 0 until copies) {
                                transmit(packet)
                            }
                            val blockedMs = (System.nanoTime() - sendStartNs) / 1_000_000
                            if (blockedMs > 8) {
                                congestionUntilMs = nowMs + 100
                                if (nowMs - lastCongestionLogMs > 4000) {
                                    lastCongestionLogMs = nowMs
                                    onLog?.invoke("WiFi saturated — UDP send blocked ${blockedMs}ms, throttling copies")
                                }
                            }
                            _sendCount++
                            _dupCount += copies - 1

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
                catch (_: SocketException) {
                    if (running && !isUsbConnection) {
                        _errorCount++
                        Log.w(TAG, "Socket error on send #$_sendCount")
                        if (_errorCount <= 3) {
                            // Re-open the losing socket on WiFi only; USB
                            // failure is handled by failUsbTransport().
                            try {
                                udpSocket?.close()
                                udpSocket = DatagramSocket().also { configureSocket(it) }
                            } catch (_: Exception) { }
                        }
                    } else if (running) {
                        failUsbTransport("Egress socket died")
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
                    if (running) {
                        transmit(Protocol.buildHeartbeatPacket())
                    }
                } catch (_: InterruptedException) { break }
                catch (e: Exception) {
                    if (running) onLog?.invoke("Heartbeat error: ${e.message}")
                }
            }
        }
    }

    /**
     * Persistent listener on the same transport. Reacts to client requests so
     * the receiver never depends on a specific connect/disconnect order:
     * - PING (0x09): echo it back as PONG with the payload untouched so the
     *   client can measure a clock-skew-proof RTT latency.
     * - CONNECT (0x03): the client just opened/needs the layout — resend it now.
     */
    private fun startReceiveLoop() {
        receiveThread = thread(name = "InputReceiver") {
            val buf = ByteArray(512)
            while (running) {
                try {
                    val data = receivePacket() ?: break
                    val header = Protocol.parseHeader(data) ?: continue
                    when (header.type) {
                        Protocol.TYPE_PING -> {
                            if (data.size > Protocol.HEADER_SIZE + Protocol.CRC_SIZE) {
                                val echo = data.copyOfRange(Protocol.HEADER_SIZE, data.size - Protocol.CRC_SIZE)
                                val pong = Protocol.buildPacket(Protocol.TYPE_PONG, echo)
                                transmit(pong)
                            }
                        }
                        Protocol.TYPE_CONNECT -> {
                            onLog?.invoke("Client requested layout resync")
                            onResyncRequested?.invoke()
                        }
                    }
                } catch (_: InterruptedException) { break }
                catch (_: SocketException) { if (!running && usbSocket == null) break }
                catch (e: Exception) {
                    if (running) onLog?.invoke("Receive error: ${e.message}")
                }
            }
        }
    }

    fun connectDirect(ip: String, onReady: () -> Unit, onError: ((String) -> Unit)? = null) {
        targetIp = ip
        usbReconnectEnabled = false
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
        if (running) {
            try {
                val zeroPacket = Protocol.buildInputPacket(
                    steering = 0, throttle = 0, brake = 0, clutch = 0,
                    activeButtons = emptySet()
                )
                val sock = udpSocket
                if (sock != null) {
                    val addr = InetSocketAddress(targetIp, targetPort)
                    repeat(5) { // send 5 times to guarantee arrival
                        sock.send(DatagramPacket(zeroPacket, zeroPacket.size, addr))
                    }
                }
            } catch (_: Exception) {}
        }
        usbReconnectEnabled = false
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
        try { usbSocket?.close() } catch (_: Exception) {}
        usbSocket = null
        usbOut = null
        usbIn = null
        isUsbConnection = false
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