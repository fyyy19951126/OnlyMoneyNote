package com.dafeng.onlymoneynote.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeng.onlymoneynote.data.local.AccountEntity
import com.dafeng.onlymoneynote.data.local.AccountIconPresets
import com.dafeng.onlymoneynote.ui.LedgerViewModel
import com.dafeng.onlymoneynote.ui.components.IconTile
import com.dafeng.onlymoneynote.ui.components.SheetDialog
import com.dafeng.onlymoneynote.ui.theme.AppTheme
import com.dafeng.onlymoneynote.util.AppIcons
import kotlin.math.abs

/**
 * 账户统计弹层 + 账户编辑对话框。
 *
 * 余额一律是「期初 + 净流水」，由 ViewModel 算好塞进 [LedgerViewModel.AccountOverview]，
 * 这里只负责展示，以及改账户本身的字段。
 *
 * 没有单独的「账户管理」页：**长按某一行直接编辑**，右上角 ＋ 新建。
 */

/* ------------------------------------------------------------------ */
/* 账户统计                                                            */
/* ------------------------------------------------------------------ */

/**
 * 账户页（整页）。关闭由外层 OverlayTopBar 的返回箭头负责，所以这里没有 onDismiss。
 */
@Composable
fun AccountStatsSheet(
    overviews: List<LedgerViewModel.AccountOverview>,
    onAdd: (name: String, iconKey: String, initialYuan: String, colorKey: String) -> Unit,
    onUpdate: (id: Long, name: String, iconKey: String, initialYuan: String, colorKey: String) -> Unit,
    onDelete: (Long) -> Unit
) {
    // null = 没开；AccountEntity = 编辑已有；NEW 哨兵 = 新建
    var editing by remember { mutableStateOf<Any?>(null) }

    // 2026-10-09 用户确认：账户改成统计页那样左上角带返回的整页。标题、口径说明和总资产
    // 都在外层 OverlayPageHeader 的主题色里，这里只留白色区的账户列表 + 右下角新建按钮
    // （新建按钮跟分类管理一样用 FloatingActionButton，用户要求统一风格）。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            items(overviews, key = { it.account.id }) { o ->
                AccountStatRow(o, onEdit = { editing = o.account })
            }
            if (overviews.isEmpty()) {
                item {
                    Text(
                        "还没有账户，点右下角 ＋ 加一个",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 20.dp)
                    )
                }
            }
        }

            Text(
                "长按账户可编辑或删除",
                fontSize = 11.5.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            )
        }

        FloatingActionButton(
            onClick = { editing = NEW },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 24.dp),
            containerColor = AppTheme.primary,
            contentColor = Color.White,
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "新建账户")
        }
    }

    val target = editing
    if (target != null) {
        val current = target as? AccountEntity
        val overview = overviews.firstOrNull { it.account.id == current?.id }
        AccountEditDialog(
            account = current,
            // 净流水 = 当前余额 - 期初：填「此刻余额」时用它反算
            netCents = (overview?.balanceCents ?: 0L) - (current?.initialCents ?: 0L),
            onDismiss = { editing = null },
            onSave = { name, icon, initial, colorKey ->
                if (current == null) onAdd(name, icon, initial, colorKey)
                else onUpdate(current.id, name, icon, initial, colorKey)
                editing = null
            },
            onDelete = if (current == null || current.builtIn) null
            else {
                {
                    onDelete(current.id)
                    editing = null
                }
            }
        )
    }
}

/** 新建时传给 editing 的哨兵值 */
private val NEW = Any()

/**
 * 账户页主题色页头里的「现有资产总额」。
 *
 * 用户要求：重点信息（总资产）放在主题色里，其余账户明细留在下面白色区。
 */
@Composable
fun AccountTotalCard(totalCents: Long) {
    Column(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 22.dp)
    ) {
        Text(
            "现有资产总额",
            fontSize = 12.5.sp,
            color = Color.White.copy(alpha = 0.75f)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            (if (totalCents < 0) "-¥" else "¥") + LedgerViewModel.formatCents(abs(totalCents)),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountStatRow(o: LedgerViewModel.AccountOverview, onEdit: () -> Unit) {
    val a = o.account
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            // 长按 = 编辑（点这行没有别的含义，不给 click 动作）
            .combinedClickable(onClick = {}, onLongClick = onEdit)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(
            iconKey = a.iconKey, size = 32.dp, cornerRadius = 10.dp,
            overrideColor = AppIcons.colorFromKey(a.colorKey)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            // 本月进出 + 累计笔数，小字放名称下面
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "本月 +" + LedgerViewModel.formatCents(o.monthIncomeCents),
                    fontSize = 11.sp,
                    color = AppTheme.income
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "本月 -" + LedgerViewModel.formatCents(o.monthExpenseCents),
                    fontSize = 11.sp,
                    color = AppTheme.expense
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${o.txCount} 笔",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            (if (o.balanceCents < 0) "-" else "") + "¥" +
                LedgerViewModel.formatCents(abs(o.balanceCents)),
            fontSize = 15.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (o.balanceCents < 0) AppTheme.expense else MaterialTheme.colorScheme.onSurface
        )
    }
}

/* ------------------------------------------------------------------ */
/* 账户编辑                                                            */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountEditDialog(
    account: AccountEntity?,
    /** 该账户的净流水（收入 - 支出）。填「此刻余额」时用它反算期初 */
    netCents: Long,
    onDismiss: () -> Unit,
    onSave: (name: String, iconKey: String, initialYuan: String, colorKey: String) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember { mutableStateOf(account?.name ?: "") }
    var icon by remember {
        mutableStateOf(account?.iconKey ?: AccountIconPresets.PRESETS[0].iconKey)
    }
    var colorKey by remember {
        mutableStateOf(account?.colorKey ?: AccountIconPresets.PRESETS[0].colorKey)
    }
    // 0 = 填期初金额，1 = 填此刻余额（用户：不可能再去找之前的总额）
    var amountMode by remember { mutableIntStateOf(0) }
    var amountText by remember {
        mutableStateOf(account?.let { LedgerViewModel.formatCents(it.initialCents) } ?: "")
    }
    val previewColor = AppIcons.colorFromKey(colorKey)
    // 不管哪种口径，落库存的始终是期初金额；余额 = 期初 + 净流水
    val enteredCents = parseYuanToCents(amountText) ?: 0L
    val initialCents = if (amountMode == 0) enteredCents else enteredCents - netCents
    val liveBalance = initialCents + netCents

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        title = { Text(if (account == null) "添加账户" else "编辑账户", fontSize = 17.sp) },
        text = {
            // 20 个图标 + 配色 + 金额，内容比屏幕高，必须能滚
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("账户名") },
                    placeholder = { Text("支付宝 / 微信 / 工行 …") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                // 金额口径二选一：期初金额 / 此刻余额
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("期初金额", amountMode == 0) { amountMode = 0 }
                    ModeChip("此刻余额", amountMode == 1) { amountMode = 1 }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = {
                        Text(if (amountMode == 0) "期初金额（元，可负）" else "此刻余额（元，可负）")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "现有资产 ¥" + (if (liveBalance < 0) "-" else "") +
                        LedgerViewModel.formatCents(abs(liveBalance)),
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                /* ---------- 以下置底：图标 + 配色 ---------- */
                Spacer(Modifier.height(16.dp))
                Text("图标", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                // 预设：微信 / 支付宝 / 抖音 + 20 家常用银行，点一下就同时定好图标和配色
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AccountIconPresets.PRESETS.forEach { p ->
                        val sel = icon == p.iconKey && colorKey == p.colorKey
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .then(
                                    if (sel) Modifier.border(
                                        2.dp, AppTheme.primary, RoundedCornerShape(13.dp)
                                    ) else Modifier
                                )
                                .clickable {
                                    icon = p.iconKey
                                    colorKey = p.colorKey
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            IconTile(
                                iconKey = p.iconKey, size = 38.dp, cornerRadius = 11.dp,
                                overrideColor = AppIcons.colorFromKey(p.colorKey)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 自定义：打一个汉字 / 词 / emoji 当图标（左边是实时预览）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(
                        iconKey = icon, size = 38.dp, cornerRadius = 11.dp,
                        overrideColor = AppIcons.colorFromKey(colorKey)
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = if (icon.startsWith("emoji:")) icon.removePrefix("emoji:") else "",
                        onValueChange = { t ->
                            val e = firstGlyph(t)
                            icon = if (e.isNotEmpty()) "emoji:$e" else AccountIconPresets.PRESETS[0].iconKey
                        },
                        label = { Text("自定义图标（文字或 emoji）") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text("配色", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 第一格 = 不指定（跟随图标默认分组色）
                    ColorDot(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        selected = colorKey.isEmpty(),
                        onClick = { colorKey = "" }
                    )
                    AppIcons.CategoryPalette.forEach { c ->
                        ColorDot(color = c, selected = colorKey == toHex(c), onClick = { colorKey = toHex(c) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, icon, LedgerViewModel.formatCents(initialCents), colorKey) },
                enabled = name.isNotBlank()
            ) { Text("保存", color = AppTheme.primary, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = AppTheme.danger)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(color)
            .then(
                if (selected) Modifier.border(2.dp, AppTheme.primary, RoundedCornerShape(9.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
    )
}

/**
 * Color → "#RRGGBB"。
 *
 * 不能写 `toArgb().toString(16)`：带不透明 alpha 的颜色是**负数**，
 * Integer.toString(16) 会输出带负号的形式，取后 6 位就串色（选蓝出橙）。
 */
private fun toHex(color: Color): String = "#%06X".format(color.toArgb() and 0xFFFFFF)

/** 「期初金额 / 此刻余额」二选一 */
@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.5.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) AppTheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) AppTheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/**
 * 取输入里的第一个字形：连变体选择符（U+FE0F）、ZWJ、肤色修饰符一起吃进来。
 * 自定义图标框用它，避免把代理对切一半（切坏了就渲染成一个方框）。
 */
private fun firstGlyph(text: String): String {
    val s = text.trim()
    if (s.isEmpty()) return ""
    var end = Character.charCount(Character.codePointAt(s, 0))
    while (end < s.length) {
        val cp = Character.codePointAt(s, end)
        val glue = cp == 0xFE0E || cp == 0xFE0F || cp == 0x200D || cp in 0x1F3FB..0x1F3FF
        if (!glue) break
        end += Character.charCount(cp)
        if (cp == 0x200D && end < s.length) end += Character.charCount(Character.codePointAt(s, end))
    }
    return s.substring(0, end)
}

/** "1234.56" → 分；空/非法返回 null。负数照收（信用卡欠款） */private fun parseYuanToCents(text: String): Long? {
    val t = text.trim().replace(",", "").replace("¥", "")
    if (t.isEmpty() || t == "-" || t == "." || t == "-.") return null
    return t.toBigDecimalOrNull()
        ?.movePointRight(2)
        ?.setScale(0, java.math.RoundingMode.HALF_UP)
        ?.toLong()
}
