package com.dafeng.onlymoneynote.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.dafeng.onlymoneynote.MainActivity
import com.dafeng.onlymoneynote.ui.LedgerViewModel
import com.dafeng.onlymoneynote.ui.screens.ChartColors
import com.dafeng.onlymoneynote.ui.screens.LegendEntry
import com.dafeng.onlymoneynote.ui.screens.legendEntries

/**
 * 「本月支出占比」桌面插件：标题 + 本月支出金额 + 环形图 + 分类图例。
 *
 * 复用统计页那套逻辑：一级分类聚合（[com.dafeng.onlymoneynote.ui.screens.aggregateByParent]）
 * 和取色（[ChartColors]），所以插件和统计页的环、图例颜色是对得上的。
 *
 * Glance 不能画自定义图形，环形图只能自己用 Canvas 画成 Bitmap 再塞 Image 进去。
 */

/** 「其他」那一段的颜色（被合并的分类），故意用中性灰，不跟分类色抢眼 */
private val OtherSliceColor = Color(0xFF9AA4AE)

/** 环上底衬（没数据时也能看出是个环） */
private const val TRACK_COLOR = 0x1A000000

object DonutWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = WidgetData.snapshot(context)
        // 最多 5 行图例，多的并成「其他」——和统计页一个规则
        val legend = legendEntries(s.monthExpenseByCategory, maxRows = 5)
        val slices = legend.map { e -> sliceColor(e).toArgb() to (e.pct / 100.0).toFloat() }
        val bitmap = donutBitmap(slices)
        provideContent { Content(context, s.monthExpense, legend, bitmap) }
    }

    @Composable
    private fun Content(
        context: Context,
        monthExpense: Long,
        legend: List<LegendEntry>,
        bitmap: Bitmap
    ) {
        Box(modifier = GlanceModifier.fillMaxSize().background(Bg).cornerRadius(20.dp)) {
            Column(
                modifier = GlanceModifier.fillMaxSize().padding(14.dp)
                    .clickable(actionStartActivity(Intent(context, MainActivity::class.java)))
            ) {
                // 标题 + 金额
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "本月支出",
                        style = TextStyle(
                            color = MainText, fontSize = 17.sp, fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(GlanceModifier.defaultWeight())
                    Text(
                        text = "¥" + LedgerViewModel.formatCents(monthExpense),
                        style = TextStyle(
                            color = ExpenseColor, fontSize = 19.sp, fontWeight = FontWeight.Bold
                        )
                    )
                }

                Spacer(GlanceModifier.height(8.dp))

                Row(
                    modifier = GlanceModifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        provider = ImageProvider(bitmap),
                        contentDescription = null,
                        modifier = GlanceModifier.size(92.dp)
                    )
                    Spacer(GlanceModifier.width(14.dp))
                    Column(
                        modifier = GlanceModifier.defaultWeight(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (legend.isEmpty()) {
                            Text(
                                text = "本月还没有支出",
                                style = TextStyle(color = SubText, fontSize = 11.sp)
                            )
                        } else {
                            legend.forEach { e ->
                                Row(
                                    modifier = GlanceModifier.fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 色点，跟环上那一段对得上
                                    Box(
                                        modifier = GlanceModifier.size(9.dp)
                                            .background(ColorProvider(sliceColor(e), sliceColor(e)))
                                            .cornerRadius(5.dp),
                                        contentAlignment = Alignment.Center
                                    ) {}
                                    Spacer(GlanceModifier.width(7.dp))
                                    Text(
                                        text = e.name,
                                        modifier = GlanceModifier.defaultWeight(),
                                        style = TextStyle(color = MainText, fontSize = 11.sp),
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "%.1f%%".format(e.pct),
                                        style = TextStyle(
                                            color = MainText, fontSize = 11.sp,
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
}

class DonutWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = DonutWidget
}

/** 图例项 → 颜色：正常分类走统计页色板，被合并的「其他」用中性灰 */
private fun sliceColor(e: LegendEntry): Color =
    if (e.colorIndex >= 0) ChartColors[e.colorIndex % ChartColors.size] else OtherSliceColor

/**
 * 把「颜色 + 占比」画成一个环形图 Bitmap（透明底）。
 * 段与段之间留 3° 缝，跟统计页的环形观感一致；只剩一段时不留缝，画满整圈。
 */
private fun donutBitmap(slices: List<Pair<Int, Float>>, sizePx: Int = 220): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val stroke = sizePx * 0.24f
    val inset = stroke / 2f
    val rect = RectF(inset, inset, sizePx - inset, sizePx - inset)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }

    // 底衬圆环
    paint.color = TRACK_COLOR
    canvas.drawArc(rect, 0f, 360f, false, paint)

    if (slices.isEmpty()) return bmp

    val gap = if (slices.size > 1) 3f else 0f
    var start = -90f
    slices.forEach { (argb, fraction) ->
        val sweep = (fraction * 360f).coerceAtLeast(1f)
        paint.color = argb
        canvas.drawArc(
            rect,
            start + gap / 2f,
            (sweep - gap).coerceAtLeast(0.5f),
            false,
            paint
        )
        start += sweep
    }
    return bmp
}
