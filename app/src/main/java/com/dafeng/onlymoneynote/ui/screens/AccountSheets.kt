package com.dafeng.onlymoneynote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeng.onlymoneynote.data.local.AccountEntity
import com.dafeng.onlymoneynote.ui.components.IconTile
import com.dafeng.onlymoneynote.ui.components.SheetDialog
import com.dafeng.onlymoneynote.ui.LedgerViewModel
import com.dafeng.onlymoneynote.ui.theme.AppTheme
import com.dafeng.onlymoneynote.util.AppIcons
import kotlin.math.abs

/**
 * 账户相关的两个弹层 + 编辑对话框。
 *
 * 余额一律是「期初 + 净流水」，由 ViewModel 算好塞进 [LedgerViewModel.AccountOverview]，
 * 这两页只负责展示和改账户本身的字段。
 */

/* ------------------------------------------------------------------ */
/* 账户统计                                                            */
/* ------------------------------------------------------------------ */

@Composable
fun AccountStatsSheet(
    overviews: List<LedgerViewModel.AccountOverview>,
    onDismiss: () -> Unit,
    onManage: () -> Unit
) {
    val totalCents = overviews.sumOf { it.balanceCents }

    SheetDialog(onDismiss = onDismiss, heightFraction = 0.82f) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text("账户", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                "余额 = 期初金额 + 该账户名下的收支流水",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            // 总资产：单独一块，跟下面的账户行拉开层级
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppTheme.primary.copy(alpha = 0.08f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "总资产",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    (if (totalCents < 0) "-" else "") + "¥" +
                        LedgerViewModel.formatCents(abs(totalCents)),
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.primary
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 18.dp, end = 18.dp, bottom = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            items(overviews, key = { it.account.id }) { o ->
                AccountStatRow(o)
            }
            if (overviews.isEmpty()) {
                item {
                    Text(
                        "还没有账户，点下面的「管理账户」加一个",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 20.dp)
                    )
                }
            }
        }

        SheetFooter {
            TextButton(onClick = onManage) { Text("管理账户", color = AppTheme.primary) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("完成", color = AppTheme.primary) }
        }
    }
}

@Composable
private fun AccountStatRow(o: LedgerViewModel.AccountOverview) {
    val a = o.account
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
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
/* 账户管理                                                            */
/* ------------------------------------------------------------------ */

@Composable
fun AccountManageSheet(
    overviews: List<LedgerViewModel.AccountOverview>,
    onDismiss: () -> Unit,
    onAdd: (name: String, iconKey: String, initialYuan: String, colorKey: String) -> Unit,
    onUpdate: (id: Long, name: String, iconKey: String, initialYuan: String, colorKey: String) -> Unit,
    onDelete: (Long) -> Unit
) {
    // null = 没开；AccountEntity? = 编辑已有；EditingNew = 新建
    var editing by remember { mutableStateOf<Any?>(null) }

    SheetDialog(onDismiss = onDismiss, heightFraction = 0.86f) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("账户管理", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "＋ 添加账户",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(AppTheme.primary.copy(alpha = 0.10f))
                    .clickable { editing = NEW }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 18.dp, end = 18.dp, bottom = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            items(overviews, key = { it.account.id }) { o ->
                val a = o.account
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(14.dp)
                        )
                        .clickable { editing = a }
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
                        Text(
                            "期初 ¥" + LedgerViewModel.formatCents(a.initialCents) +
                                " · 余额 ¥" + LedgerViewModel.formatCents(abs(o.balanceCents)),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if (a.builtIn) {
                        Text(
                            "内置",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        SheetFooter {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("完成", color = AppTheme.primary) }
        }
    }

    val target = editing
    if (target != null) {
        AccountEditDialog(
            account = target as? AccountEntity,
            onDismiss = { editing = null },
            onSave = { name, icon, initial, colorKey ->
                val current = target as? AccountEntity
                if (current == null) onAdd(name, icon, initial, colorKey)
                else onUpdate(current.id, name, icon, initial, colorKey)
                editing = null
            },
            onDelete = (target as? AccountEntity)?.let { acc ->
                if (acc.builtIn) null else { { onDelete(acc.id); editing = null } }
            }
        )
    }
}

/** 新建时传给 [AccountManageSheet.editing] 的哨兵值 */
private val NEW = Any()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountEditDialog(
    account: AccountEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, iconKey: String, initialYuan: String, colorKey: String) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember { mutableStateOf(account?.name ?: "") }
    var icon by remember { mutableStateOf(account?.iconKey ?: "emoji:💰") }
    var initial by remember {
        mutableStateOf(account?.let { LedgerViewModel.formatCents(it.initialCents) } ?: "")
    }
    var colorKey by remember { mutableStateOf(account?.colorKey ?: "") }
    val previewColor = AppIcons.colorFromKey(colorKey)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        title = { Text(if (account == null) "添加账户" else "编辑账户", fontSize = 17.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("账户名") },
                    placeholder = { Text("支付宝 / 微信 / 工行 …") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                // 图标：直接填一个 emoji，左边实时预览
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(
                        iconKey = icon, size = 34.dp, cornerRadius = 11.dp,
                        overrideColor = previewColor
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = if (icon.startsWith("emoji:")) icon.removePrefix("emoji:") else "",
                        onValueChange = { t ->
                            // 只留一个字符：粘贴一串时取第一个 emoji
                            val trimmed = t.trim()
                            icon = if (trimmed.isNotBlank()) "emoji:${trimmed.takeLast(2)}" else "emoji:💰"
                        },
                        label = { Text("图标（一个 emoji）") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = initial,
                    onValueChange = { initial = it },
                    label = { Text("期初金额（元，可负）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("配色", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 第一格 = 不指定（跟随默认）
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
                onClick = { onSave(name, icon, initial.ifBlank { "0" }, colorKey) },
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
            .size(28.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(color)
            .then(
                if (selected) Modifier.border(2.dp, AppTheme.primary, RoundedCornerShape(9.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
    )
}

@Composable
private fun SheetFooter(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

private fun toHex(color: Color): String = "#" + color.toArgb().toString(16).takeLast(6).uppercase()
