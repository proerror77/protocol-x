package com.mandonothing.ops

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalCountdownTest {
    private val start = Instant.parse("2026-11-23T02:33:00Z")
    private val end = Instant.parse("2026-11-23T03:03:00Z")

    @Test
    fun showsRangeRemainingAndOverdue() {
        assertEquals("预计还有 13–43 分钟",
            ArrivalCountdown.lines(start, end, null, null, false, Instant.parse("2026-11-23T02:20:00Z")).first)
        assertEquals("预计 23 分钟内可会合",
            ArrivalCountdown.lines(start, end, null, null, false, Instant.parse("2026-11-23T02:40:00Z")).first)
        assertEquals("已超过估算区间", ArrivalCountdown.lines(start, end, null, null, false, end).first)
    }

    @Test
    fun expiredForecastAndOnsiteConfirmationRemainDistinct() {
        val now = Instant.parse("2026-11-23T02:30:00Z")
        val stale = ArrivalCountdown.lines(start, end, now.minusSeconds(1), null, false, now)
        assertEquals("机场预计时间待更新", stale.first)
        assertTrue(stale.second.contains("10:33–11:03 HKT"))
        assertEquals("已会合", ArrivalCountdown.lines(start, end, null, now, false, now).first)
        assertEquals("航班已取消", ArrivalCountdown.lines(start, end, null, null, true, now).first)
    }
}
