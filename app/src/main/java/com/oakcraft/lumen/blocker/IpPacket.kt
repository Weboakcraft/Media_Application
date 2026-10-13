package com.oakcraft.lumen.blocker

/**
 * Just enough IP/UDP to read a DNS query off the tun device and write an answer back.
 *
 * The tunnel only ever carries DNS to our own stub resolver address, so this handles
 * exactly that: IPv4 and IPv6 UDP datagrams. Anything else read from the tun is dropped.
 */
object IpPacket {

    private const val PROTO_UDP = 17
    private const val IPV4_MIN_HEADER = 20
    private const val IPV6_HEADER = 40
    private const val UDP_HEADER = 8

    data class UdpDatagram(
        val version: Int,
        val sourceIp: ByteArray,
        val destIp: ByteArray,
        val sourcePort: Int,
        val destPort: Int,
        val payload: ByteArray
    ) {
        // ByteArray fields make the generated equals/hashCode useless; identity is the
        // right semantics for a transient packet, so make that explicit.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    fun parseUdp(buffer: ByteArray, length: Int): UdpDatagram? {
        if (length < 1) return null
        return when ((buffer[0].toInt() and 0xF0) ushr 4) {
            4 -> parseIpv4(buffer, length)
            6 -> parseIpv6(buffer, length)
            else -> null
        }
    }

    private fun parseIpv4(buffer: ByteArray, length: Int): UdpDatagram? {
        if (length < IPV4_MIN_HEADER) return null
        val headerLength = (buffer[0].toInt() and 0x0F) * 4
        if (headerLength < IPV4_MIN_HEADER || length < headerLength + UDP_HEADER) return null
        if ((buffer[9].toInt() and 0xFF) != PROTO_UDP) return null

        // A fragmented DNS query is not something we try to reassemble; drop it.
        val fragmentField = readShort(buffer, 6)
        if ((fragmentField and 0x1FFF) != 0) return null

        val sourceIp = buffer.copyOfRange(12, 16)
        val destIp = buffer.copyOfRange(16, 20)
        return readUdp(4, sourceIp, destIp, buffer, headerLength, length)
    }

    private fun parseIpv6(buffer: ByteArray, length: Int): UdpDatagram? {
        if (length < IPV6_HEADER + UDP_HEADER) return null
        // Extension headers are not expected on this tunnel; only plain UDP is handled.
        if ((buffer[6].toInt() and 0xFF) != PROTO_UDP) return null

        val sourceIp = buffer.copyOfRange(8, 24)
        val destIp = buffer.copyOfRange(24, 40)
        return readUdp(6, sourceIp, destIp, buffer, IPV6_HEADER, length)
    }

    private fun readUdp(
        version: Int,
        sourceIp: ByteArray,
        destIp: ByteArray,
        buffer: ByteArray,
        udpOffset: Int,
        length: Int
    ): UdpDatagram? {
        val sourcePort = readShort(buffer, udpOffset)
        val destPort = readShort(buffer, udpOffset + 2)
        val udpLength = readShort(buffer, udpOffset + 4)
        if (udpLength < UDP_HEADER) return null

        val payloadStart = udpOffset + UDP_HEADER
        // Trust whichever is shorter: the declared length or what actually arrived.
        val payloadEnd = minOf(udpOffset + udpLength, length)
        if (payloadEnd <= payloadStart) return null

        return UdpDatagram(
            version = version,
            sourceIp = sourceIp,
            destIp = destIp,
            sourcePort = sourcePort,
            destPort = destPort,
            payload = buffer.copyOfRange(payloadStart, payloadEnd)
        )
    }

    /**
     * Builds the datagram that answers [request], with endpoints swapped so it looks
     * like it came back from the resolver the system thinks it queried.
     */
    fun buildUdpReply(request: UdpDatagram, payload: ByteArray): ByteArray {
        val udpLength = UDP_HEADER + payload.size
        val headerLength = if (request.version == 4) IPV4_MIN_HEADER else IPV6_HEADER
        val packet = ByteArray(headerLength + udpLength)

        if (request.version == 4) {
            packet[0] = 0x45
            packet[1] = 0
            writeShort(packet, 2, packet.size)
            writeShort(packet, 4, 0)          // identification
            writeShort(packet, 6, 0x4000)     // don't fragment
            packet[8] = 64                    // TTL
            packet[9] = PROTO_UDP.toByte()
            writeShort(packet, 10, 0)         // checksum placeholder
            request.destIp.copyInto(packet, 12)
            request.sourceIp.copyInto(packet, 16)
            writeShort(packet, 10, checksum { it.addBytes(packet, 0, IPV4_MIN_HEADER) })
        } else {
            packet[0] = 0x60
            packet[1] = 0
            writeShort(packet, 2, 0)          // flow label
            writeShort(packet, 4, udpLength)  // payload length
            packet[6] = PROTO_UDP.toByte()
            packet[7] = 64                    // hop limit
            request.destIp.copyInto(packet, 8)
            request.sourceIp.copyInto(packet, 24)
        }

        val udpOffset = headerLength
        writeShort(packet, udpOffset, request.destPort)
        writeShort(packet, udpOffset + 2, request.sourcePort)
        writeShort(packet, udpOffset + 4, udpLength)
        writeShort(packet, udpOffset + 6, 0)
        payload.copyInto(packet, udpOffset + UDP_HEADER)

        // The UDP checksum is optional over IPv4 but mandatory over IPv6, so always
        // compute it. Source and destination are swapped relative to the request.
        val udpSum = checksum { sum ->
            sum.addBytes(request.destIp)
            sum.addBytes(request.sourceIp)
            if (request.version == 4) {
                sum.addShort(PROTO_UDP)
                sum.addShort(udpLength)
            } else {
                sum.addShort(udpLength ushr 16)
                sum.addShort(udpLength and 0xFFFF)
                sum.addShort(0)
                sum.addShort(PROTO_UDP)
            }
            sum.addBytes(packet, udpOffset, udpLength)
        }
        // A computed zero is sent as all-ones, since zero means "no checksum" over IPv4.
        writeShort(packet, udpOffset + 6, if (udpSum == 0) 0xFFFF else udpSum)

        return packet
    }

    private fun readShort(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    private fun writeShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    private inline fun checksum(block: (OnesComplementSum) -> Unit): Int {
        val sum = OnesComplementSum()
        block(sum)
        return sum.fold()
    }

    class OnesComplementSum {
        private var total = 0L

        fun addBytes(data: ByteArray, offset: Int = 0, length: Int = data.size) {
            var i = offset
            val end = offset + length
            while (i + 1 < end) {
                total += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                i += 2
            }
            if (i < end) total += (data[i].toInt() and 0xFF) shl 8
        }

        fun addShort(value: Int) {
            total += (value and 0xFFFF).toLong()
        }

        fun fold(): Int {
            var value = total
            while ((value ushr 16) != 0L) {
                value = (value and 0xFFFF) + (value ushr 16)
            }
            return (value.inv() and 0xFFFF).toInt()
        }
    }
}
