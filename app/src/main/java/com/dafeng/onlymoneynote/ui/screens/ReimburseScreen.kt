package com.dafeng.onlymoneynote.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeng.onlymoneynote.data.local.TxWithCategory
import com.dafeng.onlymoneynote.ui.LedgerViewModel
import com.dafeng.onlymoneynote.ui.components.IconTile
import com.dafeng.onlymoneynote.ui.components.OverlayPageHeader
import com.dafeng.onlymoneynote.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 取绝对值。冲销账单金额是负的，汇总时用它 */
private fun absCents(v: Long): Long = if (v < 0) -v else v

/**
 * 报销页。
 *
 * ## 两个 tab（2026-10-11 用户要求）
 * 报销这件事天然有「还没报」和「报完了」两种状态，混在一张列表里没法看：
 * 用户想知道「我还差多少没报」，以及「上个月报回来多少」。所以分成两页：
 * - **未报销**：报销池里还没报的。右滑 = 报掉了（写一条负支出冲销）
 * - **已报销**：报过的。右滑 = 撤销（删掉冲销记录，退回未报销）
 *
 * ## 为什么右滑要新增一条负支出账单
 * 只把原账单标成「已报销」，统计里那笔钱永远算成花掉了，而钱实际公司已经赔了，
 * 月度支出和结余都会偏大。所以在账上补一条**当天、分类相同、金额为负**的支出冲销掉，
 * 支出总额自动扣减，账单列表里也留得下痕迹。详见 [LedgerViewModel.reimburseTx]。
 *
 * ## 日期怎么处理
 * 原账单日期**不动**（还是消费那天），报销日期单独存 [TxWithCategory.reimburseDateMillis]，
 * 已报销 tab 里显示成「10-08 花的 · 09-27 报的」。
 * 挪日期会让这笔钱跑到别的月份去，跟当月支出对不上。
 */
@Composable
fun ReimburseScreen(
    transactions: List<TxWithCategory>,
    onEdit: (TxWithCategory) -> Unit,
    onDelete: (Long) -> Unit,
    /** 右滑未报销的账单 = 报掉了 */
    onReimburse: (TxWithCategory) -> Unit,
    /** 右滑已报销的账单 = 撤销报销 */
    onUnreimburse: (TxWithCategory) -> Unit,
    /** 底栏去掉后这页变成二级页，页头里要有返回 */
    onBack: () -> Unit = {}
) {
    // 0 = 未报销，1 = 已报销
    var tab by remember { mutableStateOf(0) }
    var openedId by remember { mutableStateOf<Long?>(null) }

    val pending = remember(transactions) {
        transactions.filter { !it.reimbursed }
    }
    // 已报销 tab 只显示**冲销行**，原账单本身不重复列出来。
    // 原账单已标 reimbursed，它的金额已经全额计入支出，靠这条负数把它冲回来 ——
    // 两个都列的话同一笔在 tab 里出现两次，看着像重复记账。
    // 认 note 前缀而不是「金额为负」：用户手记的负数支出不是报销冲销，混进来就成了脏数据。
    // 撤销也从这条负数行发起：它的 note 里存着原账单 id，能精确定位回去。
    val done = remember(transactions) {
        transactions.filter { it.reimbursed && isOffsetTx(it) }
    }
    val current = if (tab == 0) pending else done

    // 页头那个大数字：未报销 tab 显示「还差多少」，已报销 tab 显示「报回来多少」
    val pendingCents = remember(pending) { pending.sumOf { it.amountCents } }
    // 已报销 tab 里全是冲销行（账本是负数、界面显示正数），汇总取绝对值
    val doneCents = remember(done) { done.sumOf { it.amountCents } }
    // 冲销账单金额是负的，汇总时减掉，得到实际报销回来的净额
    val netDone = remember(done) { done.sumOf { it.amountCents } }

    val grouped = remember(current) { current.groupBy { it.dateMillis.atDay() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        /* ---------- 主题色页头 ---------- */
        OverlayPageHeader(title = "报销", onBack = onBack) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 22.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                Text(
                    if (tab == 0) "待报销" else "已报销",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.72f)
                )
                Text(
                    "¥" + LedgerViewModel.formatCents(if (tab == 0) pendingCents else absCents(netDone)),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        /* ---------- 白色面板 ---------- */
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize()) {
                /* ---------- 两个 tab ---------- */
                ReimburseTabs(
                    pendingCount = pending.size,
                    doneCount = done.size,
                    selected = tab,
                    onSelect = {
                        tab = it
                        // 换 tab 时把还张着的行收回去，不然会看到上一 tab 展开的删除带
                        openedId = null
                    }
                )

                if (current.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconTile(iconKey = "reimburse", size = 52.dp, cornerRadius = 16.dp)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (tab == 0) "没有待报销的账单" else "还没有报过销",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (tab == 0) "记一笔时打开「报销」开关就会出现在这里"
                                else "在「未报销」里右滑一笔，报过的就会挪到这边",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // 左右不留白：滑动露出的删除底板是通栏色块，
                        // 列表留 14dp 边距的话底板两端会露出白底，看着像没铺满。
                        // 行内容自己带 14dp（见 ReimburseTxCard），跟首页效果一致
                        contentPadding = PaddingValues(
                            start = 0.dp, end = 0.dp, top = 2.dp, bottom = 20.dp
                        )
                    ) {
                        grouped.forEach { (day, list) ->
                            item(key = "r-header-$day", contentType = "header") {
                                val totals = remember(list) { dayTotals(list) }
                                // 列表去掉 contentPadding 的左右边距让底板通栏，日期头自己补 14dp 缩进，
                                // 不然会顶到屏幕边上
                                Box(Modifier.padding(horizontal = 14.dp)) {
                                    DayHeader(day = day, expense = totals.first, income = totals.second)
                                }
                            }
                            items(list, key = { it.id }, contentType = { "tx" }) { tx ->
                                SwipeableReimburseRow(
                                    tx = tx,
                                    isOpen = openedId == tx.id,
                                    isReimbursed = tab == 1,
                                    onOpenChange = { open ->
                                        openedId = if (open) tx.id else null
                                    },
                                    onDelete = onDelete,
                                    onEdit = onEdit,
                                    onSwipeAction = if (tab == 0) onReimburse else onUnreimburse
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

/**
 * 「未报销 / 已报销」两个 tab。
 *
 * 自绘不用 TabRow：Material 3 的 TabRow 有自己的高度和指示器动画，
 * 跟这个页面现有的页头 + 白色面板风格对不上，索性自己画，还能顺带显示笔数。
 */
@Composable
private fun ReimburseTabs(
    pendingCount: Int,
    doneCount: Int,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TabChip(
            label = "未报销",
            count = pendingCount,
            selected = selected == 0,
            onClick = { onSelect(0) },
            modifier = Modifier.weight(1f)
        )
        TabChip(
            label = "已报销",
            count = doneCount,
            selected = selected == 1,
            onClick = { onSelect(1) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TabChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) AppTheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(bg)
            .clickable(onClick = onClick)
.padding(vertical = 11.dp),
  horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 标题 17sp、笔数 13sp：原来 13/10.5 太小，笔数那行还老想折行，
    // 现在两者拉开层级，笔数固定单行（softWrap=false）跟着标题走
        Text(
   label,
 fontSize = 17.sp,
    fontWeight = FontWeight.SemiBold,
     color = fg,
            maxLines = 1
  )
        Text(
       "$count 笔",
         fontSize = 13.sp,
    color = fg.copy(alpha = 0.75f),
          maxLines = 1,
      softWrap = false
   )
    }
}

