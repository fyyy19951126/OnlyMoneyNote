package com.dafeng.onlymoneynote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeng.onlymoneynote.data.local.CategoryEntity
import com.dafeng.onlymoneynote.ui.components.IconTile
import com.dafeng.onlymoneynote.ui.components.SheetDialog
import com.dafeng.onlymoneynote.ui.theme.AppTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * 首页筛选条件：日期（或日期范围）+ 分类（一级 / 二级）。
 *
 * 空值 = 不限。三个维度是「与」的关系，再叠在顶栏「支出 / 收入」那个类型筛选之上。
 */
data class HomeFilter(
    /** 起始日 00:00:00 的毫秒；null = 不限 */
    val from: Long? = null,
    /** 结束日 23:59:59.999 的毫秒；null = 不限 */
    val to: Long? = null,
    /** 选中的分类 id：一级 = 连它下面的二级一起算；二级 = 只算这一类 */
    val categoryIds: Set<Long> = emptySet()
) {
    val active: Boolean get() = from != null || to != null || categoryIds.isNotEmpty()
}

/* ------------------------------------------------------------------ */
/* 日期工具                                                            */
/* ------------------------------------------------------------------ */

/** 某个时间戳所在日 00:00:00 的毫秒 */
internal fun dayStart(millis: Long): Long =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** 某个时间戳所在日 23:59:59.999 的毫秒 */
internal fun dayEnd(millis: Long): Long = dayStart(millis) + 86_399_999L

/** LocalDate -> 本地时区当天 00:00 */
private fun LocalDate.toLocalMillis(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** DatePicker 给的是 UTC 零点的毫秒，转成本地日期 */
private fun utcMillisToLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/** 快捷区间：返回 (起, 止)，null 表示「全部」 */
internal fun presetRange(key: String): Pair<Long, Long>? {
    val today = LocalDate.now()
    return when (key) {
        "本周" -> {
            val mon = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            mon.toLocalMillis() to (mon.plusDays(6).toLocalMillis() + 86_399_999L)
        }
        "本月" -> {
            val first = today.withDayOfMonth(1)
            first.toLocalMillis() to (today.with(TemporalAdjusters.lastDayOfMonth())
                .toLocalMillis() + 86_399_999L)
        }
        "上月" -> {
            val first = today.minusMonths(1).withDayOfMonth(1)
            first.toLocalMillis() to (first.with(TemporalAdjusters.lastDayOfMonth())
                .toLocalMillis() + 86_399_999L)
        }
        "今年" -> {
            val jan1 = today.withDayOfYear(1)
            jan1.toLocalMillis() to (today.with(TemporalAdjusters.lastDayOfYear())
                .toLocalMillis() + 86_399_999L)
        }
        else -> null
    }
}

/** 当前筛选命中的快捷区间名（用来高亮胶囊），没有就返回 null */
internal fun matchPreset(filter: HomeFilter): String? {
    if (filter.from == null || filter.to == null) return null
    return listOf("本周", "本月", "上月", "今年").firstOrNull { k ->
        presetRange(k)?.let { it.first == filter.from && it.second == filter.to } == true
    }
}

/** 毫秒 -> 「10月3日」；跨年时带上年份 */
internal fun shortDate(millis: Long): String {
    val d = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    val thisYear = LocalDate.now().year
    return if (d.year == thisYear) "${d.monthValue}月${d.dayOfMonth}日"
    else "${d.year}年${d.monthValue}月${d.dayOfMonth}日"
}

/* ------------------------------------------------------------------ */
/* 筛选弹层                                                            */
/* ------------------------------------------------------------------ */

/**
 * 首页「筛选」弹层：跟记一笔一个模子（顶部横线把手）。
 *
 * 上半部分两栏：日期（快捷区间 + 自定义起止）、类型（支出/收入切换 + 一级带二级的层级列表）。
 * 底部「重置 / 完成」钉住，改完点完成才生效 —— 边点边筛的话，中途关窗会说不清算不算应用。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeFilterSheet(
    categories: List<CategoryEntity>,
    /** 打开时列表停在哪一类（支出 0 / 收入 1），一般跟首页当前筛选一致 */
    initialType: Int,
    filter: HomeFilter,
    onDismiss: () -> Unit,
    onApply: (HomeFilter) -> Unit
) {
    var draft by remember { mutableStateOf(filter) }
    var typeTab by remember { mutableStateOf(initialType) }
    // 展开着的一级分类 id（点它才露出二级）
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }

    val accent = AppTheme.primary

    val parents = remember(categories, typeTab) {
        categories.filter { it.parentId == null && it.type == typeTab }.sortedBy { it.sortOrder }
    }
    val kidsOf = remember(categories) {
        categories.filter { it.parentId != null }.groupBy { it.parentId }
    }

    SheetDialog(
        onDismiss = onDismiss,
        heightFraction = 0.8f
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            /* ---------------- 日期 ---------------- */
            FilterSectionHead("日期")
            Spacer(Modifier.height(8.dp))
            // 用 FlowRow：六个胶囊一行放不下就换行。
            // 之前是普通 Row + weight 分摊，窄屏上「今年」被挤成两行竖排。
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PresetChip("全部", matchPreset(draft) == null && draft.from == null) {
                    draft = draft.copy(from = null, to = null)
                }
                // 五个胶囊一行放得下，不用换行；原来的「近 30 天」按用户要求去掉
                listOf("本周", "本月", "上月", "今年").forEach { k ->
                    PresetChip(k, matchPreset(draft) == k) {
                        presetRange(k)?.let { (a, b) -> draft = draft.copy(from = a, to = b) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 自定义起止：点开是系统日期选择器
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateSlot(
                    label = "开始",
                    millis = draft.from,
                    modifier = Modifier.weight(1f),
                    onClick = { pickingStart = true }
                )
                DateSlot(
                    label = "结束",
                    millis = draft.to,
                    modifier = Modifier.weight(1f),
                    onClick = { pickingEnd = true }
                )
            }

            Spacer(Modifier.height(18.dp))

            /* ---------------- 类型（带一二级层级） ---------------- */
            FilterSectionHead("类型")
            Spacer(Modifier.height(8.dp))
            // 支出 / 收入：两套分类表是分开的，先选边再看树
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("支出" to 0, "收入" to 1).forEach { (label, value) ->
                    val sel = typeTab == value
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (sel) accent.copy(alpha = 0.14f) else Color.Transparent)
                            .border(if (sel) 1.5.dp else 1.dp,
                                if (sel) accent else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(11.dp))
                            .clickable { typeTab = value }
                            .padding(horizontal = 16.dp, vertical = 7.dp)
                    ) {
                        Text(
                            label,
                            fontSize = 13.5.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (sel) accent else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "点一级 = 连下面二级一起选",
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterVertically),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.height(10.dp))

            // 层级列表：一级一行，点开在下方缩进露出二级胶囊
            parents.forEach { parent ->
                val kids = kidsOf[parent.id].orEmpty().sortedBy { it.sortOrder }
                val parentOn = parent.id in draft.categoryIds
                val onCount = kids.count { it.id in draft.categoryIds }
                val open = parent.id in expanded || kids.isEmpty()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (parentOn) accent.copy(alpha = 0.10f) else Color.Transparent
                            )
                            .clickable {
                                // 点一级：整组进 / 出（连带把它下面的二级一起清掉或选上）
                                draft = if (parentOn) {
                                    draft.copy(
                                        categoryIds = draft.categoryIds - parent.id -
                                            kids.map { it.id }.toSet()
                                    )
                                } else {
                                    draft.copy(categoryIds = draft.categoryIds + parent.id)
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconTile(
                            iconKey = parent.iconKey, size = 28.dp, cornerRadius = 9.dp,
                            overrideColor = com.dafeng.onlymoneynote.util.AppIcons
                                .colorFromKey(parent.colorKey)
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            parent.name,
                            modifier = Modifier.weight(1f),
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (kids.isNotEmpty()) {
                            Text(
                                if (onCount > 0) "已选 $onCount/${kids.size}" else "${kids.size} 小类",
                                fontSize = 11.sp,
                                color = if (onCount > 0) accent
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Outlined.KeyboardArrowDown,
                                if (open) "收起" else "展开",
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(if (open) 180f else 0f)
                                    .clip(CircleShape)
                                    .clickable {
                                        expanded = if (parent.id in expanded)
                                            expanded - parent.id else expanded + parent.id
                                    },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Icon(
                                Icons.Outlined.Check, null,
                                modifier = Modifier.size(16.dp),
                                tint = if (parentOn) accent else Color.Transparent
                            )
                        }
                    }
                    // 二级：缩进 + 竖线，层级一眼能看出来
                    if (kids.isNotEmpty() && parent.id in expanded) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            kids.forEach { c ->
                                val on = c.id in draft.categoryIds
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (on) accent else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable {
                                            draft = draft.copy(
                                                categoryIds = if (on) draft.categoryIds - c.id
                                                else draft.categoryIds + c.id
                                            )
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        c.name,
                                        fontSize = 12.5.sp,
                                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                                        color = if (on) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        /* ---------------- 底部：重置 / 完成（钉住） ---------------- */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { draft = HomeFilter() },
                contentAlignment = Alignment.Center
            ) {
                Text("重置", fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent)
                    .clickable { onApply(draft); onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Text("完成", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }

    if (pickingStart) {
        PickDate(
            title = "开始日期",
            initial = draft.from ?: System.currentTimeMillis(),
            onPick = { millis -> draft = draft.copy(from = dayStart(millis)) },
            onDismiss = { pickingStart = false }
        )
    }
    if (pickingEnd) {
        PickDate(
            title = "结束日期",
            initial = draft.to ?: System.currentTimeMillis(),
            onPick = { millis -> draft = draft.copy(to = dayEnd(millis)) },
            onDismiss = { pickingEnd = false }
        )
    }
}

/* ------------------------------------------------------------------ */
/* 筛选弹层里的小件                                                    */
/* ------------------------------------------------------------------ */

@Composable
private fun FilterSectionHead(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 13.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AppTheme.primary)
        )
        Spacer(Modifier.width(7.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(
                if (selected) AppTheme.primary else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            // 胶囊文字永远单行：宁可换到下一行胶囊，也不把「今年」压成两字一行
            maxLines = 1,
            softWrap = false,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun DateSlot(
    label: String,
    millis: Long?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            millis?.let { shortDate(it) } ?: "不限",
            modifier = Modifier.weight(1f),
            fontSize = 13.5.sp,
            fontWeight = if (millis == null) FontWeight.Normal else FontWeight.Medium,
            color = if (millis == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 系统日期选择器（Material3），选完给的是当天 UTC 零点毫秒 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickDate(
    title: String,
    initial: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = dayStart(initial)
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let {
                    // DatePicker 给 UTC 零点，转成本地当天零点再交出去
                    val local = utcMillisToLocalDate(it)
                    onPick(local.toLocalMillis())
                }
                onDismiss()
            }) { Text("确定", color = AppTheme.primary, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    ) {
        DatePicker(
            state = state,
            title = {
                Text(
                    title,
                    modifier = Modifier.padding(start = 24.dp, top = 20.dp),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            showModeToggle = true
        )
    }
}
