package dev.specflow.slipstream

import dev.specflow.slipstream.ui.formatBytes
import dev.specflow.slipstream.ui.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `small counts stay in bytes`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun `larger counts step up a unit at a time`() {
        assertEquals("1.00 KB", formatBytes(1024))
        assertEquals("1.00 MB", formatBytes(1024L * 1024))
        assertEquals("1.00 GB", formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun `precision falls away as the number grows`() {
        // Three significant figures throughout, so the column stays a column.
        assertEquals("9.77 KB", formatBytes(10_000))
        assertEquals("97.7 KB", formatBytes(100_000))
        assertEquals("977 KB", formatBytes(1_000_000))
    }

    @Test
    fun `durations show hours only once there are hours`() {
        assertEquals("0:09", formatDuration(9))
        assertEquals("1:00", formatDuration(60))
        assertEquals("59:59", formatDuration(3599))
        assertEquals("1:00:00", formatDuration(3600))
        assertEquals("2:03:04", formatDuration(2 * 3600 + 3 * 60 + 4))
    }
}
