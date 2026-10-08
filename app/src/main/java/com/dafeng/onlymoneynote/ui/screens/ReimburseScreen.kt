package com.dafeng.onlymoneynote.ui.screens

import androidx.compose.foundation.background
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
import com.dafeng.onlymoneynote.data.local.TxType
import com.dafeng.onlymoneynote.data.local.TxWithCategory
import com.dafeng.onlymoneynote.ui.LedgerViewModel
import com.dafeng.onlymoneynote.ui.components.HeaderBackButton
import com.dafeng.onlymoneynote.ui.components.IconTile
import com.dafeng.onlymoneynote.ui.components.PageHeader
import com.dafeng.onlymoneynote.ui.theme.AppTheme

/**
 * 报销页（底栏 Tab）。
 *
 * 蓝色页头显示「待收回/已收回」大数字，白色面板里是三个汇总块 + 按天分组的账单，
 * 和首页的列表长得一样，滑动停住再点也能删、能改。
 */
@Composable
fun ReimburseScreen(
    transactions: List<TxWithCategory>,
    onEdit: (TxWithCategory) -> Unit,
    onDelete: (Long) -> Unit,
    /** 底栏去掉后这页变成二级页，页头里要有返回 */
    onBack: () -> Unit = {}
) {
    var openedId by remember { mutableStateOf<Long?>(null) }

    val expenseTotal = transactions.filter { it.type == TxType.EXPENSE.value }
        .sumOf { it.amountCents }
    val incomeTotal = transactions.filter { it.type == TxType.INCOME.value }
        .sumOf { it.amountCents }
    val pending = incomeTotal - expenseTotal

    val grouped = remember(transactions) {
        transactions.groupBy { it.dateMillis.atDay() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        /* ---------- 蓝色渐变页头 ---------- */
        PageHeader {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 26.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeaderBackButton(onBack = onBack)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "报销",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (pending >= 0) "已收回" else "待收回",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.72f)
                )
                Text(
                    "¥" + LedgerViewModel.formatCents(absCents(pending)),
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
            if (transactions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconTile(iconKey = "reimburse", size = 52.dp, cornerRadius = 16.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "还没有报销账单",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "记一笔时打开「报销」开关就会出现在这里",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 14.dp, end = 14.dp, top = 4.dp, bottom = 20.dp
                    )
                ) {
                    item(key = "r-stats", contentType = "stats") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            StatBox(
                                label = "支出报销",
                                cents = expenseTotal,
                                tint = AppTheme.expense,
                                modifier = Modifier.weight(1f)
                            )
                            StatBox(
                                label = "收入报销",
                                cents = incomeTotal,
                                tint = AppTheme.income,
                                modifier = Modifier.weight(1f)
                            )
                            StatBox(
                                label = if (pending >= 0) "已收回" else "待收回",
                                cents = absCents(pending),
                                tint = if (pending >= 0) AppTheme.income else AppTheme.expense,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    grouped.forEach { (day, list) ->
                        item(key = "r-header-$day", contentType = "header") {
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
                                onOpenChange = { open ->
                                    openedId = if (open) tx.id else null
                                },
                                onDelete = onDelete,
                                onEdit = onEdit
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */

private fun absCents(v: Long): Long = if (v < 0) -v else v

@Composable
private fun StatBox(
    label: String,
    cents: Long,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 10.dp, horizontal = 11.dp)
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "¥" + LedgerViewModel.formatCents(cents),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = tint,
            maxLines = 1
        )
    }
}
