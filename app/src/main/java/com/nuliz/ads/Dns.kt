package com.nuliz.ads

import android.net.VpnService
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

private const val IP_HEADER = 20
private const val UDP_HEADER = 8
private const val DNS_HEADER = 12

class DnsQuery private constructor(
    private val packet: ByteArray,
    private val ipLen: Int,
    val name: String,
    private val questionEnd: Int,
) {
    private val dnsStart get() = ipLen + UDP_HEADER

    fun message(): ByteArray = packet.copyOfRange(dnsStart, packet.size)

    fun blockedReply(): ByteArray {
        val dns = packet.copyOfRange(dnsStart, questionEnd)
        dns[2] = (0x80 or (dns[2].toInt() and 0x01)).toByte()
        dns[3] = 0x83.toByte()
        dns.fill(0, 6, DNS_HEADER)
        return wrap(dns)
    }

    fun wrap(dns: ByteArray): ByteArray {
        val total = IP_HEADER + UDP_HEADER + dns.size
        val udpLen = UDP_HEADER + dns.size
        val out = ByteArray(total)

        out[0] = 0x45
        out[2] = (total shr 8).toByte()
        out[3] = total.toByte()
        out[6] = 0x40
        out[8] = 64
        out[9] = 17
        packet.copyInto(out, 12, 16, 20)
        packet.copyInto(out, 16, 12, 16)
        out[10] = 0
        out[11] = 0
        val sum = checksum(out)
        out[10] = (sum shr 8).toByte()
        out[11] = sum.toByte()

        packet.copyInto(out, 20, ipLen + 2, ipLen + 4)
        packet.copyInto(out, 22, ipLen, ipLen + 2)
        out[24] = (udpLen shr 8).toByte()
        out[25] = udpLen.toByte()
        dns.copyInto(out, IP_HEADER + UDP_HEADER)
        return out
    }

    companion object {
        fun parse(buf: ByteArray, len: Int): DnsQuery? {
            if (len < IP_HEADER + UDP_HEADER + DNS_HEADER) return null
            if ((buf[0].toInt() shr 4) != 4 || buf[9].toInt() != 17) return null
            val ipLen = (buf[0].toInt() and 0x0F) * 4
            if (ipLen < IP_HEADER || len < ipLen + UDP_HEADER + DNS_HEADER) return null
            if (u16(buf, ipLen + 2) != 53) return null

            val dns = ipLen + UDP_HEADER
            val flags = u16(buf, dns + 2)
            val isQuery = (flags and 0x8000) == 0 && ((flags shr 11) and 0xF) == 0
            if (!isQuery || u16(buf, dns + 4) != 1) return null

            val name = StringBuilder()
            var pos = dns + DNS_HEADER
            while (true) {
                if (pos >= len) return null
                val n = buf[pos].toInt() and 0xFF
                if (n == 0) { pos++; break }
                if (n > 63 || pos + 1 + n > len) return null
                if (name.isNotEmpty()) name.append('.')
                for (i in 1..n) name.append((buf[pos + i].toInt() and 0xFF).toChar())
                pos += 1 + n
            }
            if (pos + 4 > len) return null
            return DnsQuery(buf.copyOf(len), ipLen, name.toString().lowercase(), pos + 4)
        }

        private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

        private fun checksum(header: ByteArray): Int {
            var sum = 0
            for (i in 0 until IP_HEADER step 2) sum += u16(header, i)
            while ((sum shr 16) != 0) sum = (sum and 0xFFFF) + (sum shr 16)
            return sum.inv() and 0xFFFF
        }
    }
}

class Upstream(private val vpn: VpnService) {
    private val servers = listOf("1.1.1.1", "8.8.8.8").map { InetAddress.getByName(it) }

    fun resolve(query: ByteArray): ByteArray? {
        for (server in servers) {
            try {
                DatagramSocket().use { socket ->
                    vpn.protect(socket)
                    socket.soTimeout = TIMEOUT_MS
                    socket.send(DatagramPacket(query, query.size, server, 53))
                    val reply = DatagramPacket(ByteArray(MAX_REPLY), MAX_REPLY)
                    socket.receive(reply)
                    return reply.data.copyOf(reply.length)
                }
            } catch (_: IOException) {
            }
        }
        return null
    }

    private companion object {
        const val TIMEOUT_MS = 3_000
        const val MAX_REPLY = 4_096
    }
}
