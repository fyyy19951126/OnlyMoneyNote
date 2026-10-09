package com.dafeng.onlymoneynote.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeng.onlymoneynote.data.local.CategoryEntity
import com.dafeng.onlymoneynote.data.local.AccountEntity
import com.dafeng.onlymoneynote.data.local.TxType
import com.dafeng.onlymoneynote.data.local.TxWithCategory
import com.dafeng.onlymoneynote.ui.LedgerViewModel
import com.dafeng.onlymoneynote.ui.theme.AppTheme
import com.dafeng.onlymoneynote.util.AppIcons
import com.dafeng.onlymoneynote.ui.components.IconTile
import com.dafeng.onlymoneynote.util.CategoryIcon
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Calendar

/**
 * 记一笔 / 改一笔。
 *
 * 布局参考截图：
 * - 顶部：支出 / 收入 切换，右侧放「选图识别」
 * - 中间：分类网格（4 列浅灰胶囊，带 ▾；选中的胶囊用强调色实底）
 * - 底部：金额 + 备注一行，下面直接是数字键盘
 * - **键盘上的 ✓ 就是保存** —— 不用再找保存按钮
 *
 * 新建时不问时间，直接用「现在」；改旧账时才显示时间微调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionScreen(
    vm: LedgerViewModel,
    categories: List<CategoryEntity>,
    editing: TxWithCategory? = null,
    initialImage: android.net.Uri? = null,
    onDone: () -> Unit
) {
    // 底部弹层（支付宝记一笔式）：全宽、圆角只在顶部、从屏幕底部滑上来。
    // 用 Dialog + 自绘遮罩，不用 ModalBottomSheet —— 后者带下拉把手和固定轨道，不好定制。
    // 底部安全区（关键）：这台机器的 Dialog 窗口比屏幕**还高**（实测往下多出 ~94px），
    // 弹层底部那段是画到屏幕外的，键盘最后一排（0 . 00 ✓）直接被切掉 —— 手感就是
    // 「底部被挡住没法操作」。而 Dialog 内部自己拿到的 navigationBars inset 在这台
    // 机器上是 0（手势导航时系统报 0），所以只能从 **Activity 的 decorView** 读，
    // 读不到就按 44dp 兜底，再多加 10dp 呼吸位。
    // 之前这段留白写成 Spacer 放在 BoxWithConstraints（= Box，层叠布局）里，
    // 完全不占列高，等于没垫。
    val activityContext = LocalContext.current
    val cardDensity = LocalDensity.current
    val bottomInset = remember(activityContext, cardDensity) {
        val px = (activityContext as? android.app.Activity)?.window?.decorView?.let { dv ->
            androidx.core.view.WindowInsetsCompat
                .toWindowInsetsCompat(dv.rootWindowInsets)
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                .bottom
        } ?: 0
        with(cardDensity) { maxOf(px.toDp(), 44.dp) + 10.dp }
    }
    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        // 遮罩淡入；卡片自己用 translationY 从底下滑上来
        val enter = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            enter.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
        }
        // 日历展开时弹层抬高一点（0.85 → 0.93 屏高）：
        // 日历面板 = 月历 + 时分滚轮 + 取消/确认，比分类网格高一大截，
        // 不抬高的话底部的按钮直接被键盘挤到屏幕外（用户反馈「打开下面会被遮挡」）。
        // 日历是否展开。状态放在这一层：卡片要把数字键盘让位给日历，
        // 而键盘和日历分属表单的两个区域，得由共同的上位管着。
        var calendarOpen by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f * enter.value))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDone() },
            contentAlignment = Alignment.BottomCenter
        ) {
            // 下拉偏移量（横条可拖动，往下拖超过阈值就关闭）
            var dragY by remember { mutableStateOf(0f) }
            Column(
                modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                    .graphicsLayer {
                        // 进场只做渐入（用户要求：不要从底下滑上来）；再叠加用户往下拖的 dragY
                        alpha = enter.value
                        translationY = dragY
                    }
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    // 卡片必须**完全不透明**，否则底下的主页会透出来跟文字叠在一起
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(
                        // 吃掉内部点击，避免穿透到遮罩把弹窗关掉
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {}
                    // 底部留白放在 clickable **之后**：留白区域仍然属于卡片（背景/点击都覆盖），
                    // 只是把里面的内容整体上抬，避开导航条。
                    .padding(bottom = bottomInset)
            ) {
                // 顶部操作横条：居中细横条 + **点一下就关闭** + **往下拖也能关**
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onDone() }
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = {},
                                onDragEnd = {
                                    // 往下拖超过 60dp 就关掉，否则弹回去
                                    if (dragY > 60f) onDone() else dragY = 0f
                                },
                                onDragCancel = { dragY = 0f },
                                onVerticalDrag = { _, dy ->
                                    dragY = (dragY + dy).coerceAtLeast(0f)
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 38.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f))
                    )
                }
                AddTransactionForm(
                    vm = vm,
                    categories = categories,
                    editing = editing,
                    initialImage = initialImage,
                    onDone = onDone,
                    // 日历开关提到这一层：展开日历时弹层要跟着变高，
                    // 高度状态得跟卡片 Column 待在一起才好用。
                    calendarOpen = calendarOpen,
                    onCalendarOpen = { calendarOpen = it },
                    // 用 weight 分掉「把手横条」以外的剩余高度。
                    // 之前是 fillMaxSize，表单自己又占满整个弹层高，
                    // 加上把手 34dp 正好把内容顶出底部一排键（底部弹层后必现）。
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun AddTransactionForm(
    vm: LedgerViewModel,
    categories: List<CategoryEntity>,
    editing: TxWithCategory?,
    initialImage: android.net.Uri?,
    onDone: () -> Unit,
    /** 日历是否展开。状态在弹层那一层，因为展开时整个弹层要变高 */
    calendarOpen: Boolean,
    onCalendarOpen: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val isEdit = editing != null
    val lastUsed by vm.lastUsed.collectAsStateCompat()
    // 账户按「用得多排前面」排序：笔数降序，同笔数按用户自己的排序
    val accountOverviews by vm.accountOverviews.collectAsStateCompat()
    val accounts = remember(accountOverviews) {
        accountOverviews.sortedWith(
            compareByDescending<LedgerViewModel.AccountOverview> { it.txCount }
                .thenBy { it.account.sortOrder }
        ).map { it.account }
    }

    // 新建时**默认支出**（多数记账都是支出场景）。
    // 之前用 lastUsed.type 记忆上次选择，上次记了收入就连着几次都默认收入，
    // 打开就得手动切一下 —— 反而更烦。改旧账仍按账单原本的类型。
    var type by remember { mutableIntStateOf(editing?.type ?: TxType.EXPENSE.value) }
    var amount by remember {
        mutableStateOf(editing?.let { LedgerViewModel.formatCents(it.amountCents) } ?: "")
    }
    var note by remember { mutableStateOf(editing?.note ?: "") }
    // 是否报销：选中后这笔会进首页「报销」统计
    var reimbursed by remember { mutableStateOf(editing?.reimbursed ?: false) }
    // 当前选中的分类（二级优先）
    var categoryId by remember { mutableLongStateOf(editing?.categoryId ?: 0L) }
    // 这笔钱走哪个账户。新建先占「未指定」，等 lastUsed 到位再换成上次用的
    var accountId by remember {
        mutableLongStateOf(editing?.accountId ?: AccountEntity.UNSPECIFIED_ID)
    }
    var dateMillis by remember {
        mutableLongStateOf(editing?.dateMillis ?: System.currentTimeMillis())
    }
    // 时间没被改过就是「现在」—— 键盘上的时间键显示「现在」而不是 HH:mm。
    // 改过（日历里选了别的时刻）才显示具体值。
    var timeTouched by remember { mutableStateOf(false) }
    // 正在展开二级列表的一级分类（点它自己那个胶囊切换）
    var expandedParent by remember { mutableStateOf<CategoryEntity?>(null) }
    // 触发展开的下拉箭头在屏幕中的位置 —— 弹层从这儿缩放出来
    var childAnchorPos by remember { mutableStateOf(Offset.Zero) }

    // 切换「支出 / 收入」时收掉二级面板、清掉已选分类。
    // 收支两套分类表是分开的，留着上一次的 categoryId 会指向另一个收支下不存在的分类。
    // （旧 bug：展开着「餐饮」的二级列表时点「收入」，二级面板还挂着餐饮。）
    // 首次组合要跳过 —— 编辑旧账时 type 初值就带着原分类，不能被清掉。
    var firstTypeRun by remember { mutableStateOf(true) }
    LaunchedEffect(type) {
        if (firstTypeRun) { firstTypeRun = false; return@LaunchedEffect }
        expandedParent = null
        if (categoryId != 0L) {
            val cat = categories.firstOrNull { it.id == categoryId }
            if (cat == null || cat.type != type) categoryId = 0L
        }
    }

    val context = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.recognizeImage(context, uri) }

    LaunchedEffect(initialImage) {
        if (initialImage != null) vm.recognizeImage(context, initialImage)
    }

    // 新建：自动带出上次用的分类和账户（分类还在、类型对得上才带）
    LaunchedEffect(lastUsed, isEdit) {
        if (isEdit) return@LaunchedEffect
        if (categoryId == 0L && lastUsed.categoryId != 0L) {
            val c = categories.firstOrNull { it.id == lastUsed.categoryId }
            if (c != null && c.type == type) categoryId = c.id
        }
        if (accounts.any { it.id == lastUsed.accountId }) accountId = lastUsed.accountId
    }

    // OCR 预填：**只填金额、类型、时间**，备注一律不碰（那是用户自己的字段）
    val pendingOcr by vm.pendingOcr.collectAsStateCompat()
    LaunchedEffect(pendingOcr) {
        val p = pendingOcr ?: return@LaunchedEffect
        p.amountCents?.let { amount = LedgerViewModel.formatCents(it) }
        p.dateMillis?.let {
            dateMillis = it
            // **必须标记时间被改过**：不然时间键还显示「现在」，点开日历才发现
            // 已经是账单日期 —— 用户反馈的「识图后时间显示不一致」就是这个
            timeTouched = true
        }
        type = if (p.isIncome) TxType.INCOME.value else TxType.EXPENSE.value

        // 分类自动选：按推荐的一级分类名反查真实 id
        p.suggestParentName?.let { parentName ->
            val parent = categories.firstOrNull { it.type == type && it.name == parentName && it.parentId == null }
            if (parent != null) {
                val child = p.suggestChildName?.let { cn ->
                    categories.firstOrNull { it.parentId == parent.id && it.name == cn }
                }
                categoryId = child?.id ?: parent.id
            }
        }
        vm.setPendingOcr(null)
    }

    // 日历展开时，右滑返回手势**只关日历**，不退出整个新建页。
    // 之前没有拦截，Back 一路退到首页，填了一半的内容全丢。
    BackHandler(enabled = calendarOpen) {
        onCalendarOpen(false)
    }

    // 只显示当前收支类型下的分类
    val parents = remember(categories, type) {
        categories.filter { it.parentId == null && it.type == type }.sortedBy { it.sortOrder }
    }

    // 切收支类型时，清掉不属于该类型的选中项
    var lastType by remember { mutableIntStateOf(type) }
    LaunchedEffect(type, categories) {
        if (lastType != type) {
            lastType = type
            val c = categories.firstOrNull { it.id == categoryId }
            if (c == null || c.type != type) categoryId = 0L
        }
    }

    // 控件色统一用主题色（支付宝蓝）：分类选中、键盘确认、日历选中都跟主色走。
    // 收支类型只体现在顶部切换和金额颜色上，不把整个键盘染成红/绿。
    val accent = AppTheme.primary
    // 新建且没动过时间 = 「现在」
    val isNowTime = !isEdit && !timeTouched
    val selectedParent = parents.firstOrNull { p ->
        p.id == categoryId || categories.any { it.parentId == p.id && it.id == categoryId }
    }

    /** 保存：把表达式算掉，校验，然后落库 */
    /** 保存：把表达式算掉，校验，然后落库。keepOpen=true 时保存后留在页面继续记。 */
    fun save(keepOpen: Boolean = false) {
        if (categoryId <= 0L) {
            vm.showToast("先选个分类")
            return
        }
        val finalAmount = if (amount.any { it == '+' || it == '−' }) {
            calculate(amount) ?: run {
                vm.showToast("金额算不出来，检查一下")
                return
            }
        } else amount

        if (isEdit) {
            vm.updateTransaction(
                id = editing!!.id,
                amountYuan = finalAmount,
                type = TxType.from(type),
                categoryId = categoryId,
                dateMillis = dateMillis,
                note = note,
                reimbursed = reimbursed,
                accountId = accountId
            )
        } else {
            // 新建：用界面上选的时间（默认就是打开时的「现在」）
            vm.addTransaction(
                finalAmount, TxType.from(type), categoryId,
                dateMillis, note, reimbursed, accountId
            )
        }
        if (keepOpen && !isEdit) {
            // 连续记账：只清金额，省掉反复开关页面。分类/时间/报销保持不变（多半是连着记同类）。
            amount = ""
        } else {
            onDone()
        }
    }

    // fillMaxSize 而非 fillMaxWidth —— 下面的 Spacer(weight) 要靠它才能撑开留白。
    // 之前是 fillMaxWidth（高度由内容决定），weight 会失效，中间就压不出一块空。
    // statusBarsPadding：返回按钮不被状态栏盖住（参考图里顶栏贴着状态栏下沿）。
    Column(
        modifier = modifier
            // 高度由外层 weight 分配（弹层高 - 把手横条），这里只填满宽度。
            // 之前是 fillMaxSize：会把表单撑到整个 Dialog 窗口高（超过弹层本体），
            // 内容超出部分画到弹层外面，键盘最后一排直接被顶出屏幕。
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {

        /* ---------- 顶栏：居中的收支切换（返回/关闭在弹窗顶部横条） ---------- */
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
            contentAlignment = Alignment.Center
        ) {
            TypeSwitch(type) { type = it }
        }

        // 顶栏和一级分类之间留空行（用户反馈距离太近）
        Spacer(Modifier.height(18.dp))

        // 「日历 / 分类网格」区：占据键盘以上的全部剩余高度，内容超出就在区内滚动。
        // 注意结构：weight 放在**普通 Box** 上（高度严格 = 分配值，永远不会把键盘顶出屏幕），
        // verticalScroll 放在里面的内容 Column 上。之前 weight+verticalScroll 直接叠在
        // Crossfade 上，实测高度会失控（多出 ~150dp），键盘最后一排被顶出屏幕外。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
        ) {
            Crossfade(
                targetState = calendarOpen,
                animationSpec = tween(200, easing = FastOutSlowInEasing),
                label = "calendarSwap",
                modifier = Modifier.fillMaxSize()
            ) { open ->
                if (open) {
                    // 日历自己占满这一整块高度：月历 + 时分滚轮在面板内部滚，
                    // 「取消 / 确认」钉在面板下沿。
                    // 之前是整个面板挂在外面那层 verticalScroll 里，面板比可视区高一大截，
                    // 确认按钮永远滚在下面看不见（用户反馈「打开下面会被遮挡」）。
                    CalendarPanel(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        dateMillis = dateMillis,
                        accent = accent,
                        onConfirm = {
                            dateMillis = it
                            timeTouched = true
                            onCalendarOpen(false)
                        },
                        onCancel = { onCalendarOpen(false) }
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        /* ---------- 分类网格 ---------- */
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                // 胶囊固定 46dp 高（见 CategoryChip），网格按行数算总高。
                // 行距 14dp：之前 38dp 胶囊 + 10dp 间距，两行之间实际只有 10dp 空隙，
                // 手指按在行缝上就容易选错分类（用户反馈「每行间隔太小，容易误触」）。
                val gridCols = 3
                val cellH = 46.dp
                val vGap = 14.dp
                val gridRows = (parents.size + gridCols - 1) / gridCols
                // 真实行高；超过 5 行才滚动
                val gridH = (cellH * gridRows + vGap * (gridRows - 1) + 12.dp)
                    .coerceAtMost(cellH * 5 + vGap * 4 + 12.dp)
                    .coerceAtLeast(0.dp)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridCols),
                    modifier = Modifier.fillMaxWidth().height(gridH),
                    // 左右留白和列间距各收一点，把宽度让给文字：
                    // 胶囊里要放「图标 + 名称 + 下拉箭头」，横向空间本来就不宽裕。
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(vGap)
                ) {
                    items(parents, key = { it.id }) { p ->
                        val kids = categories.filter { it.parentId == p.id }
                        val pickedChild = kids.firstOrNull { it.id == categoryId }
                        val selected = pickedChild != null || p.id == categoryId
                        CategoryChip(
                            iconKey = pickedChild?.iconKey ?: p.iconKey,
                            tileColor = com.dafeng.onlymoneynote.util.AppIcons
                                .colorFromKey(p.colorKey),
                            label = pickedChild?.name ?: p.name,
                            hasChildren = kids.isNotEmpty(),
                            selected = selected,
                            accent = accent,
                            onBodyClick = { categoryId = p.id },
                            onArrowClick = if (kids.isNotEmpty()) {
                                { pos ->
                                    // 记录下拉箭头的屏幕位置，弹层从这儿展开
                                    childAnchorPos = pos
                                    expandedParent = if (expandedParent?.id == p.id) null else p
                                }
                            } else null
                        )
                    }
                }
            }

                }
            }
            }
            }

            /* ---------- 键盘区 ---------- */
            // 键盘是 4 列等分 + 右侧 1 列功能键，共 5 列 4 个间隙。
            // 金额栏宽度要精确等于「1 2 3」三列 + 2 个间隙，所以**不能用 weight** ——
            // 右侧功能列是固定宽，weight 分出来的宽度跟键盘列宽对不上。
            // 这里先算出精确列宽，金额/备注/键盘/功能列全部用固定 dp 宽度。
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // 布局对齐的关键：整个键盘区是 5 列 4 间隙（键盘 4 列 + 右侧功能列 1 列）。
            // 上面的金额行也按 5 列 4 间隙切：金额占 4 列、备注占 1 列。
            // 两行用同一个 colW/keyGap，**上下就严格对齐**，不靠 weight（weight 算不准）。
            val keyGap = 8.dp
            val colW = (maxWidth - 16.dp - keyGap * 4) / 5
            // 金额/备注是**独立一行**，占满整行宽度（不跟键盘抢列）。
            // 键盘区（4 列）+ 右侧功能列（1 列）= 5 列 4 间隙，正好用满整行。
            // 金额在左占 3 列（键盘「1 2 3」+ 间隙），备注在右占 2 列
            val sideW = colW                            // 右侧功能列 1 列
            val noteW = colW * 2 + keyGap
            val amountW = colW * 3 + keyGap * 2

            // 键盘区：金额行 + 键盘行，两行各自占满 5 列 4 间隙
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            ) {
                /* ---------- 账户：这笔钱从哪个账户走（紧贴金额行上方） ---------- */
                AccountPickerRow(
                    accounts = accounts,
                    selectedId = accountId,
                    accent = accent,
                    onSelect = { accountId = it }
                )

                // 金额行：备注（左，3 列宽）+ 金额（右，2 列宽 = 符号列+功能列）
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(keyGap)
                ) {
                        // 金额（左）：宽度 = 键盘「1 2 3」三列 + 2 个间隙
                        Box(
                            modifier = Modifier
                                .width(amountW)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "¥",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (amount.isBlank())
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                    else accent
                                )
                                Text(
                                    text = amount.ifBlank { "0" },
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (amount.isBlank()) {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                    } else accent
                                )
                            }
                        }
                        // 备注（右）：宽度 = 键盘符号列 + 右侧功能列（2 列 + 1 个间隙）
                        Box(
                            modifier = Modifier
                                .width(noteW)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicTextField(
                                value = note,
                                onValueChange = { note = it },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                ),
                                cursorBrush = SolidColor(accent),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.Center) {
                                        if (note.isEmpty()) {
                                            Text(
                                                "备注",
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                            )
                                        }
                                        inner()
                                    }
                                }
                            )
                        }

                    }
                // 键盘行：键盘（4 列）+ 右侧功能列（1 列）。
                // **日历展开时整块隐藏**：日期 + 时分滚轮本来就比键盘高，
                // 两个挤在一屏里必然有一个被挡（用户反馈「日期和时间都会被遮挡」）。
                // 让位之后上面那块 weight 区域自动撑开，日历能完整显示，
                // 「取消 / 确认」钉在面板底部，不用再猜哪儿能滚。
                if (!calendarOpen) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(keyGap),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Keypad(
                        modifier = Modifier.width(colW * 4 + keyGap * 3),
                        accent = accent,
                        hasOperator = amount.any { it == '+' || it == '−' },
                        onKey = { k ->
                            when (k) {
                                "⌫" -> amount = amount.dropLast(1)
                                "+", "−" -> if (amount.isNotEmpty()) {
                                    amount = if (amount.last() in "+−") amount.dropLast(1) + k else amount + k
                                }
                                "." -> {
                                    val seg = amount.takeLastWhile { it.isDigit() || it == '.' }
                                    if (!seg.contains('.')) amount = if (seg.isEmpty()) amount + "0." else amount + "."
                                }
                                // = 只负责把表达式算成结果，按钮随自动变成 ✓
                                "=" -> {
                                    val r = calculate(amount)
                                    if (r != null) amount = r else vm.showToast("表达式算不出来")
                                }
                                "✓" -> save()
                                else -> amount += k
                            }
                        },
                        onLongBackspace = { amount = "" }
                    )
                // 右侧功能列：宽度 = 一列，跟键盘符号列严格同宽
                Column(
                    // 上下各 3dp 跟键盘每行的 padding(vertical=3.dp) 对齐，
                    // 否则键盘总高 232dp、功能列只有 226dp，肉眼能看出差 6dp
                    modifier = Modifier
                        .width(sideW)
                        .padding(vertical = 3.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 第 1 行：识图
                    KeypadSideButton(
                        label = "识图",
                        icon = Icons.Outlined.Image,
                        accent = accent,
                        active = false,
                        onClick = {
                            pickImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                    // 第 2 行：时间。默认显示「现在」，改过时间才显示具体值
                    KeypadSideButton(
                        label = if (isNowTime) "现在" else timeLabelForKeypad(dateMillis),
                        icon = Icons.Outlined.Schedule,
                        accent = accent,
                        active = !isNowTime,
                        onClick = { onCalendarOpen(!calendarOpen) }
                    )
                    // 第 3 行：报销
                    KeypadSideButton(
                        label = "报销",
                        icon = Icons.Outlined.Assignment,
                        accent = accent,
                        active = reimbursed,
                        onClick = { reimbursed = !reimbursed }
                    )
                    // 第 4 行：双对勾 —— 保存后留在本页继续记（连续记账）
                    if (!isEdit) {
                        KeypadSideButton(
                            label = "",   // 只留双对勾图标，不加文字
                            icon = Icons.Outlined.DoneAll,
                            accent = accent,
                            active = false,
                            onClick = { save(keepOpen = true) }
                        )
                    } else {
                        Spacer(Modifier.height(52.dp))
                    }
                }
                }   // 键盘行 Row
                }   // if (!calendarOpen)：日历展开时键盘让位
                // 键盘区底部的呼吸位。注意要放在这个 Column **里面**：
                // 之前这个留白写在外层 BoxWithConstraints（= Box，层叠布局）里，
                // 根本不占列高，等于没垫。导航条留白现在统一由弹层卡片底部 padding 负责。
                Spacer(Modifier.height(2.dp))
            }
        }
    }

    /* ---------- 二级分类弹窗：点一级分类的下拉按钮才弹 ---------- */
    expandedParent?.let { p ->
        ChildPickerPopup(
            parent = p,
            children = categories.filter { it.parentId == p.id }.sortedBy { it.sortOrder },
            selectedId = categoryId,
            accent = accent,
            anchor = childAnchorPos,
            onDismiss = { expandedParent = null },
            onPickChild = {
                categoryId = it.id
                expandedParent = null
            }
        )
    }
}

/* ------------------------------------------------------------------ */
/* 顶部收支切换                                                        */
/* ------------------------------------------------------------------ */

@Composable
private fun TypeSwitch(selected: Int, onSelect: (Int) -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            listOf("支出", "收入").forEachIndexed { i, label ->
                val sel = i == selected
                val c = if (i == TxType.EXPENSE.value) AppTheme.expense else AppTheme.income
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (sel) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 16.dp, vertical = 7.dp)
                ) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (sel) c else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* 报销开关                                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun ReimburseToggle(
    selected: Boolean,
    accent: Color,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(50.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(
                if (selected) accent.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "报销",
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/* ------------------------------------------------------------------ */
/* 分类胶囊                                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun CategoryChip(
    iconKey: String,
    label: String,
    hasChildren: Boolean,
    selected: Boolean,
    accent: Color,
    /** 图标块底色：分类自定义配色（null = 由 IconTile 回退到图标分组色） */
    tileColor: Color? = null,
    // 点胶囊主体：只选这一级（一级），不展开二级
    onBodyClick: () -> Unit,
    // 点右边小箭头：展开 / 收起二级；没有二级时传 null。
    // 回调带上下拉箭头在屏幕中的位置（中心点），弹层从那儿展开。
    onArrowClick: ((center: Offset) -> Unit)? = null
) {
    // 下拉箭头在窗口中的位置（由 onGloballyPositioned 写入，点击时传给回调）
    var arrowPos by remember { mutableStateOf(Offset.Zero) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 胶囊 46dp 高（原 38dp）+ 行距 14dp（原 10dp）：两行之间不再贴着，
            // 上下误触明显减少；横向尺寸见下面图标块/箭头的注释，宽度要让给名称。
            .height(46.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(
                if (selected) accent else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onBodyClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 彩色图标块：底色走分类自己的配色（一级自定义色优先，没自定义按图标分组色），
     // 跟账单列表 / 分类管理 / 统计里是同一套观感。之前这里是灰底 + 单色图标，
     // 用户反馈「类别图标没有对应的颜色」。
        // 横向预算仍是硬约束：一格约 108~113dp，图标每大 2dp 名称就少 2dp，
        // 上一版把图标放到 34dp 时三个字的分类被压成了「…」。
        IconTile(
            iconKey = iconKey, size = 30.dp, cornerRadius = 9.dp,
            modifier = Modifier.padding(start = 4.dp),
            overrideColor = tileColor
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            // 13.5sp：三个字 40dp，正好落在名称可用宽度（约 47~52dp）以内
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface
        )
        if (hasChildren) {
            // 小箭头单独可点：展开二级，不触发主体选中。
            // 触控区 22x46dp —— 纵向铺满整格（上下不误触），横向不跟名称抢宽度。
            // onGloballyPositioned 拿它在**屏幕**中的位置，弹层从这儿展开。
            Box(
                modifier = Modifier
                    .onGloballyPositioned { coords ->
                        arrowPos = coords.positionInWindow()
                    }
                    .clickable(enabled = onArrowClick != null) {
                        onArrowClick?.invoke(arrowPos)
                    }
                    .size(width = 22.dp, height = 46.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.KeyboardArrowDown, null,
                    modifier = Modifier.size(17.dp),
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Spacer(Modifier.width(6.dp))
        }
    }
}

/* ------------------------------------------------------------------ */
/* 二级分类弹层（从被点的下拉箭头位置展开，落到屏幕正中）                  */
/* ------------------------------------------------------------------ */

/**
 * 二级分类弹层。展开动画的**缩放原点 = 被点的那个下拉箭头**，
 * 所以每个一级分类点开时动画起点都不一样；终点固定在屏幕正中间。
 *
 * 做法：Popup 全屏铺开，内容用 `graphicsLayer` 的 `transformOrigin`
 * 把缩放中心挪到 anchor 位置（换算成 0~1 比例），再从 0.7 放大到 1。
 */
@Composable
private fun ChildPickerPopup(
    parent: CategoryEntity,
    children: List<CategoryEntity>,
    selectedId: Long,
    accent: Color,
    /** 触发的下拉箭头在窗口中的位置 */
    anchor: Offset,
    onDismiss: () -> Unit,
    onPickChild: (CategoryEntity) -> Unit
) {
    val density = LocalDensity.current
    // 宽度按**内容**定，不再撑满 340dp —— 二级名通常 2~4 字，撑太宽列里全是空白。
    // 两列 × 每列最多 6 个字 + 图标，算出来大概 210~260dp。
    // 宽度固定 = 记一笔弹窗（90% 屏宽）的 80% = 72% 屏宽。
    // 之前按内容自适应，长短不一；固定比例跟记账弹窗形成层级关系。
    val panelWidth = with(density) {
        LocalConfiguration.current.screenWidthDp.dp * 0.72f
    }
    // 二级分类的图标块颜色永远跟一级走（一级没自定义色就由 IconTile 回退到分组色）
    val tileColor = com.dafeng.onlymoneynote.util.AppIcons.colorFromKey(parent.colorKey)
    // 进场只做渐入（用户要求：不要从箭头缩放展开）
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(170, easing = FastOutSlowInEasing)) }

    Popup(
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
        onDismissRequest = onDismiss
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f * enter.value))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(panelWidth)
                    .graphicsLayer { alpha = enter.value }
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp)
            ) {
                // 标题行：一级分类名（主题色实底，跟一级胶囊同款尺寸）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent)
                        .padding(start = 5.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 标题那一行本身是主题色实底，图标块用分类色会糊在一起，
                    // 所以这里保持半透明白底 + 白图标，靠名称区分
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CategoryIcon(iconKey = parent.iconKey,
                            size = 24.dp, modifier = Modifier,
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        parent.name,
                        modifier = Modifier.weight(1f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 二级分类：两列。行距 12dp + 胶囊 46dp，跟一级网格同一套间距，
                // 免得两行名字贴在一起点错。
                // 二级多了要能滚：面板限高 + 内部滚动，否则子分类一多整个面板顶出屏幕。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.52f).dp)
                        .verticalScroll(rememberScrollState())
                ) {
                Column {
                children.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { c ->
                            val selected = c.id == selectedId
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (selected) accent
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { onPickChild(c) }
                                    .padding(start = 5.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconTile(
                                    iconKey = c.iconKey, size = 34.dp, cornerRadius = 9.dp,
                                    overrideColor = tileColor
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    c.name,
                                    modifier = Modifier.weight(1f),
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                }   // Column（可滚动的二级列表容器）
                }   // Box + verticalScroll
            }
        }
    }
}


/* ------------------------------------------------------------------ */
/* 键盘右侧功能键（时间 / 报销 / 识图）                                    */
/* ------------------------------------------------------------------ */

/** 键盘右侧竖排的功能键。激活时用强调色实底，跟数字键的视觉层级一致。 */
@Composable
private fun KeypadSideButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (active) accent
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // label 为空（连记键）时只放图标，否则「空 Text + Spacer」会把图标顶到上面去
        if (label.isEmpty()) {
            Icon(
                icon, null,
                modifier = Modifier.size(20.dp),
                tint = if (active) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    icon, null,
                    modifier = Modifier.size(17.dp),
                    tint = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    label,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 键盘上显示的时间：今天只显示 HH:mm，其他显示 MM-dd */
private fun timeLabelForKeypad(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    val now = Calendar.getInstance()
    val sameYear = c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
    val sameDay = sameYear && c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) {
        "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    } else {
        "%02d-%02d".format(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }
}

/* ------------------------------------------------------------------ */
/* 数字键盘（✓ = 保存）                                                 */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(
    accent: Color,
    hasOperator: Boolean,
    onKey: (String) -> Unit,
    onLongBackspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = listOf(
        listOf("1", "2", "3", "+"),
        listOf("4", "5", "6", "−"),
        listOf("7", "8", "9", "⌫"),
        // 连续记账移到右侧功能列的第 4 行（双对勾图标），这里只留保存
        listOf("0", ".", "00", if (hasOperator) "=" else "✓")
    )
    Column(modifier = modifier) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { k ->
                    if (k.isEmpty()) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        KeyButton(
                            label = k,
                            accent = accent,
                            modifier = Modifier.weight(1f),
                            onClick = { onKey(k) },
                            onLongClick = if (k == "⌫") onLongBackspace else null
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyButton(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val isOp = label in listOf("+", "−")
    // ✓ 保存 和 = 算结果，都用强调色实底，让它在键盘里最显眼
    val isOk = label == "✓" || label == "="
    val isBack = label == "⌫"
    val bg = when {
        isOk -> accent
        isOp -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val fg = when {
        isOk -> MaterialTheme.colorScheme.onPrimary
        isOp -> accent
        isBack -> accent
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(bg)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (label == "✓") {
            Icon(
                Icons.Outlined.Check, "保存",
                modifier = Modifier.size(26.dp),
                tint = fg
            )
        } else {
            Text(
                label,
                fontSize = when {
                    isOk -> 24.sp
                    isBack -> 20.sp
                    else -> 19.sp
                },
                fontWeight = if (isOp || isBack || isOk) FontWeight.Bold else FontWeight.Medium,
                color = fg
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* 日期时间入口                                                        */
/* ------------------------------------------------------------------ */

/**
 * 日期时间胶囊。点一下把日历在下面展开（再点收起），不弹窗遮挡。
 */
@Composable
private fun DateTimeChip(
    dateMillis: Long,
    expanded: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    val cal = remember(dateMillis) {
        Calendar.getInstance().apply { timeInMillis = dateMillis }
    }
    val now = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
    }
    val isToday = remember(dateMillis) {
        val c = Calendar.getInstance().apply { timeInMillis = dateMillis }
        c.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    }
    val isThisYear = cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)

    // 只要日期 + 时间，不带周几
    val dayText = when {
        isToday -> "今天"
        else -> {
            val d = "%d月%d日".format(cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            if (isThisYear) d else "${cal.get(Calendar.YEAR)}年$d"
        }
    }
    val timeText = "%02d:%02d".format(
        cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
    )

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (expanded) accent.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(start = 13.dp, end = 9.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Schedule, null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                dayText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = accent
            )
            Spacer(Modifier.width(9.dp))
            Text(
                timeText,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* 日历面板（内联展开，不是弹窗）                                        */
/* ------------------------------------------------------------------ */

/**
 * 日历 + 滚轮时间。
 *
 * - 顶部「2026/10」两侧「« ‹」「› »」：双箭头切年、单箭头切月
 * - 星期行：选中日期所在的那一列用强调色标出
 * - 日期格：圆角方块，选中的用强调色实底白字
 * - 下面两个**滚轮**（小时 0-23 / 分钟 0-59），像定闹钟那样拖
 * - 底部：取消 / 确认
 */
@Composable
private fun CalendarPanel(
    dateMillis: Long,
    accent: Color,
    onConfirm: (Long) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val init = remember(dateMillis) {
        Calendar.getInstance().apply { timeInMillis = dateMillis }
    }
    // 正在浏览的年月（每次展开都跟着当前选中日期重新初始化）
    var viewYear by remember(dateMillis) { mutableIntStateOf(init.get(Calendar.YEAR)) }
    var viewMonth by remember(dateMillis) { mutableIntStateOf(init.get(Calendar.MONTH)) }
    var selYear by remember(dateMillis) { mutableIntStateOf(init.get(Calendar.YEAR)) }
    var selMonth by remember(dateMillis) { mutableIntStateOf(init.get(Calendar.MONTH)) }
    var selDay by remember(dateMillis) { mutableIntStateOf(init.get(Calendar.DAY_OF_MONTH)) }
    var hour by remember(dateMillis) { mutableIntStateOf(init.get(Calendar.HOUR_OF_DAY)) }
    var minute by remember(dateMillis) { mutableIntStateOf(init.get(Calendar.MINUTE)) }

    fun shiftMonth(delta: Int) {
        var m = viewMonth + delta
        var y = viewYear
        while (m < 0) { m += 12; y -= 1 }
        while (m > 11) { m -= 12; y += 1 }
        viewYear = y
        viewMonth = m
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        // 面板 = 上面可滚动的内容 + 下面**固定不动**的取消/确认。
        // 之前整块都靠外面的滚动，展开日历后确认按钮被顶到可视区下面，
        // 想改完日期点个确认还得先猜哪儿能滚 —— 这就是「打开下面会被遮挡」。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
            /* ---------- 年月标题 + 四个箭头 ---------- */
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArrowBtn("«") { viewYear -= 1 }
                ArrowBtn("‹") { shiftMonth(-1) }
                Text(
                    "%d/%d".format(viewYear, viewMonth + 1),
                    modifier = Modifier.weight(1f),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
                ArrowBtn("›") { shiftMonth(1) }
                ArrowBtn("»") { viewYear += 1 }
            }

            Spacer(Modifier.height(8.dp))

            /* ---------- 星期行 ---------- */
            val weekLabels = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
            val selDow = remember(selYear, selMonth, selDay) {
                Calendar.getInstance().apply { set(selYear, selMonth, selDay) }
                    .get(Calendar.DAY_OF_WEEK)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                weekLabels.forEachIndexed { idx, label ->
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = if (idx + 1 == selDow) FontWeight.Bold else FontWeight.Normal,
                        color = if (idx + 1 == selDow) accent
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(5.dp))

            /* ---------- 日期网格 ---------- */
            val firstCal = remember(viewYear, viewMonth) {
                Calendar.getInstance().apply { set(viewYear, viewMonth, 1) }
            }
            val leading = firstCal.get(Calendar.DAY_OF_WEEK) - 1   // 周日 = 0
            val daysInMonth = firstCal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val totalCells = ((leading + daysInMonth + 6) / 7) * 7
            val today = remember {
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (rowStart in 0 until totalCells step 7) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (col in 0 until 7) {
                            val cellIndex = rowStart + col
                            val dayNum = cellIndex - leading + 1
                            if (dayNum in 1..daysInMonth) {
                                val isSel = dayNum == selDay &&
                                    viewMonth == selMonth && viewYear == selYear
                                val isToday = viewMonth == today.get(Calendar.MONTH) &&
                                    viewYear == today.get(Calendar.YEAR) &&
                                    dayNum == today.get(Calendar.DAY_OF_MONTH)
                                DayCell(
                                    day = dayNum,
                                    selected = isSel,
                                    isToday = isToday,
                                    accent = accent,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    selYear = viewYear
                                    selMonth = viewMonth
                                    selDay = dayNum
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                                        )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            /* ---------- 时间：两个滚轮 ---------- */
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                WheelPicker(
                    count = 24,
                    selected = hour,
                    unit = "时",
                    onSelected = { hour = it },
                    accent = accent,
                    modifier = Modifier.width(88.dp).fillMaxHeight()
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    ":",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                WheelPicker(
                    count = 60,
                    selected = minute,
                    unit = "分",
                    onSelected = { minute = it },
                    accent = accent,
                    modifier = Modifier.width(88.dp).fillMaxHeight()
                )
            }

            }   // 可滚动区结束
            Spacer(Modifier.height(10.dp))

            /* ---------- 底部：取消 / 确认（固定在面板下沿） ---------- */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "取消",
                        fontSize = 14.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(accent)
                        .clickable {
                            val picked = Calendar.getInstance().apply {
                                set(selYear, selMonth, selDay, hour, minute, 0)
                                set(Calendar.MILLISECOND, 0)
                            }
                            onConfirm(picked.timeInMillis)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "确认",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

/**
 * 滚轮选择器（像定闹钟那样上下滑）。
 *
 * 实现：LazyColumn + snap fling（松手自动吸附到整格），
 * 上下各留两个格子的空白让首尾也能滚到正中间，
 * 中间那格用高亮底 + 加粗放大表示"当前选中"。
 */
@Composable
private fun WheelPicker(
    count: Int,
    selected: Int,
    unit: String,
    onSelected: (Int) -> Unit,
    accent: Color = AppTheme.primary,
    modifier: Modifier = Modifier
) {
    val visibleRows = 3
    val itemHeight = 34.dp
    val itemPx = with(LocalDensity.current) { itemHeight.toPx() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    val fling = rememberSnapFlingBehavior(lazyListState = state)

    // 滚动时把「停在中间的那一格」回报出去
    LaunchedEffect(state) {
        snapshotFlow {
            val index = state.firstVisibleItemIndex
            val offset = state.firstVisibleItemScrollOffset
            if (offset > itemPx * 0.5f) (index + 1).coerceAtMost(count - 1) else index
        }
            .distinctUntilChanged()
            .collect { onSelected(it) }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 中间的选中指示条（画在列表底下，靠数字显示出来）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .clip(RoundedCornerShape(11.dp))
                .background(accent.copy(alpha = 0.10f))
        )

        LazyColumn(
            state = state,
            flingBehavior = fling,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = itemHeight * ((visibleRows - 1) / 2)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(count) { i ->
                val isCenter = i == selected
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "%02d".format(i),
                            fontSize = if (isCenter) 21.sp else 16.sp,
                            fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCenter) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        )
                        if (isCenter) {
                            Spacer(Modifier.width(2.dp))
                            Text(
                                unit,
                                modifier = Modifier.padding(bottom = 3.dp),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 年/月切换的小箭头按钮 */
@Composable
private fun ArrowBtn(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 日期格 */
@Composable
private fun DayCell(
    day: Int,
    selected: Boolean,
    isToday: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(
                if (selected) accent else MaterialTheme.colorScheme.surfaceVariant
            )
            .then(
                if (isToday && !selected) {
                    Modifier.border(1.5.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(11.dp))
                } else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "$day",
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = when {
                selected -> MaterialTheme.colorScheme.onPrimary
                isToday -> accent
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}


/* ------------------------------------------------------------------ */
/* 金额解析与求值                                                       */
/* ------------------------------------------------------------------ */


/** 表达式求值。支持 + − 与小数（减号也认 ASCII 的 -）。返回 null 表示非法。 */
internal fun calculate(expr: String): String? {
    if (expr.isBlank()) return null
    val cleaned = expr.trimEnd('+', '−', '-')
    if (cleaned.isBlank()) return null

    val normalized = cleaned.replace('−', '-')
    val tokens = Regex("(\\d*\\.?\\d+|[+\\-])")
        .findAll(normalized)
        .map { it.value }
        .toList()
    if (tokens.isEmpty()) return null
    tokens.forEachIndexed { i, t ->
        val isNum = t.first().isDigit() || t.first() == '.'
        if (isNum == (i % 2 == 1)) return null
    }

    var acc = 0.0
    var op = '+'
    tokens.forEach { t ->
        if (t.first().isDigit() || t.first() == '.') {
            val v = t.toDoubleOrNull() ?: return null
            acc = when (op) {
                '+' -> acc + v
                '-' -> acc - v
                else -> acc
            }
        } else {
            op = t[0]
        }
    }
    return trimNumber(acc)
}

/** 保留两位小数，去掉没意义的尾随零（0.30 → 0.3） */
private fun trimNumber(d: Double): String {
    val rounded = kotlin.math.round(d * 100) / 100
    if (kotlin.math.abs(rounded) >= 1_000_000_000L) return "0"
    if (rounded == rounded.toLong().toDouble()) return rounded.toLong().toString()
    return "%.2f".format(rounded).trimEnd('0').trimEnd('.')
}

/* ------------------------------------------------------------------ */
/* 账户选择行                                                          */
/* ------------------------------------------------------------------ */

/**
 * 记一笔里的「这笔钱从哪个账户走」：横向滚动的小胶囊，按使用频率排序。
 * 账户一般就七八个，滚一行够用，不像分类那样铺网格。
 */
@Composable
private fun AccountPickerRow(
    accounts: List<AccountEntity>,
    selectedId: Long,
    accent: Color,
    onSelect: (Long) -> Unit
) {
    if (accounts.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        accounts.forEach { a ->
            val selected = a.id == selectedId
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) accent else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelect(a.id) }
                    .padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconTile(
                    iconKey = a.iconKey, size = 24.dp, cornerRadius = 8.dp,
                    overrideColor = com.dafeng.onlymoneynote.util.AppIcons.colorFromKey(a.colorKey)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    a.name,
                    fontSize = 14.sp,
                    maxLines = 1,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
