package com.iccidscanner.app

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 生成带样式的 .xlsx（Excel / WPS 都能打开），不依赖 Apache POI。
 * 版式：第 1 行标题、第 2 行说明、第 3 行表头（冻结），之后是数据，隔行底色。
 * 字符串一律按文本单元格写入，ICCID 不会被 Excel 显示成科学计数法。
 */
object XlsxWriter {

    enum class Kind { NORMAL, MONO, ACCENT }

    class Column(val title: String, val width: Double, val kind: Kind = Kind.NORMAL)

    private const val HEADER_ROW = 3

    // 对应 STYLES 里 cellXfs 的下标
    private const val S_TITLE = 1
    private const val S_SUBTITLE = 2
    private const val S_HEADER = 3
    private fun bodyStyle(kind: Kind, striped: Boolean): Int {
        val base = when (kind) {
            Kind.NORMAL -> 4
            Kind.MONO -> 6
            Kind.ACCENT -> 8
        }
        return if (striped) base + 1 else base
    }

    /** [rows] 中的 Number 写成数字单元格，null 或空串写成空的带格式单元格，其余写成文本。 */
    fun write(out: OutputStream, title: String, subtitle: String, columns: List<Column>, rows: List<List<Any?>>) {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", ROOT_RELS)
            put("xl/workbook.xml", WORKBOOK)
            put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            put("xl/styles.xml", STYLES)
            put("xl/worksheets/sheet1.xml", sheet(title, subtitle, columns, rows))
        }
    }

    private fun sheet(title: String, subtitle: String, columns: List<Column>, rows: List<List<Any?>>): String =
        buildString {
            val lastCol = colName(columns.lastIndex)
            val lastRow = HEADER_ROW + rows.size

            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            append("""<sheetViews><sheetView workbookViewId="0" showGridLines="0">""")
            append("""<pane ySplit="$HEADER_ROW" topLeftCell="A${HEADER_ROW + 1}" activePane="bottomLeft" state="frozen"/>""")
            append("""</sheetView></sheetViews>""")
            append("""<sheetFormatPr defaultRowHeight="20"/>""")
            append("<cols>")
            columns.forEachIndexed { i, c ->
                append("""<col min="${i + 1}" max="${i + 1}" width="${c.width}" customWidth="1"/>""")
            }
            append("</cols><sheetData>")

            appendRow(1, 32.0, listOf(title) + List(columns.size - 1) { null }) { S_TITLE }
            appendRow(2, 22.0, listOf(subtitle) + List(columns.size - 1) { null }) { S_SUBTITLE }
            appendRow(HEADER_ROW, 26.0, columns.map { it.title }) { S_HEADER }
            rows.forEachIndexed { i, row ->
                appendRow(HEADER_ROW + 1 + i, 22.0, row) { col -> bodyStyle(columns[col].kind, striped = i % 2 == 1) }
            }

            append("</sheetData>")
            append("""<mergeCells count="2"><mergeCell ref="A1:${lastCol}1"/><mergeCell ref="A2:${lastCol}2"/></mergeCells>""")
            append("""<printOptions horizontalCentered="1"/>""")
            append("""<pageMargins left="0.5" right="0.5" top="0.6" bottom="0.6" header="0.3" footer="0.3"/>""")
            if (rows.isNotEmpty()) {
                // 数字按文本存，关掉 Excel 的“以文本形式存储的数字”绿色角标
                append("""<ignoredErrors><ignoredError sqref="A${HEADER_ROW + 1}:$lastCol$lastRow" numberStoredAsText="1"/></ignoredErrors>""")
            }
            append("</worksheet>")
        }

    private inline fun StringBuilder.appendRow(rowNum: Int, height: Double, cells: List<Any?>, style: (col: Int) -> Int) {
        append("""<row r="$rowNum" ht="$height" customHeight="1">""")
        cells.forEachIndexed { col, value ->
            val ref = "${colName(col)}$rowNum"
            val s = style(col)
            when {
                value is Number -> append("""<c r="$ref" s="$s"><v>$value</v></c>""")
                value == null || value.toString().isEmpty() -> append("""<c r="$ref" s="$s"/>""")
                else -> append("""<c r="$ref" s="$s" t="inlineStr"><is><t>${escape(value.toString())}</t></is></c>""")
            }
        }
        append("</row>")
    }

    private fun colName(index: Int): String = ('A' + index).toString()

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private const val CONTENT_TYPES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
            """<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" +
            """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
            """</Types>"""

    private const val ROOT_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """</Relationships>"""

    private const val WORKBOOK =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            """<sheets><sheet name="ICCID" sheetId="1" r:id="rId1"/></sheets>""" +
            """</workbook>"""

    private const val WORKBOOK_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>""" +
            """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
            """</Relationships>"""

    private const val CENTER = """<alignment horizontal="center" vertical="center"/>"""
    private const val LEFT = """<alignment horizontal="left" vertical="center" indent="1"/>"""

    /*
     * 字体 0 正文 / 1 标题 / 2 说明（灰） / 3 表头（白粗） / 4 ICCID（等宽） / 5 字母（红粗）
     * 填充 0 无 / 1 gray125（规范要求占位）/ 2 表头蓝 / 3 隔行浅蓝
     * 边框 0 无 / 1 细线
     * cellXfs：0 默认 / 1 标题 / 2 说明 / 3 表头 / 4-5 正文 / 6-7 ICCID / 8-9 字母（后者为隔行底色）
     */
    private const val STYLES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<fonts count="6">""" +
            """<font><sz val="11"/><color rgb="FF333333"/><name val="微软雅黑"/><family val="2"/></font>""" +
            """<font><b/><sz val="16"/><color rgb="FF0D47A1"/><name val="微软雅黑"/><family val="2"/></font>""" +
            """<font><sz val="10"/><color rgb="FF757575"/><name val="微软雅黑"/><family val="2"/></font>""" +
            """<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="微软雅黑"/><family val="2"/></font>""" +
            """<font><sz val="12"/><color rgb="FF1A1A1A"/><name val="Consolas"/><family val="3"/></font>""" +
            """<font><b/><sz val="12"/><color rgb="FFC62828"/><name val="Consolas"/><family val="3"/></font>""" +
            """</fonts>""" +
            """<fills count="4">""" +
            """<fill><patternFill patternType="none"/></fill>""" +
            """<fill><patternFill patternType="gray125"/></fill>""" +
            """<fill><patternFill patternType="solid"><fgColor rgb="FF1565C0"/><bgColor indexed="64"/></patternFill></fill>""" +
            """<fill><patternFill patternType="solid"><fgColor rgb="FFEEF4FB"/><bgColor indexed="64"/></patternFill></fill>""" +
            """</fills>""" +
            """<borders count="2">""" +
            """<border><left/><right/><top/><bottom/><diagonal/></border>""" +
            """<border>""" +
            """<left style="thin"><color rgb="FFC9D6E8"/></left><right style="thin"><color rgb="FFC9D6E8"/></right>""" +
            """<top style="thin"><color rgb="FFC9D6E8"/></top><bottom style="thin"><color rgb="FFC9D6E8"/></bottom><diagonal/>""" +
            """</border>""" +
            """</borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="10">""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1">$LEFT</xf>""" +
            """<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1">$LEFT</xf>""" +
            """<xf numFmtId="0" fontId="3" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">$CENTER</xf>""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1">$CENTER</xf>""" +
            """<xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">$CENTER</xf>""" +
            """<xf numFmtId="0" fontId="4" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1">$CENTER</xf>""" +
            """<xf numFmtId="0" fontId="4" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">$CENTER</xf>""" +
            """<xf numFmtId="0" fontId="5" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1">$CENTER</xf>""" +
            """<xf numFmtId="0" fontId="5" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">$CENTER</xf>""" +
            """</cellXfs>""" +
            """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
            """</styleSheet>"""
}
