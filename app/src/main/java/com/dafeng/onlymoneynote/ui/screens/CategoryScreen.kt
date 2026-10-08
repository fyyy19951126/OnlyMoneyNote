package com.dafeng.onlymoneynote.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.dafeng.onlymoneynote.data.local.CategoryEntity
import com.dafeng.onlymoneynote.data.local.TxType
import com.dafeng.onlymoneynote.data.local.TxWithCategory
import com.dafeng.onlymoneynote.ui.components.IconTile
import com.dafeng.onlymoneynote.ui.theme.AppTheme
import com.dafeng.onlymoneynote.util.AppIcons
import com.dafeng.onlymoneynote.util.CategoryIcon

/**
 * 分类管理。
 *
 * 交互参考截图：
 * - 顶部居中「支出 / 收入」切换，网格里的分类跟着切换
 * - 一级分类用**两列网格**，每个是一张圆角卡片（图标 + 名称）
 * - **点卡片** → 弹出这个分类的二级管理弹窗：二级列表 + 添加 + 删除/修改
 * - 弹窗里的二级分类支持长按拖动排序（同一父级内）
 * - 右下角 + FAB：新建一级分类（按当前切换的收支类型）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CategoryScreen(
    categories: List<CategoryEntity>,
    /** 全量账单，用来判断某个分类名下有没有占用 */
    transactions: List<TxWithCategory> = emptyList(),
    onAdd: (String, String, Long?, Int?, String) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
    /** 名下有账单时的删除：先全部转移到 targetId 再删 */
    onMoveAndDelete: (CategoryEntity, Long) -> Unit = { _, _ -> },
    onUpdate: (CategoryEntity, String, String, String?) -> Unit,
    onMove: (CategoryEntity, Boolean) -> Unit,
    onReorder: (Long?, List<Long>) -> Unit
) {
    // 0 = 支出，1 = 收入
    var tab by remember { mutableStateOf(0) }
    // 正在管理（弹窗中）的一级分类
    var managing by remember { mutableStateOf<CategoryEntity?>(null) }
    // 编辑（改名换图标）目标
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    // 新建弹层：null 没开；否则 Pair(是否二级, 父id)
    var creating by remember { mutableStateOf<Pair<Boolean, Long?>?>(null) }
    // 待转移删除的分类 + 占用笔数；null 表示没弹
    var reassigning by remember { mutableStateOf<Pair<CategoryEntity, Int>?>(null) }

    // 删除入口统一走这里：有账单先弹转移选择，没有才直接删
    fun requestDelete(c: CategoryEntity) {
        val used = transactions.count { it.categoryId == c.id }
        if (used > 0) reassigning = c to used else onDelete(c)
    }

    val allParents = remember(categories) { categories.filter { it.parentId == null } }
    val parents = remember(allParents, tab) {
        allParents.filter { it.type == tab }.sortedBy { it.sortOrder }
    }
    // 拖动排序用的**本地顺序**：拖的过程中实时改这个列表（配合 animateItemPlacement
    // 让别的卡片滑开让位），松手才一次性写库。
    // 之前是「拖的时候什么反应都没有，松手才跳位」，落点全靠猜。
    var localParents by remember(tab, parents) { mutableStateOf(parents) }
    // 正在拖动的一级卡片 id：被拖的那张不参与位移动画（见 CategoryGridCard）
    var draggingParentId by remember { mutableStateOf<Long?>(null) }

    // 页面级强调色统一主题蓝（支付宝式）；收支区分只留在顶部切换胶囊上
    val accent = AppTheme.primary

    // 网格单格尺寸（px）：拖动排序时把位移换算成跨几行几列要用到。
    // 与 LazyVerticalGrid 的参数保持一致：2 列、左右 14dp、中间间距 12dp、卡片 76dp 高
    val density = LocalDensity.current
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val cellW = screenW - 14.dp * 2 - 12.dp      // 两列 + 一个间距
    val cellWPx = with(density) { (cellW / 2).toPx() }
    val cellHPx = with(density) { (60.dp + 20.dp).toPx() }   // 卡片高 + 行间距

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            /* ---------- 支出 / 收入 切换 ---------- */
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                SegmentedTab(
                    labels = listOf("支出", "收入"),
                    selected = tab,
                    colors = listOf(AppTheme.expense, AppTheme.income),
                    onSelect = { tab = it }
                )
            }

            /* ---------- 操作提示（数量文案按用户要求去掉了，太挤也不好看） ---------- */
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "点卡片管理子分类 · 长按拖动排序",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }

            /* ---------- 两列网格 ---------- */
            if (parents.isEmpty()) {
                // 空状态：别让用户对着一片空白猜
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Add, null,
                            modifier = Modifier.size(26.dp),
                            tint = accent
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "还没有${if (tab == 0) "支出" else "收入"}分类",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "点右下角 + 新建一个",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(
                        start = 14.dp, end = 14.dp, top = 4.dp, bottom = 110.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    // 行距加大到 20dp：之前 12dp 太挤，点卡片容易误触到隔壁
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    itemsIndexed(localParents, key = { _, it -> it.id }) { idx, p ->
                        CategoryGridCard(
                            category = p,
                            index = idx,
                            cellW = cellWPx,
                            cellH = cellHPx,
                            total = localParents.size,
                            // 被拖的那张不做位移动画：它的落点由 translation 精确补偿，
                            // 再叠一层 animateItemPlacement 会「先弹回去再滑过来」。
                            modifier = if (draggingParentId == p.id) Modifier
                            else Modifier.animateItemPlacement(),
                            onDraggingChanged = { on ->
                                draggingParentId = if (on) p.id else null
                            },
                            // 拖动过程中实时换位：只改本地顺序，不写库
                            onMoveTo = { target ->
                                val list = localParents.toMutableList()
                                val from = list.indexOf(p)
                                if (from in list.indices && target in list.indices && from != target) {
                                    list.removeAt(from)
                                    list.add(target, p)
                                    localParents = list
                                }
                            },
                            // 松手一次性提交最终顺序
                            onDragFinished = {
                                onReorder(null, localParents.map { it.id })
                            },
                            onClick = { managing = p }
                        )
                    }
                }
            }
        }

        /* ---------- 新建一级分类 FAB：主题蓝，跟全局一致 ---------- */
        FloatingActionButton(
            onClick = { creating = false to null },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 24.dp),
            containerColor = accent,
            contentColor = Color.White,
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "新建分类")
        }
    }

    /* ---------- 二级管理弹窗 ---------- */
    managing?.let { p ->
        ManageCategoryDialog(
            parent = p,
            children = categories.filter { it.parentId == p.id }.sortedBy { it.sortOrder },
            onDismiss = { managing = null },
            onAddChild = { creating = true to p.id },
            onEditChild = { editing = it },
            onMoveChild = { c, up -> onMove(c, up) },
            onDeleteChild = { requestDelete(it) },
            onDeleteParent = {
                requestDelete(p)
                managing = null
            },
            onEditParent = {
                editing = p
                managing = null
            },
            onReorderChildren = { orderedIds ->
                onReorder(p.id, orderedIds)
            }
        )
    }

    /* ---------- 新建 / 编辑弹层 ---------- */
    if (creating != null) {
        val (isChild, parentId) = creating!!
        CategoryEditorSheet(
            target = null,
            isCreate = true,
            parentId = parentId,
            parentType = if (isChild) {
                parentId?.let { pid -> allParents.firstOrNull { it.id == pid }?.type } ?: tab
            } else tab,
            allParents = parents,
            onDismiss = { creating = null },
            onConfirm = { name, icon, pid, ck ->
                onAdd(name, icon, pid, null, ck ?: "")
                creating = null
            }
        )
    }
    editing?.let { target ->
        CategoryEditorSheet(
            target = target,
            isCreate = false,
            parentId = target.parentId,
            parentType = target.type,
            allParents = allParents,
            onDismiss = { editing = null },
            onConfirm = { name, icon, _, ck ->
                onUpdate(target, name, icon, ck)
                editing = null
            },
            onDelete = {
                editing = null
                requestDelete(target)
            }
        )
    }

    /* ---------- 删除占用分类：选个分类把账单转移过去 ---------- */
    reassigning?.let { (cat, used) ->
        ReassignDialog(
            from = cat,
            usedCount = used,
            candidates = categories.filter { it.type == cat.type && it.id != cat.id },
            allCategories = categories,
            onDismiss = { reassigning = null },
            onPick = { target ->
                reassigning = null
                onMoveAndDelete(cat, target.id)
            }
        )
    }
}

@Composable
private fun ReassignDialog(
    from: CategoryEntity,
    usedCount: Int,
    candidates: List<CategoryEntity>,
    /** 全量分类，用来解析候选分类的图标块颜色（二级继承一级） */
    allCategories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onPick: (CategoryEntity) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "「${from.name}」名下有 $usedCount 笔账单",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            if (candidates.isEmpty()) {
                Text(
                    "没有其他分类可以转移，先新建一个分类再删",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column {
                    Text(
                        "选一个分类，把这些账单转移过去：",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(
                        modifier = Modifier.height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(candidates, key = { it.id }) { c ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                    )
                                    .clickable { onPick(c) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconTile(
                                    iconKey = c.iconKey, size = 34.dp, cornerRadius = 10.dp,
                                    overrideColor = AppIcons.colorFromKey(
                                        allCategories.firstOrNull { it.id == c.parentId }?.colorKey
                                            ?: c.colorKey
                                    )
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    c.name,
                                    modifier = Modifier.weight(1f),
                                    fontSize = 14.5.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/* ------------------------------------------------------------------ */
/* 顶部切换胶囊                                                        */
/* ------------------------------------------------------------------ */

@Composable
/** 支出 / 收入切换。选中的那半边用**对应类型色**（支出绿、收入红），一眼能看出在看哪边。 */
private fun SegmentedTab(
    labels: List<String>,
    selected: Int,
    /** 每个 tab 对应的强调色（支出 / 收入） */
    colors: List<Color>,
    onSelect: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            labels.forEachIndexed { i, label ->
                val sel = i == selected
                val tint = colors.getOrElse(i) { MaterialTheme.colorScheme.primary }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(17.dp))
                        .background(
                            if (sel) tint else Color.Transparent
                        )
                        .clickable { onSelect(i) }
                        .padding(horizontal = 26.dp, vertical = 8.dp)
                ) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        // 选中时用白色字压在实色底上，更清晰
                        color = if (sel) Color.White
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* 一级分类网格卡片                                                    */
/* ------------------------------------------------------------------ */

@Composable
private fun CategoryGridCard(
    category: CategoryEntity,
    /** 本卡片在网格里的位置（拖动过程中会随本地顺序实时变） */
    index: Int,
    /** 网格单格宽高（px），用来把拖动位移换算成跨了几行几列 */
    cellW: Float,
    cellH: Float,
    /** 一级分类总数，用来把落点限制在范围内 */
    total: Int,
    /** 外层给的位移动画（被拖的那张传 Modifier，不参与 animateItemPlacement） */
    modifier: Modifier = Modifier,
    /** 拖动状态上报：外层据此决定谁不做位移动画 */
    onDraggingChanged: (Boolean) -> Unit = {},
    /** 拖动过程中实时换位（只改本地顺序，松手才写库） */
    onMoveTo: (Int) -> Unit = {},
    /** 松手 / 取消：提交当前本地顺序 */
    onDragFinished: () -> Unit = {},
    onClick: () -> Unit
) {
    // 支付宝式卡片：白底 + 分类组色的图标块，颜色跟着分类内容走，
    // 和账单列表里的彩色图标一套观感；不再按收支类型整卡染色。
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    // 拖动中的实时位移：卡片跟着手指走，松手归位（网格自己会做位置交换动画）
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // 手势闭包里必须读**最新**的 index/total：拖动过程中列表会实时重排，
    // 用捕获的旧值算落点会越拖越偏。
    val curIndex by rememberUpdatedState(index)
    val curTotal by rememberUpdatedState(total)

    Surface(
        modifier = modifier
            .zIndex(if (dragging) 1f else 0f)
            .fillMaxWidth()
            // 60dp 高：只剩图标 + 名称，不用给徽章留第二行了
            .height(60.dp)
            .clip(RoundedCornerShape(16.dp))
            // 长按拖动排序：位移换算成「跨了几行几列」得出落点
            .pointerInput(category.id) {
                var acc = Offset.Zero
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragging = true
                        onDraggingChanged(true)
                        acc = Offset.Zero
                        dragOffset = Offset.Zero
                        // 起手给一下震动，明确告诉用户「已经进入拖动模式」，
                        // 不然长按和误触难以区分
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        acc += amount
                        // 网格是 2 列：行位移贡献 ±2，列位移贡献 ±1
                        val rowShift = (acc.y / cellH).roundToInt()
                        val colShift = (acc.x / cellW).roundToInt()
                        val target = (curIndex + rowShift * 2 + colShift)
                            .coerceIn(0, (curTotal - 1).coerceAtLeast(0))
                        if (target != curIndex) {
                            // 换位之后卡片基线挪了，把这段差值从位移里扣掉，
                            // 卡片才继续停在手指底下，不会「跳一下」。
                            val dCol = (target % 2) - (curIndex % 2)
                            val dRow = (target / 2) - (curIndex / 2)
                            acc -= Offset(dCol * cellW, dRow * cellH)
                            onMoveTo(target)
                        }
                        dragOffset = acc
                    },
                    onDragEnd = {
                        dragging = false
                        onDraggingChanged(false)
                        dragOffset = Offset.Zero
                        onDragFinished()
                    },
                    onDragCancel = {
                        dragging = false
                        onDraggingChanged(false)
                        dragOffset = Offset.Zero
                        onDragFinished()
                    }
                )
            }
            .graphicsLayer {
                translationX = dragOffset.x
                translationY = dragOffset.y
                if (dragging) {
                    scaleX = 1.05f
                    scaleY = 1.05f
                    shadowElevation = 18f
                }
            }
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        // 拖动时抬起来一点，跟底下的卡片拉开层级。
        // 描边用**中性 outline**，不用主题色：主题一换成红色系，
        // 主题色描边就变成一个红框挂在卡片上，跟「危险/删除」撞信号。
        shadowElevation = if (dragging) 6.dp else 1.dp,
        border = if (dragging) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 彩色分类图标块：优先用一级分类自定义的配色
            IconTile(
                iconKey = category.iconKey, size = 40.dp, cornerRadius = 13.dp,
                overrideColor = AppIcons.colorFromKey(category.colorKey)
            )
            Spacer(Modifier.width(10.dp))
            // 只有名字：数量徽章去掉了（用户觉得丑，而且「10 个子分类」还会折行）
            Text(
                category.name,
                modifier = Modifier.weight(1f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            // 三条横线：跟二级行同一套把手，告诉用户「长按可以拖动排序」
            DragLines(
                modifier = Modifier.padding(end = 10.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* 二级管理弹窗（长按/点卡片弹出）                                       */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ManageCategoryDialog(
    parent: CategoryEntity,
    children: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onAddChild: () -> Unit,
    onEditChild: (CategoryEntity) -> Unit,
    onMoveChild: (CategoryEntity, Boolean) -> Unit,
    onDeleteChild: (CategoryEntity) -> Unit,
    onDeleteParent: () -> Unit,
    onEditParent: () -> Unit,
    onReorderChildren: (List<Long>) -> Unit
) {
    // 行高实测值（px）：拖动落点按「拖过了几行」算，写死 52dp 跟真实行高对不上，
    // 拖得越多累计误差越大（表现就是「拖到第五个却排到第三」）。
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    // 正在拖动的二级行：它自己不做位移动画，别的行才滑开让位
    var draggingChildId by remember { mutableStateOf<Long?>(null) }
    // 弹窗里的强调色（选中态、拖动高亮、按钮）统一主题蓝，跟页面一致
    val accent = AppTheme.primary
    // 分类本体色：一级自定义配色优先，没自定义才回退到图标分组色。
    // 标题图标块、每一行的图标块、添加二级入口全用这一个色，
    // 这样「一个分类 = 一个颜色」在弹窗里也是统一的。
    val tileColor = AppIcons.colorFromKey(parent.colorKey)
        ?: AppIcons.categoryTileColor(parent.iconKey)
    // 二级分类的本地顺序（拖动时实时变，松手提交）
    var childOrder by remember(children) {
        mutableStateOf(children.map { it.id })
    }
    val orderedChildren = remember(children, childOrder) {
        val byId = children.associateBy { it.id }
        childOrder.mapNotNull { byId[it] } + children.filter { it.id !in childOrder }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            // 整条标题可点 → 直接进「编辑这个一级分类」（改名 / 换图标 / 换配色 / 删除都在里面）。
            // 底部那两个「删除 / 修改」文字按钮去掉了：入口重复，还占掉一行高度。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onEditParent)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 彩色图标块，跟网格卡片**同一个颜色**（一级自定义配色）
                IconTile(
                    iconKey = parent.iconKey, size = 40.dp, cornerRadius = 12.dp,
                    overrideColor = tileColor
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(parent.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "点一下改名 / 换图标 / 删除",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Outlined.Edit, "编辑这个一级分类",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column {
                if (orderedChildren.isEmpty()) {
                    // 空状态用分类本体色打底，跟卡片图标块一个色系
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(tileColor.copy(alpha = 0.08f))
                            .padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "还没有子分类",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "点下面的「添加二级分类」建一个",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // 二级列表：LazyColumn + animateItemPlacement，
                    // 拖动换位时其他行有滑开让位的动画（之前是普通 Column，瞬间跳位）。
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        items(orderedChildren, key = { it.id }) { c ->
                            ChildManageRow(
                                child = c,
                                // 二级分类配色永远跟一级走
                                tileColor = tileColor,
                                modifier = if (draggingChildId == c.id) Modifier
                                else Modifier.animateItemPlacement(),
                                onDraggingChanged = { on ->
                                    draggingChildId = if (on) c.id else null
                                },
                                onEdit = { onEditChild(c) },
                                onDragBy = { steps ->
                                    val cur = childOrder.toMutableList()
                                    val from = cur.indexOf(c.id)
                                    if (from >= 0) {
                                        cur.removeAt(from)
                                        cur.add((from + steps).coerceIn(0, cur.size), c.id)
                                        childOrder = cur
                                    }
                                },
                                onDragEnd = {
                                    onReorderChildren(childOrder)
                                },
                                rowHeightPx = rowHeightPx,
                                onRowMeasured = { h ->
                                    if (rowHeightPx == 0f) rowHeightPx = h
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 添加二级分类：底色用分类本体色，跟这个弹窗里的其他色块一致
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(tileColor.copy(alpha = 0.08f))
                        .clickable(onClick = onAddChild)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(tileColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Add, null,
                            modifier = Modifier.size(19.dp),
                            tint = Color.White
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "添加二级分类",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        // 底部不再有「删除 / 修改」：点标题就进编辑层，删除在编辑层里
        confirmButton = {},
        dismissButton = {}
    )
}

/** 弹窗里的二级分类行 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChildManageRow(
    child: CategoryEntity,
    /** 图标块颜色：二级分类永远跟一级走 */
    tileColor: Color? = null,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDragBy: (Int) -> Unit,
    onDragEnd: () -> Unit,
    /** 拖动状态上报（外层据此决定这一行做不做位移动画） */
    onDraggingChanged: (Boolean) -> Unit = {},
    /** 实测行高（px），0 = 还没测到 */
    rowHeightPx: Float,
    onRowMeasured: (Float) -> Unit = {}
) {
    val haptics = LocalHapticFeedback.current
    var dragAccum by remember { mutableStateOf(0f) }
    var dragActive by remember { mutableStateOf(false) }
    // 落点用的行高：优先实测值，没测到之前按 52dp 兜底
    val rowH = if (rowHeightPx > 0f) rowHeightPx
    else with(LocalDensity.current) { 52.dp.toPx() }

            Row(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { if (rowHeightPx == 0f) onRowMeasured(it.height.toFloat()) }
            // 拖动中的反馈：**中性色**浮起（灰底 + 浅描边 + 阴影 + 轻微放大）。
            // 之前用主题色描边，主题一换成红色系就变成一个红框，
            // 跟「删除/危险」同一个信号，看着非常不协调（用户反馈）。
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (dragActive) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
                else Color.Transparent,
                RoundedCornerShape(14.dp)
            )
            .then(
                if (dragActive) Modifier.border(
                    1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)
                ) else Modifier
            )
            .graphicsLayer {
                translationY = if (dragActive) dragAccum else 0f
                if (dragActive) {
                    scaleX = 1.03f
                    scaleY = 1.03f
                    shadowElevation = 18f
                }
            }
            .pointerInput(child.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragAccum = 0f
                        dragActive = true
                        onDraggingChanged(true)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragAccum += amount.y
                        // 拖过 0.6 行就换位：既跟手，又不会在临界点来回抖动
                        val steps = (dragAccum / (rowH * 0.6f)).toInt()
                        if (steps != 0) {
                            onDragBy(steps)
                            // 换位后基线挪了一行，把这段扣掉，行才继续停在手指底下
                            dragAccum -= steps * rowH
                        }
                    },
                    onDragEnd = {
                        dragActive = false; dragAccum = 0f
                        onDraggingChanged(false); onDragEnd()
                    },
                    onDragCancel = {
                        dragActive = false; dragAccum = 0f
                        onDraggingChanged(false); onDragEnd()
                    }
                )
            }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 彩色分类图标块（组色底 + 白图标），跟一级卡片同款
        IconTile(
            iconKey = child.iconKey, size = 38.dp, cornerRadius = 11.dp,
            overrideColor = tileColor
        )
        Spacer(Modifier.width(12.dp))
        Text(
            child.name,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onEdit)
                .padding(vertical = 6.dp),
            fontSize = 16.5.sp,
            fontWeight = FontWeight.Medium
        )
        // 只剩一个三条横线把手：长按拖动排序。
        // 原来的 ⋮ 菜单（编辑/上移/下移/删除）整个去掉了 —— 编辑和删除点名字就能进，
        // 上下移动靠拖，菜单里那几项全是重复入口。
        DragLines(
            modifier = Modifier.padding(horizontal = 6.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

/**
 * 三条横线拖动把手。
 * 不用 Material 的 DragHandle：它的线间距太密，缩小后看着像一个灰色方块，
 * 这里线粗 2dp、间距 3.5dp，一眼能认出是「按住拖动」。
 */
@Composable
private fun DragLines(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = 18.dp
) {
    Column(
        modifier = modifier.size(size),
        verticalArrangement = Arrangement.spacedBy(3.5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(tint)
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* 新建 / 编辑弹层                                                     */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryEditorSheet(
    target: CategoryEntity?,
    isCreate: Boolean,
    parentId: Long?,
    parentType: Int,
    allParents: List<CategoryEntity>,
    onDismiss: () -> Unit,
    /** 第 4 个参数是配色 key：一级分类传选中的色值（"" = 自动），二级传 null 不改 */
    onConfirm: (String, String, Long?, String?) -> Unit,
    /** 编辑态在底部显示「删除」；新建时传 null 就不显示 */
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(target?.name ?: "") }
    var icon by remember { mutableStateOf(target?.iconKey ?: AppIcons.pickerKeys.first()) }
    // 新建二级时归属已经定了；新建一级时没有归属
    var pid by remember { mutableStateOf(parentId) }
    // 自定义配色：只有一级分类能改，二级永远跟一级走
    val isParentLevel = if (isCreate) parentId == null else target?.parentId == null
    var colorKey by remember { mutableStateOf(target?.colorKey ?: "") }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                // 跟 SheetDialog / 记一笔 的把手统一尺寸和颜色，别两个弹层叠一起时看出色差
                Box(
                    modifier = Modifier
                        .size(width = 38.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f))
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                if (isCreate) {
                    if (parentId == null) "新建一级分类" else "添加二级分类"
                } else "编辑分类",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(14.dp))

            Text(
                "名称",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.primary
            )
            Spacer(Modifier.height(7.dp))
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(AppTheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 14.dp, vertical = 13.dp)
                    ) {
                        if (name.isEmpty()) {
                            Text(
                                "比如「午餐」",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        inner()
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            /* ---------- 配色：只有一级分类能改，二级自动跟一级 ---------- */
            // 右侧预览块实时跟着「图标 + 配色」变，选完不用再保存才知道颜色长什么样
            val parentOfTarget = allParents.firstOrNull { it.id == (parentId ?: target?.parentId) }
            if (isParentLevel) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "配色",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    IconTile(
                        iconKey = icon, size = 26.dp, cornerRadius = 8.dp,
                        overrideColor = AppIcons.colorFromKey(colorKey)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (colorKey.isEmpty()) "跟随图标分组色" else "自定义",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(7.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    // 第一个格子 = 「自动」（跟随图标分组色）
                    item {
                        val sel = colorKey.isEmpty()
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .then(
                                    if (sel) Modifier.border(
                                        2.dp, AppTheme.primary, CircleShape
                                    ) else Modifier.border(
                                        1.dp, MaterialTheme.colorScheme.outline, CircleShape
                                    )
                                )
                                .clickable { colorKey = "" },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "A", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(AppIcons.CategoryPalette.size) { i ->
                        val c = AppIcons.CategoryPalette[i]
                        val sel = colorKey == AppIcons.colorToKey(c)
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(c)
                                .then(
                                    if (sel) Modifier.border(
                                        2.dp, MaterialTheme.colorScheme.onSurface, CircleShape
                                    ) else Modifier
                                )
                                .clickable { colorKey = AppIcons.colorToKey(c) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (sel) Icon(
                                Icons.Outlined.Check, null,
                                modifier = Modifier.size(15.dp), tint = Color.White
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "二级分类会自动跟随这里的配色",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(12.dp))
            } else {
                // 二级不能单独配色 —— 显示它继承自哪一级、什么颜色，
                // 免得用户以为「二级颜色坏了」。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "配色",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    IconTile(
                        iconKey = icon, size = 26.dp, cornerRadius = 8.dp,
                        overrideColor = AppIcons.colorFromKey(parentOfTarget?.colorKey)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "跟随「${parentOfTarget?.name ?: "上级分类"}」",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "图标",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "共 ${AppIcons.pickerKeys.size} 个，可上下滑 · 或输入 emoji",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.height(216.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 6 个一行，手动切块
                val keys = AppIcons.pickerKeys
                items(keys.chunked(6)) { rowKeys ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowKeys.forEach { key ->
                            val sel = key == icon
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (sel) AppTheme.primary.copy(alpha = 0.14f)
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .then(
                                        if (sel) Modifier.border(
                                            1.5.dp, AppTheme.primary, RoundedCornerShape(12.dp)
                                        ) else Modifier
                                    )
                                    .clickable { icon = key },
                                contentAlignment = Alignment.Center
                            ) {
                                CategoryIcon(iconKey = key,
                                    size = 20.dp, modifier = Modifier,
                                    tint = if (sel) AppTheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        repeat(6 - rowKeys.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            // —— 自定义 emoji 图标 ——
            // 用户可以直接粘贴/输入一个 emoji 当图标，存库时加 "emoji:" 前缀，
            // CategoryIcon 看到前缀就画文字，否则画矢量图标，全 App 一致。
            val emojiText = if (icon.startsWith("emoji:")) icon.removePrefix("emoji:") else ""
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "或自定义 emoji",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.primary
                )
                Spacer(Modifier.width(8.dp))
                // 实时预览当前图标（矢量或 emoji 都行）
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    CategoryIcon(
                        iconKey = icon,
                        size = 22.dp,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            BasicTextField(
                value = emojiText,
                onValueChange = { v ->
                    val t = v.take(4)
                    icon = if (t.isNotBlank()) "emoji:$t" else "misc"
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(AppTheme.primary),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 14.dp, vertical = 11.dp)
                    ) {
                        if (emojiText.isEmpty()) {
                            Text(
                                "粘贴/输入一个 emoji，如 🍔",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        inner()
                    }
                }
            )

            Spacer(Modifier.height(18.dp))

            // 底部：编辑态是「左删除 / 右保存」两个；新建时只有一个「创建」
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!isCreate && onDelete != null) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(15.dp))
                            .clickable { onDelete() },
                        color = AppTheme.danger.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 15.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Outlined.DeleteOutline, null,
                                modifier = Modifier.size(18.dp),
                                tint = AppTheme.danger
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "删除",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTheme.danger
                            )
                        }
                    }
                }
                Surface(
                    modifier = Modifier
                        .weight(if (!isCreate && onDelete != null) 1f else 1f)
                        .clip(RoundedCornerShape(15.dp))
                        .clickable(enabled = name.isNotBlank()) {
                            onConfirm(
                                name.trim(), icon, pid,
                                if (isParentLevel) colorKey else null
                            )
                        },
                    color = if (name.isNotBlank()) AppTheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(15.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 15.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Outlined.Check, null,
                            modifier = Modifier.size(18.dp),
                            tint = if (name.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isCreate) "创建" else "保存",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (name.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
            }
            }

            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/* ------------------------------------------------------------------ */
