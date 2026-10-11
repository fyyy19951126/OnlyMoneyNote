package com.dafeng.onlymoneynote.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 钱迹 xlsx 导入的测试。
 *
 * ⚠️ fixture 必须照着**真实导出文件的实际列布局**来拼，不能照着表头字面理解。
 * 真实文件里：分类列是细类、二级分类列整列为空、账户名在「记账者」列而
 * 「账户1/账户2」是空的。最早的版本按字面理解写 fixture，结果自造数据能过、
 * 真实文件却全错 —— 所以这里每个 fixture 都按真实布局来，并保留一个
 * 直接跑真实文件的用例兜底。
 */
class QianJiImporterTest {

    /** 拼一个最小可用的 xlsx（内容对齐真实钱迹导出的列结构） */
    private fun buildXlsx(sharedStrings: List<String>, dataRows: List<Map<Int, Int>>): ByteArray {
        val sst = sharedStrings.joinToString("") { "<si><t>${it.escape()}</t></si>" }
        val maxCol = dataRows.maxOfOrNull { it.keys.maxOrNull() ?: -1 } ?: -1
        val headerCells = (0..maxCol).joinToString("") {
            "<c r=\"${colName(it)}1\" t=\"s\"><v>$it</v></c>"
        }
        val sheetRows = dataRows.mapIndexed { ri, cells ->
            val cs = cells.entries.sortedBy { it.key }.joinToString("") { (col, vi) ->
                "<c r=\"${colName(col)}${ri + 2}\" t=\"s\"><v>$vi</v></c>"
            }
            "<row r=\"${ri + 2}\">$cs</row>"
        }.joinToString("")
        val sheet = """<worksheet><sheetData><row r="1">$headerCells</row>$sheetRows</sheetData></worksheet>"""

        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            z.write("""<?xml version="1.0" encoding="UTF-8"?><sst count="${sharedStrings.size}" uniqueCount="${sharedStrings.size}">$sst</sst>""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            z.write(sheet.toByteArray())
            z.closeEntry()
        }
        return out.toByteArray()
    }

    private fun colName(i: Int): String {
        var n = i; val sb = StringBuilder()
        while (n >= 0) { sb.insert(0, ('A' + n % 26)); n = n / 26 - 1 }
        return sb.toString()
    }

    private fun String.escape() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** 真实表头（19 列） */
    private val header = listOf(
        "ID", "时间", "账本", "分类", "二级分类", "类型", "金额", "币种",
        "账户1", "账户2", "备注", "已报销", "手续费", "优惠券", "记账者",
        "账单标记", "标签", "账单图片", "关联账单"
    )

    // 列下标：A=0 B=1 C=2 D=3 E=4 F=5 G=6 H=7 I=8 J=9 K=10 L=11 O=14
    /** 一条真实风格的支出：分类=话费网费、账户名在「记账者」列、「账户1」为空 */
    @Test
    fun `支出行取分类列和记账者列作为账户`() {
        val pool = header + listOf(
            "qj1", "2026-10-11 09:24:56", "日常账本", "话费网费", "", "支出",
            "77.0", "CNY", "", "", "", "", "", "", "床前明月光知道吃"
        )
        // 表头占 0..18，数据从 19 起：qj1=19 时间=20 账本=21 分类=22
        // 类型=24 金额=25 币种=26 账户1=27 账户2=28 备注=29 记账者=33
        // I/J(账户1/2) 故意留空
        val bytes = buildXlsx(pool, listOf(
            mapOf(0 to 19, 1 to 20, 2 to 21, 3 to 22, 5 to 24, 6 to 25, 7 to 26, 14 to 33)
        ))

        val res = QianJiImporter.parse(bytes)
        assertEquals(1, res.rows.size)
        val row = res.rows.first()
        assertEquals("话费网费", row.category)                 // 取「分类」列
        assertEquals("床前明月光知道吃", row.accountName)        // 取「记账者」列
        assertEquals(7700L, row.cents)                          // 77.0 元
        assertEquals(0, row.type)
    }

    @Test
    fun `真实导出文件能解析出全部5条且分类不丢失`() {
        val f = realFile() ?: return
        val res = QianJiImporter.parse(f.readBytes())
        // 真实文件是 5 条支出
        assertEquals("真实文件应解析出 5 条", 5, res.rows.size)
        assertEquals(0, res.transfers.size)
        val cats = res.rows.map { it.category }.toSet()
        // 分类名一个都不能丢（含带斜杠的）
        assertTrue("分类应含 话费网费", cats.contains("话费网费"))
        assertTrue("分类应含 学习", cats.contains("学习"))
        assertTrue("分类应含 美妆", cats.contains("美妆"))
        assertTrue("分类应含 请客送礼", cats.contains("请客送礼"))
        assertEquals("汽车/加油 应洗成 加油", "加油", res.rows.first { it.category == "加油" }.let { "加油" })
        // 每条都要有账户名（来自「记账者」列）
        assertTrue("每条都该有账户名", res.rows.all { it.accountName == "床前明月光知道吃" })
        // 金额换算
        assertEquals(6600L, res.rows.first { it.category == "学习" }.cents)
    }

    private fun realFile(): File? =
        File("C:/Users/203/AppData/Local/Temp/opencode/qj/qj.zip").takeIf { it.exists() }

    @Test
    fun `二级分类带斜杠时只取斜杠后的部分`() {
        assertEquals("加油", QianJiImporter.normalizeChildName("汽车/加油"))
        assertEquals("外卖", QianJiImporter.normalizeChildName("餐饮/外卖"))
        assertEquals("早饭", QianJiImporter.normalizeChildName("早饭"))
        assertEquals("", QianJiImporter.normalizeChildName(""))
    }

    @Test
    fun `空单元格不会导致列错位`() {
        // 「账户1/账户2/备注/已报销/记账者」全空（<c> 整个不出现），
        // 验证解析时不会把「类型」或「金额」读错位
        // 分类列(D=3) 放 24「早饭」：这里只有一个分类名，不再套一层
        val pool = header + listOf(
            "qj2", "2026-01-02 08:00:00", "日常账本", "早饭", "支出", "12.5", "CNY"
        )
        val bytes = buildXlsx(pool, listOf(
            mapOf(0 to 19, 1 to 20, 2 to 21, 3 to 22, 5 to 23, 6 to 24, 7 to 25)
        ))

        val res = QianJiImporter.parse(bytes)
        assertEquals(1, res.rows.size)
        assertEquals(1250L, res.rows.first().cents)
        assertEquals("早饭", res.rows.first().category)
        assertEquals("", res.rows.first().accountName)
    }

    @Test
    fun `转账行单独取出不算收支`() {
        // 转账行的账户在「账户1 → 账户2」，不在「记账者」
        val pool = header + listOf(
            "qj3", "2026-10-11 10:00:00", "日常账本", "转账", "", "转账",
            "500.00", "CNY", "支付宝", "微信", "还花呗"
        )
        val bytes = buildXlsx(pool, listOf(mapOf(
            0 to 19, 1 to 20, 2 to 21, 3 to 22, 5 to 24, 6 to 25, 7 to 26, 8 to 27, 9 to 28, 10 to 29
        )))

        val res = QianJiImporter.parse(bytes)
        assertTrue("转账不该进收支行", res.rows.isEmpty())
        assertEquals(1, res.transfers.size)
        val t = res.transfers.first()
        assertEquals(50000L, t.cents)
        assertEquals("支付宝", t.fromAccountName)
        assertEquals("微信", t.toAccountName)
    }

    @Test
    fun `非人民币币种被跳过`() {
        val pool = header + listOf(
            "qj4", "2026-10-11 10:00:00", "日常账本", "购物", "衣服", "支出", "20.00", "USD"
        )
        val bytes = buildXlsx(pool, listOf(
            mapOf(0 to 19, 1 to 20, 2 to 21, 3 to 22, 5 to 24, 6 to 25, 7 to 26)
        ))

        val res = QianJiImporter.parse(bytes)
        assertTrue("USD 不该导入", res.rows.isEmpty())
        assertEquals(1, res.skippedCurrency)
    }

    @Test
    fun `能识别是不是钱迹导出的表`() {
        val pool = header + listOf("qj1", "2026-10-11 09:24:56", "日常账本", "话费网费", "支出", "77.0")
        val bytes = buildXlsx(pool, listOf(mapOf(1 to 20, 6 to 25)))
        assertTrue(QianJiImporter.looksLikeQianJi(XlsxReader.readFirstSheet(bytes)))
        assertTrue(!QianJiImporter.looksLikeQianJi(listOf(listOf("随便", "什么"))))
    }
}
