package com.ztc1997.nocturne

import com.ztc1997.nocturne.core.ConfigKeys
import com.ztc1997.nocturne.core.ConfigSnapshot
import com.ztc1997.nocturne.core.SleepWindow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** SleepWindow 纯逻辑单测：时段边界、跨午夜、覆盖优先级。 */
class SleepWindowTest {

    private fun at(hour: Int, minute: Int): Long {
        val base = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return base.timeInMillis
    }

    @Test
    fun `跨午夜窗口 23点到7点`() {
        assertTrue(SleepWindow.isInWindow("23:00", "07:00", at(23, 0)))
        assertTrue(SleepWindow.isInWindow("23:00", "07:00", at(2, 30)))
        assertTrue(SleepWindow.isInWindow("23:00", "07:00", at(6, 59)))
        assertFalse(SleepWindow.isInWindow("23:00", "07:00", at(7, 0)))
        assertFalse(SleepWindow.isInWindow("23:00", "07:00", at(22, 59)))
    }

    @Test
    fun `同一天窗口 1点到3点`() {
        assertFalse(SleepWindow.isInWindow("01:00", "03:00", at(0, 59)))
        assertTrue(SleepWindow.isInWindow("01:00", "03:00", at(1, 0)))
        assertTrue(SleepWindow.isInWindow("01:00", "03:00", at(2, 0)))
        assertFalse(SleepWindow.isInWindow("01:00", "03:00", at(3, 0)))
    }

    @Test
    fun `总开关关闭时不屏蔽`() {
        val cfg = ConfigSnapshot(enabled = false, windowStart = "23:00", windowEnd = "07:00")
        assertFalse(SleepWindow.shouldMask(cfg, at(2, 0), dndActive = true))
    }

    @Test
    fun `强制显示优先于时段和勿扰`() {
        val cfg = ConfigSnapshot(
            manualOverride = ConfigKeys.OVERRIDE_OFF,
            scheduleEnabled = true,
            windowStart = "23:00", windowEnd = "07:00",
            linkDnd = true,
        )
        assertFalse(SleepWindow.shouldMask(cfg, at(2, 0), dndActive = true))
    }

    @Test
    fun `强制屏蔽优先于关时段`() {
        val cfg = ConfigSnapshot(
            manualOverride = ConfigKeys.OVERRIDE_ON,
            scheduleEnabled = false,
        )
        assertTrue(SleepWindow.shouldMask(cfg, at(12, 0), dndActive = false))
    }

    @Test
    fun `勿扰联动`() {
        val cfg = ConfigSnapshot(
            scheduleEnabled = false,
            linkDnd = true,
            windowStart = "23:00", windowEnd = "07:00",
        )
        assertTrue(SleepWindow.shouldMask(cfg, at(12, 0), dndActive = true))
        assertFalse(SleepWindow.shouldMask(cfg, at(12, 0), dndActive = false))
    }

    @Test
    fun `非法时段解析失败返回null`() {
        assertTrue(SleepWindow.parseHhMm("25:00") == null)
        assertTrue(SleepWindow.parseHhMm("12:60") == null)
        assertTrue(SleepWindow.parseHhMm("bad") == null)
        assertTrue(SleepWindow.parseHhMm("23:00") == 23 * 60)
    }
}
