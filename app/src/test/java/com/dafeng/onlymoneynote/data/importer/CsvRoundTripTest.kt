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

    /* ------------------------- 报销往返 ------------------------- */

    /** 带「报销日期」列的新表头（跟 [com.dafeng.onlymoneynote.data.exporter.CsvExporter] 一致） */
    private val reimbHeader =
        "日期,一级分类,二级分类,收支,金额,备注,报销,报销日期,账户,账户→,转出账户,转入账户"

    @Test
    fun `已报销的行报销状态和日期都能带回来`() {
        val csv = buildString {
            append('\uFEFF')
            append(reimbHeader).append('\n')
            append("2026/09/20 14:00,日常,其他,支出,88.00,出差垫付,已报销,2026/09/27 10:00,支付宝,,,\n")
        }
        val rows = RecordsCsvImporter.parse(csv)
        assertEquals(1, rows.size)
        val r = rows.first()
        assertTrue("应该已报销", r.reimbursed)
        assertTrue("应该留在报销池里", r.isReimburse)
        assertEquals(8800L, r.cents)
        // 报销日期必须真落库：冲销行备注里的「报销于 xxx」就是它
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
        assertEquals("2026-09-27", f.format(java.util.Date(r.reimburseDateMillis)))
    }

    @Test
    fun `待报销的行不能被当成已报销也不能丢出报销池`() {
        val csv = buildString {
            append('\uFEFF')
            append(reimbHeader).append('\n')
            append("2026/09/20 14:00,日常,其他,支出,50.00,,待报销,,支付宝,,,\n")
        }
        val r = RecordsCsvImporter.parse(csv).first()
        assertTrue("还没报，不能是已报销", !r.reimbursed)
        assertTrue("但必须在报销池里", r.isReimburse)
        assertEquals("还没报就没有报销日期", 0L, r.reimburseDateMillis)
    }

    @Test
    fun `老表写「是」等价于已报销且补上报销池归属`() {
        // 用户手上已有的导出文件里，报销列写的是「是」。以前只判成 reimbursed，
        // 池子归属 false → 恢复后这笔账在报销页一个 tab 都不出现。
        val csv = buildString {
            append('\uFEFF')
            append("日期,分类名称,收支,金额,备注,账户,报销\n")
            append("2026/09/20 14:00,家用,支出,88.00,出差垫付,支付宝,是\n")
        }
        val r = RecordsCsvImporter.parse(csv).first()
        assertTrue(r.reimbursed)
        assertTrue(r.isReimburse)
    }

    @Test
    fun `冲销行不会被重复导入`() {
        // 冲销行是导出时生成的负支出行。原单标了已报销的话 importPlan 会再配一条，
        // CSV 里这条要是也导进去就变成两条冲销 —— 支出被扣两次。
        val csv = buildString {
            append('\uFEFF')
            append(reimbHeader).append('\n')
            append("2026/09/20 14:00,日常,其他,支出,88.00,出差垫付,已报销,2026/09/27 10:00,支付宝,,,\n")
            append("2026/09/20 14:00,日常,其他,支出,-88.00,报销冲销#123（报销于 2026-09-27）,已报销,2026/09/27 10:00,支付宝,,,\n")
        }
        val rows = RecordsCsvImporter.parse(csv)
        assertEquals("冲销行必须被跳过", 1, rows.size)
        assertEquals(8800L, rows.first().cents)
    }

    @Test
    fun `完全没有报销列的表不影响其他字段`() {
        val csv = buildString {
            append('\uFEFF')
            append("日期,分类名称,收支,金额,备注,账户\n")
            append("2026/09/20 14:00,家用,支出,88.00,出差垫付,支付宝\n")
        }
        val r = RecordsCsvImporter.parse(csv).first()
        assertTrue(!r.reimbursed)
        assertTrue(!r.isReimburse)
        assertEquals(8800L, r.cents)
    }
}
