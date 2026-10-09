package com.dafeng.onlymoneynote.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeng.onlymoneynote.data.local.AccountEntity
import com.dafeng.onlymoneynote.data.local.CategoryStat
import com.dafeng.onlymoneynote.data.local.TxType
import com.dafeng.onlymoneynote.data.local.TxWithCategory
import com.dafeng.onlymoneynote.ui.LedgerViewModel
import com.dafeng.onlymoneynote.ui.components.IconTile
import com.dafeng.onlymoneynote.ui.components.HeaderBackButton
import com.dafeng.onlymoneynote.ui.components.OverlayPageHeader
import com.dafeng.onlymoneynote.ui.theme.AppTheme
import com.dafeng.onlymoneynote.util.AppIcons
import android.graphics.Paint
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import java.util.Calendar
import kotlin.math.abs

/**
 * 统计页（支付宝账单分析式）。
 *
 * - 蓝色渐变页头：支出 / 收入白色大数字 + 结余 + 月/季/年区间切换（白色胶囊）
 * - 下面是一摞白色圆角卡片：支出构成 / 收入构成（环形图+图例）、收支曲线、柱状统计、分类排行
 * - 分类行可点开，看该分类下的单笔明细
 */

/**
 * 环形图配色环。
 * 色相要**拉开**——之前用的黄/橙/浅橙挤在一起，两段相邻根本看不出分界。
 */
// 环形图色板：统计页和桌面插件共用，改这里两边一起变
internal val ChartColors = listOf(
    Color(0xFF1677FF), // 蚂蚁蓝
    Color(0xFFFF9436), // 橙
    Color(0xFF00B578), // 绿
    Color(0xFFFA5151), // 红
    Color(0xFF9254DE), // 紫
    Color(0xFF13B8C4), // 青
    Color(0xFFF759AB), // 玫红
    Color(0xFF8C959F)  // 灰蓝
)

/** 明细一次最多列多少条，防止年模式下几千条全组合出来卡住 */
private const val DETAIL_LIMIT = 300

@Composable
fun StatsScreen(
    vm: LedgerViewModel,
    /** 点了某笔账单 → 打开编辑层 */
    onEditTx: (TxWithCategory) -> Unit = {},
    /** 底栏去掉后这页变成二级页，页头里要有返回 */
    onBack: () -> Unit = {}
) {
    val state by vm.stats.collectAsStateCompat()
    val control by vm.statsControl.collectAsStateCompat()
    val accountOverviews by vm.accountOverviews.collectAsStateCompat()
    val (mode, _, _) = control

    // 展开的一级分类（按 parentName）。默认空集合 = 全部折叠，要看得手点开
    var expandedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 展开到单笔的二级分类（按 categoryId）
    var expandedChild by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // 折线图显示支出还是收入
    var curveIncome by remember { mutableStateOf(false) }
    // 下面列表的展示方式：0 = 按分类汇总（默认），1 = 按时间倒序的明细
    var tab by remember { mutableIntStateOf(0) }

    val balance = state.totalIncome - state.totalExpense

    // 提前算好，LazyColumn 的 DSL 里不能写 remember
    val expenseGrouped = remember(state.expenseByCategory) {
        state.expenseByCategory.groupBy { it.parentName ?: it.name }
    }
    val incomeGrouped = remember(state.incomeByCategory) {
        state.incomeByCategory.groupBy { it.parentName ?: it.name }
    }
    val txsByCategory = remember(state.transactions) {
        state.transactions.groupBy { it.categoryId }
    }
    // 一级分类 → 环形图里的颜色，列表行上的小圆点用它，跟环上的色块对得上
    val expenseDot = remember(state.expenseByCategory) { donutColorMap(state.expenseByCategory) }
    val incomeDot = remember(state.incomeByCategory) { donutColorMap(state.incomeByCategory) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        /* ---------- 主题色页头：标题居中 + 大数字 + 区间切换 ---------- */
        OverlayPageHeader(title = "统计", onBack = onBack) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 24.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    HeaderStat(
                        label = "支出",
                        cents = state.totalExpense
                    )
                    HeaderStat(
                        label = "收入",
                        cents = state.totalIncome
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    (if (balance >= 0) "+" else "-") + "¥" +
                        LedgerViewModel.formatCents(abs(balance)) + " 结余",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.72f)
                )
                Spacer(Modifier.height(16.dp))
                RangePicker(
                    mode = mode,
                    label = state.rangeLabel,
                    onMode = { vm.setStatsMode(it) },
                    onShift = { vm.shiftStats(it) }
                )
            }
        }

        /* ---------- 白色卡片摞 ---------- */
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            /* ---------- 支出构成 ---------- */
            item(key = "donut-expense") {
                SectionCard {
                    SectionHead("支出构成", Icons.Outlined.PieChart)
                    Spacer(Modifier.height(12.dp))
                    DonutWithLegend(
                        title = "支出",
                        stats = state.expenseByCategory,
                        total = state.totalExpense,
                        accent = AppTheme.expense
                    )
                }
            }

            /* ---------- 收入构成 ---------- */
            item(key = "donut-income") {
                SectionCard {
                    SectionHead("收入构成", Icons.Outlined.PieChart)
                    Spacer(Modifier.height(12.dp))
                    DonutWithLegend(
                        title = "收入",
                        stats = state.incomeByCategory,
                        total = state.totalIncome,
                        accent = AppTheme.income
                    )
                }
            }

            /* ---------- 收支曲线 ---------- */
            item(key = "curve") {
                SectionCard {
                    SectionHead(
                        title = "收支曲线",
                        icon = Icons.Outlined.ShowChart,
                        trailing = { CurveToggle(curveIncome) { curveIncome = it } }
                    )
                    Spacer(Modifier.height(12.dp))
                    if (state.bars.isEmpty()) {
                        EmptyHint("还没有足够的数据")
                    } else {
                        LineChart(
                            values = state.bars.map { if (curveIncome) it.income else it.expense },
                            labels = state.bars.map { it.label },
                            color = if (curveIncome) AppTheme.income else AppTheme.expense,
                            modifier = Modifier.fillMaxWidth().height(140.dp)
                        )
                    }
                }
            }

            /* ---------- 柱状统计 ---------- */
            item(key = "bars") {
                SectionCard {
                    SectionHead("柱状统计", Icons.Outlined.BarChart)
                    Spacer(Modifier.height(12.dp))
                    if (state.bars.isEmpty()) {
                        EmptyHint("还没有足够的数据")
                    } else {
                        BarChart(
                            bars = state.bars,
                            expenseColor = AppTheme.expense,
                            incomeColor = AppTheme.income,
                            modifier = Modifier.fillMaxWidth().height(150.dp)
                        )
                    }
                }
            }

            /* ---------- 分类排行 / 明细 ---------- */
            item(key = "list") {
                SectionCard {
                    ListModeTabs(tab) { tab = it }
                    Spacer(Modifier.height(12.dp))

                    if (tab == 1) {
                        // 明细：支出收入混在一起，按时间倒序
                        val allTxs = state.transactions
                        if (allTxs.isEmpty()) {
                            EmptyHint("这个区间没有记录")
                        } else {
                            allTxs.take(DETAIL_LIMIT).forEach { tx ->
                                DetailRow(
                                    tx,
                                    AppTheme.byType(tx.type == TxType.EXPENSE.value),
                                    onEdit = { onEditTx(tx) }
                                )
                            }
                            if (allTxs.size > DETAIL_LIMIT) {
                                Text(
                                    "只显示最近 $DETAIL_LIMIT 条",
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    fontSize = 11.5.sp,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else if (tab == 2) {
                        // 账户：这个区间每个账户走了多少钱
                        AccountBreakdown(state.transactions, accountOverviews)
                    } else {
                        // 分类：先支出后收入
                        if (expenseGrouped.isEmpty() && incomeGrouped.isEmpty()) {
                            EmptyHint("这个区间没有记录")
                        }
                        if (expenseGrouped.isNotEmpty()) {
                            GroupLabel("支出", AppTheme.expense)
                            expenseGrouped.forEach { (parentName, children) ->
                                StatGroup(
                                    parentName = parentName,
                                    children = children,
                                    total = state.totalExpense,
                                    collapsed = parentName !in expandedGroups,
                                    expandedChild = expandedChild,
                                    txsByCategory = txsByCategory,
                                    accent = AppTheme.expense,
                                    dotColor = expenseDot[parentName],
                                    onEditTx = onEditTx,
                                    onToggleGroup = {
                                        expandedGroups =
                                            if (parentName in expandedGroups) expandedGroups - parentName
                                            else expandedGroups + parentName
                                    },
                                    onToggleChild = { id ->
                                        expandedChild = if (id in expandedChild) expandedChild - id
                                        else expandedChild + id
                                    }
                                )
                            }
                        }
                        if (incomeGrouped.isNotEmpty()) {
                            if (expenseGrouped.isNotEmpty()) Spacer(Modifier.height(14.dp))
                            GroupLabel("收入", AppTheme.income)
                            incomeGrouped.forEach { (parentName, children) ->
                                StatGroup(
                                    parentName = parentName,
                                    children = children,
                                    total = state.totalIncome,
                                    collapsed = parentName !in expandedGroups,
                                    expandedChild = expandedChild,
                                    txsByCategory = txsByCategory,
                                    accent = AppTheme.income,
                                    dotColor = incomeDot[parentName],
                                    onEditTx = onEditTx,
                                    onToggleGroup = {
                                        expandedGroups =
                                            if (parentName in expandedGroups) expandedGroups - parentName
                                            else expandedGroups + parentName
                                    },
                                    onToggleChild = { id ->
                                        expandedChild = if (id in expandedChild) expandedChild - id
                                        else expandedChild + id
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* 页头与卡片                                                          */
/* ------------------------------------------------------------------ */

@Composable
private fun HeaderStat(label: String, cents: Long) {
    Column {
        Text(
            label,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.72f)
        )
        Text(
            "¥" + LedgerViewModel.formatCents(cents),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/** 统计页里的一张白色圆角卡片 */
@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            content()
        }
    }
}

/* ------------------------------------------------------------------ */
/* 区间选择（页头白样式）                                                */
/* ------------------------------------------------------------------ */

@Composable
private fun RangePicker(
    mode: LedgerViewModel.RangeMode,
    label: String,
    onMode: (LedgerViewModel.RangeMode) -> Unit,
    onShift: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(17.dp),
            color = Color.White.copy(alpha = 0.16f)
        ) {
            Row(modifier = Modifier.padding(3.dp)) {
                LedgerViewModel.RangeMode.entries.forEach { m ->
                    val sel = m == mode
                    val text = when (m) {
                        LedgerViewModel.RangeMode.MONTH -> "月"
                        LedgerViewModel.RangeMode.QUARTER -> "季"
                        LedgerViewModel.RangeMode.YEAR -> "年"
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (sel) Color.White else Color.Transparent)
                            .clickable { onMode(m) }
                            .padding(horizontal = 18.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text,
                            fontSize = 14.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (sel) AppTheme.primary else Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { onShift(-1) }, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft, "往前",
                modifier = Modifier.size(22.dp),
                tint = Color.White
            )
        }
        // 区间文字（如「2026年10月」）跟左边「月/季/年」三个字保持同一字号观感
        Text(
            label,
            fontSize = 16.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
        IconButton(onClick = { onShift(1) }, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, "往后",
                modifier = Modifier.size(22.dp),
                tint = Color.White
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* 小节标题                                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun SectionHead(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, modifier = Modifier.size(17.dp), tint = AppTheme.primary)
        Spacer(Modifier.width(7.dp))
        Text(title, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(70.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 曲线切换：支出 / 收入 */
@Composable
private fun CurveToggle(income: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ToggleDot(AppTheme.expense, !income) { onChange(false) }
        ToggleDot(AppTheme.income, income) { onChange(true) }
    }
}

@Composable
private fun ToggleDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) color.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (selected) color else color.copy(alpha = 0.35f))
        )
    }
}

/* ------------------------------------------------------------------ */
/* 环形图 + 图例（支出、收入各一块，上下排）                              */
/* ------------------------------------------------------------------ */

/**
 * 一个环 + 右侧图例。
 *
 * 环上按**一级分类**分段（stats 是二级粒度，不汇总会出现一堆同名项），
 * 段色用 [ChartColors] 循环；图例里同样颜色的小方块 + 分类名 + 百分比，
 * 这样每一段是什么、占多少，一眼能对上。
 */
@Composable
private fun DonutWithLegend(
    title: String,
    stats: List<CategoryStat>,
    total: Long,
    accent: Color
) {
    val byParent = remember(stats) { aggregateByParent(stats) }
    val sum = byParent.sumOf { it.second }.coerceAtLeast(1L)
    val slices = byParent.mapIndexed { i, (_, cents) ->
        ChartColors[i % ChartColors.size] to (cents.toFloat() / sum)
    }
    val entries = remember(byParent) { legendEntries(byParent) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左边：环（中间写总额）
        Box(contentAlignment = Alignment.Center) {
            DonutChart(
                slices = slices.ifEmpty {
                    listOf(MaterialTheme.colorScheme.surfaceVariant to 1f)
                },
                modifier = Modifier.size(112.dp)
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "¥" + compactAmount(total),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        // 右边：图例
        Column(modifier = Modifier.weight(1f)) {
            if (entries.isEmpty()) {
                Text(
                    "这个区间没有记录",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                entries.forEach { e ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.5f.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (e.colorIndex < 0) {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                    } else {
                                        ChartColors[e.colorIndex % ChartColors.size]
                                    }
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            e.name,
                            modifier = Modifier.weight(1f),
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "%.2f%%".format(e.pct),
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "¥" + compactAmount(e.cents),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/** 环心里的金额：上万就缩写成「1.2万」，免得撑破圆环 */
internal fun compactAmount(cents: Long): String {
    val yuan = cents / 100.0
    return if (yuan >= 10000) {
        "%.1f万".format(yuan / 10000)
    } else {
        LedgerViewModel.formatCents(cents)
    }
}

/** 图例最多显示几行，多的并成「其他」 */
internal const val LEGEND_MAX_ROWS = 6

/** 合并项的名字与颜色下标（-1 表示用中性灰） */
internal const val OTHER_LABEL = "其他"

internal data class LegendEntry(
    val name: String,
    val pct: Double,
    val colorIndex: Int,
    /** 这一项的金额（分）。「其他」是所有被合并项之和 */
    val cents: Long
)

/**
 * 图例行。
 *
 * 超过 [maxRows] 行时把剩下的并成「其他」，它的百分比用 `100 - 前面之和` 算，
 * 而不是自己再除一遍 —— 否则四舍五入后会差 0.01%，看着别扭。
 */
internal fun legendEntries(
    byParent: List<Pair<String, Long>>,
    maxRows: Int = LEGEND_MAX_ROWS
): List<LegendEntry> {
    if (byParent.isEmpty()) return emptyList()
    val total = byParent.sumOf { it.second }.coerceAtLeast(1L)

    if (byParent.size <= maxRows) {
        return byParent.mapIndexed { i, (name, cents) ->
            LegendEntry(name, cents * 100.0 / total, i, cents)
        }
    }
    val head = byParent.take(maxRows - 1).mapIndexed { i, (name, cents) ->
        LegendEntry(name, cents * 100.0 / total, i, cents)
    }
    val shown = head.sumOf { it.pct }
    val restCents = byParent.drop(maxRows - 1).sumOf { it.second }
    return head + LegendEntry(
        OTHER_LABEL,
        (100.0 - shown).coerceAtLeast(0.0),
        -1,
        restCents
    )
}

/* ------------------------------------------------------------------ */
/* 分组小标题（分类视图里区分 支出 / 收入）                                */
/* ------------------------------------------------------------------ */

@Composable
private fun GroupLabel(text: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(7.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/* ------------------------------------------------------------------ */
/* 饼图数据计算（抽成纯函数，方便单测）                                   */
/* ------------------------------------------------------------------ */

/**
 * 按一级分类汇总。
 * stats 是二级粒度的（餐饮下面有午餐/晚餐/外卖…），不汇总会出现好几个同名项。
 */
internal fun aggregateByParent(stats: List<CategoryStat>): List<Pair<String, Long>> =
    stats.groupBy { it.parentName ?: it.name }
        .map { (name, list) -> name to list.sumOf { it.totalCents } }
        .sortedByDescending { it.second }

/** 一级分类名 → 它在环上用的颜色（列表行的小圆点用它跟环对应起来） */
internal fun donutColorIndexMap(stats: List<CategoryStat>): Map<String, Int> =
    aggregateByParent(stats)
        .mapIndexed { i, (name, _) -> name to i % ChartColors.size }
        .toMap()

/** 一级分类名 → 颜色（只读固定色板，不需要 Composable，可以被 remember 包住） */
private fun donutColorMap(stats: List<CategoryStat>): Map<String, Color> =
    donutColorIndexMap(stats).mapValues { (_, i) -> ChartColors[i] }

/** 环形图：用 Stroke 画弧，中间自然空心 */
@Composable
private fun DonutChart(
    slices: List<Pair<Color, Float>>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val stroke = 22.dp.toPx()
        val inset = stroke / 2f
        val side = size.minDimension - stroke
        var start = -90f
        slices.forEach { (color, frac) ->
            val sweep = frac * 360f
            // 每段留 1.2° 缝隙（参考图里就是分段式的环），太小的段也别跳过，免得少画一块
            if (sweep > 0f) {
                drawArc(
                    color = color,
                    startAngle = start,
                    sweepAngle = (sweep - 1.2f).coerceAtLeast(0.1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(side, side),
                    style = Stroke(width = stroke, cap = StrokeCap.Butt)
                )
            }
            start += sweep
        }
    }
}


/** 「分类 / 明细」二选一（分类在前，默认选分类） */
@Composable
private fun ListModeTabs(tab: Int, onTab: (Int) -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("分类", "明细", "账户").forEachIndexed { i, label ->
                val sel = i == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(
                            if (sel) AppTheme.primary.copy(alpha = 0.12f) else Color.Transparent
                        )
                        .clickable { onTab(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontSize = 13.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (sel) AppTheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 统计页「账户」tab：区间内每个账户走了多少支出 / 收入，按支出从多到少排。
 * 区间内的账单直接从 [StatsUiState.transactions] 现算，不另开查询。
 */
@Composable
private fun AccountBreakdown(
    txs: List<TxWithCategory>,
    overviews: List<LedgerViewModel.AccountOverview>
) {
    if (txs.isEmpty()) {
        EmptyHint("这个区间没有记录")
        return
    }
    val rows = txs.groupBy { it.accountId }.map { (id, list) ->
        AccountRowData(
            overview = overviews.firstOrNull { it.account.id == id },
            expense = list.filter { it.type == TxType.EXPENSE.value }.sumOf { it.amountCents },
            income = list.filter { it.type == TxType.INCOME.value }.sumOf { it.amountCents }
        )
    }
    val totalExpense = rows.sumOf { it.expense }

    rows.sortedByDescending { it.expense }.forEach { r ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(
                iconKey = r.overview?.account?.iconKey ?: "emoji:💳",
                size = 32.dp,
                cornerRadius = 10.dp,
                overrideColor = AppIcons.colorFromKey(r.overview?.account?.colorKey.orEmpty())
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    r.overview?.account?.name ?: AccountEntity.UNSPECIFIED_NAME,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "现有资产 ¥" + LedgerViewModel.formatCents(abs(r.overview?.balanceCents ?: 0L)) +
                        (if (r.income > 0) "　·　收入 ¥" + LedgerViewModel.formatCents(r.income) else ""),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Text(
                "¥" + LedgerViewModel.formatCents(r.expense),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.expense
            )
            if (totalExpense > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "${r.expense * 100 / totalExpense}%",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private data class AccountRowData(
    val overview: LedgerViewModel.AccountOverview?,
    val expense: Long,
    val income: Long
)

/** 明细行：图标 + 大类·小类 + 日期时间（含备注）+ 金额。点击进编辑 */
@Composable
private fun DetailRow(tx: TxWithCategory, accent: Color, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onEdit)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(
            iconKey = tx.iconKey, size = 34.dp, cornerRadius = 10.dp,
            overrideColor = AppIcons.colorFromKey(tx.colorKey)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                listOfNotNull(tx.parentName, tx.categoryName).distinct().joinToString(" - "),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                buildString {
                    append(dateTimeLabel(tx.dateMillis))
                    if (tx.note.isNotBlank()) append("  ").append(tx.note)
                },
                fontSize = 11.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "¥" + LedgerViewModel.formatCents(tx.amountCents),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = accent
        )
    }
}

/* ------------------------------------------------------------------ */
/* 分类分组（一级可折叠、二级可展开单笔）                                 */
/* ------------------------------------------------------------------ */

@Composable
private fun StatGroup(
    parentName: String,
    children: List<CategoryStat>,
    total: Long,
    collapsed: Boolean,
    expandedChild: Set<Long>,
    txsByCategory: Map<Long, List<TxWithCategory>>,
    accent: Color,
    /** 这个大类在环形图里的颜色，列表行前的小圆点用它对应 */
    dotColor: Color?,
    onEditTx: (TxWithCategory) -> Unit,
    onToggleGroup: () -> Unit,
    onToggleChild: (Long) -> Unit
) {
    val groupTotal = children.sumOf { it.totalCents }
    val groupCount = children.sumOf { it.count }
    val groupIcon = children.first().iconKey

    // 一级行
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggleGroup)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 环形图里这个大类用的颜色
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor ?: MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(8.dp))
        IconTile(
            iconKey = groupIcon, size = 30.dp, cornerRadius = 9.dp,
            // 同一父级下的子分类 colorKey 都解析自一级分类，取第一个即可
            overrideColor = AppIcons.colorFromKey(children.firstOrNull()?.colorKey)
        )
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(parentName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "$groupCount 笔",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "¥" + LedgerViewModel.formatCents(groupTotal),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = accent
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            if (collapsed) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
            contentDescription = if (collapsed) "展开" else "收起",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (!collapsed) {
        children.forEach { stat ->
            val ratio = if (total > 0) stat.totalCents.toFloat() / total else 0f
            val childTxs = txsByCategory[stat.categoryId].orEmpty()
            val isExpanded = stat.categoryId in expandedChild

            // 二级行：名称 + 金额 + 进度条
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stat.name,
                        modifier = Modifier.weight(1f),
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "¥" + LedgerViewModel.formatCents(stat.totalCents),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "%.2f%%".format(ratio * 100),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (childTxs.isNotEmpty()) {
                        Icon(
                            if (isExpanded) Icons.Outlined.KeyboardArrowUp
                            else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "收起" else "展开单笔",
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(15.dp)
                                .clickable { onToggleChild(stat.categoryId) },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                // 进度条
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(ratio.coerceIn(0f, 1f))
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(accent.copy(alpha = 0.85f))
                    )
                }

                // 展开的单笔明细
                if (isExpanded) {
                    Spacer(Modifier.height(4.dp))
                    childTxs.forEach { tx ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onEditTx(tx) }
                                .padding(start = 4.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                dateTimeLabel(tx.dateMillis) +
                                    if (tx.note.isNotBlank()) " · ${tx.note}" else "",
                                modifier = Modifier.weight(1f),
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            Text(
                                (if (tx.type == TxType.EXPENSE.value) "-¥" else "+¥") +
                                    LedgerViewModel.formatCents(tx.amountCents),
                                fontSize = 12.sp,
                                color = AppTheme.byType(tx.type == TxType.EXPENSE.value)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

private fun dateTimeLabel(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return "%d月%d日 %02d:%02d".format(
        c.get(Calendar.MONTH) + 1,
        c.get(Calendar.DAY_OF_MONTH),
        c.get(Calendar.HOUR_OF_DAY),
        c.get(Calendar.MINUTE)
    )
}

/** Y 轴刻度文案：尽量短，塞得下左边的留白 */
private fun axisLabel(cents: Long): String {
    if (cents <= 0L) return "0"
    val yuan = cents / 100.0
    return when {
        yuan >= 10000 -> "%.1f万".format(yuan / 10000)
        yuan >= 100 -> "%.0f".format(yuan)
        else -> "%.1f".format(yuan)
    }
}

/* ------------------------------------------------------------------ */
/* 折线图                                                              */
/* ------------------------------------------------------------------ */

@Composable
private fun LineChart(
    values: List<Long>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier
) {
    if (values.isEmpty()) return
    val maxV = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val w = size.width
            val h = size.height
            val n = values.size
            if (n < 2) return@Canvas

            // 左边留出 Y 轴刻度的位置
            val leftPad = 38.dp.toPx()
            val topPad = 8.dp.toPx()
            val bottomPad = 5.dp.toPx()
            val plotH = h - topPad - bottomPad

            val textPaint = Paint()
            textPaint.setColor(labelColor.toArgb())
            textPaint.textSize = 8.5.sp.toPx()
            textPaint.isAntiAlias = true

            // 背景横网格 + 左侧刻度值
            val rows = 4
            for (i in 0..rows) {
                val frac = i.toFloat() / rows
                val y = h - bottomPad - frac * plotH
                drawLine(
                    color = gridColor,
                    start = Offset(leftPad, y),
                    end = Offset(w, y),
                    strokeWidth = 1f
                )
                drawContext.canvas.nativeCanvas.drawText(
                    axisLabel((maxV * frac).toLong()),
                    0f, y + 3.dp.toPx(), textPaint
                )
            }

            // 折线
            val stepX = (w - leftPad) / (n - 1)
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = leftPad + stepX * i
                val y = h - bottomPad - (v.toFloat() / maxV) * plotH
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )

            // 数据点
            values.forEachIndexed { i, v ->
                val x = leftPad + stepX * i
                val y = h - bottomPad - (v.toFloat() / maxV) * plotH
                drawCircle(color = color, radius = 2.5.dp.toPx(), center = Offset(x, y))
            }
        }
        Spacer(Modifier.height(4.dp))
        // x 轴标签：太密就隔几个画（往右让开 Y 轴刻度）
        Row(modifier = Modifier.fillMaxWidth().padding(start = 38.dp)) {
            val step = if (labels.size > 8) labels.size / 6 else 1
            labels.forEachIndexed { i, l ->
                Text(
                    if (i % step == 0) l else "",
                    modifier = Modifier.weight(1f),
                    fontSize = 9.sp,
                    color = labelColor,
                    maxLines = 1
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* 柱状图                                                              */
/* ------------------------------------------------------------------ */

@Composable
private fun BarChart(
    bars: List<LedgerViewModel.BarPoint>,
    expenseColor: Color,
    incomeColor: Color,
    modifier: Modifier = Modifier
) {
    val maxValue = remember(bars) {
        bars.maxOfOrNull { maxOf(it.expense, it.income) } ?: 0L
    }.coerceAtLeast(1L)

    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val w = size.width
            val h = size.height

            // 左边留出 Y 轴刻度的位置
            val leftPad = 38.dp.toPx()
            val topPad = 8.dp.toPx()
            val bottomPad = 5.dp.toPx()
            val plotH = h - topPad - bottomPad

            val textPaint = Paint()
            textPaint.setColor(labelColor.toArgb())
            textPaint.textSize = 8.5.sp.toPx()
            textPaint.isAntiAlias = true

            // 横网格 + 左侧刻度值
            for (i in 0..3) {
                val frac = i / 3f
                val y = h - bottomPad - frac * plotH
                drawLine(gridColor, Offset(leftPad, y), Offset(w, y), strokeWidth = 1f)
                drawContext.canvas.nativeCanvas.drawText(
                    axisLabel((maxValue * frac).toLong()),
                    0f, y + 3.dp.toPx(), textPaint
                )
            }

            val n = bars.size.coerceAtLeast(1)
            val slot = (w - leftPad) / n
            val barW = (slot * 0.26f).coerceAtMost(14.dp.toPx())
            bars.forEachIndexed { i, b ->
                val cx = leftPad + slot * i + slot / 2f
                val hExp = (b.expense.toFloat() / maxValue) * plotH
                val hInc = (b.income.toFloat() / maxValue) * plotH
                // 支出柱（左）
                drawRoundRect(
                    color = expenseColor,
                    topLeft = Offset(cx - barW - 1.5f, h - bottomPad - hExp),
                    size = Size(barW, hExp),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 3f, barW / 3f)
                )
                // 收入柱（右）
                drawRoundRect(
                    color = incomeColor,
                    topLeft = Offset(cx + 1.5f, h - bottomPad - hInc),
                    size = Size(barW, hInc),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 3f, barW / 3f)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(start = 38.dp)) {
            val step = if (bars.size > 8) bars.size / 6 else 1
            bars.forEachIndexed { i, b ->
                Text(
                    if (i % step == 0) b.label else "",
                    modifier = Modifier.weight(1f),
                    fontSize = 9.sp,
                    color = labelColor,
                    maxLines = 1
                )
            }
        }
    }
}
