package com.example.localmovielibrarz.ui.logs

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrapeLogExportTest {
    @Test
    fun `export file name includes selected date`() {
        assertEquals(
            "鸭影-刮削日志-2026-08-02.txt",
            logExportFileName("2026-08-02")
        )
    }

    @Test
    fun `export file name handles missing date`() {
        assertEquals(
            "鸭影-刮削日志-未知日期.txt",
            logExportFileName("")
        )
    }
}
