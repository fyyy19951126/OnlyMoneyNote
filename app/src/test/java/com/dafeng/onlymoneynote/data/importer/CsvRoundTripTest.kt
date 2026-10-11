package com.dafeng.onlymoneynote.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV 往返测试：导出的文件必须能原样导回来。
 *
 * 起因是一个真实的静默数据丢失：导出侧给转账写「转账」，
 * 导入侧的 `when (at(iType))` 只认 支出/收入，其余 `return@forEach` 跳过 ——
 * **自己导出的文件自己导不回来，转账全没了，而且没有任何提示**。
 * 而 [com.dafeng.onlymoneynote.data.exporter.CsvExporter] 的头注释却写着
 * 「导出的文件能原样再导回本 App」。这句话在修好之前是假的。
 */
class CsvRoundTripTest {

    private val header = "日期,一级分类,二级分类,收支,金额,备注,报销,账户,账户→,转出账户,转入账户"

    @Test
    fun `导出的转账行再导回来不丢`() {
        val csv = buildString {
            append('\uFEFF')
            append(header).append('\n')
            append("2026/10/01 12:00,日常,早饭,支出,35.50,,,支付宝,,,\n")
            append("2026/10/02 09:00,,转账,转账,500.00,还花呗,,工行 → 微信,工行 → 微信,工行,微信\n")
            append("2026/10/03 18:00,职业收入,月工资,收入,8000.00,,,工行,,,\n")
        }

        val (rows, transfers) = RecordsCsvImporter.parseWithTransfers(csv)

        assertEquals("两条收支", 2, rows.size)
        assertEquals("转账不能被丢掉", 1, transfers.size)
        val t = transfers.first()
        assertEquals(50000L, t.cents)
        assertEquals("工行", t.fromAccountName)
        assertEquals("微信", t.toAccountName)
        assertEquals("还花呗", t.note)
    }

    @Test
    fun `收支行的转账专用列是空的不会误判`() {
        val csv = buildString {
            append('\uFEFF')
            append(header).append('\n')
            append("2026/10/01 12:00,日常,早饭,支出,35.50,,,支付宝,,,\n")
        }
        val (rows, transfers) = RecordsCsvImporter.parseWithTransfers(csv)
        assertEquals(1, rows.size)
        assertTrue("没有转账行", transfers.isEmpty())
        assertEquals("支付宝", rows.first().accountName)
    }

    @Test
    fun `老格式CSV（没有转账专用列）也能正常导入`() {
        // 这是用户手上已有的导出文件，不能因为加了列就导不进来
        val csv = buildString {
            append('\uFEFF')
            append("日期,一级分类,二级分类,收支,金额,备注,报销,账户\n")
            append("2026/10/01 12:00,日常,早饭,支出,35.50,,,支付宝\n")
        }
        val (rows, transfers) = RecordsCsvImporter.parseWithTransfers(csv)
        assertEquals(1, rows.size)
        assertEquals(3550L, rows.first().cents)
        assertTrue(transfers.isEmpty())
    }

    @Test
    fun `只有箭头没有独立列时也能拆出两边`() {
        val csv = buildString {
            append('\uFEFF')
            append("日期,一级分类,二级分类,收支,金额,备注,报销,账户,账户→\n")
            append("2026/10/02 09:00,,转账,转账,500.00,,,工行 → 微信,工行 → 微信\n")
        }
        val (_, transfers) = RecordsCsvImporter.parseWithTransfers(csv)
        assertEquals(1, transfers.size)
        assertEquals("工行", transfers.first().fromAccountName)
        assertEquals("微信", transfers.first().toAccountName)
    }
}
