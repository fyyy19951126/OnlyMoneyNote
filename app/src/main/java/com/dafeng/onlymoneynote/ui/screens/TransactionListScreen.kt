package com.dafeng.onlymoneynote.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeng.onlymoneynote.data.local.CategoryEntity
import com.dafeng.onlymoneynote.data.local.TxType
import com.dafeng.onlymoneynote.data.local.TxWithCategory
import com.dafeng.onlymoneynote.ui.LedgerViewModel
import com.dafeng.onlymoneynote.ui.components.IconTile
import com.dafeng.onlymoneynote.ui.components.PageHeader
import com.dafeng.onlymoneynote.ui.theme.AppTheme
import com.dafeng.onlymoneynote.util.AppIcons
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 账单页（唯一的主界面）。
 *
 * - 蓝色渐变页头：App 名称（连点 3 下改名）+ **标题右侧 5 个入口**
 *   （分类管理 / 云端备份 / 导入导出 / 主题外观 / 关于，从原来的「我的」页挪过来的）
 *   + 支出 / 收入白色大数字（点按筛选）+ 结余，结余同一行右对齐放「统计 / 报销·N 笔」
 * - 白色大圆角面板：按天分组的账单列表，日期行灰色小字 + 当日收支小计
 * - 右下角**浮空 ＋** 记一笔（底栏已经去掉，不再占一整条屏幕高度）
 *
 * 交互约定不变：单击改、左滑停住再点删除。
 */
@Composable
fun TransactionListScreen(
    transactions: List<TxWithCategory>,
    /** 全部分类：筛选弹层要按一级 / 二级层级列出来 */
    categories: List<CategoryEntity>,
    monthExpense: Long,
    monthIncome: Long,
    appTitle: String,
    onSetTitle: (String) -> Unit,
    /** 报销入口后面备注的笔数 */
    reimburseCount: Int,
    onDelete: (Long) -> Unit,
    onEdit: (TxWithCategory) -> Unit,
    onAdd: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenReimburse: () -> Unit,
    onOpenCategory: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenIo: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenAbout: () -> Unit
) {
    var openedId by remember { mutableStateOf<Long?>(null) }
    val balance = monthIncome - monthExpense

    // 顶部「支出/收入」点一下筛选：null=全部，否则只显示该类型；再点一次回到全部
    var typeFilter by remember { mutableStateOf<Int?>(null) }
    fun toggleType(t: Int) {
        typeFilter = if (typeFilter == t) null else t
    }

    // 「筛选」弹层：日期（或日期范围）+ 分类（一级带二级）
    var filter by remember { mutableStateOf(HomeFilter()) }
    var filterSheet by remember { mutableStateOf(false) }

    // 选中一级 = 连它下面的二级一起算，所以先把允许的 id 展开成集合
    val allowedCategoryIds = remember(categories, filter.categoryIds) {
        if (filter.categoryIds.isEmpty()) emptySet()
        else filter.categoryIds + categories
            .filter { it.parentId != null && it.parentId in filter.categoryIds }
            .map { it.id }
    }
    val shown = remember(transactions, typeFilter, filter, allowedCategoryIds) {
        // 先取成局部变量：filter 是 delegated var，直接写 filter.from 编译器不给智能转换
        val from = filter.from
        val to = filter.to
        val type = typeFilter
        transactions.filter { tx ->
            (type == null || tx.type == type) &&
                (from == null || tx.dateMillis >= from) &&
                (to == null || tx.dateMillis <= to) &&
                (allowedCategoryIds.isEmpty() || tx.categoryId in allowedCategoryIds)
        }
    }
    // 筛选生效时页头那条摘要
    val filterSummary = remember(filter, categories) {
        val parts = mutableListOf<String>()
        if (filter.from != null && filter.to != null) {
            parts += matchPreset(filter)
                ?: (shortDate(filter.from!!) + " - " + shortDate(filter.to!!))
        } else if (filter.from != null) {
            parts += shortDate(filter.from!!) + " 起"
        } else if (filter.to != null) {
            parts += "截至 " + shortDate(filter.to!!)
        }
        if (filter.categoryIds.isNotEmpty()) {
            val names = filter.categoryIds.mapNotNull { id ->
                categories.firstOrNull { it.id == id }?.name
            }
            parts += if (names.size <= 2) names.joinToString("、")
            else names.first() + " 等 " + names.size + " 类"
        }
        parts.joinToString(" · ")
    }

    // 主页标题：连点 3 下进入改名（防误触）；改名时显示输入框
    var titleClicks by remember { mutableStateOf(0) }
    var lastTitleTap by remember { mutableStateOf(0L) }
    var editingTitle by remember { mutableStateOf(false) }
    var draftTitle by remember { mutableStateOf(appTitle) }

    // 分组是纯计算，remember 缓存
    val grouped = remember(shown) {
        shown.groupBy { it.dateMillis.atDay() }
    }

    // 整页套一层 Box：右下角的浮空「＋」要 align 到屏幕底部
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        /* ---------- 蓝色渐变页头 ---------- */
        PageHeader {
            Column(
                // 底部留白从 26dp 收到 14dp：结余下面那段空行太宽（用户反馈）
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 14.dp)
            ) {
                if (editingTitle) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = draftTitle,
                            onValueChange = { draftTitle = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            maxLines = 1,
                            textStyle = TextStyle(
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            ),
                            cursorBrush = SolidColor(Color.White),
                            decorationBox = { inner ->
                                if (draftTitle.isEmpty()) {
                                    Text(
                                        "输入名称",
                                        fontSize = 24.sp,
                                        color = Color.White.copy(alpha = 0.5f)
                                    )
                                }
                                inner()
                            }
                        )
                        Icon(
                            Icons.Outlined.Check, "完成",
                            modifier = Modifier
                                .size(24.dp)
                                .clickable {
                                    onSetTitle(draftTitle.ifBlank { "Only记账" })
                                    editingTitle = false
                                },
                            tint = Color.White
                        )
                    }
                } else {
                    // 标题 + 右侧 5 个入口（原「我的」页那五项，按用户要求挪到首页标题后面）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            appTitle,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val now = System.currentTimeMillis()
                                    titleClicks = if (now - lastTitleTap < 1500) titleClicks + 1 else 1
                                    lastTitleTap = now
                                    if (titleClicks >= 3) {
                                        draftTitle = appTitle
                                        editingTitle = true
                                        titleClicks = 0
                                    }
                                },
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        HeaderEntry(Icons.Outlined.Category, "分类管理") { onOpenCategory() }
                        HeaderEntry(Icons.Outlined.Cloud, "云端备份") { onOpenBackup() }
                        HeaderEntry(Icons.Outlined.UploadFile, "导入导出") { onOpenIo() }
                        HeaderEntry(Icons.Outlined.Palette, "主题外观") { onOpenTheme() }
                        // 2026-10-09 用户新要求：去掉红点，更新提示只保留在「关于」的版本号行
                        HeaderEntry(Icons.Outlined.Info, "关于") { onOpenAbout() }
                    }
                }

                Spacer(Modifier.height(16.dp))

                /* ---------- 支出 / 收入（白字大数字，点按筛选） ---------- */
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    HeaderAmount(
                        label = "支出",
                        cents = monthExpense,
                        active = typeFilter == TxType.EXPENSE.value,
                        onClick = { toggleType(TxType.EXPENSE.value) }
                    )
                    HeaderAmount(
                        label = "收入",
                        cents = monthIncome,
                        active = typeFilter == TxType.INCOME.value,
                        onClick = { toggleType(TxType.INCOME.value) }
                    )
                }

                Spacer(Modifier.height(10.dp))
                // 结余在左；「统计 / 报销」跟在结余后面、整行右对齐
                // （这俩原来是底栏上的 Tab，底栏去掉后就挪到页头）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        (if (balance >= 0) "+" else "-") + "¥" +
                            LedgerViewModel.formatCents(abs(balance)) + " 结余",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                    Spacer(Modifier.weight(1f))
                    // 筛选入口放在统计左边；生效时文字带个点、底色加深
                    HeaderLink(
                        text = if (filter.active) "筛选 ·" else "筛选",
                        highlight = filter.active
                    ) { filterSheet = true }
                    Spacer(Modifier.width(7.dp))
                    HeaderLink("统计") { onOpenStats() }
                    Spacer(Modifier.width(7.dp))
                    HeaderLink(
                        // 报销后面备注笔数，一眼知道有没有待处理的
                        if (reimburseCount > 0) "报销 · $reimburseCount 笔" else "报销"
                    ) { onOpenReimburse() }
                }

                // 筛选生效时：摘要 + 笔数 + 一键清除
                if (filter.active) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            filterSummary,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.18f))
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "共 " + shown.size + " 笔",
                            fontSize = 11.5.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "清除",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { filter = HomeFilter() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            color = Color.White
                        )
                    }
                }
            }
        }

        /* ---------- 白色大圆角面板：账单都在里面 ---------- */
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            if (shown.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Filled.ReceiptLong,
                            contentDescription = null,
                            modifier = Modifier.size(52.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (typeFilter == null) "还没有账单，点右下角 ＋ 记一笔"
                            else "没有符合条件的账单",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // 底部留 96dp：右下角的浮空 ＋ 会盖在列表上，
                    // 不留够最后几笔就永远点不到
                    contentPadding = PaddingValues(
                        start = 14.dp, end = 14.dp, top = 2.dp, bottom = 96.dp
                    )
                ) {
                    grouped.forEach { (day, list) ->
                        item(key = "header-$day", contentType = "header") {
                            val totals = remember(list) { dayTotals(list) }
                            DayHeader(
                                day = day,
                                expense = totals.first,
                                income = totals.second
                            )
                        }
                        items(list, key = { it.id }, contentType = { "tx" }) { tx ->
                            SwipeableTxRow(
                                tx = tx,
                                isOpen = openedId == tx.id,
                                // 别的行正展开着删除时，本行不许点进编辑 ——
                                // 否则看着像点了 A 行，实际打开的是 B 行
                                interactive = openedId == null || openedId == tx.id,
                                onOpenChange = { open ->
                                    openedId = if (open) tx.id else null
                                },
                                // 删完必须把 openedId 清掉 —— 否则它一直指向已删除的 id，
                                // 所有行的 interactive 都变 false，就再也点不进编辑了
                                onDelete = { txId ->
                                    openedId = null
                                    onDelete(txId)
                                },
                                onEdit = onEdit
                            )
                        }
                    }
                }
            }
        }
      }   // Column

        /* ---------- 右下角浮空「＋」：记一笔 ---------- */
        FloatingActionButton(
            onClick = onAdd,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 24.dp),
            containerColor = AppTheme.primary,
            contentColor = Color.White,
            shape = RoundedCornerShape(18.dp),
            elevation = FloatingActionButtonDefaults.elevation(6.dp, 8.dp, 8.dp, 10.dp)
        ) {
            Icon(Icons.Outlined.Add, "记一笔", modifier = Modifier.size(26.dp))
        }
        /* ---------- 筛选弹层 ---------- */
        if (filterSheet) {
            HomeFilterSheet(
                categories = categories,
                initialType = typeFilter ?: 0,
                filter = filter,
                onDismiss = { filterSheet = false },
                onApply = { filter = it }
            )
        }
    }   // Box
}

/* ------------------------------------------------------------------ */
/* 页头入口                                                            */
/* ------------------------------------------------------------------ */

/** 页头标题右侧的图标入口（白色线性图标 + 半透明白底圆角方块） */
@Composable
private fun HeaderEntry(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(start = 5.dp)
            .size(34.dp)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(11.dp))
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, label,
                modifier = Modifier.size(18.dp),
                tint = Color.White.copy(alpha = 0.95f)
            )
        }
    }
}

/** 页头文字入口（统计 / 报销） */
@Composable
private fun HeaderLink(
    text: String,
    /** 生效中的筛选项：底色加深，提醒下面的列表是被筛过的 */
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(Color.White.copy(alpha = if (highlight) 0.30f else 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        fontSize = 12.sp,
        fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Medium,
        color = Color.White
    )
}

/* ------------------------------------------------------------------ */
/* 页头数字                                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun HeaderAmount(
    label: String,
    cents: Long,
    active: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(Color.White.copy(alpha = if (active) 0.18f else 0f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = if (active) 0.95f else 0.72f)
        )
        Text(
            "¥" + LedgerViewModel.formatCents(cents),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/* ------------------------------------------------------------------ */

/** 日期分组行：灰色小字日期 + 当日收支柱小计（支付宝账单页样式） */
@Composable
internal fun DayHeader(day: DayLabel, expense: Long, income: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${day.monthDay} ${day.weekday}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "+¥" + LedgerViewModel.formatCents(income),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.income
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "-¥" + LedgerViewModel.formatCents(expense),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.expense
            )
        }
    }
}

/** 手指左滑后露出的宽度 */
private val REVEAL_WIDTH = 92.dp

/**
 * 可左滑的账单项。
 *
 * 这里没用 `SwipeToDismissBox`——它的 confirmValueChange 只有「过阈值就删除」两种结果，
 * 做不到「滑开停住、等用户点删除」。所以自己用 draggable + Animatable 控制位移：
 * 松手后吸附到「露出」或「收回」两个位置，删除必须真的点到删除键。
 */
@Composable
internal fun SwipeableTxRow(
    tx: TxWithCategory,
    isOpen: Boolean,
    /** false 时整行不响应点击（别的行正展开着删除）。滑动仍然可用。 */
    interactive: Boolean = true,
    onOpenChange: (Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onEdit: (TxWithCategory) -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val revealPx = with(density) { REVEAL_WIDTH.toPx() }
    val offsetX = remember { Animatable(0f) }

    // 外部把这条关掉时（比如滑了别的行），同步收回
    LaunchedEffect(isOpen) {
        val target = if (isOpen) -revealPx else 0f
        if (offsetX.value != target) {
            offsetX.animateTo(target, tween(180))
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        // ---- 底层：删除按钮（平时被上面的不透明行完全盖住） ----
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(14.dp))
                .background(AppTheme.danger),
            contentAlignment = Alignment.CenterEnd
        ) {
            Row(
                modifier = Modifier
                    .padding(end = 24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onDelete(tx.id) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline, "删除",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("删除", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            }
        }

        // ---- 上层：卡片，可横向拖动 ----
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            offsetX.snapTo((offsetX.value + delta).coerceIn(-revealPx, 0f))
                        }
                    },
                    onDragStopped = {
                        val shouldOpen = offsetX.value < -revealPx * 0.4f
                        offsetX.animateTo(if (shouldOpen) -revealPx else 0f, tween(180))
                        onOpenChange(shouldOpen)
                    }
                )
        ) {
            TxRow(
                tx = tx,
                interactive = interactive,
                onEdit = {
                    if (offsetX.value != 0f) {
                        scope.launch { offsetX.animateTo(0f, tween(180)) }
                        onOpenChange(false)
                    } else {
                        onEdit(tx)
                    }
                }
            )
        }
    }
}

@Composable
private fun TxRow(
    tx: TxWithCategory,
    interactive: Boolean = true,
    onEdit: (TxWithCategory) -> Unit
) {
    val isExpense = tx.type == TxType.EXPENSE.value
    val color = AppTheme.byType(isExpense)

    // 注意：这一层**必须是不透明的** —— 下面压着红色删除层，背景一透明
    // 删除按钮就直接显在表面上。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = interactive) { onEdit(tx) }
            .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 彩色分类图标块（支付宝式）：一级分类自定义了配色就跟着走
        IconTile(
            iconKey = tx.iconKey,
            size = 38.dp,
            cornerRadius = 12.dp,
            overrideColor = AppIcons.colorFromKey(tx.colorKey)
        )
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = listOfNotNull(tx.parentName, tx.categoryName)
                    .distinct()
                    .joinToString(" · "),
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeLabel(tx.dateMillis),
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 报销标记：跟普通账单一眼能分开。选中报销的账单才有。
                if (tx.reimbursed) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "报销",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppTheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppTheme.primary.copy(alpha = 0.10f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                if (tx.note.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = tx.note,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = (if (isExpense) "-¥" else "+¥") + LedgerViewModel.formatCents(tx.amountCents),
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }
}

/* ------------------------------------------------------------------ */
/* 小工具                                                              */
/* ------------------------------------------------------------------ */

internal data class DayLabel(val monthDay: String, val weekday: String)

internal fun Long.atDay(): DayLabel {
    val c = Calendar.getInstance().apply { timeInMillis = this@atDay }
    return DayLabel(
        monthDay = "%d月%d日".format(
            c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        ),
        weekday = "周" + "日一二三四五六"[c.get(Calendar.DAY_OF_WEEK) - 1]
    )
}

internal fun dayTotals(list: List<TxWithCategory>): Pair<Long, Long> {
    var expense = 0L
    var income = 0L
    list.forEach {
        if (it.type == TxType.EXPENSE.value) expense += it.amountCents
        else income += it.amountCents
    }
    return expense to income
}

private fun timeLabel(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}
