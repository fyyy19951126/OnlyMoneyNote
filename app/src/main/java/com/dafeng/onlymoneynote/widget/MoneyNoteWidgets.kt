package com.dafeng.onlymoneynote.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.color.ColorProvider
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.dafeng.onlymoneynote.MainActivity
import com.dafeng.onlymoneynote.data.local.TxType
import com.dafeng.onlymoneynote.ui.LedgerViewModel
import java.util.Calendar

/**
 * 桌面小组件 ×4：
 * - TodayWidget   今日收支（2×1）
 * - MonthWidget   本月结余（2×2）
 * - RecentWidget  最近账单（3×2 列表）
 * - QuickAddWidget 快速记一笔（1×1，点开直达记账页）
 *
 * 配色跟 App 一致：花费=绿、挣钱=红（用户定死的约定），米白底。
 */

/* ---------------- 配色（日/夜各一套，跟 Theme.kt 的取值对齐） ---------------- */

internal val Bg = ColorProvider(Color(0xFFF7F2EE), Color(0xFF1C1B1B))
internal val Card = ColorProvider(Color(0xFFFFFFFF), Color(0xFF2A2828))
internal val MainText = ColorProvider(Color(0xFF1C1B1F), Color(0xFFE6E1E5))
internal val SubText = ColorProvider(Color(0xFF79747E), Color(0xFF938F99))
internal val AccentRed = ColorProvider(Color(0xFFC4697A), Color(0xFFD98A99))

// 花费=绿、挣钱=红（项目约定，别改反）
internal val ExpenseColor = ColorProvider(Color(0xFF2E8B6A), Color(0xFF57D69B))
internal val IncomeColor = ColorProvider(Color(0xFFC0392B), Color(0xFFFF7A7A))

private fun openApp(context: Context) =
    actionStartActivity(Intent(context, MainActivity::class.java))

private fun quickAdd(context: Context) =
    actionStartActivity(
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_ADD, true)
    )

private fun openOnTap(modifier: GlanceModifier, context: Context): GlanceModifier =
    modifier.clickable(openApp(context))

/* --------------------------------- 今日收支 --------------------------------- */

object TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = WidgetData.snapshot(context)
        provideContent { Content(context, s) }
    }

    @Composable
    private fun Content(context: Context, s: WidgetData.Snapshot) {
        Box(
            modifier = GlanceModifier.fillMaxSize().background(Bg).cornerRadius(18.dp)
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize().padding(12.dp)
                    .clickable(openApp(context)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("今日收支", style = TextStyle(color = SubText, fontSize = 10.sp))
                Spacer(GlanceModifier.height(4.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            "¥" + LedgerViewModel.formatCents(s.todayExpense),
                            style = TextStyle(
                                color = ExpenseColor, fontSize = 16.sp, fontWeight = FontWeight.Bold
                            )
                        )
                        Text("支出", style = TextStyle(color = SubText, fontSize = 9.sp))
                    }
                    Spacer(GlanceModifier.width(14.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            "¥" + LedgerViewModel.formatCents(s.todayIncome),
                            style = TextStyle(
                                color = IncomeColor, fontSize = 16.sp, fontWeight = FontWeight.Bold
                            )
                        )
                        Text("收入", style = TextStyle(color = SubText, fontSize = 9.sp))
                    }
                }
            }
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TodayWidget
}

/* --------------------------------- 本月结余 --------------------------------- */

object MonthWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = WidgetData.snapshot(context)
        provideContent { Content(context, s) }
    }

    @Composable
    private fun Content(context: Context, s: WidgetData.Snapshot) {
        val balance = s.monthIncome - s.monthExpense
        Box(
            modifier = GlanceModifier.fillMaxSize().background(Bg).cornerRadius(18.dp)
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize().padding(12.dp)
                    .clickable(openApp(context)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("本月", style = TextStyle(color = SubText, fontSize = 10.sp))
                Spacer(GlanceModifier.height(4.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            "¥" + LedgerViewModel.formatCents(s.monthExpense),
                            style = TextStyle(
                                color = ExpenseColor, fontSize = 15.sp, fontWeight = FontWeight.Bold
                            )
                        )
                        Text("支出", style = TextStyle(color = SubText, fontSize = 9.sp))
                    }
                    Spacer(GlanceModifier.width(14.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            "¥" + LedgerViewModel.formatCents(s.monthIncome),
                            style = TextStyle(
                                color = IncomeColor, fontSize = 15.sp, fontWeight = FontWeight.Bold
                            )
                        )
                        Text("收入", style = TextStyle(color = SubText, fontSize = 9.sp))
                    }
                }
                Spacer(GlanceModifier.height(6.dp))
                Text(
                    (if (balance >= 0) "结余 +¥" else "结余 -¥") +
                        LedgerViewModel.formatCents(kotlin.math.abs(balance)),
                    style = TextStyle(color = MainText, fontSize = 11.sp)
                )
            }
        }
    }
}

class MonthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = MonthWidget
}

/* --------------------------------- 最近账单 --------------------------------- */

object RecentWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = WidgetData.snapshot(context)
        provideContent { Content(context, s) }
    }

    @Composable
    private fun Content(context: Context, s: WidgetData.Snapshot) {
        Box(
            modifier = GlanceModifier.fillMaxSize().background(Bg).cornerRadius(18.dp)
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)
                    .clickable(openApp(context))
            ) {
                Text("最近账单", style = TextStyle(color = SubText, fontSize = 10.sp))
                Spacer(GlanceModifier.height(4.dp))
                if (s.recent.isEmpty()) {
                    Text(
                        "还没有账单",
                        style = TextStyle(color = SubText, fontSize = 11.sp),
                        modifier = GlanceModifier.padding(top = 6.dp)
                    )
                } else {
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(s.recent) { tx ->
                            val isExpense = tx.type == TxType.EXPENSE.value
                            Row(
                                modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    tx.categoryName,
                                    style = TextStyle(color = MainText, fontSize = 11.sp),
                                    modifier = GlanceModifier.defaultWeight()
                                )
                                Text(
                                    (if (isExpense) "-" else "+") + "¥" +
                                        LedgerViewModel.formatCents(tx.amountCents),
                                    style = TextStyle(
                                        color = if (isExpense) ExpenseColor else IncomeColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

class RecentWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = RecentWidget
}

/* -------------------------------- 快速记一笔 -------------------------------- */

object QuickAddWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Content(context) }
    }

    @Composable
    private fun Content(context: Context) {
        Box(
            modifier = GlanceModifier.fillMaxSize()
                .background(AccentRed)
                .cornerRadius(24.dp)
                .clickable(quickAdd(context)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "＋",
                style = TextStyle(
                    color = ColorProvider(Color.White, Color.White),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = QuickAddWidget
}
