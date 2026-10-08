package com.dafeng.onlymoneynote.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.dafeng.onlymoneynote.data.local.TransactionDao
import com.dafeng.onlymoneynote.data.local.TxType
import com.dafeng.onlymoneynote.data.local.TxWithCategory
import com.dafeng.onlymoneynote.ui.LedgerViewModel
import com.dafeng.onlymoneynote.ui.screens.aggregateByParent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 桌面小组件的数据层。
 *
 * 小组件不走 Hilt 注入（receiver 拿不到 viewModel），用 EntryPoint 直接从
 * 单例组件里拿 TransactionDao，每次刷新现场查一把（数据量小，开销可忽略）。
 */
object WidgetData {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun transactionDao(): TransactionDao
    }

    data class Snapshot(
        val todayExpense: Long,
        val todayIncome: Long,
        val monthExpense: Long,
        val monthIncome: Long,
        val recent: List<TxWithCategory>,
        /** 本月支出按一级分类汇总，已按金额倒序（「本月支出占比」插件用） */
        val monthExpenseByCategory: List<Pair<String, Long>>
    )

    suspend fun snapshot(context: Context): Snapshot {
        val dao = EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            .transactionDao()

        val now = Calendar.getInstance()
        val todayStart = now.clone() as Calendar
        todayStart.apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val todayEnd = todayStart.timeInMillis + 24 * 60 * 60 * 1000L

        val (m0, m1) = LedgerViewModel.monthRange(
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1
        )

        val today = dao.observeRangeWithCategory(todayStart.timeInMillis, todayEnd).first()
        val month = dao.observeRangeWithCategory(m0, m1).first()
        val recent = dao.observeAll().first().take(5)
        // 分类聚合复用统计页的纯函数（二级滚动到一级，同名不重复）
        val byCategory = aggregateByParent(
            dao.observeCategoryStats(m0, m1, TxType.EXPENSE.value).first()
        )

        fun sum(list: List<TxWithCategory>, type: Int) =
            list.filter { it.type == type }.sumOf { it.amountCents }

        return Snapshot(
            todayExpense = sum(today, TxType.EXPENSE.value),
            todayIncome = sum(today, TxType.INCOME.value),
            monthExpense = sum(month, TxType.EXPENSE.value),
            monthIncome = sum(month, TxType.INCOME.value),
            recent = recent,
            monthExpenseByCategory = byCategory
        )
    }
}

/**
 * 账单一有增删改就刷一遍小组件。
 * App 进程开着的时候由 ViewModel 调；小组件自身还有 30 分钟的系统级兜底刷新，
 * 加上打开 App 时 MainActivity 会再刷一次，够用了。
 */
object WidgetRefresher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun refreshAll(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val manager = GlanceAppWidgetManager(app)
                listOf(TodayWidget, MonthWidget, RecentWidget, DonutWidget).forEach { widget ->
                    manager.getGlanceIds(widget::class.java).forEach { id ->
                        widget.update(app, id)
                    }
                }
            }
        }
    }
}
