package com.max.focus2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class EngineTest {

    private fun dnsQuery(host: String): ByteArray {
        val out = ArrayList<Byte>()
        repeat(12) { out.add(if (it == 5) 1 else 0) } // header, qdcount=1
        host.split('.').forEach { label ->
            out.add(label.length.toByte())
            label.forEach { out.add(it.code.toByte()) }
        }
        out.add(0)
        repeat(4) { out.add(0) } // qtype/qclass
        return out.toByteArray()
    }

    @Test
    fun parsesQueryName() {
        assertEquals("example.com", parseQName(dnsQuery("Example.COM")))
        assertNull(parseQName(ByteArray(5)))
    }

    @Test
    fun cheatWindows() {
        // Mon 10:00-11:00
        Engine.windows = listOf(CheatWindow(1, 600, 660, 1))
        val mon = LocalDateTime.of(2026, 6, 29, 10, 30) // a Monday
        assertTrue(Engine.cheatActive(mon))
        assertFalse(Engine.cheatActive(mon.withHour(11).withMinute(30)))
        assertFalse(Engine.cheatActive(mon.plusDays(1))) // Tuesday

        // overnight Tue 23:00 - 01:00 -> active Tue 23:30 and Wed 00:30
        Engine.windows = listOf(CheatWindow(2, 23 * 60, 60, 1 shl 1))
        assertTrue(Engine.cheatActive(LocalDateTime.of(2026, 6, 30, 23, 30)))
        assertTrue(Engine.cheatActive(LocalDateTime.of(2026, 7, 1, 0, 30)))
        assertFalse(Engine.cheatActive(LocalDateTime.of(2026, 7, 1, 1, 30)))
        Engine.windows = emptyList()
    }

    @Test
    fun siteMatchingAndVerdicts() {
        Engine.items = listOf(BlockItem("tiktok.com", TYPE_SITE, "tiktok.com", 0))
        Engine.usage = emptyMap()
        assertEquals(VERDICT_BLOCK, Engine.siteVerdict("tiktok.com", 0))
        assertEquals(VERDICT_BLOCK, Engine.siteVerdict("www.tiktok.com", 0))
        assertEquals(VERDICT_ALLOW, Engine.siteVerdict("nottiktok.com", 0))
        assertEquals(VERDICT_BLOCK, Engine.siteVerdict("dns.google", 0)) // DoH always blocked

        // allowance: first hit starts the clock, expires after N minutes
        Engine.items = listOf(BlockItem("x.com", TYPE_SITE, "x.com", 10))
        assertEquals(VERDICT_START, Engine.siteVerdict("x.com", 1000L))
        val today = Engine.today()
        Engine.usage = mapOf("x.com|$today" to Usage("x.com", today, 0, 1000L))
        assertEquals(VERDICT_ALLOW, Engine.siteVerdict("x.com", 1000L + 5 * 60_000))
        assertEquals(VERDICT_BLOCK, Engine.siteVerdict("x.com", 1000L + 11 * 60_000))
        Engine.items = emptyList()
        Engine.usage = emptyMap()
    }

    @Test
    fun normalizesHosts() {
        assertEquals("tiktok.com", normalizeHost(" https://www.TikTok.com/foo?x=1 "))
        assertEquals("tiktok.com", normalizeHost("tiktok.com"))
    }
}
