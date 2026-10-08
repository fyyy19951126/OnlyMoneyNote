package com.dafeng.onlymoneynote.ui.screens

import com.dafeng.onlymoneynote.data.local.CategoryStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计页饼图的数据计算测试。
 *
 * 用户报过「饼图加起来不是 100%」，根因是当时只画了前 6 个分类、分母却是全部，
 * 而且图例直接拿二级分类名，同一个大类会重复出现。
 * 现在环上按一级分类汇总后全画出来，这两条都锁死。
 */
class StatsChartTest {

    private fun stat(
        categoryId: Long,
        name: String,
        parent: String?,
        cents: Long,
        count: Int = 1
    ) = CategoryStat(
        categoryId = categoryId,
        name = name,
        iconKey = "more_horiz",
        parentName = parent,
        totalCents = cents,
        count = count
    )

    /* ---------------- 按一级分类汇总 ---------------- */

    @Test
    fun `同大类的二级要合并成一项`() {
        val list = listOf(
            stat(1, "午餐", "餐饮", 1000),
            stat(2, "晚餐", "餐饮", 2000),
            stat(3, "外卖", "餐饮", 500),
            stat(4, "打车", "交通", 1500)
        )
        val merged = aggregateByParent(list)
        assertEquals(2, merged.size)
        // 按金额倒序：餐饮 3500 > 交通 1500
        assertEquals("餐饮", merged[0].first)
        assertEquals(3500L, merged[0].second)
        assertEquals("交通", merged[1].first)
        assertEquals(1500L, merged[1].second)
    }

    @Test
    fun `没有父级的分类用自己的名字`() {
        val merged = aggregateByParent(listOf(stat(1, "其他", null, 800)))
        assertEquals(1, merged.size)
        assertEquals("其他", merged[0].first)
        assertEquals(800L, merged[0].second)
    }

    @Test
    fun `汇总后各段之和等于总额 —— 环才能画满`() {
        val list = listOf(
            stat(1, "午餐", "餐饮", 1234),
            stat(2, "晚餐", "餐饮", 5678),
            stat(3, "打车", "交通", 901),
            stat(4, "理财", "投资", 4321)
        )
        val merged = aggregateByParent(list)
        assertEquals(list.sumOf { it.totalCents }, merged.sumOf { it.second })
    }

    @Test
    fun `空数据不崩`() {
        assertTrue(aggregateByParent(emptyList()).isEmpty())
    }

    /* ---------------- 环上配色 ---------------- */

    @Test
    fun `配色按金额倒序分配，且和大类一一对应`() {
        val list = listOf(
            stat(1, "午餐", "餐饮", 5000),
            stat(2, "打车", "交通", 3000),
            stat(3, "房租", "居住", 1000)
        )
        val idx = donutColorIndexMap(list)
        assertEquals(0, idx["餐饮"])
        assertEquals(1, idx["交通"])
        assertEquals(2, idx["居住"])
    }

    @Test
    fun `大类超过色板长度时颜色循环使用`() {
        // 造 10 个大类，色板只有 8 色
        val list = (1..10).map { stat(it.toLong(), "类$it", "大类$it", (100 - it) * 100L) }
        val idx = donutColorIndexMap(list)
        assertEquals(10, idx.size)
        // 第 9 个（下标 8）应该绕回 0 号色
        val names = aggregateByParent(list).map { it.first }
        assertEquals(0, idx[names[8]])
        assertEquals(1, idx[names[9]])
    }

    /* ---------------- 图例 ---------------- */

    @Test
    fun `图例百分比加起来正好 100`() {
        val list = listOf(
            stat(1, "午餐", "餐饮", 2500),
            stat(2, "打车", "交通", 2500),
            stat(3, "房租", "居住", 5000)
        )
        val entries = legendEntries(aggregateByParent(list))
        assertEquals(3, entries.size)
        assertEquals(100.0, entries.sumOf { it.pct }, 0.001)
        // 颜色下标跟环上的段一一对应
        assertEquals(listOf(0, 1, 2), entries.map { it.colorIndex })
    }

    @Test
    fun `图例超过上限时并成其他，总和仍是 100`() {
        val list = (1..10).map { stat(it.toLong(), "类$it", "大类$it", it * 100L) }
        val entries = legendEntries(aggregateByParent(list))
        assertEquals(LEGEND_MAX_ROWS, entries.size)
        assertEquals(OTHER_LABEL, entries.last().name)
        // 「其他」用中性灰，不占色环颜色
        assertEquals(-1, entries.last().colorIndex)
        assertEquals(100.0, entries.sumOf { it.pct }, 0.001)
    }

    @Test
    fun `其他这一项等于被合并掉的那些之和`() {
        val list = (1..8).map { stat(it.toLong(), "类$it", "大类$it", 1000L) }
        val entries = legendEntries(aggregateByParent(list))
        // 8 个大类各 1000，共 8000；前 5 个 = 62.5%，其他 = 37.5%
        assertEquals(6, entries.size)
        assertEquals(37.5, entries.last().pct, 0.001)
    }

    @Test
    fun `图例空数据不崩`() {
        assertTrue(legendEntries(emptyList()).isEmpty())
    }

    @Test
    fun `图例带金额，各项金额对得上`() {
        val list = listOf(
            stat(1, "午餐", "餐饮", 2500),
            stat(2, "晚餐", "餐饮", 1500),
            stat(3, "打车", "交通", 6000)
        )
        val entries = legendEntries(aggregateByParent(list))
        // 按金额倒序：交通 6000 > 餐饮 4000
        assertEquals("交通", entries[0].name)
        assertEquals(6000L, entries[0].cents)
        assertEquals("餐饮", entries[1].name)
        assertEquals(4000L, entries[1].cents)
    }

    @Test
    fun `其他那一项的金额是被合并掉的总和`() {
        val list = (1..10).map { stat(it.toLong(), "类$it", "大类$it", 1000L) }
        val entries = legendEntries(aggregateByParent(list))
        val other = entries.last()
        assertEquals(OTHER_LABEL, other.name)
        // 10 个大类各 1000，前 5 个显示，剩 5 个 = 5000
        assertEquals(5000L, other.cents)
        // 显示的金额加起来应该等于总额
        assertEquals(10000L, entries.sumOf { it.cents })
    }

    /* ---------------- 环心里的金额缩写 ---------------- */

    @Test
    fun `不足一万显示原金额`() {
        assertEquals("9999.99", compactAmount(999999))
        assertEquals("0.00", compactAmount(0))
    }

    @Test
    fun `超过一万缩写成万`() {
        assertEquals("1.0万", compactAmount(1_000_000))     // 10000 元
        assertEquals("1.2万", compactAmount(1_234_500))     // 12345 元
        assertEquals("10.0万", compactAmount(10_000_000))
    }
}
