package com.dafeng.onlymoneynote.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowRightAlt
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeng.onlymoneynote.data.local.AccountEntity
import com.dafeng.onlymoneynote.data.local.CategoryEntity
import com.dafeng.onlymoneynote.data.local.TxType
import com.dafeng.onlymoneynote.data.local.TxWithCategory
import com.dafeng.onlymoneynote.data.repo.originIdFromNote
import com.dafeng.onlymoneynote.ui.LedgerViewModel
import com.dafeng.onlymoneynote.ui.components.IconTile
import com.dafeng.onlymoneynote.ui.components.PageHeader
import com.dafeng.onlymoneynote.ui.components.SheetDialog
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
    onOpenAbout: () -> Unit,
    /** 结余行右侧「账户」链接 → 账户统计弹层 */
    onOpenAccounts: () -> Unit,
    /** 批量整理用：选账户的弹层要列账户 */
    accounts: List<AccountEntity>,
    /** 批量把选中的一批账单改到某个账户下 */
    onBulkAssign: (ids: List<Long>, accountId: Long) -> Unit,
    /** 批量把选中的一批账单改到某个分类下（多重确认后才调） */
    onBulkRecategorize: (ids: List<Long>, categoryId: Long) -> Unit,
    /** 选分类的弹层要列分类 */
    categoriesForPick: List<CategoryEntity>
) {
    var openedId by remember { mutableStateOf<Long?>(null) }
    // 改分类的确认流程：null = 没开；1 = 第一步（讲后果）；2 = 第二步（最终确认）
    var catConfirmStep by remember { mutableStateOf(0) }
    var pendingCatId by remember { mutableStateOf(0L) }
    var categorySheet by remember { mutableStateOf(false) }
    val balance = monthIncome - monthExpense

    // 记完一笔（或改了日期）把列表拉回顶部：不然顶部数字变了、列表还停在原处，
    // 看着像没记上（用户要求「每新增一条就上下刷新一下」）
    val listState = rememberLazyListState()
    val newestId = transactions.firstOrNull()?.id
    LaunchedEffect(newestId) {
        if (newestId != null) listState.animateScrollToItem(0)
    }

    // 顶部「支出/收入」点一下筛选：null=全部，否则只显示该类型；再点一次回到全部
    //
    // 转账（第三类）**故意不进这个切换**：它是中性事件，塞进来会让「收入」高亮
    // 和红绿金额的语义全乱。它在「全部」视图里照常显示，点开就看得见。
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

    /* ---------- 批量整理：多选 → 设为账户 ---------- */
    // 迁移前的老账单全部挂在「未指定」，一条条长按改不现实（用户有 8000 条）。
    // 配合上面的「筛选」先按分类 / 日期把范围缩小，再全选，一次就能归掉一大片。
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var assignSheet by remember { mutableStateOf(false) }

    fun exitSelect() {
        selectMode = false
        selectedIds = emptySet()
    }

    fun toggleSelect(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    // 只拿**当前筛选后**的这些条来全选，不然会把没显示的也勾上（用户看不见却被动归类）
    val allSelected = shown.isNotEmpty() && shown.all { it.id in selectedIds }
    fun toggleSelectAll() {
        selectedIds = if (allSelected) emptySet() else shown.map { it.id }.toSet()
    }

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
                        HeaderEntry(Icons.Outlined.UploadFile, "本地备份") { onOpenIo() }
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
                    HeaderLink("报销") { onOpenReimburse() }
                    Spacer(Modifier.width(7.dp))
                    HeaderLink("账户") { onOpenAccounts() }
                }

                // 批量整理：进了多选就在页头补一条选中状态栏（全选 / 退出）。
                // 放在页头而不是底部，是因为底部要留给「设为账户」这个主操作。
                if (selectMode) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (selectedIds.isEmpty()) "点账单选中，可多选"
                            else "已选 " + selectedIds.size + " 笔",
                            modifier = Modifier.weight(1f),
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                        Text(
                            if (allSelected) "取消全选" else "全选",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { toggleSelectAll() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            color = Color.White
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "退出",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { exitSelect() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            color = Color.White
                        )
                    }
                }

                // 筛选生效时：摘要 + 笔数 + 「整理」入口 + 一键清除
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
                                // 摘要框收窄一点（2026-10-11 用户反馈太占地方），
                                // 右边腾出位置给「共 X 笔」和「整理」
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "共 " + shown.size + " 笔",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Spacer(Modifier.width(6.dp))
                        // 「整理」跟着筛选走：筛出一批之后顺手改账户/分类最自然，
                        // 不用退出筛选再点页头那个
                        Text(
                            "整理",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.18f))
                                .clickable {
                                    if (selectMode) exitSelect() else selectMode = true
                                }
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                            color = Color.White
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "清除",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { filter = HomeFilter() }
                                .padding(horizontal = 7.dp, vertical = 3.dp),
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
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // 底部留白 = 悬浮元素占的那块（FAB 或多选操作条）+ 一点呼吸位。
                    // **不能写死 dp**：三大金刚键的导航栏比手势小白条高得多，
                    // 写死 96dp 在有导航栏的机型上不够，最后几笔会被压住。
                    // MainActivity 那边 Scaffold 已经把 insets 消费掉并垫在根容器上了，
                    // 这里只负责「悬浮元素之上的那一段」。
                    contentPadding = PaddingValues(
                        start = 14.dp, end = 14.dp, top = 2.dp,
                        bottom = if (selectMode) 76.dp else 100.dp
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
                                // 多选模式下不许左滑：滑开露出的是「删除」，
                                // 而这时用户想干的是勾选，两种手势混在一起会误删
                                swipeEnabled = !selectMode,
                                selectMode = selectMode,
                                selected = tx.id in selectedIds,
                                onOpenChange = { open ->
                                    openedId = if (open) tx.id else null
                                },
                                // 删完必须把 openedId 清掉 —— 否则它一直指向已删除的 id，
                                // 所有行的 interactive 都变 false，就再也点不进编辑了
                                onDelete = { txId ->
                                    openedId = null
                                    onDelete(txId)
                                },
                                onEdit = onEdit,
                                onToggleSelect = { toggleSelect(tx.id) },
                                // 长按账单 = 直接进多选并顺手勾上这一笔，
                                // 省得先点「整理」再点账单，两步变一步
                                onLongPress = {
                                    if (!selectMode) selectMode = true
                                    if (tx.id !in selectedIds) selectedIds = selectedIds + tx.id
                                }
                            )
                        }
                    }
                }
            }
        }
      }   // Column

        /* ---------- 右下角浮空「＋」：记一笔。多选时收起来，别跟底部操作条抢位置 ---------- */
        if (!selectMode) {
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
        }

        /* ---------- 批量整理：底部主操作条 ---------- */
        if (selectMode) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp)
            ) {
                Spacer(Modifier.height(6.dp))
                // 两个批量操作并排：改账户 / 改分类。
                // 改分类比改账户更容易点错（一改就是几百笔的分类全变），
                // 所以用描边样式区分，并且下面走**两步确认**。
                // 已选笔数 / 全选 / 退出只留在页头那条状态栏里，底部不再重复一遍。
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BulkActionButton(
                        text = "设为账户",
                        primary = true,
                        enabled = selectedIds.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        onClick = { assignSheet = true }
                    )
                    BulkActionButton(
                        text = "修改分类",
                        primary = false,
                        enabled = selectedIds.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        onClick = { categorySheet = true }
                    )
                }
            }
        }

        /* ---------- 选账户弹层 ---------- */
        if (assignSheet) {
            AccountPickerSheet(
                accounts = accounts,
                onDismiss = { assignSheet = false },
                onPick = { accId ->
                    assignSheet = false
                    onBulkAssign(selectedIds.toList(), accId)
                    exitSelect()
                }
            )
        }

        /* ---------- 选分类弹层（点分类后进入两步确认） ---------- */
        if (categorySheet) {
            CategoryPickerSheet(
                categories = categoriesForPick,
                onDismiss = { categorySheet = false },
                onPick = { catId ->
                    categorySheet = false
                    pendingCatId = catId
                    catConfirmStep = 1
                }
            )
        }

        /* ---------- 改分类的两步确认 ---------- */
        if (catConfirmStep > 0) {
            val target = categoriesForPick.firstOrNull { it.id == pendingCatId }
            val targetName = target?.let { c ->
                val parent = categoriesForPick.firstOrNull { it.id == c.parentId }
                listOfNotNull(parent?.name, c.name).joinToString(" · ")
            } ?: "该分类"
            val count = selectedIds.size
            if (catConfirmStep == 1) {
                AlertDialog(
                    onDismissRequest = { catConfirmStep = 0 },
                    shape = RoundedCornerShape(24.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    title = {
                        Text("把 $count 笔改成「$targetName」？", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    },
                    text = {
                        Text(
                            "这 $count 笔账单的分类会**全部**变成「$targetName」。\n\n" +
                                "金额、时间、备注、账户都不动，只改分类。\n" +
                                "改错了可以再改回来，但批量操作一次就是几百笔，请确认选对了。",
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    confirmButton = {
                        Text(
                            "确定，继续",
                            color = AppTheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { catConfirmStep = 2 }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    },
                    dismissButton = {
                        Text(
                            "取消",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { catConfirmStep = 0 }.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                )
            } else {
                AlertDialog(
                    onDismissRequest = { catConfirmStep = 0 },
                    shape = RoundedCornerShape(24.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    title = {
                        Text("最后确认", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.primary)
                    },
                    text = {
                        Text(
                            "再确认一次：\n\n" +
                                "**$count 笔**账单的分类 → **$targetName**\n\n" +
                                "点下面这个按钮才会真的执行。",
                            fontSize = 14.sp,
                            lineHeight = 21.sp
                        )
                    },
                    confirmButton = {
                        Text(
                            "就是它，改",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(AppTheme.primary)
                                .clickable {
                                    onBulkRecategorize(selectedIds.toList(), pendingCatId)
                                    catConfirmStep = 0
                                    exitSelect()
                                }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    },
                    dismissButton = {
                        Text(
                            "我点错了",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { catConfirmStep = 0 }.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                )
            }
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
 * 这条账单是不是报销冲销行。
 *
 * ⚠️ 必须认 note 前缀，**不能只看金额为负** —— 用户手记一笔负数支出也会命中，
 * 那样就会被染成红色的「报销到账」，凭空多出一笔收入。两页（首页 / 报销页）共用这个判断。
 */
internal fun isOffsetTx(tx: TxWithCategory): Boolean = originIdFromNote(tx.note) != null

/**
 * 冲销行给人看的备注。
 *
 * 数据库里存的是 `报销冲销#<原id>（报销于 2026-09-27）`：
 * 前缀 + id 给程序认（撤销、级联删除都靠它精确定位），括号里那截才是人话。
 * 提取时**必须把尾括号一起去掉** —— 只 substringAfter('（') 会带上「）」，
 * 界面上就成了「报销于 2026-09-27）」，多一个孤零零的括号。
 * 两页（首页 / 报销页）共用这一个函数，别各写一遍。
 */
internal fun offsetDisplayNote(tx: TxWithCategory): String {
    if (!isOffsetTx(tx)) return tx.note
    val inner = tx.note.substringAfter('（', "")
        .removeSuffix("）")
        .trim()
    return inner.ifBlank { "报销冲回" }
}

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
    /** 多选模式下关掉左滑：这时用户想勾选，滑开露出删除键容易误触。 */
    swipeEnabled: Boolean = true,
    selectMode: Boolean = false,
    selected: Boolean = false,
    onOpenChange: (Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onEdit: (TxWithCategory) -> Unit,
    onToggleSelect: () -> Unit = {},
    /** 长按账单：直接进多选（还没在多选里的话）。批量归类最快的方式 */
    onLongPress: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val revealPx = with(density) { REVEAL_WIDTH.toPx() }
    val offsetX = remember { Animatable(0f) }
    // 拖动中的实时位移（同步状态）。Animatable.snapTo 是挂起函数，
    // 放在协程里执行，松手那一刻 offsetX.value 可能还是旧值，
    // 阈值判断成「没滑够」就把手柄弹回去 —— 偶尔滑开停不住就是这个原因。
    var rawX by remember { mutableFloatStateOf(0f) }

    // 外部把这条关掉时（比如滑了别的行），同步收回
    LaunchedEffect(isOpen) {
        val target = if (isOpen) -revealPx else 0f
        if (offsetX.value != target) {
            offsetX.animateTo(target, tween(180))
        }
        rawX = offsetX.value
    }
    // 进多选时把还展开着的那一行收回去，别留一条张着的红色删除带
    LaunchedEffect(selectMode) {
        if (selectMode && offsetX.value != 0f) {
            offsetX.animateTo(0f, tween(180))
            rawX = 0f
            onOpenChange(false)
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        // ---- 底层：删除按钮（平时被上面的不透明行完全盖住）。多选时不画，避免误触 ----
        if (!selectMode) {
            Box(
            modifier = Modifier
                .matchParentSize()
                // 只切**右侧**两个角。删除区是滑开后才露出来的外缘，
                // 左边贴着卡片，切了角会在接缝处透出页面底色，看着像按钮镶了白边。
                .clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
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
        }

        // ---- 上层：卡片，可横向拖动 ----
        Box(
            modifier = Modifier
                .offset { IntOffset(rawX.roundToInt(), 0) }
                .then(
                    if (!swipeEnabled) {
                        Modifier
                    } else {
                        Modifier.draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                rawX = (rawX + delta).coerceIn(-revealPx, 0f)
                            },
                            onDragStopped = {
                                val v = rawX
                                val shouldOpen = v < -revealPx * 0.4f
                                scope.launch {
                                    offsetX.snapTo(v)
                                    offsetX.animateTo(if (shouldOpen) -revealPx else 0f, tween(180))
                                    rawX = offsetX.value
                                }
                                onOpenChange(shouldOpen)
                            }
                        )
                    }
                )
        ) {
            TxRow(
                tx = tx,
                interactive = interactive && !selectMode,
                selectMode = selectMode,
                selected = selected,
                onLongClick = onLongPress,
                onClick = {
                    if (selectMode) {
                        onToggleSelect()
                    } else {
                        if (rawX != 0f) {
                            scope.launch {
                                offsetX.animateTo(0f, tween(180))
                                rawX = 0f
                            }
                            onOpenChange(false)
                        } else if (isOffsetTx(tx)) {
                            // 冲销行的金额 / 日期跟原始账单是配对的，改了就撤销不了。
                            // 要改去改原始那笔，冲销行应随之重算。
                            onOpenChange(false)
                        } else {
                            onEdit(tx)
                        }
                    }
                }
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TxRow(
    tx: TxWithCategory,
    interactive: Boolean = true,
    selectMode: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val isExpense = tx.type == TxType.EXPENSE.value
// 冲销行的判断**不能只看金额为负** —— 用户完全可能手记一笔负数支出，
    // 那样也会命中，把它染成红色的「收入」就成了凭空多出一笔进账。
    // 认准 note 前缀，这是本 App 造冲销行时写死的。
    val isOffset = isOffsetTx(tx)
    // 显示层跟账本口径反着来：账本里冲销行是负支出（用于抵扣总支出），
    // 但从用户视角这是「报销款到账」，所以显示成正数 + 收入红。
    // ⚠️ 只改显示，**不要改数据库里的金额** —— 那个负数是统计扣减的依据。
    val shownCents = if (isOffset) -tx.amountCents else tx.amountCents
    val color = if (isOffset) AppTheme.income else AppTheme.byType(isExpense)

    // 注意：这一层**必须是不透明的** —— 下面压着红色删除层，背景一透明
    // 删除按钮就直接显在表面上。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            // 长按进多选：跟系统相册一致的手势，省得先点「整理」再点账单
            .combinedClickable(
                enabled = interactive || selectMode,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 多选时在最左边让出一个勾选圈，不然勾选和「点开编辑」两件事分不清
        if (selectMode) {
            SelectTick(selected)
            Spacer(Modifier.width(11.dp))
        }
        // 彩色分类图标块（支付宝式）：一级分类自定义了配色就跟着走
        IconTile(
            iconKey = tx.iconKey,
            size = 38.dp,
            cornerRadius = 12.dp,
            overrideColor = AppIcons.colorFromKey(tx.colorKey)
        )
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (tx.type == TxType.TRANSFER.value) {
                // 转账没分类，标题位置写「从哪转到哪」，比光一个分类名有意义
                Text(
                    text = "${tx.accountName} → ${tx.toAccountName}",
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    text = listOfNotNull(tx.parentName, tx.categoryName)
                        .distinct()
                        .joinToString(" · "),
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeLabel(tx.dateMillis),
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 「报销」标记：这笔进了报销池就打标，冲销行除外。
                // 判据用 isReimburse（进池）而不是 reimbursed（已报完）——
                // 用户刚记一笔勾了报销、在还没报之前就该看到标记，
                // 否则那段时间里它看起来跟普通账单毫无区别。
                // 冲销行不标：它本身就是报销的结果，再挂一个「报销」等于同义反复。
                if (tx.isReimburse && !isOffset) {
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
                // 冲销行备注要把「报销冲销#<id>」前缀去掉，只留括号里的人话
                val noteText = offsetDisplayNote(tx)
                if (noteText.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = noteText,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        // 转账中性显示：不带 +/- 前缀，也不染红绿 —— 它既不是花钱也不是进账
        val isTransfer = tx.type == TxType.TRANSFER.value
        Text(
            // 冲销行显示 +¥正数（钱回来了），其余照旧按收支类型带 +/- 前缀。
            // 之前那个「金额为负就不加前缀」的分支会让冲销行变成裸数字，
            // 跟旁边带 ¥ 的行对不齐，看着像漏了币种符号。
            text = (when {
                isTransfer -> ""
                isOffset -> "+¥"
                isExpense -> "-¥"
                else -> "+¥"
            }) + LedgerViewModel.formatCents(shownCents),
            // 金额右对齐并预留固定宽度：位数不同也不会把右边的账户头像推得左右跳
            modifier = Modifier.widthIn(min = 104.dp),
            textAlign = TextAlign.End,
            maxLines = 1,
            color = if (isTransfer) AppTheme.primary else color,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
        Spacer(Modifier.width(8.dp))
        // 转账：左边转出账户头像 + 箭头 + 右边转入账户头像，不显示分类图标
        // （转账没有分类）。收支账单才显示单个账户头像。
        if (tx.type == TxType.TRANSFER.value) {
            IconTile(
                iconKey = tx.accountIconKey,
                size = 20.dp,
                cornerRadius = 6.dp,
                overrideColor = AppIcons.colorFromKey(tx.accountColorKey)
            )
            Icon(
                Icons.AutoMirrored.Outlined.ArrowRightAlt,
                contentDescription = "转到",
                modifier = Modifier.padding(horizontal = 2.dp).size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconTile(
                iconKey = tx.toAccountIconKey.ifBlank { "emoji:💳" },
                size = 20.dp,
                cornerRadius = 6.dp,
                overrideColor = AppIcons.colorFromKey(tx.toAccountColorKey)
            )
        } else {
            IconTile(
                iconKey = tx.accountIconKey,
                size = 22.dp,
                cornerRadius = 7.dp,
                overrideColor = AppIcons.colorFromKey(tx.accountColorKey)
            )
        }
    }
}

/**
 * 多选模式的勾选圈。
 *
 * 用 Box 画而不是 Material 的 CheckCircle —— 少一个依赖，
 * 而且空心/实心两种态都能精确控制描边色，深色模式下也不会糊。
 */
@Composable
private fun SelectTick(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(21.dp)
            .clip(CircleShape)
            .background(if (selected) AppTheme.primary else Color.Transparent)
            .border(
                width = 1.5.dp,
                color = if (selected) AppTheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                Icons.Outlined.Check, null,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * 批量操作条上的一个按钮。
 *
 * [primary] 决定是实心（主操作）还是描边（次要操作）——
 * 改分类比改账户更容易点错，用样式区分让「设为账户」成为默认的那个。
 */
@Composable
private fun BulkActionButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        primary -> AppTheme.primary
        else -> MaterialTheme.colorScheme.surface
    }
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        primary -> Color.White
        else -> AppTheme.primary
    }
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(bg)
            .then(
                if (enabled && !primary)
                    Modifier.border(1.dp, AppTheme.primary, shape)
                else Modifier
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1)
    }
}

/**
 * 选分类弹层：一级分组列出，点一级下的二级才选中。
 *
 * 只列**二级**（也就是真正挂在账单上的那个粒度）—— 账单本来就都指向二级，
 * 让用户选一级反而会造成「以为改了、其实只改了一部分」。
 */
@Composable
private fun CategoryPickerSheet(
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit
) {
    // 跟筛选页一致：一级分类平铺，点一下展开它的二级。
    // 之前是一律展开全量二级，分类一多就得长距离拖到底才能找到目标。
    var expandedParentId by remember { mutableStateOf<Long?>(null) }
    val parents = remember(categories) {
        categories.filter { it.parentId == null }.sortedBy { it.sortOrder }
    }

    SheetDialog(onDismiss = onDismiss, heightFraction = 0.7f) {
        Text(
            "改成哪个分类",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp)
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            items(parents, key = { it.id }) { parent ->
                val kids = remember(categories, parent.id) {
                    categories.filter { it.parentId == parent.id }.sortedBy { it.sortOrder }
                }
                val expanded = expandedParentId == parent.id
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                expandedParentId = if (expanded) null else parent.id
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconTile(
                            iconKey = parent.iconKey,
                            size = 34.dp,
                            cornerRadius = 11.dp,
                            overrideColor = AppIcons.colorFromKey(parent.colorKey)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            parent.name,
                            modifier = Modifier.weight(1f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            Icons.Outlined.KeyboardArrowDown,
                            contentDescription = if (expanded) "收起" else "展开",
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (expanded) {
                        Spacer(Modifier.height(6.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .padding(vertical = 4.dp, horizontal = 6.dp)
                        ) {
                            kids.forEach { c ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onPick(c.id) }
                                        .padding(horizontal = 8.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconTile(
                                        iconKey = c.iconKey,
                                        size = 28.dp,
                                        cornerRadius = 9.dp,
                                        overrideColor = AppIcons.colorFromKey(parent.colorKey)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        c.name,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 选账户弹层：列出现有账户，点一下就把选中的那批账单归过去。
 *
 * 「未指定」照样列出来 —— 把归错的账单拨回未指定是高频操作，不该被藏起来。
 * 只列账户本身（名字 + 图标），不显示余额：这里的作用只是「挑一个目的地」。
 */
@Composable
private fun AccountPickerSheet(
    accounts: List<AccountEntity>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit
) {
    SheetDialog(onDismiss = onDismiss, heightFraction = 0.62f) {
        Text(
            "归到哪个账户",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp)
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp)
        ) {
            items(accounts, key = { it.id }) { a ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPick(a.id) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconTile(
                        iconKey = a.iconKey,
                        size = 34.dp,
                        cornerRadius = 11.dp,
                        overrideColor = AppIcons.colorFromKey(a.colorKey)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        a.name,
                        fontSize = 14.5.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
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
    // 转账跳过：钱只是在自己账户之间挪位置，当天的收支小计不该被它撑大
    list.forEach {
        when (it.type) {
            TxType.EXPENSE.value -> expense += it.amountCents
            TxType.INCOME.value -> income += it.amountCents
        }
    }
    return expense to income
}

private fun timeLabel(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}
