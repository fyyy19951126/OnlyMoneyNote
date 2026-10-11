package com.dafeng.onlymoneynote.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeng.onlymoneynote.data.local.TxWithCategory
import com.dafeng.onlymoneynote.ui.LedgerViewModel
import com.dafeng.onlymoneynote.ui.components.IconTile
import com.dafeng.onlymoneynote.ui.theme.AppTheme
import com.dafeng.onlymoneynote.util.AppIcons
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 取绝对值。冲销账单金额是负的，显示时去掉负号 */
private fun absCents(v: Long): Long = if (v < 0) -v else v

/**
 * 手指滑动后露出的一侧宽度。
 * **必须跟首页 [REVEAL_WIDTH]（TransactionListScreen.kt，92.dp）一样** ——
 * 两页来回切，露出的宽度差几 dp 用户一眼就看出来不是一套手感。
 */
private val REIMBURSE_REVEAL = 92.dp

/**
 * 报销页的行：**左滑露出删除，右滑露出报销/撤销**。
 *
 * 跟首页的 [SwipeableTxRow] 是两套东西，刻意没合并：
 * 那边只有左滑一个方向，这边要双向；合并的话状态机要拆成两套分支，
 * 反而比各写一份更难读。两边共用底层那套「拖动 + 吸附」的写法。
 *
 * 右滑露出的按钮文案跟着 tab 变：未报销 tab 是「报销」（绿色），
 * 已报销 tab 是「撤销」（灰蓝色）—— 颜色用绿色而不是收入红，
 * 免得跟「收入 = 红」的全局约定打架。
 */
@Composable
internal fun SwipeableReimburseRow(
    tx: TxWithCategory,
    isOpen: Boolean,
    isReimbursed: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onEdit: (TxWithCategory) -> Unit,
    /** 右滑那个动作：未报销 tab 报销，已报销 tab 撤销 */
    onSwipeAction: (TxWithCategory) -> Unit
) {
    val offsetX = remember { Animatable(0f) }
    // 拖动中的实时位移（同步），松手后才交给 offsetX 做吸附动画
    var rawX by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val revealPx = with(density) { REIMBURSE_REVEAL.toPx() }

    // 外部把它关掉时（滑了别的行 / 换了 tab）同步收回。
    // 只判布尔值不知道该往哪边收，所以记一下上次是哪个方向露出的。
    var lastDir by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(isOpen) {
        val target = if (isOpen) lastDir * revealPx else 0f
        if (offsetX.value != target) offsetX.animateTo(target, tween(180))
        rawX = offsetX.value
    }

    val actionColor = if (isReimbursed) AppTheme.primary else AppTheme.expense
    val actionLabel = if (isReimbursed) "撤销" else "报销"
    val actionIcon = if (isReimbursed) Icons.Outlined.Undo else Icons.Outlined.Undo

    Box(modifier = Modifier.fillMaxWidth()) {
        // ---- 底层：左滑（负位移）露出左边的「报销 / 撤销」，右滑露出右边的「删除」。
      //      方向跟首页左滑删除保持一致：往左 = 侧边动作，往右 = 删除。
      //      两块都用 matchParentSize 严格等于卡片高度 —— 之前套了一层 Row + weight，
      //      高度跟上层卡片差了几 dp，滑动时底部露白，像按钮只占了半格。----
        // 左侧动作区（报销 / 撤销）：只铺左侧，右边**给删除区让出 [REIMBURSE_REVEAL]**。
      //      之前绿色铺满整行，卡片滑开后删除按钮的 14dp 圆角处透出后面的绿色，
      //      看着像删除按钮镶了一圈绿边。首页没这问题是因为它底层只有红色一块，
      //      圆角处透出来的是页面底色。padding 必须排在 background 前面才有效。
      Box(
        modifier = Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(end = REIMBURSE_REVEAL)
                    // 只切**左侧**两个角（外缘），跟删除区只切右侧对称
                    .clip(RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
                    .background(actionColor),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier
                        .padding(start = 20.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSwipeAction(tx) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(actionIcon, actionLabel, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(actionLabel, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                }
            }
            // 右侧删除区：右滑时露出来。
            // 只切**右侧**两个角（外缘），左边贴着卡片不切 ——
            // 切了角会在接缝处透出页面底色，看着像按钮镶了一圈白边。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(REIMBURSE_REVEAL)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                    .background(AppTheme.danger),
                contentAlignment = Alignment.CenterEnd
            ) {
                Row(
                    modifier = Modifier
                        .padding(end = 18.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onDelete(tx.id) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline, "删除",
                        tint = Color.White, modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("删除", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                }
            }
        }

        // ---- 上层：账单卡片，可横向拖动 ----
    //
    // 拖动位移用 [rawX] 这个**同步**的普通状态，不是 Animatable：
    // Animatable.snapTo 是挂起函数，只能在协程里调，于是松手那一刻
    // offsetX.value 还是拖动前的旧值，阈值判断成「没滑够」就把手柄弹回去了 ——
    // 右滑（露出报销/撤销）几乎每次都被自动取消，就是这个原因。
    // Animatable 只留给松手后的吸附动画用。
    Box(
        modifier = Modifier
            .offset { IntOffset(rawX.roundToInt(), 0) }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    // 双向限死在 ±revealPx：不封顶能一路拖出屏幕外
                    rawX = (rawX + delta).coerceIn(-revealPx, revealPx)
                },
                onDragStopped = {
                    val v = rawX
                    val shouldOpen = kotlin.math.abs(v) > revealPx * 0.4f
                    val target = if (shouldOpen) {
                        if (v > 0f) revealPx else -revealPx
                    } else 0f
                    if (shouldOpen) lastDir = if (v > 0f) 1f else -1f
                    scope.launch {
                        offsetX.snapTo(v)
                        offsetX.animateTo(target, tween(180))
                        rawX = offsetX.value
                    }
                    onOpenChange(shouldOpen)
                }
            )
            // 白底 + **不**切圆角，跟首页 TxRow 完全一致。
            // 首页只有底层那块 14dp 圆角的删除底板，卡片本身是方的；
            // 之前这里也给卡片加了 14dp 圆角，两页形状就不一样了。
            .background(MaterialTheme.colorScheme.surface)
            .clickable {
                // 展开状态下点卡片 = 收回去，不该直接进编辑
                if (rawX != 0f) {
                    scope.launch {
                        offsetX.animateTo(0f, tween(180))
                        rawX = 0f
                    }
                    onOpenChange(false)
                    // 冲销行不让点进编辑。
                    // 它的金额、日期都是跟原单**配对**的（金额 = -原金额、日期 = 原消费日期），
                    // 改任何一样都会让「撤销报销」找不回原单、账也对不上。
                    // 要改就去改原始那笔。
                } else if (isOffsetTx(tx)) {
                    onOpenChange(false)
                } else {
                    onEdit(tx)
                }
            }
        ) {
            ReimburseTxCard(tx = tx, isReimbursed = isReimbursed)
        }
    }
}

/**
 * 行内容：分类名 + 备注（已报销的顺带把报销日期写在这行） + 金额。
 *
 * 冲销账单（金额为负）额外标一个「冲销」小标签并用绿色 ——
 * 不标的话用户会以为 App 凭空多了一笔负数支出。
 */
@Composable
private fun ReimburseTxCard(tx: TxWithCategory, isReimbursed: Boolean) {
    // 冲销行的判断认 note 前缀，不能只看金额为负 ——
    // 用户手记的负数支出也会命中，那样就被误染成「报销到账」了。
    val isOffset = isOffsetTx(tx)
    // 显示成正数 + 收入红，跟首页同一套口径。账本里仍是负支出，不受影响。
    val shownCents = if (isOffset) -tx.amountCents else tx.amountCents
    val amountColor = when {
        isOffset -> AppTheme.income
        tx.type == 1 -> AppTheme.income
        else -> AppTheme.expense
    }
    // 二级时显示「一级 · 二级」，跟首页账单行一个写法
    val title = listOfNotNull(tx.parentName, tx.categoryName).distinct().joinToString(" · ")
    // 冲销行的前缀 + id 给程序认，界面上只留括号里的人话
    val sub = offsetDisplayNote(tx)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 左右 14dp（列表本身不留边距，色块要通栏）+ 上下 10dp：
            // 跟首页 TxRow 的左右 4dp 不同，因为那边列表有 14dp contentPadding 兜着，
            // 这边为了让底板铺满去掉了，这里就得自己补回来
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(
            iconKey = tx.iconKey,
            size = 38.dp,
            cornerRadius = 12.dp,
            overrideColor = AppIcons.colorFromKey(tx.colorKey)
        )
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 「已报销」tab 里显示的就是冲销行（金额为负），
                // 再挂个「冲销」标签等于说「这条是冲销的」，跟它本身是同义反复。
                // 未报销 tab 不会走到这个分支（那边金额都是正的）。
                if (!isReimbursed && isOffset) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "冲销",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppTheme.expense)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            if (sub.isNotBlank()) {
                Text(
                    text = sub,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            // 冲销行显示 +¥正数（钱回来了），其余照旧。
            // 账本里它是负支出，靠这个负数抵扣总支出；界面上要表达的是「报销到账」。
            text = (if (isOffset) "+¥" else "¥") + LedgerViewModel.formatCents(absCents(shownCents)),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = amountColor,
            maxLines = 1
        )
    }
}
