namespace OmniWheelPC.Network;

/// <summary>
/// Optimized binary protocol v2.
/// Supports both v1 (16B header) and v2 (8B header) for backward compatibility.
/// CRC-16/CCITT-FALSE with pre-computed lookup table.
/// </summary>
public static class Protocol
{
    public const byte ProtocolVersion = 2;
    public const ushort Magic = 0x4F57;

    public const int DiscoveryPort = 19700;
    public const int InputPort = 19701;
    public const int ControlPort = 19702;

    // v2 header size
    public const int HeaderSizeV2 = 8;
    // v1 header size (legacy)
    public const int HeaderSizeV1 = 16;
    public const int CrcSize = 2;

    public enum PacketType : byte
    {
        Discover = 0x01,
        DiscoverResponse = 0x02,
        Connect = 0x03,
        ConnectAck = 0x04,
        Disconnect = 0x05,
        Input = 0x06,
        Heartbeat = 0x07,
        HeartbeatAck = 0x08,
        Ping = 0x09,
        Pong = 0x0A,
        Error = 0xFF
    }

    // Input payload offsets
    public const int OffSteering = 0;
    public const int OffThrottle = 2;
    public const int OffBrake = 3;
    public const int OffClutch = 4;
    public const int OffGyroX = 5;
    public const int OffGyroY = 7;
    public const int OffGyroZ = 9;
    public const int ButtonBytes = 2; // always 2 bytes for up to 16 buttons

    // ========== CRC-16/CCITT-FALSE LOOKUP TABLE ==========
    private static readonly ushort[] CrcTable = new ushort[256];

    static Protocol()
    {
        for (int i = 0; i < 256; i++)
        {
            ushort crc = (ushort)(i << 8);
            for (int j = 0; j < 8; j++)
            {
                if ((crc & 0x8000) != 0)
                    crc = (ushort)((crc << 1) ^ 0x1021);
                else
                    crc = (ushort)(crc << 1);
            }
            CrcTable[i] = crc;
        }
    }

    public static ushort ComputeCrc(byte[] data, int offset, int length)
    {
        ushort crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++)
        {
            crc = (ushort)((crc << 8) ^ CrcTable[((crc >> 8) ^ data[i]) & 0xFF]);
        }
        return crc;
    }

    // ========== PACKET BUILDING (v2 compact) ==========

    public static byte[] BuildPacket(PacketType type, ushort sequence, byte[]? payload = null)
    {
        int pLen = payload?.Length ?? 0;
        int totalLen = HeaderSizeV2 + pLen + CrcSize;
        var pkt = new byte[totalLen];

        // Magic
        pkt[0] = (byte)(Magic & 0xFF);
        pkt[1] = (byte)((Magic >> 8) & 0xFF);
        // Version
        pkt[2] = ProtocolVersion;
        // Type
        pkt[3] = (byte)type;
        // Sequence (1 byte)
        pkt[4] = (byte)(sequence & 0xFF);
        // Timestamp: ms within current second
        int ms = (int)(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() % 60000);
        pkt[5] = (byte)(ms & 0xFF);
        pkt[6] = (byte)((ms >> 8) & 0xFF);
        // Payload length
        pkt[7] = (byte)(pLen & 0xFF);

        if (payload != null && pLen > 0)
            Array.Copy(payload, 0, pkt, HeaderSizeV2, pLen);

        // CRC
        var crc = ComputeCrc(pkt, 0, HeaderSizeV2 + pLen);
        pkt[HeaderSizeV2 + pLen] = (byte)(crc & 0xFF);
        pkt[HeaderSizeV2 + pLen + 1] = (byte)((crc >> 8) & 0xFF);

        return pkt;
    }

    // ========== PARSING (supports v1 and v2) ==========

    public struct ParsedHeader
    {
        public PacketType Type;
        public byte Sequence;
        public int TimestampMs;
        public int PayloadLength;
        public int HeaderSize; // 8 for v2, 16 for v1
    }

    /// <summary>
    /// Parse header supporting both v1 (16B) and v2 (8B) formats.
    /// Detects version from byte [2].
    /// </summary>
    public static bool TryParseHeader(byte[] data, int offset, out ParsedHeader header)
    {
        header = default;
        if (data == null || data.Length - offset < HeaderSizeV2) return false;

        ushort magic = (ushort)(data[offset] | (data[offset + 1] << 8));
        if (magic != Magic) return false;

        byte version = data[offset + 2];
        int headerSize;

        if (version == 2)
        {
            // v2: compact 8-byte header
            headerSize = HeaderSizeV2;
            header.Type = (PacketType)data[offset + 3];
            header.Sequence = data[offset + 4];
            header.TimestampMs = data[offset + 5] | (data[offset + 6] << 8);
            header.PayloadLength = data[offset + 7];
        }
        else if (version == 1)
        {
            // v1: legacy 16-byte header
            if (data.Length - offset < HeaderSizeV1) return false;
            headerSize = HeaderSizeV1;
            header.Type = (PacketType)data[offset + 3];
            header.Sequence = (byte)(data[offset + 4] | (data[offset + 5] << 8));
            header.TimestampMs = (int)(data[offset + 6] | (data[offset + 7] << 8) | (data[offset + 8] << 16) | (data[offset + 9] << 24));
            header.PayloadLength = data[offset + 10] | (data[offset + 11] << 8) | (data[offset + 12] << 16) | (data[offset + 13] << 24);
        }
        else
        {
            return false;
        }

        header.HeaderSize = headerSize;
        return true;
    }

    public static bool ValidateCrc(byte[] packet, int headerSize)
    {
        if (packet.Length < headerSize + CrcSize) return false;
        var crc = ComputeCrc(packet, 0, packet.Length - CrcSize);
        var received = (ushort)(packet[packet.Length - 2] | (packet[packet.Length - 1] << 8));
        return crc == received;
    }
}
