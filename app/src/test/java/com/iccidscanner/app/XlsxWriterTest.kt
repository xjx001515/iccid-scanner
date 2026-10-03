package com.iccidscanner.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class XlsxWriterTest {

    @Test
    fun writesStyledWorkbookWithTextCells() {
        val file = File("build/test-output/sample.xlsx").apply { parentFile!!.mkdirs() }
        file.outputStream().use {
            XlsxWriter.write(
                it,
                title = "ICCID 扫描记录",
                subtitle = "导出时间 2026-10-03 10:30　·　共 3 条",
                columns = listOf(
                    XlsxWriter.Column("序号", 8.0),
                    XlsxWriter.Column("ICCID（数字）", 28.0, XlsxWriter.Kind.MONO),
                    XlsxWriter.Column("字母", 8.0, XlsxWriter.Kind.ACCENT),
                    XlsxWriter.Column("扫描时间", 22.0),
                ),
                rows = listOf(
                    listOf(1, "8986012580125842493", "M", "2026-10-03 10:00:00"),
                    listOf(2, "8986012580125842494", "", "2026-10-03 10:00:05"),
                    listOf(3, "8986012580125842495", "N", "2026-10-03 10:00:09"),
                ),
            )
        }
        ZipFile(file).use { zip ->
            val sheet = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).reader().readText()
            assertTrue(sheet.contains("""t="inlineStr"><is><t>8986012580125842493</t>"""))
            assertTrue(sheet.contains("""<mergeCell ref="A1:D1"/>"""))
            assertTrue(sheet.contains("""<c r="C5" s="9"/>"""))
        }
    }
}
