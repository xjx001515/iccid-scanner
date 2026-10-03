package com.iccidscanner.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IccidParserTest {

    private val expected = "8986012580125842493M"

    @Test
    fun barcode() {
        assertEquals(expected, IccidParser.fromBarcode("8986012580125842493M"))
        assertEquals(expected, IccidParser.fromBarcode(" 8986 0125 8012 5842 493m "))
        assertEquals("8986012580125842493", IccidParser.fromBarcode("8986012580125842493"))
        assertNull(IccidParser.fromBarcode("https://u.10010.cn/xxxx"))
        assertNull(IccidParser.fromBarcode("6901234567892"))
    }

    @Test
    fun ocrLineFromCard() {
        assertEquals(expected, IccidParser.fromOcrText("ICCID: 8986 0125 8012 5842 493M"))
        assertEquals(expected, IccidParser.fromOcrText("8986 0125 8012 5842 493M"))
    }

    @Test
    fun ocrFixesLookalikeCharacters() {
        assertEquals(expected, IccidParser.fromOcrText("1CCID: 8986 O125 8O12 5842 493M"))
        assertEquals(expected, IccidParser.fromOcrText("ICCID: 898G 0I25 8012 S842 493M"))
    }

    @Test
    fun ocrChipTextJoinedAcrossLines() {
        // 芯片上印成 4 行：89860 / 12580 / 12584 / 2493M
        assertEquals(expected, IccidParser.fromOcrText("5Gn128kUSIM89860125801258424 93M"))
    }

    @Test
    fun ocrRejectsIncompleteOrNoise() {
        assertNull(IccidParser.fromOcrText("ICCID: 8986 0125 8012 5842 49"))   // 漏读
        assertNull(IccidParser.fromOcrText("ICCID: 8986 0125 8012 5842 4493M")) // 多读一位
        assertNull(IccidParser.fromOcrText("PUK: N"))
        assertNull(IccidParser.fromOcrText("下载中国联通APP，畅享便捷智慧服务"))
    }

    @Test
    fun splitDigitsAndLetter() {
        assertEquals("8986012580125842493" to "M", IccidParser.split(expected))
        assertEquals("8986012580125842493" to "", IccidParser.split("8986012580125842493"))
        assertEquals("89860125801258424930" to "", IccidParser.split("89860125801258424930"))
    }

    @Test
    fun pretty() {
        assertEquals("8986 0125 8012 5842 493M", IccidParser.pretty(expected))
    }
}
