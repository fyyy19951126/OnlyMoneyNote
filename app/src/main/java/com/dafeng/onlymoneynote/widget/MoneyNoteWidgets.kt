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
                modifier = GlanceModifier.fillMaxSize()
                    .padding(10.dp)
                    .clickable(openApp(context)),
                // 顶部对齐：这个格子只有 2 行高，内容居中会在上下各留一截空白，
                // 看着像缺了什么；靠上更紧凑
                verticalAlignment = Alignment.Top
            ) {
                Text("今日支出", style = TextStyle(color = SubText, fontSize = 11.sp))
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    "¥" + LedgerViewModel.formatCents(s.todayExpense),
                    style = TextStyle(
                        color = ExpenseColor, fontSize = 19.sp, fontWeight = FontWeight.Bold
                    )
                )
                Spacer(GlanceModifier.height(6.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            "¥" + LedgerViewModel.formatCents(s.todayIncome),
                            style = TextStyle(
                                color = IncomeColor, fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                        )
                        Text("收入", style = TextStyle(color = SubText, fontSize = 9.sp))
                    }
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        val balance = s.todayIncome - s.todayExpense
                        Text(
                            (if (balance >= 0) "+¥" else "-¥") +
                                LedgerViewModel.formatCents(kotlin.math.abs(balance)),
                            style = TextStyle(color = MainText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        )
                        Text("结余", style = TextStyle(color = SubText, fontSize = 9.sp))
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
                modifier = GlanceModifier.fillMaxSize()
                    // 内边距收到 10dp：原来 12dp 在 2×2 的格子里显得空，
                    // 文字挤在左上角，下面一大片留白
                    .padding(10.dp)
                    .clickable(openApp(context)),
                // 顶部对齐而不是垂直居中：占满 4 行时内容更靠上，
                // 数字不会飘在格子中间，下方留白连成一片
                verticalAlignment = Alignment.Top
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "本月支出",
                        style = TextStyle(color = SubText, fontSize = 11.sp)
                    )
                }
                // 支出是这块的主角：单独一行、字号最大，下面「收入 / 结余」退成小字。
                // 之前支出和收入平分两列，视觉权重一样，看不出哪个是重点。
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    "¥" + LedgerViewModel.formatCents(s.monthExpense),
                    style = TextStyle(
                        color = ExpenseColor,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(GlanceModifier.height(8.dp))
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            "收入",
                            style = TextStyle(color = SubText, fontSize = 9.sp)
                        )
                        Text(
                            "¥" + LedgerViewModel.formatCents(s.monthIncome),
                            style = TextStyle(
                                color = IncomeColor, fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    Spacer(GlanceModifier.width(10.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            "结余",
                            style = TextStyle(color = SubText, fontSize = 9.sp)
                        )
                        Text(
                            (if (balance >= 0) "+¥" else "-¥") +
                                LedgerViewModel.formatCents(kotlin.math.abs(balance)),
                            style = TextStyle(
                                color = MainText, fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
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
                modifier = GlanceModifier.fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .clickable(openApp(context))
            ) {
                Text("最近账单", style = TextStyle(color = SubText, fontSize = 11.sp))
                Spacer(GlanceModifier.height(4.dp))
                if (s.recent.isEmpty()) {
                    // 空列表时也占住位置，免得「还没有账单」贴着标题、
                    // 整个格子显得空荡荡
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
