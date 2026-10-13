package com.oakcraft.lumen.blocker

/**
 * Minimal DNS reading and answering.
 *
 * Only two things are needed: the name a client is asking about, and a well-formed
 * refusal to send back when that name is on a blocklist. Everything else is forwarded
 * to the upstream resolver untouched, so the rest of the protocol never has to be
 * modelled here.
 */
object DnsMessage {

    const val PORT = 53
    private const val HEADER_SIZE = 12
    private const val MAX_NAME_LENGTH = 255
    private const val LABEL_POINTER_MASK = 0xC0

    data class Question(val name: String, val endOffset: Int)

    /**
     * @return the question in a standard query, or null if this is not one we handle
     *         (a response, a non-zero opcode, a malformed or compressed question).
     */
    fun readQuestion(payload: ByteArray): Question? {
        if (payload.size < HEADER_SIZE + 5) return null

        val flags = payload[2].toInt() and 0xFF
        if ((flags and 0x80) != 0) return null          // already a response
        if ((flags and 0x78) != 0) return null          // opcode other than QUERY

        val questionCount = readShort(payload, 4)
        if (questionCount < 1) return null

        val builder = StringBuilder()
        var offset = HEADER_SIZE
        var totalLength = 0

        while (true) {
            if (offset >= payload.size) return null
            val labelLength = payload[offset].toInt() and 0xFF

            if (labelLength == 0) {
                offset += 1
                break
            }
            // A pointer in the question section means a malformed query; refuse to guess.
            if ((labelLength and LABEL_POINTER_MASK) != 0) return null

            offset += 1
            if (offset + labelLength > payload.size) return null

            totalLength += labelLength + 1
            if (totalLength > MAX_NAME_LENGTH) return null

            if (builder.isNotEmpty()) builder.append('.')
            for (i in 0 until labelLength) {
                builder.append((payload[offset + i].toInt() and 0xFF).toChar())
            }
            offset += labelLength
        }

        // QTYPE and QCLASS follow the name.
        if (offset + 4 > payload.size) return null
        offset += 4

        if (builder.isEmpty()) return null
        return Question(builder.toString().lowercase(), offset)
    }

    /**
     * Builds an NXDOMAIN answer for [query], keeping the transaction id and question
     * so the client matches it to its request.
     *
     * NXDOMAIN is used rather than an answer pointing at 0.0.0.0 because clients give up
     * immediately instead of opening a connection to a null address and waiting for it
     * to time out — which is both faster and easier on the battery.
     */
    fun refuse(query: ByteArray, question: Question): ByteArray {
        val response = query.copyOfRange(0, question.endOffset)

        response[2] = ((query[2].toInt() or 0x80) and 0xF9).toByte()  // QR=1, clear AA/TC
        response[3] = 0x83.toByte()                                   // RA=1, RCODE=NXDOMAIN

        writeShort(response, 4, 1)   // QDCOUNT
        writeShort(response, 6, 0)   // ANCOUNT
        writeShort(response, 8, 0)   // NSCOUNT
        writeShort(response, 10, 0)  // ARCOUNT

        return response
    }

    private fun readShort(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    private fun writeShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }
}
