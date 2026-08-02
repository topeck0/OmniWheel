package com.topeck.omniwheel.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import java.net.*
import kotlin.concurrent.thread

/**
 * Optimized discovery client.
 * - Broadcasts every 3s (was 2s) to reduce network noise
 * - Caches the discovery packet (same payload every time)
 * - Reuses receive buffer
 */
class DiscoveryClient(private val context: Context) {
    
    data class DiscoveredDevice(
        val name: String,
        val ipAddress: String,
        val port: Int,
        val lastSeen: Long = System.currentTimeMillis()
    )
    
    private var udpSocket: DatagramSocket? = null
    private var running = false
    private var discoverThread: Thread? = null
    private var listenThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    
    var onDeviceFound: ((DiscoveredDevice) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var deviceName: String = Build.MODEL
    
    private val discoveredDevices = mutableMapOf<String, DiscoveredDevice>()
    private val lock = Any()
    
    // Cached discovery packet
    private var cachedDiscoverPacket: ByteArray? = null
    private var cachedDeviceName: String = ""
    
    fun start() {
        if (running) return
        running = true
        
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("omniwheel")?.also { it.acquire() }
            
            udpSocket = DatagramSocket(null).also {
                it.reuseAddress = true
                it.bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
                it.broadcast = true
            }
        } catch (e: BindException) {
            udpSocket = DatagramSocket(null).also { it.broadcast = true }
            onLog?.invoke("Discovery: using random port for sending")
        }
        
        // Broadcast every 3 seconds (reduced from 2s)
        discoverThread = thread(name = "Discovery") {
            while (running) {
                try {
                    sendDiscover()
                    Thread.sleep(3000)
                } catch (_: InterruptedException) { break }
                catch (e: Exception) {
                    onLog?.invoke("Discovery send error: ${e.message}")
                    Thread.sleep(3000)
                }
            }
        }
        
        // Listen for responses
        listenThread = thread(name = "DiscoveryListen") {
            val buf = ByteArray(512) // Reused buffer
            while (running) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(packet)
                    val data = packet.data.copyOfRange(0, packet.length)
                    
                    val header = Protocol.parseHeader(data) ?: continue
                    if (header.type == Protocol.TYPE_DISCOVER_RESPONSE) {
                        val name = if (header.payloadLength > 0) {
                            String(data, Protocol.HEADER_SIZE, header.payloadLength, Charsets.UTF_8)
                        } else "Unknown PC"
                        
                        val device = DiscoveredDevice(
                            name = name,
                            ipAddress = packet.address.hostAddress ?: "",
                            port = packet.port
                        )
                        
                        synchronized(lock) {
                            discoveredDevices[device.ipAddress] = device
                        }
                        onDeviceFound?.invoke(device)
                        onLog?.invoke("Found PC: ${device.name} @ ${device.ipAddress}")
                    }
                } catch (_: InterruptedException) { break }
                catch (e: SocketException) {
                    if (running) onLog?.invoke("Discovery error: ${e.message}")
                    break
                } catch (e: Exception) {
                    if (running) onLog?.invoke("Discovery error: ${e.message}")
                }
            }
        }
        
        onLog?.invoke("Discovery started on port ${Protocol.DISCOVERY_PORT}")
    }
    
    private fun sendDiscover() {
        // Cache the discovery packet
        if (cachedDeviceName != deviceName) {
            cachedDiscoverPacket = Protocol.buildDiscoverPacket(deviceName)
            cachedDeviceName = deviceName
        }
        val packet = cachedDiscoverPacket ?: return
        val addr = InetSocketAddress("255.255.255.255", Protocol.DISCOVERY_PORT)
        udpSocket?.send(DatagramPacket(packet, packet.size, addr))
    }
    
    fun getDiscoveredDevices(): List<DiscoveredDevice> {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            return discoveredDevices.values
                .filter { now - it.lastSeen < 15000 }
                .toList()
        }
    }
    
    fun stop() {
        running = false
        discoverThread?.interrupt()
        listenThread?.interrupt()
        udpSocket?.close()
        udpSocket = null
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
        onLog?.invoke("Discovery stopped")
    }
}