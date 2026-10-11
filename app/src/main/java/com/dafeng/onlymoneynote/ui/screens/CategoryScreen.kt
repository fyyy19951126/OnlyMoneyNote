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
import androidx.compose.foundation.lazy.grid.items
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
    // 上移/下移用本地顺序先算再提交，列表动画才跟得上
    var localParents by remember(tab, parents) { mutableStateOf(parents) }
    // 展开着的一级分类：点一下展开、再点收起，同一时刻只展开一个
    var expandedId by remember { mutableStateOf<Long?>(null) }

    // 页面级强调色统一主题蓝（支付宝式）；收支区分只留在顶部切换胶囊上
    val accent = AppTheme.primary

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

/* ---------- 操作提示 ---------- */
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
      "点一级分类展开二级 · 用箭头调顺序",
 fontSize = 11.5.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
       }

          /* ---------- 一级分类列表：点一下就地展开二级 ---------- */
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
          LazyColumn(
  modifier = Modifier.fillMaxSize(),
       contentPadding = PaddingValues(
start = 14.dp, end = 14.dp, top = 4.dp, bottom = 110.dp
          ),
      verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
          itemsIndexed(localParents, key = { _, it -> it.id }) { _, p ->
            val expanded = expandedId == p.id
   val children = remember(categories, p.id) {
        categories.filter { it.parentId == p.id }.sortedBy { it.sortOrder }
   }
            CategoryParentRow(
                category = p,
                expanded = expanded,
      children = children,
         modifier = Modifier.animateItemPlacement(),
  onToggle = { expandedId = if (expanded) null else p.id },
         onEditParent = { editing = p },
          onAddChild = { creating = true to p.id },
            onMove = { up -> onMove(p, up) },
          onEditChild = { editing = it },
                onDeleteChild = { requestDelete(it) },
          onReorderChildren = { ids -> onReorder(p.id, ids) }
            )
          }
        }
    }

        }   // 关掉 Column

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
    }   // 关掉最外层 Box

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

/**
 * 删除有账单的分类：选一个目标分类转移过去。
 *
 * 2026-10-11 用户要求「跟上面的一级/二级两层保持一致」——
 * 之前是一长条平铺的候选分类（父级和子级混在一起，还各带各的图标色），
 * 分类一多根本认不出哪个是哪个的子类。现在按一级分类分组，
 * 点一级展开它的二级，跟分类管理页是同一套交互和外观。
 */
@Composable
private fun ReassignDialog(
    from: CategoryEntity,
    usedCount: Int,
    candidates: List<CategoryEntity>,
    /** 全量分类，用来把候选分类按父子关系分组 */
    allCategories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onPick: (CategoryEntity) -> Unit
) {
    // 被删的这个分类可能是一级也可能是二级，它自己所在的父级要一起从候选里排掉
    val byParent = remember(candidates, allCategories) {
        val parentIds = candidates.filter { it.parentId == null }.map { it.id }.toSet()
        val parentOf = allCategories.associate { it.id to it.parentId }
        candidates.groupBy { c ->
            if (c.parentId == null) "自身一级"
            // 父分类正好在候选里（不会被删），归到它下面一起显示
            else if (c.parentId in parentIds) "子:${c.parentId}" else "其他"
        }
    }
    // 自身二级候选（它爹不会被删，所以那组可以当一级直接列出来）
    val ownChildren = byParent["自身一级"]?.filter { it.parentId != null } ?: emptyList()
    val otherParents = byParent["自身一级"]?.filter { it.parentId == null } ?: emptyList()
    var expandedKey by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Column {
                Text(
                    "「${from.name}」名下有 $usedCount 笔账单",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "选一个分类承接这些账单：",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            if (candidates.isEmpty()) {
                Text(
                    "没有其他分类可以转移，先新建一个分类再删",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // —— 待删分类自己的那些二级：它们爹还在，一并列出来当一级用
                    if (ownChildren.isNotEmpty()) {
                        item(key = "own") {
                            Column {
                                Text(
                                    "「${from.name}」的二级分类",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                                )
                                ownChildren.forEach { c ->
                                    ReassignFlatRow(
                                        c = c,
                                        tileColor = AppIcons.colorFromKey(from.colorKey)
                                            ?: AppIcons.categoryTileColor(from.iconKey),
                                        onPick = { onPick(c) }
                                    )
                                }
                            }
                        }
                    }
                    // —— 其它一级分类，点一下展开它的二级
                    otherParents.forEach { p ->
                        val kids = candidates.filter { it.parentId == p.id }
                            .sortedBy { it.sortOrder }
                        val expanded = expandedKey == "p${p.id}"
                        item(key = "p${p.id}") {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable {
                                            expandedKey = if (expanded) null else "p${p.id}"
                                        }
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconTile(
                                        iconKey = p.iconKey, size = 34.dp, cornerRadius = 10.dp,
                                        overrideColor = AppIcons.colorFromKey(p.colorKey)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            p.name,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                        Text(
                                            if (kids.isEmpty()) "可直接转移到这里"
                                            else "${kids.size} 个二级分类",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        Icons.Outlined.KeyboardArrowDown,
                                        contentDescription = if (expanded) "收起" else "展开",
                                        modifier = Modifier
                                            .size(20.dp)
                                            .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (expanded && kids.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 18.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                            )
                                            .padding(vertical = 6.dp, horizontal = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        kids.forEach { c ->
                                            ReassignFlatRow(
                                                c = c,
                                                tileColor = AppIcons.colorFromKey(p.colorKey),
                                                onPick = { onPick(c) },
                                                compact = true
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 转移列表里的一行 */
@Composable
private fun ReassignFlatRow(
    c: CategoryEntity,
    tileColor: Color?,
    onPick: () -> Unit,
    compact: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onPick)
            .padding(horizontal = 6.dp, vertical = if (compact) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(
            iconKey = c.iconKey,
            size = if (compact) 30.dp else 34.dp,
            cornerRadius = 10.dp,
            overrideColor = tileColor
        )
        Spacer(Modifier.width(10.dp))
        Text(
            c.name,
            modifier = Modifier.weight(1f),
            fontSize = if (compact) 14.5.sp else 15.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
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
/* 一级分类行（点一下展开二级）                                          */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryParentRow(
    category: CategoryEntity,
    expanded: Boolean,
    children: List<CategoryEntity>,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onEditParent: () -> Unit,
    onAddChild: () -> Unit,
    /** 上移 / 下移一级分类。true = 上移 */
    onMove: (Boolean) -> Unit = { _ -> },
    onEditChild: (CategoryEntity) -> Unit,
    onDeleteChild: (CategoryEntity) -> Unit,
    onReorderChildren: (List<Long>) -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            // 点整行 = 展开/收起二级。
            // 这里只挂 clickable，不叠 pointerInput：detectDragGesturesAfterLongPress
            // 哪怕排在后面也会先吃掉 down，click 收不到，整行点了没反应。
            // 排序改用行内的上移/下移按钮（见下），稳当且不用手势。
            .clickable(onClick = onToggle),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 1.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconTile(
                    iconKey = category.iconKey, size = 40.dp, cornerRadius = 13.dp,
                    overrideColor = AppIcons.colorFromKey(category.colorKey)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (expanded) "${category.name} ▾" else category.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        if (children.isEmpty()) "暂无二级分类" else "${children.size} 个二级分类",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1
                    )
                }
                // 排序：上移 / 下移。不用长按拖动 —— 拖动跟整行点击抢手势，
                // 一抢整行就点不动了。两个箭头 + 编辑都收在行内，操作反而更直白。
                IconButton(
                    onClick = { onMove(true) },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Outlined.KeyboardArrowUp, "上移「${category.name}」",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { onMove(false) },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown, "下移「${category.name}」",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 编辑一级分类：改名 / 换图标 / 删除都在里面
                IconButton(onClick = onEditParent, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Outlined.Edit, "编辑「${category.name}」",
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

if (expanded) {
     CategoryChildrenPanel(
         parent = category,
    children = children,
        onEditChild = onEditChild,
     onDeleteChild = onDeleteChild,
      onReorderChildren = onReorderChildren,
     onAddChild = onAddChild
  )
  }
        }
    }
}

/**
 * 展开后的一级分类下面的二级分类区。
 *
 * 单独拆出来是因为删除分类时的「转移账单」选择器要复用同一套外观，
 * 两边长得一样，用户不用重新学一次操作。
 */
@Composable
internal fun CategoryChildrenPanel(
    parent: CategoryEntity,
    children: List<CategoryEntity>,
    onEditChild: (CategoryEntity) -> Unit,
    onDeleteChild: (CategoryEntity) -> Unit,
    onReorderChildren: (List<Long>) -> Unit,
    onAddChild: () -> Unit,
    /** 转移选择器里不需要拖动排序 */
    sortable: Boolean = true,
    /** 每行的额外点击动作（转移模式：点这一行 = 选它） */
    onPick: ((CategoryEntity) -> Unit)? = null,
    /** 顶部一行提示，转移模式下显示 */
    hint: String? = null
) {
    val tileColor = AppIcons.colorFromKey(parent.colorKey)
        ?: AppIcons.categoryTileColor(parent.iconKey)
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    var draggingChildId by remember { mutableStateOf<Long?>(null) }
    var childOrder by remember(children) { mutableStateOf(children.map { it.id }) }
    val orderedChildren = remember(children, childOrder) {
        val byId = children.associateBy { it.id }
        childOrder.mapNotNull { byId[it] } + children.filter { it.id !in childOrder }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
            .background(tileColor.copy(alpha = 0.06f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        if (hint != null) {
            Text(
                hint,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
        if (orderedChildren.isEmpty()) {
            Text(
                "还没有二级分类，点下面「添加二级分类」建一个",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        } else {
            orderedChildren.forEachIndexed { i, c ->
                ChildPickRow(
                    child = c,
tileColor = tileColor,
                    onClick = {
                        when {
                            onPick != null -> onPick(c)
                            else -> onEditChild(c)
                        }
                    },
                    sortable = sortable,
                    onDraggingChanged = { draggingChildId = if (it) c.id else null },
                    onDragBy = { steps ->
                        val cur = childOrder.toMutableList()
                        val from = cur.indexOf(c.id)
                        if (from >= 0) {
                            cur.removeAt(from)
                            cur.add((from + steps).coerceIn(0, cur.size), c.id)
                            childOrder = cur
                        }
                    },
                    onDragEnd = { onReorderChildren(childOrder) },
                    rowHeightPx = rowHeightPx,
                    onRowMeasured = { h -> if (rowHeightPx == 0f) rowHeightPx = h }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(tileColor.copy(alpha = 0.12f))
                .clickable(onClick = onAddChild)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tileColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(17.dp), tint = Color.White)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "添加二级分类",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 二级分类行：名字可点（编辑 / 选中），长按可拖动排序 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChildPickRow(
    child: CategoryEntity,
    tileColor: Color?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    sortable: Boolean,
    onDraggingChanged: (Boolean) -> Unit = {},
    onDragBy: (Int) -> Unit = {},
    onDragEnd: () -> Unit = {},
    rowHeightPx: Float,
    onRowMeasured: (Float) -> Unit = {}
) {
    val haptics = LocalHapticFeedback.current
    var dragAccum by remember { mutableStateOf(0f) }
    var dragActive by remember { mutableStateOf(false) }
    val rowH = if (rowHeightPx > 0f) rowHeightPx
    else with(LocalDensity.current) { 52.dp.toPx() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { if (rowHeightPx == 0f) onRowMeasured(it.height.toFloat()) }
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (dragActive) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            // 排在 clickable 之后（更内层），长按成立前不消费事件，单击才有效
            .then(
                if (sortable) Modifier.pointerInput(child.id) {
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
                            val steps = (dragAccum / (rowH * 0.6f)).toInt()
                            if (steps != 0) {
                                onDragBy(steps)
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
                } else Modifier
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(iconKey = child.iconKey, size = 36.dp, cornerRadius = 11.dp, overrideColor = tileColor)
        Spacer(Modifier.width(12.dp))
        Text(
            child.name,
            modifier = Modifier.weight(1f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        if (sortable) {
            DragLines(
                modifier = Modifier.padding(horizontal = 6.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
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
