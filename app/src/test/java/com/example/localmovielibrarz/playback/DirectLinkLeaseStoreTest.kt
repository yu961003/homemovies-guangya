package com.example.localmovielibrarz.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 租约表的行为测试。时钟一律由构造参数注入，测试不依赖真实时间流逝，
 * 因此「1 毫秒边界」这类断言在慢速 CI 上也不会抖动。
 */
class DirectLinkLeaseStoreTest {

    private var now = 1_724_000_000_000L

    private fun store(leadMillis: Long = 60_000L): DirectLinkLeaseStore =
        DirectLinkLeaseStore(clock = { now }, renewalLeadMillis = leadMillis)

    private fun lease(ttlMillis: Long = 300_000L, label: String = "115"): DirectLinkLease =
        DirectLinkLease.leaseFromNow(
            url = "https://cdn.example.com/$label.mp4?signature=abc",
            sourceLabel = label,
            ttlMillis = ttlMillis,
            now = now
        )

    @Test
    fun `fresh lease is readable through get and available`() {
        val store = store()
        val lease = lease(ttlMillis = 120_000)

        store.put("pickcode-1", lease)

        assertSame(lease, store.get("pickcode-1"))
        assertSame(lease, store.available("pickcode-1"))
        assertFalse(lease.isExpired(now))
        assertEquals(120_000L, lease.remainingMillis(now))
        assertEquals(now + 120_000L, lease.expiresAtMillis)
    }

    @Test
    fun `expiry boundary is inclusive at the expiry millisecond`() {
        val lease = lease(ttlMillis = 5_000)

        assertFalse(lease.isExpired(now + 4_999))
        assertTrue(lease.isExpired(now + 5_000))
        assertEquals(-1L, lease.remainingMillis(now + 5_001))
    }

    @Test
    fun `expired lease stops being available and get prunes it`() {
        val store = store()
        val fresh = lease(ttlMillis = 600_000, label = "guangya_res")
        store.put("old", lease(ttlMillis = 1_000))
        store.put("fresh", fresh)

        now += 1_000

        assertNull(store.available("old"))
        assertNull(store.get("old"))
        assertSame(fresh, store.available("fresh"))
        assertEquals(setOf("fresh"), store.snapshot().keys)
    }

    @Test
    fun `needsRenewal uses an inclusive lead boundary and includes expired leases`() {
        val lease = lease(ttlMillis = 60_000)

        assertTrue(lease.needsRenewal(now, leadMillis = 60_000))
        assertFalse(lease.needsRenewal(now, leadMillis = 59_999))
        assertTrue(lease.needsRenewal(now + 1, leadMillis = 59_999))
        assertTrue(lease.needsRenewal(now + 60_000, leadMillis = 60_000))
        assertTrue(lease.needsRenewal(now + 60_001, leadMillis = 60_000))
    }

    @Test
    fun `negative lead cannot suppress renewal of an expired lease`() {
        val lease = lease(ttlMillis = 10_000)

        assertFalse(lease.needsRenewal(now, leadMillis = -5_000))
        assertTrue(lease.needsRenewal(now + 10_000, leadMillis = -5_000))
        assertTrue(lease.needsRenewal(now + 60_000, leadMillis = -5_000))
    }

    @Test
    fun `invalidate removes only the requested key`() {
        val store = store()
        store.put("a", lease(label = "115"))
        store.put("b", lease(label = "guangya_res"))

        store.invalidate("a")

        assertNull(store.available("a"))
        assertEquals("guangya_res", store.available("b")?.sourceLabel)
        assertEquals(setOf("b"), store.snapshot().keys)
    }

    @Test
    fun `clear drops every lease`() {
        val store = store()
        store.put("a", lease())
        store.put("b", lease())

        store.clear()

        assertNull(store.get("a"))
        assertNull(store.available("b"))
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun `put overwrites the previous lease for the same key`() {
        val store = store()
        store.put("k", lease(ttlMillis = 10_000, label = "115"))
        store.put("k", lease(ttlMillis = 20_000, label = "guangya_res"))

        val current = store.available("k")

        assertEquals("guangya_res", current?.sourceLabel)
        assertEquals(20_000L, current?.remainingMillis(now))
        assertEquals(1, store.snapshot().size)
    }

    @Test
    fun `snapshot is detached from the store`() {
        val store = store()
        val live = lease()
        store.put("k", live)

        val snapshot = store.snapshot()

        assertEquals(mapOf("k" to live), snapshot)
        store.invalidate("k")
        assertEquals(setOf("k"), snapshot.keys)
        assertNull(store.available("k"))
    }

    @Test
    fun `snapshot prunes expired entries without a prior get`() {
        val store = store()
        store.put("stale", lease(ttlMillis = 1_000))

        now += 2_000

        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun `expiry follows the injected clock`() {
        var fakeNow = 10_000L
        val store = DirectLinkLeaseStore(clock = { fakeNow })
        store.put(
            "k",
            DirectLinkLease(
                url = "https://cdn.example.com/video.mp4",
                issuedAtMillis = fakeNow,
                expiresAtMillis = fakeNow + 1_000,
                sourceLabel = "115"
            )
        )

        assertEquals("https://cdn.example.com/video.mp4", store.available("k")?.url)
        fakeNow += 999
        assertEquals("https://cdn.example.com/video.mp4", store.available("k")?.url)
        fakeNow += 1
        assertNull(store.available("k"))
    }

    @Test
    fun `store level renewal check uses the configured lead and treats a missing key as renewal due`() {
        val store = store(leadMillis = 60_000)

        assertTrue(store.needsRenewal("missing", now))

        store.put("k", lease(ttlMillis = 61_000))
        assertFalse(store.needsRenewal("k", now))

        store.put("k", lease(ttlMillis = 60_000))
        assertTrue(store.needsRenewal("k", now))

        store.put("k", lease(ttlMillis = 1_000))
        assertTrue(store.needsRenewal("k", now + 5_000))
    }
}
