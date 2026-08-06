package com.topeck.omniwheel.network

import java.net.DatagramPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Optimized binary protocol v2.
 *
 * **Compact header** (8 bytes, was 16):
 *   [0-1]  Magic       0x4F57 ("OW")
 *   [2]    Version     2
 *   [3]    Type        packet type
 *   [4]    Sequence    1-byte wrapping counter
 *   [5-6]  Timestamp   ms within current second (0..59999)
 *   [7]    PayloadLen  0..255
 *
 * **Input payload** (5 or 11 bytes, was always 11+):
 *   [0-1]  Steering    int16
 *   [2]    Throttle    uint8
 *   [3]    Brake       uint8
 *   [4]    Clutch      uint8
 *   [5-10] Gyro X/Y/Z  3x int16 (ONLY when gyro flag bit is set)
 *   [11+]  Buttons     bitmap (always 2 bytes for up to 16 buttons)
 *
 * **Packet**: [Header 8B] [Payload NB] [CRC-16 2B]
 *
 * Bandwidth at 240Hz:
 *   No gyro:  (8 + 5 + 2 + 2) * 240 = 4,128 B/s  (was ~7,440)
 *   With gyro: (8 + 11 + 2 + 2) * 240 = 6,912 B/s  (was ~7,440)
 *   With delta (no change): 0 B/s (up to ~80% reduction)
 */
object Protocol {
    const val PROTOCOL_VERSION: Byte = 2
    const val MAGIC: Short = 0x4F57

    // Ports
    const val DISCOVERY_PORT = 19700
    const val INPUT_PORT = 19701
    const val CONTROL_PORT = 19702

    const val HEADER_SIZE = 8
    const val CRC_SIZE = 2

    // Packet types
    const val TYPE_DISCOVER = 0x01.toByte()
    const val TYPE_DISCOVER_RESPONSE = 0x02.toByte()
    const val TYPE_CONNECT = 0x03.toByte()
    const val TYPE_CONNECT_ACK = 0x04.toByte()
    const val TYPE_INPUT = 0x06.toByte()
    const val TYPE_HEARTBEAT = 0x07.toByte()
    const val TYPE_HEARTBEAT_ACK = 0x08.toByte()
    const val TYPE_DISCONNECT = 0x05.toByte()
    const val TYPE_META = 0x0B.toByte()
    const val TYPE_HUD_WIDGET = 0x0C.toByte()
    const val TYPE_HUD_FULL = 0x0D.toByte()

    // Input payload offsets
    const val OFF_STEERING = 0
    const val OFF_THROTTLE = 2
    const val OFF_BRAKE = 3
    const val OFF_CLUTCH = 4
    const val OFF_GYRO_X = 5
    const val OFF_GYRO_Y = 7
    const val OFF_GYRO_Z = 9
    const val OFF_BUTTONS_NO_GYRO = 5   // buttons start here when no gyro
    const val OFF_BUTTONS_WITH_GYRO = 11 // buttons start here when gyro
    const val BUTTON_BYTES = 3            // always 3 bytes (up to 24 buttons)

    @Volatile
    var sequenceNumber: Short = 0
        private set

    @Synchronized
    fun nextSequence(): Byte {
        sequenceNumber = if (sequenceNumber >= 127) 0 else (sequenceNumber + 1).toShort()
        return sequenceNumber.toByte()
    }

    // ========== CRC-16/CCITT-FALSE LOOKUP TABLE ==========
    private val CRC_TABLE = ShortArray(256)

    init {
        for (i in 0..255) {
            var crc = i shl 8
            for (j in 0..7) {
                crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
            }
            CRC_TABLE[i] = (crc and 0xFFFF).toShort()
        }
    }

    fun computeCrc16(data: ByteArray, offset: Int, length: Int): Short {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            val idx = ((crc ushr 8) xor (data[i].toInt() and 0xFF)) and 0xFF
            crc = ((crc shl 8) xor CRC_TABLE[idx].toInt()) and 0xFFFF
        }
        return crc.toShort()
    }

    // ========== PACKET BUILDING ==========

    fun buildPacket(type: Byte, payload: ByteArray? = null): ByteArray {
        val pLen = payload?.size ?: 0
        val seq = nextSequence()
        val totalLen = HEADER_SIZE + pLen + CRC_SIZE

        // Use thread-local buffer pool: allocate once, reuse
        val buf = ByteArray(totalLen)

        // Header (8 bytes)
 buf[0] = (MAGIC.toInt() and 0xFF).toByte()
        buf[1] = ((MAGIC.toInt() shr 8) and 0xFF).toByte()
        buf[2] = PROTOCOL_VERSION
        buf[3] = type
        buf[4] = seq
        // Timestamp: ms within current second
        val msInSecond = ((System.currentTimeMillis() % 60000).toInt() and 0xFFFF)
        buf[5] = (msInSecond and 0xFF).toByte()
        buf[6] = ((msInSecond shr 8) and 0xFF).toByte()
        buf[7] = (pLen and 0xFF).toByte()

        // Payload
        if (payload != null && pLen > 0) {
            System.arraycopy(payload, 0, buf, HEADER_SIZE, pLen)
        }

        // CRC
        val crcVal = computeCrc16(buf, 0, HEADER_SIZE + pLen)
        buf[HEADER_SIZE + pLen] = (crcVal.toInt() and 0xFF).toByte()
        buf[HEADER_SIZE + pLen + 1] = ((crcVal.toInt() shr 8) and 0xFF).toByte()

        return buf
    }

    /**
     * Build optimized input packet.
     * @param includeGyro if true, includes 6 bytes of gyro data
     * @param steering raw steering value
     * @param throttle 0..255
     * @param brake 0..255
     * @param clutch 0..255
     * @param gyroX/Y/Z gyro axes
     * @param activeButtons button IDs that are pressed
     */
    fun buildInputPacket(
        steering: Short, throttle: Byte, brake: Byte, clutch: Byte,
        gyroX: Short = 0, gyroY: Short = 0, gyroZ: Short = 0,
        activeButtons: Set<Int> = emptySet(),
        includeGyro: Boolean = false
    ): ByteArray {
        val gyroBytes = if (includeGyro) 6 else 0
        val payloadSize = 5 + gyroBytes + BUTTON_BYTES
        val payload = ByteArray(payloadSize)

        // Steering
        payload[0] = (steering.toInt() and 0xFF).toByte()
        payload[1] = ((steering.toInt() shr 8) and 0xFF).toByte()
        // Throttle, Brake, Clutch
        payload[2] = throttle
        payload[3] = brake
        payload[4] = clutch

        // Gyro (conditional)
        if (includeGyro) {
            payload[5] = (gyroX.toInt() and 0xFF).toByte()
            payload[6] = ((gyroX.toInt() shr 8) and 0xFF).toByte()
            payload[7] = (gyroY.toInt() and 0xFF).toByte()
            payload[8] = ((gyroY.toInt() shr 8) and 0xFF).toByte()
            payload[9] = (gyroZ.toInt() and 0xFF).toByte()
            payload[10] = ((gyroZ.toInt() shr 8) and 0xFF).toByte()
        }

        // Button bitmap (always 3 bytes)
        val btnOffset = 5 + gyroBytes
        var bitmap = 0
        for (btn in activeButtons) {
            val bitIdx = (btn - 1) // 0-indexed bit
            if (bitIdx in 0..23) bitmap = bitmap or (1 shl bitIdx)
        }
        payload[btnOffset] = (bitmap and 0xFF).toByte()
        payload[btnOffset + 1] = ((bitmap shr 8) and 0xFF).toByte()

        return buildPacket(TYPE_INPUT, payload)
    }

    fun buildDiscoverPacket(deviceName: String): ByteArray {
        return buildPacket(TYPE_DISCOVER, deviceName.toByteArray(Charsets.UTF_8))
    }

    fun buildHeartbeatPacket(): ByteArray {
        return buildPacket(TYPE_HEARTBEAT)
    }

    /**
     * Build a metadata packet describing the phone:
     *   [0] battery % (0..100, 255 = unknown)
     *   [1-2] steering max angle uint16 LE
     *   [3-6] screen width px uint32 LE
     *   [7-10] screen height px uint32 LE
     *   [11] flags (bit0 = clutch enabled)
     *   [12+] device type name UTF-8
     */
    fun buildMetaPacket(
        batteryPercent: Int,
        maxAngle: Int,
        screenWidthPx: Int,
        screenHeightPx: Int,
        deviceType: String,
        clutchEnabled: Boolean = true
    ): ByteArray {
        val nameBytes = deviceType.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(12 + nameBytes.size)
        payload[0] = batteryPercent.coerceIn(0, 100).toByte()
        // max angle u16 LE
        val ma = maxAngle.coerceIn(0, 65535)
        payload[1] = (ma and 0xFF).toByte()
        payload[2] = ((ma shr 8) and 0xFF).toByte()
        // screen w u32 LE
        payload[3] = (screenWidthPx and 0xFF).toByte()
        payload[4] = ((screenWidthPx shr 8) and 0xFF).toByte()
        payload[5] = ((screenWidthPx shr 16) and 0xFF).toByte()
        payload[6] = ((screenWidthPx shr 24) and 0xFF).toByte()
        // screen h u32 LE
        payload[7] = (screenHeightPx and 0xFF).toByte()
        payload[8] = ((screenHeightPx shr 8) and 0xFF).toByte()
        payload[9] = ((screenHeightPx shr 16) and 0xFF).toByte()
        payload[10] = ((screenHeightPx shr 24) and 0xFF).toByte()
        // flags
        payload[11] = if (clutchEnabled) 1 else 0
        nameBytes.copyInto(payload, 12)
        return buildPacket(TYPE_META, payload)
    }

    // ========== PARSING ==========

    data class ParsedHeader(
        val type: Byte,
        val sequence: Byte,
        val timestampMs: Int,
        val payloadLength: Int
    )

    fun parseHeader(data: ByteArray, offset: Int = 0): ParsedHeader? {
        if (data.size - offset < HEADER_SIZE) return null
        val magic = ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)
        if (magic != MAGIC.toInt()) return null
        val version = data[offset + 2]
        // Accept v1 and v2 for backward compatibility during transition
        if (version != 1.toByte() && version != PROTOCOL_VERSION) return null
        return ParsedHeader(
            type = data[offset + 3],
            sequence = data[offset + 4],
            timestampMs = (data[offset + 5].toInt() and 0xFF) or
                         ((data[offset + 6].toInt() and 0xFF) shl 8),
            payloadLength = data[offset + 7].toInt() and 0xFF
        )
    }

    fun validateCrc(packet: ByteArray): Boolean {
        if (packet.size < HEADER_SIZE + CRC_SIZE) return false
        val crcDataLen = packet.size - CRC_SIZE
        val computed = computeCrc16(packet, 0, crcDataLen)
        val received = ((packet[packet.size - 1].toInt() and 0xFF) shl 8) or
                       (packet[packet.size - 2].toInt() and 0xFF)
        return (computed.toInt() and 0xFFFF) == received
    }
}
