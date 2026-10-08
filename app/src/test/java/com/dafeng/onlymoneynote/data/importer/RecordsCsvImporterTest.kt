package com.dafeng.onlymoneynote.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * 导入器的解析与归类测试。
 *
 * 其中两个用例直接吃真实的「大象记账」导出文件（app/src/test/resources），
 * 用来兜住「换个文件就漏分类」这种问题 —— 表里没收录的名字会掉进兜底「其他」，
 * 让人一眼看出来。
 */
class RecordsCsvImporterTest {

    /* ------------------------- CSV 切分 ------------------------- */

    @Test
    fun `带引号逗号转义引号的字段能正确切分`() {
        val text = "\"a,b\",\"he said \"\"hi\"\"\",c\n1,2,3"
        val rows = RecordsCsvImporter.splitCsv(text)
        assertEquals(2, rows.size)
        assertEquals(listOf("a,b", "he said \"hi\"", "c"), rows[0])
        assertEquals(listOf("1", "2", "3"), rows[1])
    }

    @Test
    fun `空行不会被当成数据行`() {
        val rows = RecordsCsvImporter.splitCsv("A,B\n\n1,2\n\n")
        assertEquals(2, rows.size)
    }

    /* ------------------------- 解析 ------------------------- */

    @Test
    fun `按表头名找列 顺序换了也能认`() {
        val text = "金额,备注,收支,日期,分类名称,账户\n12.34,午饭,支出,2026/10/02 09:51,餐饮,账单流水"
        val rows = RecordsCsvImporter.parse(text)
        assertEquals(1, rows.size)
        assertEquals(1234L, rows[0].cents)
        assertEquals("餐饮", rows[0].category)
        assertEquals(0, rows[0].type)
        assertEquals("午饭", rows[0].note)
    }

    @Test
    fun `金额换算成分 不吃浮点误差`() {
        val text = "日期,分类名称,收支,金额,备注\n" +
            "2026/10/02 09:51,家用,支出,0.1,,\n" +
            "2026/10/02 09:51,家用,支出,10.87,\n" +
            "2026/10/02 09:51,家用,支出,121,\n" +
            "2026/10/02 09:51,家用,支出,\"1,234.5\","
        val rows = RecordsCsvImporter.parse(text)
        assertEquals(listOf(10L, 1087L, 12100L, 123450L), rows.map { it.cents })
    }

    @Test
    fun `脏数据行跳过不抛异常`() {
        val text = "日期,分类名称,收支,金额,备注\n" +
            "坏日期,家用,支出,10,\n" +
            "2026/10/02 09:51,家用,转账,10,\n" +
            "2026/10/02 09:51,家用,支出,,\n" +
            "2026/10/02 09:51,家用,支出,0,\n" +
            "2026/10/02 09:51,家用,支出,-5,\n" +
            "2026/10/02 09:51,家用,收入,88,"
        val rows = RecordsCsvImporter.parse(text)
        assertEquals(1, rows.size)
        assertEquals(8800L, rows[0].cents)
        assertEquals(1, rows[0].type)
    }

    @Test
    fun `表头不对就返回空`() {
        assertTrue(RecordsCsvImporter.parse("甲,乙,丙\n1,2,3").isEmpty())
    }

    /* ------------------------- 编码 ------------------------- */

    @Test
    fun `UTF8 和 GBK 都能读出来`() {
        val text = "日期,分类名称,收支,金额,备注\n2026/10/02 09:51,午饭,支出,12,备注中文"
        val utf8 = RecordsCsvImporter.decode(text.toByteArray(Charsets.UTF_8))
        assertEquals(1, RecordsCsvImporter.parse(utf8).size)

        val gbk = RecordsCsvImporter.decode(text.toByteArray(Charset.forName("GB18030")))
        assertEquals(1, RecordsCsvImporter.parse(gbk).size)
        assertEquals("午饭", RecordsCsvImporter.parse(gbk)[0].category)
    }

    /* ------------------------- 归类 ------------------------- */

    @Test
    fun `没收录的分类名兜底到其他 不丢账单`() {
        val text = "日期,分类名称,收支,金额,备注\n" +
            "2026/10/02 09:51,氦气采购,支出,10,\n" +
            "2026/10/02 09:51,天上掉的,收入,20,"
        val plan = RecordsCsvImporter.buildPlan(text)
        assertEquals(2, plan.txs.size)
        assertEquals("其他", plan.txs[0].parent)
        assertEquals("其他收入", plan.txs[1].parent)
        // 兜底一级是现建的，二级名保留原样，用户自己改名归位
        assertTrue(plan.parents.any { it.name == "其他" && it.children.any { c -> c.name == "氦气采购" } })
    }

    @Test
    fun `股票基金理财 支出收入两边都建`() {
        val text = "日期,分类名称,收支,金额,备注\n" +
            "2026/10/02 09:51,股票,支出,100,\n" +
            "2026/10/02 09:51,股票,收入,200,"
        val plan = RecordsCsvImporter.buildPlan(text)
        val expenseParents = plan.parents.filter { it.type == 0 }.map { it.name }
        val incomeParents = plan.parents.filter { it.type == 1 }.map { it.name }
        assertTrue(expenseParents.contains("投资理财"))
        assertTrue(incomeParents.contains("投资理财"))
        assertEquals(2, plan.parents.size)
        assertEquals(2, plan.txs.size)
    }

    @Test
    fun `文件里没出现过的二级不会建出来`() {
        val text = "日期,分类名称,收支,金额,备注\n2026/10/02 09:51,午饭,支出,10,"
        val plan = RecordsCsvImporter.buildPlan(text)
        assertEquals(1, plan.parents.size)
        assertEquals("餐饮", plan.parents[0].name)
        assertEquals(listOf("午饭"), plan.parents[0].children.map { it.name })
        assertEquals(2, plan.categoryCount)   // 1 个一级 + 1 个二级
    }

    /* ------------------------- 真实导出文件 ------------------------- */

    private fun realExportText(): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("elephant_export_records.csv")
        assertNotNull("测试资源 elephant_export_records.csv 没找到", stream)
        return RecordsCsvImporter.decode(stream!!.readBytes())
    }

    @Test
    fun `真实导出文件 6733 笔全部解析成功`() {
        val rows = RecordsCsvImporter.parse(realExportText())
        assertEquals(6733, rows.size)
        assertTrue(rows.all { it.cents > 0 })
        assertTrue(rows.all { it.type == 0 || it.type == 1 })
        assertEquals(6102, rows.count { it.type == 0 })
        assertEquals(631, rows.count { it.type == 1 })
    }

    @Test
    fun `真实导出文件里每个分类名都能归类 没有一个掉进兜底`() {
        val rows = RecordsCsvImporter.parse(realExportText())
        val unknown = rows.map { it.type to it.category }.distinct()
            .filter { (type, name) -> RecordsCsvImporter.resolveParentName(name, type) == null }
        assertTrue("有分类没收录：$unknown", unknown.isEmpty())
    }

    @Test
    fun `真实导出文件 生成的方案规模正确`() {
        val plan = RecordsCsvImporter.buildPlan(realExportText())
        assertEquals(6733, plan.txs.size)
        // 支出 10 个一级 / 49 个二级，收入 3 个一级 / 12 个二级
        assertEquals(13, plan.parentCount)
        assertEquals(61, plan.childCount)
        assertEquals(74, plan.categoryCount)
        assertEquals(
            listOf("其他收入", "投资理财", "职业收入"),
            plan.parents.filter { it.type == 1 }.map { it.name }.sorted()
        )
    }

    @Test
    fun `真实导出文件里没有孤立的二级`() {
        val plan = RecordsCsvImporter.buildPlan(realExportText())
        val declared = plan.parents.flatMap { p -> p.children.map { p.type to it.name } }.toHashSet()
        val used = plan.txs.map { it.type to it.child }.distinct()
        val orphans = used.filter { it !in declared }
        assertTrue("这些二级没挂在任何一级下：$orphans", orphans.isEmpty())
    }

    @Test
    fun `真实导出文件的日期范围落回 2020 到 2026`() {
        val plan = RecordsCsvImporter.buildPlan(realExportText())
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = plan.minDate!!
        assertEquals(2020, cal.get(java.util.Calendar.YEAR))
        cal.timeInMillis = plan.maxDate!!
        assertEquals(2026, cal.get(java.util.Calendar.YEAR))
    }

    @Test
    fun `未知编码的文件不会崩`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val text = RecordsCsvImporter.decode(bytes)
        assertTrue(RecordsCsvImporter.parse(text).isEmpty())
        assertNull(RecordsCsvImporter.resolveParentName("不存在的分类", 0))
    }
}
