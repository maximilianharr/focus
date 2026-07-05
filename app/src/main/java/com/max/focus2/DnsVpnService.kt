package com.max.focus2

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

// Well-known public DoH/DoT resolver IPs. Routed into the tun and dropped
// there (only UDP:53 is serviced), so browser "Secure DNS" can't bypass the
// filter. ponytail: IPv4 only — v6 DoH is a known gap, add v6 routes if it
// ever shows up in the wild here.
val DOH_IPS = listOf(
    "1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4",
    "9.9.9.9", "9.9.9.10", "149.112.112.112",
    "94.140.14.14", "94.140.15.15",
    "208.67.222.222", "208.67.220.220",
    "185.228.168.9", "185.228.169.9",
    "76.76.2.0", "76.76.10.0",
)

private const val UPSTREAM_DNS = "8.8.8.8"

class DnsVpnService : VpnService() {
    companion object {
        @Volatile var running = false
    }

    private var tun: ParcelFileDescriptor? = null
    private val pool = Executors.newCachedThreadPool()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            shutdownVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) startVpn()
        return START_STICKY
    }

    override fun onRevoke() {
        shutdownVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdownVpn()
        super.onDestroy()
    }

    private fun startVpn() {
        val b = Builder()
            .setSession("Focus")
            .addAddress("10.111.0.1", 24)
            .addDnsServer("10.111.0.53")
            .addRoute("10.111.0.53", 32)
        DOH_IPS.forEach { b.addRoute(it, 32) }
        try {
            b.addDisallowedApplication(packageName)
        } catch (_: Exception) {
        }
        b.setBlocking(true)
        tun = b.establish() ?: return
        running = true
        Thread({ loop() }, "focus-dns").apply { isDaemon = true }.start()
    }

    private fun shutdownVpn() {
        running = false
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
    }

    private fun loop() {
        val fd = tun ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val out = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (running) {
            val n = try {
                input.read(buf)
            } catch (_: Exception) {
                break
            }
            if (n <= 0) continue
            try {
                handle(buf.copyOf(n), out)
            } catch (_: Exception) {
            }
        }
    }

    // Everything that is not an IPv4 UDP packet to port 53 is silently
    // dropped — that is what kills DoH (TCP/UDP 443) to the routed IPs.
    private fun handle(pkt: ByteArray, out: FileOutputStream) {
        if (pkt.size < 28 || (pkt[0].toInt() ushr 4) != 4) return
        val ihl = (pkt[0].toInt() and 0xF) * 4
        if (pkt[9].toInt() != 17) return
        if (pkt.size < ihl + 8) return
        val dstPort = ((pkt[ihl + 2].toInt() and 0xFF) shl 8) or (pkt[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return
        val dns = pkt.copyOfRange(ihl + 8, pkt.size)
        val host = parseQName(dns) ?: return
        when (Engine.siteVerdict(host, System.currentTimeMillis())) {
            VERDICT_BLOCK -> synchronized(out) { out.write(nxdomain(pkt, ihl)) }
            VERDICT_START -> {
                val v = Engine.siteItem(host)?.value
                if (v != null) pool.execute {
                    App.dao.markFirstSeen(v, Engine.today(), System.currentTimeMillis())
                }
                forward(pkt, ihl, dns, out)
            }
            else -> forward(pkt, ihl, dns, out)
        }
    }

    private fun forward(query: ByteArray, ihl: Int, dns: ByteArray, out: FileOutputStream) {
        pool.execute {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 5000
                    s.send(DatagramPacket(dns, dns.size, InetAddress.getByName(UPSTREAM_DNS), 53))
                    val resp = DatagramPacket(ByteArray(4096), 4096)
                    s.receive(resp)
                    val reply = udpReply(query, ihl, resp.data, resp.length)
                    synchronized(out) { out.write(reply) }
                }
            } catch (_: Exception) {
            }
        }
    }
}

// --- pure packet helpers (unit-tested) ---

fun parseQName(dns: ByteArray): String? {
    if (dns.size < 14) return null
    var i = 12
    val sb = StringBuilder()
    while (i < dns.size) {
        val len = dns[i].toInt() and 0xFF
        if (len == 0) break
        if (len >= 0xC0 || i + 1 + len > dns.size) return null
        if (sb.isNotEmpty()) sb.append('.')
        for (j in 1..len) sb.append((dns[i + j].toInt() and 0xFF).toChar())
        i += len + 1
    }
    return if (sb.isEmpty()) null else sb.toString().lowercase()
}

fun nxdomain(query: ByteArray, ihl: Int): ByteArray {
    val dns = query.copyOfRange(ihl + 8, query.size)
    dns[2] = (dns[2].toInt() or 0x80).toByte() // QR = response, keep opcode/RD
    dns[3] = (0x80 or 3).toByte() // RA set, RCODE = NXDOMAIN
    for (k in 6..11) dns[k] = 0 // an/ns/ar counts = 0
    return udpReply(query, ihl, dns, dns.size)
}

fun udpReply(query: ByteArray, ihl: Int, payload: ByteArray, plen: Int): ByteArray {
    val total = 28 + plen
    val p = ByteArray(total)
    p[0] = 0x45
    p[2] = (total ushr 8).toByte(); p[3] = total.toByte()
    p[8] = 64 // TTL
    p[9] = 17 // UDP
    System.arraycopy(query, 16, p, 12, 4) // src ip = query dst
    System.arraycopy(query, 12, p, 16, 4) // dst ip = query src
    var sum = 0
    var i = 0
    while (i < 20) {
        sum += ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
        i += 2
    }
    while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum ushr 16)
    val ck = sum.inv() and 0xFFFF
    p[10] = (ck ushr 8).toByte(); p[11] = ck.toByte()
    p[20] = query[ihl + 2]; p[21] = query[ihl + 3] // src port = query dst port
    p[22] = query[ihl]; p[23] = query[ihl + 1] // dst port = query src port
    val ulen = 8 + plen
    p[24] = (ulen ushr 8).toByte(); p[25] = ulen.toByte()
    // udp checksum 0 = "none", legal for IPv4
    System.arraycopy(payload, 0, p, 28, plen)
    return p
}
