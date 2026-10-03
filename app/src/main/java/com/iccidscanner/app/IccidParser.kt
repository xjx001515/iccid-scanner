package com.iccidscanner.app

/**
 * ICCID 识别与校验。
 *
 * 中国的 ICCID 以 8986 开头，卡板上印的一般是 20 位，部分运营商末位印的是字母（如 "…493M"）。
 * 条码自带校验，结果可信，19~20 位都接受；文字识别（OCR）容易丢字，只接受完整的 20 位。
 */
object IccidParser {

    const val SOURCE_BARCODE = "barcode"
    const val SOURCE_OCR = "ocr"
    const val SOURCE_BARCODE_OCR = "barcode+ocr"
    const val SOURCE_MANUAL = "manual"

    private val VALID = Regex("^89\\d{16,17}[0-9A-Z]$")
    private val VALID_OCR = Regex("^89\\d{17}[0-9A-Z]$")

    /** OCR 常把数字认成形状相近的字母，前 19 位只可能是数字，按这张表纠正。 */
    private val DIGIT_FIXES = mapOf(
        'O' to '0', 'D' to '0', 'Q' to '0', 'U' to '0',
        'I' to '1', 'L' to '1', '|' to '1',
        'Z' to '2', 'S' to '5', 'G' to '6', 'B' to '8',
    )

    fun normalize(raw: String): String =
        raw.uppercase().filter { it in '0'..'9' || it in 'A'..'Z' }

    fun isValid(iccid: String): Boolean = VALID.matches(iccid)

    fun fromBarcode(raw: String): String? = normalize(raw).takeIf(::isValid)

    /** 从一行识别出的文字里取 ICCID，例如 "ICCID: 8986 0125 8012 5842 493M"。 */
    fun fromOcrText(text: String): String? {
        val upper = text.uppercase()
        val label = upper.indexOf("ICCID")
        val afterLabel = if (label >= 0) upper.substring(label + "ICCID".length) else upper
        val cleaned = afterLabel.filter { it in '0'..'9' || it in 'A'..'Z' || it == '|' }
        if (cleaned.length < 20) return null

        // ICCID 印在行尾，标签被认错（如 "1CCID"）时也能取到
        val candidate = cleaned.takeLast(20)
        val head = buildString {
            for (c in candidate.take(19)) {
                append(if (c.isDigit()) c else DIGIT_FIXES[c] ?: return null)
            }
        }
        return (head + candidate.last()).takeIf { VALID_OCR.matches(it) }
    }

    /** 拆成数字部分和末尾字母，如 "8986…493M" → ("8986…493", "M")；没有字母时第二项为空。 */
    fun split(iccid: String): Pair<String, String> {
        val digits = iccid.takeWhile { it.isDigit() }
        return digits to iccid.substring(digits.length)
    }

    /** 按 4 位一组显示，便于和卡板上的印刷对照。 */
    fun pretty(iccid: String): String = iccid.chunked(4).joinToString(" ")
}
