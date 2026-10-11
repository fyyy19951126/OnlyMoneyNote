package com.dafeng.onlymoneynote.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dafeng.onlymoneynote.data.local.AccountEntity
import com.dafeng.onlymoneynote.data.local.AccountNet
import com.dafeng.onlymoneynote.data.local.AccountPeriodStat
import com.dafeng.onlymoneynote.data.local.CategoryEntity
import com.dafeng.onlymoneynote.data.local.CategoryStat
import com.dafeng.onlymoneynote.data.local.TxType
import com.dafeng.onlymoneynote.data.local.TransactionEntity
import com.dafeng.onlymoneynote.data.local.TransferWithAccount
import com.dafeng.onlymoneynote.data.local.TxWithCategory
import com.dafeng.onlymoneynote.data.ocr.ReceiptParser
import com.dafeng.onlymoneynote.data.repo.BackupRepository
import com.dafeng.onlymoneynote.data.repo.LedgerRepository
import com.dafeng.onlymoneynote.data.repo.SettingsRepository
import com.dafeng.onlymoneynote.util.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class LedgerViewModel @Inject constructor(
    private val ledger: LedgerRepository,
    private val backup: BackupRepository,
    private val settings: SettingsRepository,
    private val ocrEngine: com.dafeng.onlymoneynote.data.ocr.OcrEngine,
    @ApplicationContext private val appContext: android.content.Context
) : ViewModel() {

    init {
        viewModelScope.launch {
            ledger.seedIfEmpty()
            ledger.seedAccountsIfEmpty()
            // 旧版默认主题是红，新版默认换成支付宝蓝（界面整体改版）。
            // 只迁移一次存量值：迁移后用户再手动选红会原样保存，不会被覆盖。
            settings.migrateLegacyTheme()
            // 早期默认标题是英文 OnlyMoneyNote，统一迁成「Only记账」（只一次）
            settings.migrateLegacyTitle()
        }
    }

    /** 账单变了就刷桌面插件，让今日收支/最近账单跟上 */
    private fun refreshWidgets() {
        com.dafeng.onlymoneynote.widget.WidgetRefresher.refreshAll(appContext)
    }

    val transactions: StateFlow<List<TxWithCategory>> =
        ledger.observeTransactions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> =
        ledger.observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ============ 账户 ============

    val accounts: StateFlow<List<AccountEntity>> =
        ledger.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val accountNet: StateFlow<List<AccountNet>> =
        ledger.observeAccountNet().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val accountMonth: StateFlow<List<AccountPeriodStat>> =
        ledger.observeAccountMonthStats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 账户统计页的一行：余额 = 期初 + 全时段净流水（收入加、支出减） */
    data class AccountOverview(
        val account: AccountEntity,
        val balanceCents: Long,
        val monthExpenseCents: Long,
        val monthIncomeCents: Long,
        val txCount: Int
    )

    val accountOverviews: StateFlow<List<AccountOverview>> =
        combine(accounts, accountNet, accountMonth) { accs, net, month ->
            accs.map { a ->
                AccountOverview(
                    account = a,
                    balanceCents = a.initialCents +
                        (net.firstOrNull { it.accountId == a.id }?.netCents ?: 0L),
                    monthExpenseCents = month.firstOrNull { it.accountId == a.id }?.expenseCents ?: 0L,
                    monthIncomeCents = month.firstOrNull { it.accountId == a.id }?.incomeCents ?: 0L,
                    txCount = net.firstOrNull { it.accountId == a.id }?.count ?: 0
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 新建账户。[initialYuan] 期初金额（元，可为负）。 */
    fun addAccount(name: String, iconKey: String, initialYuan: String, colorKey: String = "") {
        if (name.isBlank()) { showToast("账户名不能是空的"); return }
        viewModelScope.launch {
            ledger.addAccount(
                AccountEntity(
                    name = name.trim(),
                    iconKey = iconKey,
                    sortOrder = (accounts.value.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                    initialCents = parseAmountToCents(initialYuan) ?: 0L,
                    colorKey = colorKey
                )
            )
            showToast("已添加账户")
        }
    }

    fun updateAccount(
        id: Long,
        name: String,
        iconKey: String,
        initialYuan: String,
        colorKey: String
    ) {
        if (name.isBlank()) { showToast("账户名不能是空的"); return }
        viewModelScope.launch {
            val old = accounts.value.firstOrNull { it.id == id } ?: return@launch
            ledger.updateAccount(
                old.copy(
                    name = name.trim(),
                    iconKey = iconKey,
                    initialCents = parseAmountToCents(initialYuan) ?: old.initialCents,
                    colorKey = colorKey
                )
            )
            showToast("已保存")
        }
    }

    /** 删账户：名下账单先转移到「未指定」，账户表里不留孤儿。 */
    fun deleteAccount(id: Long) {
        if (id == AccountEntity.UNSPECIFIED_ID) { showToast("「未指定」不能删除"); return }
        viewModelScope.launch {
            // 两个方向都要处理：
            // 1) accountId = 这个账户的账单（它作为转出方/收支账户）
            // 2) toAccountId = 这个账户的转账（它作为**转入方**）
            //
            // 之前只处理了第 1 个。删掉某笔转账的转入账户之后，
            // 那笔转账在列表里会渲染成「甲 → 」（toAccountName 查不到变空串），
            // 看着像数据坏了。备份恢复路径本来就做了这个兜底，删除路径漏了。
            ledger.moveTransactionsToAccount(id, AccountEntity.UNSPECIFIED_ID)
            ledger.clearToAccount(id)
            ledger.deleteAccount(id)
            if (settings.lastUsed.first().accountId == id) {
                settings.saveLastUsed(
                    settings.lastUsed.first().categoryId,
                    settings.lastUsed.first().type,
                    AccountEntity.UNSPECIFIED_ID
                )
            }
            showToast("已删除，账单已转到「未指定」")
        }
    }

    /**
     * 批量把选中的一批账单改到某个账户下。
     *
     * 账单一多（比如迁移前的老账单全挂在「未指定」）就只能靠这个整理：
     * 账单页多选 → 「设为账户」，一条 SQL 改完，不走逐条编辑。
     */
    fun bulkAssignAccount(ids: List<Long>, accountId: Long) {
        if (ids.isEmpty()) { showToast("还没选中账单"); return }
        val name = accounts.value.firstOrNull { it.id == accountId }?.name ?: "该账户"
        viewModelScope.launch {
            val n = ledger.assignTransactionsToAccount(ids, accountId)
            refreshWidgets()
            showToast("已把 $n 笔归到「$name」")
        }
    }

    /** 全部报销账单（首页「报销」入口点进来显示这个） */
    val reimbursedTxs: StateFlow<List<TxWithCategory>> =
        ledger.observeReimbursed().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 报销汇总：支出报销、收入报销各多少（分） */
    val reimburseSummary: StateFlow<Pair<Long, Long>> =
        ledger.observeReimburseStats()
            .map { list ->
                val exp = list.firstOrNull { it.type == TxType.EXPENSE.value }?.totalCents ?: 0L
                val inc = list.firstOrNull { it.type == TxType.INCOME.value }?.totalCents ?: 0L
                exp to inc
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L to 0L)

    /** 报销笔数 */
    val reimburseCount: StateFlow<Int> =
        ledger.observeReimburseStats()
            .map { list -> list.sumOf { it.count } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 支出、收入合计（分） */
    /**
     * 支出、收入合计（分）。
     *
     * 之前这里 `.combine(MutableStateFlow(Unit))` —— 第二个 flow 的值根本没用，
     * 只是为了多触发一次重算，白白让每次订阅多收一个值。直接 map 就够了。
     */
    val summary: StateFlow<Pair<Long, Long>> =
        ledger.observeMonthSummary()
            .map { list ->
                val expense = list.firstOrNull { it.type == TxType.EXPENSE.value }?.totalCents ?: 0L
                val income = list.firstOrNull { it.type == TxType.INCOME.value }?.totalCents ?: 0L
                expense to income
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L to 0L)

    val webDavSettings: StateFlow<SettingsRepository.WebDavSettings> =
        settings.settings.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            SettingsRepository.WebDavSettings()
        )

    /** 自动备份开关状态，供「云端备份」页那个 Switch 用 */
    val autoBackupOn: StateFlow<Boolean> =
        settings.autoBackup.map { it.enabled }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 应用设置（配色等） */
    val appSettings: StateFlow<SettingsRepository.AppSettings> =
        settings.appSettings.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            SettingsRepository.AppSettings()
        )

    /** 上次记账用的分类与类型，记一笔时自动带出 */
    val lastUsed: StateFlow<SettingsRepository.LastUsed> =
        settings.lastUsed.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            SettingsRepository.LastUsed()
        )

    fun saveLastUsed(categoryId: Long, type: Int, accountId: Long) {
        viewModelScope.launch { settings.saveLastUsed(categoryId, type, accountId) }
    }

    fun setTheme(themeId: String) {
        viewModelScope.launch { settings.saveTheme(themeId) }
    }

    fun setMode(mode: String) {
        viewModelScope.launch { settings.saveMode(mode) }
    }

    fun setCustomColor(argb: Int) {
        viewModelScope.launch { settings.saveCustomColor(argb) }
    }

    fun setAppTitle(title: String) {
        viewModelScope.launch { settings.saveAppTitle(title) }
    }

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /**
     * 点「关于」里的版本号才触发：拉一次 update.json。
     * 有新版 → 直接跳浏览器下载页；其余结果一律 toast。
     */
    fun checkUpdateNow() {
        viewModelScope.launch {
            _busy.value = true
            val current = runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
                    ?: "0"
            }.getOrDefault("0")
            val r = UpdateChecker.check(current)
            _busy.value = false
            when (r) {
                is UpdateChecker.Result.NotConfigured -> _toast.value = "还没配置更新地址"
                is UpdateChecker.Result.UpToDate -> _toast.value = "已是最新版本 $current"
                is UpdateChecker.Result.Failed -> _toast.value = "检查更新失败：${r.reason}"
                is UpdateChecker.Result.Newer -> runCatching {
                    appContext.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(r.info.url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { _toast.value = "打不开下载页：${r.info.url}" }
            }
        }
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    // ============ 统计 ============

    enum class RangeMode(val label: String) { MONTH("按月"), QUARTER("按季"), YEAR("按年") }

    /** 趋势图的一个柱子 */
    data class BarPoint(val label: String, val expense: Long, val income: Long)

    data class StatsUiState(
        val mode: RangeMode = RangeMode.MONTH,
        val anchorYear: Int = Calendar.getInstance().get(Calendar.YEAR),
        /** 月模式下是月份(1-12)，季模式下是季度(1-4)，年模式下忽略 */
        val anchorUnit: Int = Calendar.getInstance().get(Calendar.MONTH) + 1,
        val totalExpense: Long = 0,
        val totalIncome: Long = 0,
        val expenseByCategory: List<CategoryStat> = emptyList(),
        val incomeByCategory: List<CategoryStat> = emptyList(),
        val bars: List<BarPoint> = emptyList(),
        val rangeLabel: String = "",
        /** 区间内带分类名的原始账单，供展开看单笔明细 */
        val transactions: List<TxWithCategory> = emptyList()
    )

    private val statsMode = MutableStateFlow(RangeMode.MONTH)
    private val statsYear = MutableStateFlow(Calendar.getInstance().get(Calendar.YEAR))
    private val statsUnit = MutableStateFlow(Calendar.getInstance().get(Calendar.MONTH) + 1)

    /** 供界面读写的统计区间控件 */
    val statsControl: StateFlow<Triple<RangeMode, Int, Int>> =
        combine(statsMode, statsYear, statsUnit) { m, y, u -> Triple(m, y, u) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
                Triple(RangeMode.MONTH, Calendar.getInstance().get(Calendar.YEAR), Calendar.getInstance().get(Calendar.MONTH) + 1))

    val stats: StateFlow<StatsUiState> =
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        statsControl.flatMapLatest { (mode, year, unit) ->
            val modeFlow = MutableStateFlow(mode)
            val (start, end) = when (mode) {
                RangeMode.MONTH -> monthRange(year, unit)
                RangeMode.QUARTER -> quarterRange(year, unit)
                RangeMode.YEAR -> yearRange(year)
            }
            // 趋势条：月模式看该年内 12 个月；季模式看该年内 4 个季度；年模式看近 5 年
            //
            // ⚠️ 这里原来写的是 `combine(rangeFlow, observeRange(start, end)) { all, _ -> }`：
            // 第二个 flow 的值**被丢弃**，纯粹为了「账单在区间内变了也重算一次」，
            // 属于冗余订阅 —— rangeFlow 本来就覆盖了全年，区间内变了一定会触发它。
            //
            // 更要紧的是里面 `(1..12).map { all.filter{...} }`：每个桶都全表扫一遍，
            // 8000 条就是 12 × 8000 = 96000 次比较，而账单一变就重来一次。
            // 现在用 [bucketBy] 一次线性遍历分桶，O(n) 而不是 O(n × 桶数)。
            val barsSource: kotlinx.coroutines.flow.Flow<List<BarPoint>> = when (mode) {
                RangeMode.MONTH -> {
                    val ys = yearRange(year)
                    ledger.observeRange(ys.first, ys.second).let { rangeFlow ->
                        combine(rangeFlow, ledger.observeRange(start, end)) { all, _ ->
                            val buckets = HashMap<Int, Pair<Long, Long>>()
                            all.forEach { tx ->
                                val m = Calendar.getInstance().apply {
                                    timeInMillis = tx.dateMillis
                                }.get(Calendar.MONTH) + 1
                                val old = buckets[m] ?: 0L to 0L
                                buckets[m] = when (tx.type) {
                                    TxType.EXPENSE.value -> old.first + tx.amountCents to old.second
                                    TxType.INCOME.value -> old.first to old.second + tx.amountCents
                                    else -> old
                                }
                            }
                            (1..12).map { m ->
                                val (e, i) = buckets[m] ?: 0L to 0L
                                BarPoint(label = "${m}月", expense = e, income = i)
                            }
                        }
                    }
                }
                RangeMode.QUARTER -> {
                    val ys = yearRange(year)
                    ledger.observeRange(ys.first, ys.second).let { rangeFlow ->
                        combine(rangeFlow, ledger.observeRange(start, end)) { all, _ ->
                            val buckets = HashMap<Int, Pair<Long, Long>>()
                            all.forEach { tx ->
                                val c = Calendar.getInstance().apply { timeInMillis = tx.dateMillis }
                                // 季度 = 月份落在哪一段
                                val q = c.get(Calendar.MONTH) / 3 + 1
                                val old = buckets[q] ?: 0L to 0L
                                buckets[q] = when (tx.type) {
                                    TxType.EXPENSE.value -> old.first + tx.amountCents to old.second
                                    TxType.INCOME.value -> old.first to old.second + tx.amountCents
                                    else -> old
                                }
                            }
                            (1..4).map { q ->
                                val (e, i) = buckets[q] ?: 0L to 0L
                                BarPoint(label = "Q$q", expense = e, income = i)
                            }
                        }
                    }
                }
                RangeMode.YEAR -> {
                    val startY = year - 4
                    val (s, e) = yearRange(startY).first to yearRange(year).second
                    ledger.observeRange(s, e).let { rangeFlow ->
                        combine(rangeFlow, ledger.observeRange(start, end)) { all, _ ->
                            val buckets = HashMap<Int, Pair<Long, Long>>()
                            all.forEach { tx ->
                                val y = Calendar.getInstance().apply {
                                    timeInMillis = tx.dateMillis
                                }.get(Calendar.YEAR)
                                val old = buckets[y] ?: 0L to 0L
                                buckets[y] = when (tx.type) {
                                    TxType.EXPENSE.value -> old.first + tx.amountCents to old.second
                                    TxType.INCOME.value -> old.first to old.second + tx.amountCents
                                    else -> old
                                }
                            }
                            (startY..year).map { y ->
                                val (ey, iy) = buckets[y] ?: 0L to 0L
                                BarPoint(label = "$y", expense = ey, income = iy)
                            }
                        }
                    }
                }
            }

            combine(
                ledger.observeRange(start, end),
                ledger.observeCategoryStats(start, end, TxType.EXPENSE.value),
                ledger.observeCategoryStats(start, end, TxType.INCOME.value),
                barsSource,
                ledger.observeRangeWithCategory(start, end)
            ) { txs, expCats, incCats, bars, txsWithCat ->
                StatsUiState(
                    mode = mode,
                    anchorYear = year,
                    anchorUnit = unit,
                    totalExpense = txs.filter { it.type == TxType.EXPENSE.value }.sumOf { it.amountCents },
                    totalIncome = txs.filter { it.type == TxType.INCOME.value }.sumOf { it.amountCents },
                    expenseByCategory = expCats,
                    incomeByCategory = incCats,
                    bars = bars,
                    rangeLabel = rangeLabelOf(mode, year, unit),
                    transactions = txsWithCat
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    fun setStatsMode(mode: RangeMode) {
        statsMode.value = mode
        // 切到年模式时，把 unit 归到 1 避免残留
        if (mode == RangeMode.YEAR) statsUnit.value = 1
        if (mode == RangeMode.QUARTER && statsUnit.value !in 1..4) statsUnit.value = 1
        if (mode == RangeMode.MONTH && statsUnit.value !in 1..12) statsUnit.value = Calendar.getInstance().get(Calendar.MONTH) + 1
    }

    fun shiftStats(delta: Int) {
        when (statsMode.value) {
            RangeMode.YEAR -> statsYear.value += delta
            RangeMode.MONTH -> {
                var m = statsUnit.value + delta
                var y = statsYear.value
                while (m > 12) { m -= 12; y += 1 }
                while (m < 1) { m += 12; y -= 1 }
                statsUnit.value = m
                statsYear.value = y
            }
            RangeMode.QUARTER -> {
                var q = statsUnit.value + delta
                var y = statsYear.value
                while (q > 4) { q -= 4; y += 1 }
                while (q < 1) { q += 4; y -= 1 }
                statsUnit.value = q
                statsYear.value = y
            }
        }
    }

    fun statsToNow() {
        val now = Calendar.getInstance()
        statsYear.value = now.get(Calendar.YEAR)
        statsUnit.value = when (statsMode.value) {
            RangeMode.YEAR -> 1
            RangeMode.QUARTER -> (now.get(Calendar.MONTH) / 3) + 1
            RangeMode.MONTH -> now.get(Calendar.MONTH) + 1
        }
    }

    // ============ 截图识别 ============

    /** 分享进来的图片识别结果，交给记账页预填 */
    private val _pendingOcr = MutableStateFlow<ReceiptParser.Parsed?>(null)
    val pendingOcr: StateFlow<ReceiptParser.Parsed?> = _pendingOcr.asStateFlow()

    fun setPendingOcr(p: ReceiptParser.Parsed?) { _pendingOcr.value = p }

    fun recognizeImage(
        context: android.content.Context,
        uri: android.net.Uri,
        ocr: com.dafeng.onlymoneynote.data.ocr.OcrEngine = ocrEngine
    ) {
        _busy.value = true
        viewModelScope.launch {
            try {
                val text = ocr.recognize(context, uri)
                val parsed = ReceiptParser.parse(text)
                _pendingOcr.value = parsed
                val found = buildList {
                    parsed.amountCents?.let { add("金额 ¥${formatCents(it)}") }
                    parsed.dateMillis?.let { add("时间") }
                    parsed.suggestParentName?.let { add("分类 $it") }
                }
                _toast.value = if (found.isEmpty()) {
                    "没认出账单信息，请手动填写"
                } else {
                    "识别到：${found.joinToString("、")}，核对后保存"
                }
            } catch (e: Exception) {
                _toast.value = "识别失败：${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    // ============ 分类编辑 ============

    fun updateCategory(c: CategoryEntity, newName: String, newIcon: String, newColorKey: String? = null) {
        if (newName.isBlank()) { _toast.value = "分类名不能是空的"; return }
        viewModelScope.launch {
            ledger.updateCategory(
                c.copy(
                    name = newName.trim(), iconKey = newIcon,
                    // 不传颜色就保持原值
                    colorKey = newColorKey ?: c.colorKey
                )
            )
            _toast.value = "分类已更新"
        }
    }

    fun moveCategory(c: CategoryEntity, up: Boolean) {
        val siblings = categories.value
            .filter { it.parentId == c.parentId }
            .sortedBy { it.sortOrder }
        val idx = siblings.indexOfFirst { it.id == c.id }
        if (idx < 0) return
        val swapWith = if (up) idx - 1 else idx + 1
        if (swapWith !in siblings.indices) return
        val other = siblings[swapWith]
        viewModelScope.launch {
            ledger.updateCategory(c.copy(sortOrder = other.sortOrder))
            ledger.updateCategory(other.copy(sortOrder = c.sortOrder))
        }
    }

    /**
     * 把一个分类拖到同级里的指定位置（长按拖动排序用）。
     * 重排这一级所有分类的 sortOrder，一次性写库。
     */
    fun moveCategoryTo(c: CategoryEntity, targetIndex: Int) {
        val siblings = categories.value
            .filter { it.parentId == c.parentId }
            .sortedBy { it.sortOrder }
        val from = siblings.indexOfFirst { it.id == c.id }
        if (from < 0) return
        val to = targetIndex.coerceIn(0, siblings.size - 1)
        if (from == to) return

        // 把 c 从 from 挪到 to，其余顺延
        val reordered = siblings.toMutableList()
        reordered.removeAt(from)
        reordered.add(to, c)

        viewModelScope.launch {
            reordered.forEachIndexed { i, item ->
                if (item.sortOrder != i) {
                    ledger.updateCategory(item.copy(sortOrder = i))
                }
            }
        }
    }

    /**
     * 按给定顺序重排同一级分类（长按拖动松手时一次性提交）。
     * orderedIds 是该级分类 id 的最终顺序。
     */
    fun reorderCategories(parentId: Long?, orderedIds: List<Long>) {
        val siblings = categories.value.filter { it.parentId == parentId }
        val orderMap = orderedIds.withIndex().associate { (i, id) -> id to i }
        viewModelScope.launch {
            siblings.forEach { c ->
                val newOrder = orderMap[c.id] ?: c.sortOrder
                if (c.sortOrder != newOrder) {
                    ledger.updateCategory(c.copy(sortOrder = newOrder))
                }
            }
        }
    }

    fun showToast(msg: String) { _toast.value = msg }
    fun clearToast() { _toast.value = null }

    fun addTransaction(
        amountYuan: String,
        type: TxType,
        categoryId: Long,
        dateMillis: Long,
        note: String,
        reimbursed: Boolean = false,
        accountId: Long = AccountEntity.UNSPECIFIED_ID
    ) {
        val amount = parseAmountToCents(amountYuan)
        if (amount == null || amount <= 0) {
            showToast("金额填写不对，检查一下")
            return
        }
        viewModelScope.launch {
            ledger.addTransaction(
                TransactionEntity(
                    amountCents = amount,
                    type = type.value,
                    categoryId = categoryId,
                    dateMillis = dateMillis,
                    note = note.trim(),
                    reimbursed = reimbursed,
                    accountId = accountId
                )
            )
            // 记住这次用的分类和账户，下次记一笔自动带出来
            settings.saveLastUsed(categoryId, type.value, accountId)
            refreshWidgets()
            showToast("已记一笔")
        }
    }

    fun deleteTransaction(id: Long) {
        viewModelScope.launch {
            ledger.deleteTransaction(id)
            refreshWidgets()
            showToast("已删除")
        }
    }

    /** 改一条已有账单。金额非法就拦下来。 */
    fun updateTransaction(
        id: Long,
        amountYuan: String,
        type: TxType,
        categoryId: Long,
        dateMillis: Long,
        note: String,
        reimbursed: Boolean = false,
        accountId: Long = AccountEntity.UNSPECIFIED_ID,
        /**
         * 转账的转入方。非转账一律 0。
         * 之前这个参数根本不存在，所以**把类型改成转账走 update 路径时转入方必丢**。
         */
        toAccountIdArg: Long = 0L
    ) {
        val amount = parseAmountToCents(amountYuan)
        if (amount == null || amount <= 0) {
            showToast("金额填写不对，检查一下")
            return
        }
        viewModelScope.launch {
            // createdAt 必须带回：不带的话 TransactionEntity 会走默认值
            // System.currentTimeMillis()，等于每编辑一次就把「创建于」改成现在。
            val old = transactions.value.firstOrNull { it.id == id }
            ledger.updateTransaction(
                TransactionEntity(
                    id = id,
                    amountCents = amount,
                    type = type.value,
                    categoryId = categoryId,
                    dateMillis = dateMillis,
                    note = note.trim(),
                    createdAt = old?.createdAt ?: System.currentTimeMillis(),
                    reimbursed = reimbursed,
                    accountId = accountId,
                    // 从转账改成支出时要把 toAccountId 清零，否则这条账单
                    // 还留着旧转入方的指向，转账页会渲染出一条幽灵转账
                    toAccountId = if (type == TxType.TRANSFER) {
                        toAccountIdArg
                    } else 0L
                )
            )
            refreshWidgets()
            showToast("已保存")
        }
    }

    fun addCategory(
        name: String,
        iconKey: String,
        parentId: Long?,
        type: Int? = null,
        colorKey: String = ""
    ) {
        if (name.isBlank()) { showToast("分类名不能是空的"); return }
        viewModelScope.launch {
            // 新分类的类型：显式指定 > 继承父分类 > 默认支出
            val resolvedType = type
                ?: parentId?.let { pid ->
                    categories.value.firstOrNull { it.id == pid }?.type
                }
                ?: 0
            val siblings = categories.value.count { it.parentId == parentId }
            ledger.addCategory(
                CategoryEntity(
                    name = name.trim(), iconKey = iconKey,
                    parentId = parentId, sortOrder = siblings, type = resolvedType,
                    colorKey = colorKey
                )
            )
            showToast("分类已添加")
        }
    }

    /**
     * 记一笔转账：钱从 [fromAccountId] 挪到 [toAccountId]。
     *
     * 转账不是收入也不是支出，所以**不写 categoryId**（存 0）、
     * 不参与任何收支统计、也不进报销。但它必须影响两个账户的余额，
     * 否则「银行卡转 500 到支付宝」这件事在 App 里根本记不下来，
     * 支付宝的余额会莫名其妙变负 —— 这正是用户 8000 条老账单遇到的问题。
     */
    fun addTransfer(
        amountYuan: String,
        fromAccountId: Long,
        toAccountId: Long,
        dateMillis: Long,
        note: String
    ) {
        val amount = parseAmountToCents(amountYuan)
        if (amount == null || amount <= 0) {
            showToast("金额填写不对，检查一下")
            return
        }
        if (fromAccountId == toAccountId) {
            showToast("转出和转入是同一个账户，这不算转账")
            return
        }
        viewModelScope.launch {
            ledger.addTransaction(
                TransactionEntity(
                    amountCents = amount,
                    type = TxType.TRANSFER.value,
                    categoryId = 0L,
                    dateMillis = dateMillis,
                    note = note.trim(),
                    accountId = fromAccountId,
                    toAccountId = toAccountId
                )
            )
            val from = accounts.value.firstOrNull { it.id == fromAccountId }?.name ?: "转出账户"
            val to = accounts.value.firstOrNull { it.id == toAccountId }?.name ?: "转入账户"
            refreshWidgets()
            showToast("已记转账 $from → $to")
        }
    }

    /**
     * 批量把选中的一批账单改到某个分类下。
     *
     * 跟 [bulkAssignAccount] 一样只改一列。分类批量改动比改账户更容易点错
     * （一次几百笔的统计全变），所以 UI 上走了**两步确认**才调到这里。
     */
    fun bulkRecategorize(ids: List<Long>, categoryId: Long) {
        if (ids.isEmpty()) { showToast("还没选中账单"); return }
        val name = categories.value.firstOrNull { it.id == categoryId }?.name ?: "该分类"
        viewModelScope.launch {
            val n = ledger.assignTransactionsToCategory(ids, categoryId)
            refreshWidgets()
            showToast("已把 $n 笔改成「$name」")
        }
    }

    /**
     * 改一笔已有的转账。
     *
     * 之前没有这个方法，`save()` 里转账分支一律走 [addTransfer]（纯 insert），
     * 导致「长按转账改金额保存」会新增一条而不是改原来那条。
     * 转入方 [toAccountId] 必须一起写回去，否则编辑一次就把转入账户丢了。
     *
     * 校验跟新增保持一致：转出转入不能是同一个账户。
     */
    fun updateTransfer(
        id: Long,
        amountYuan: String,
        fromAccountId: Long,
        toAccountId: Long,
        dateMillis: Long,
        note: String
    ) {
        val amount = parseAmountToCents(amountYuan)
        if (amount == null || amount <= 0) {
            showToast("金额填写不对，检查一下")
            return
        }
        if (fromAccountId == toAccountId) {
            showToast("转出和转入是同一个账户，这不算转账")
            return
        }
        viewModelScope.launch {
            val old = transactions.value.firstOrNull { it.id == id }
            ledger.updateTransaction(
                TransactionEntity(
                    id = id,
                    amountCents = amount,
                    type = TxType.TRANSFER.value,
                    // 转账没有分类，统一 0
                    categoryId = 0L,
                    dateMillis = dateMillis,
                    note = note.trim(),
                    // 保持原来的创建时间，别把「创建于」改成现在
                    createdAt = old?.createdAt ?: System.currentTimeMillis(),
                    reimbursed = false,
                    accountId = fromAccountId,
                    toAccountId = toAccountId
                )
            )
            refreshWidgets()
            val from = accounts.value.firstOrNull { it.id == fromAccountId }?.name ?: "转出账户"
            val to = accounts.value.firstOrNull { it.id == toAccountId }?.name ?: "转入账户"
            showToast("已改：$from → $to")
        }
    }

    /** 全部转账，单独一页看（不进账单列表，但用户得能查自己记过什么） */
    val transfers: StateFlow<List<TransferWithAccount>> =
        ledger.observeTransfers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteCategory(c: CategoryEntity) {
        val hasChildren = categories.value.any { it.parentId == c.id }
        if (hasChildren) { showToast("先删掉它下面的二级分类"); return }
        viewModelScope.launch {
            // 关键保护：名下有账单就不许删。
            // 删了会留下「孤儿账单」——账单还引用着不存在的分类 id，
            // 列表查询 LEFT JOIN 出来分类名是 NULL，直接崩在启动页。
            val txCount = ledger.countTransactionsInCategory(c.id)
            if (txCount > 0) {
                showToast("「${c.name}」名下还有 $txCount 笔账单，先改到别的分类再删")
                return@launch
            }
            ledger.deleteCategory(c.id)
            showToast("分类已删除")
        }
    }

    /**
     * 删除分类前，把名下账单全部转移到目标分类。
     * 给「删除已占用的分类」弹窗用：先转移再删，不会留孤儿账单。
     */
    fun deleteCategoryReassign(c: CategoryEntity, targetId: Long) {
        val targetName = categories.value.firstOrNull { it.id == targetId }?.name ?: "其他分类"
        viewModelScope.launch {
            ledger.moveTransactions(c.id, targetId)
            ledger.deleteCategory(c.id)
            refreshWidgets()
            showToast("账单已转移到「$targetName」，分类已删除")
        }
    }

    fun saveWebDav(baseUrl: String, user: String, pass: String, path: String) {
        viewModelScope.launch {
            settings.save(SettingsRepository.WebDavSettings(baseUrl, user, pass, path))
            showToast("设置已保存")
        }
    }

    private fun deviceName(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun testConnection() {
        val cfg = webDavSettings.value
        if (!cfg.isConfigured) { showToast("先把服务器地址和用户名填上"); return }
        _busy.value = true
        viewModelScope.launch {
            val r = backup.test(cfg.toClientConfig())
            _busy.value = false
            showToast(r.getOrElse { "连接失败：${it.message}" })
        }
    }

    fun uploadBackup() {
        val cfg = webDavSettings.value
        if (!cfg.isConfigured) { showToast("先把 WebDAV 设置填好"); return }
        _busy.value = true
        viewModelScope.launch {
            val r = backup.upload(cfg.toClientConfig(), deviceName())
            _busy.value = false
            showToast(r.getOrElse { "上传失败：${it.message}" })
        }
    }

    fun restoreBackup() {
        val cfg = webDavSettings.value
        if (!cfg.isConfigured) { showToast("先把 WebDAV 设置填好"); return }
        _busy.value = true
        viewModelScope.launch {
            val r = backup.restore(cfg.toClientConfig())
            _busy.value = false
            showToast(r.getOrElse { "恢复失败：${it.message}" })
        }
    }

    /** 本地导出：整份备份（设置 + 分类 + 账单）写成 JSON，存到用户用系统选择器挑的文件 */
    /**
     * 从本机一个 JSON 备份文件恢复（「导入导出 → 从 JSON 备份恢复」）。
     * 跟云端恢复走同一条路：先清空本机，再按备份里的原 id 重建分类和账单。
     */
    fun restoreFromJson(uri: Uri) {
        _busy.value = true
        viewModelScope.launch {
            val r = runCatching {
                val text = appContext.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IOException("读不到这个文件，换一份再试")
                backup.restoreFromJson(text).getOrThrow()
            }
            _busy.value = false
            showToast(r.fold({ it }, { "恢复失败：${it.message}" }))
            refreshWidgets()
        }
    }

    fun exportToUri(uri: Uri) {
        _busy.value = true
        viewModelScope.launch {
            val r = runCatching {
                val json = backup.buildPayload(deviceName())
                val bytes = json.toByteArray(Charsets.UTF_8)
                // 模式必须显式给 "wt"（写 + 截断）。只写 "w" 时，部分文件管理器 /
                // 云盘提供器不会把旧内容截掉：重复导出到同一个文件名，新内容比旧的短，
                // 尾部就残留上一份的零头，恢复时直接报「解析失败」。
                appContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: throw IOException("无法写入选定的文件，换个位置再试")
                bytes.size
            }
            _busy.value = false
            showToast(
                r.fold(
                    { "已导出 JSON 备份，约 ${it / 1024} KB（设置、分类、账单都在）" },
                    { "导出失败：${it.message}" }
                )
            )
        }
    }

    /** 本地导出 CSV：纯账单表（日期/分类/收支/金额/备注/报销），给 Excel 或别的软件看。 */
    fun exportCsv(uri: Uri) {
        _busy.value = true
        viewModelScope.launch {
            val r = runCatching {
                val list = ledger.observeTransactions().first()
                val bytes = com.dafeng.onlymoneynote.data.exporter.CsvExporter
                    .build(list).toByteArray(Charsets.UTF_8)
                // 同样是 "wt"（写 + 截断），理由跟 exportToUri 完全一样：
                // 不显式截断的话，重复导出到同一个文件名时尾部会残留上一份的零头，
                // 这份 CSV 再导回来就会解析错行。这个坑 JSON 那边已经踩过。
                appContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: throw IOException("无法写入选定的文件，换个位置再试")
                bytes.size to list.size
            }
            _busy.value = false
            showToast(
                r.fold(
                    { (bytes, n) -> "已导出 ${n} 笔账单到 CSV，约 ${bytes / 1024} KB" },
                    { "导出失败：${it.message}" }
                )
            )
        }
    }

    /**
     * 清空全部账单；按用户在确认框里的开关，决定要不要连主题 / 分类 / 其他设置一起清。
     * 分类被清后用内置默认分类顶上 —— 不然「记一笔」会没有分类可选。
     */
    fun clearAllData(wipeTheme: Boolean, wipeCategories: Boolean, wipeSettings: Boolean) {
        _busy.value = true
        viewModelScope.launch {
            ledger.clearTransactions()
            if (wipeCategories) {
                ledger.clearCategories()
                ledger.seedIfEmpty()
            }
            if (wipeTheme) settings.clearTheme()
            if (wipeSettings) settings.clearAppConfig()
            _busy.value = false
            refreshWidgets()
            val also = listOfNotNull(
                if (wipeTheme) "主题" else null,
                if (wipeCategories) "分类" else null,
                if (wipeSettings) "设置" else null
            )
            _toast.value = if (also.isEmpty()) "账单已清空"
            else "已清空账单和" + also.joinToString("、")
        }
    }

    // ============ 从其他记账软件导入 ============

    /**
     * 导入 CSV（目前是大象记账的导出格式）。
     *
     * 会**清空现有账单**并按文件重建，分类只补缺不覆盖：同名的已存在分类原样保留
     * （图标、配色、顺序都不动），文件里有而库里没有的才新建、用默认图标。
     * 解析和归类都在后台线程做完，落库是一个事务，中途失败整体回滚。
     */
    fun importCsv(bytes: ByteArray) {
        _busy.value = true
        viewModelScope.launch {
            try {
                val (rows, rawTransfers) = kotlinx.coroutines.withContext(
                    kotlinx.coroutines.Dispatchers.Default
                ) {
                    com.dafeng.onlymoneynote.data.importer.RecordsCsvImporter
                        .parseWithTransfers(
                            com.dafeng.onlymoneynote.data.importer.RecordsCsvImporter.decode(bytes)
                        )
                }
                val plan = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.dafeng.onlymoneynote.data.importer.RecordsCsvImporter.buildPlanFromRows(rows)
                }
                if (plan.txs.isEmpty() && rawTransfers.isEmpty()) {
                    _toast.value = "这个文件里没解析出账单，确认是记账软件导出的 CSV"
                    return@launch
                }
                val newCats = if (plan.txs.isEmpty()) 0 else ledger.importPlan(plan)
                // 转账单独落：没有分类、也不该混在 importPlan 里
                // （importPlan 是按分类名反查 id 的，转账 categoryId 恒为 0）
                var txCount = plan.txs.size
                var skippedTx = 0
                rawTransfers.forEach { t ->
                    val from = ledger.findAccountByName(t.fromAccountName)
                    val to = ledger.findAccountByName(t.toAccountName)
                    if (from == null || to == null || from == to) {
                        skippedTx++
                        return@forEach
                    }
                    ledger.addTransaction(
                        TransactionEntity(
                            amountCents = t.cents,
                            type = TxType.TRANSFER.value,
                            categoryId = 0L,
                            dateMillis = t.dateMillis,
                            note = t.note,
                            accountId = from,
                            toAccountId = to
                        )
                    )
                    txCount++
                }
                refreshWidgets()
                val parts = mutableListOf("已导入 $txCount 笔账单")
                if (newCats > 0) parts.add("新增 $newCats 个分类")
                if (skippedTx > 0) {
                    parts.add("$skippedTx 笔转账因账户对不上已跳过")
                }
                _toast.value = parts.joinToString("，")
            } catch (e: Exception) {
                _toast.value = "导入失败：${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * 每天第一次打开 App 自动备份一次。
     *
     * 2026-10-11 用户要求。几个刻意的设计：
     * - **只在成功后才记「今天已备份」**，失败不写，所以下次打开还会重试；
     * - **默认关闭**，需要用户在「云端备份」页自己打开 —— App 平时完全不联网的
     *   承诺不能因为加了这个功能就破掉，不然用户会莫名其妙看到流量消耗；
     * - **没配 WebDAV 直接跳过**，不报错；
     * - 成功后只发一条**最低重要性**的通知（不响不震），见 [BackupNotifier]。
     */
    fun runDailyAutoBackupIfNeeded() {
        viewModelScope.launch {
            val dav = webDavSettings.value
            if (!dav.isConfigured) return@launch
            val auto = settings.autoBackup.first()
            val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA)
                .format(java.util.Date())
            if (!auto.shouldRunToday(today)) return@launch

            _busy.value = true
            val result = backup.upload(dav.toClientConfig(), deviceNameForBackup())
            _busy.value = false
            result.onSuccess {
                settings.markAutoBackupDone(today)
                com.dafeng.onlymoneynote.util.BackupNotifier
                    .notifyBackupOk(appContext, transactions.value.size)
            }
            // 失败：不写标记（下次还会试），也不通知（不打扰）。
            // 密码错这类问题在「云端备份」页手动试一次就能看到。
        }
    }

    fun setAutoBackupEnabled(on: Boolean) {
        viewModelScope.launch {
            settings.saveAutoBackupEnabled(on)
            showToast(if (on) "已开启每天自动备份" else "已关闭每天自动备份")
        }
    }

    /** 备份文件里记的「这台设备叫什么」，自动备份沿用同一套 */
    private fun deviceNameForBackup(): String =
        runCatching { android.os.Build.MODEL ?: "" }.getOrDefault("").ifBlank { "Android" }

    /**
     * 导入第三方记账软件（钱迹等）的账单。
     *
     * 和 [importCsv] 的区别只在**解析层**：解析完同样得到
     * [com.dafeng.onlymoneynote.data.importer.RecordsCsvImporter.Plan]，
     * 所以「清空重建、分类只补缺不覆盖」那一套复用现成逻辑，不重复实现。
     *
     * 多了一件事：**转账要单独落**。钱迹把转账放在「账户1 → 账户2」，
     * 跟收支不是一回事，漏掉的话账户余额会算不准 —— 而这正是用户迁移数据
     * 最在意的东西（总资产 / 单账户余额）。
     */
    fun importThirdParty(kind: com.dafeng.onlymoneynote.ui.screens.ThirdPartyKind, bytes: ByteArray) {
        _busy.value = true
        viewModelScope.launch {
            try {
                val parsed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.dafeng.onlymoneynote.ui.screens.ThirdPartyImportSupport.parse(kind, bytes)
                }
                if (parsed.rows.isEmpty() && parsed.transfers.isEmpty()) {
                    _toast.value = "这个文件里没解析出账单，确认是 ${kind.label} 导出的账单明细"
                    return@launch
                }
                val plan = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.dafeng.onlymoneynote.ui.screens.ThirdPartyImportSupport.planOf(parsed)
                }
                // 转账要往账单表里插，但分类 id 是 0，绕开 importPlan 那套
                // （它按分类名反查 id，转账没分类）
                val transfers = parsed.transfers.mapNotNull { t ->
                    val from = ledger.findAccountByName(t.fromAccountName)
                    val to = ledger.findAccountByName(t.toAccountName)
                    if (from == null || to == null) null
                    else com.dafeng.onlymoneynote.data.local.TransactionEntity(
                        amountCents = t.cents,
                        type = com.dafeng.onlymoneynote.data.local.TxType.TRANSFER.value,
                        categoryId = 0L,
                        dateMillis = t.dateMillis,
                        note = t.note,
                        accountId = from,
                        toAccountId = to
                    )
                }
                val newCats = ledger.importPlan(plan)
                transfers.forEach { ledger.addTransaction(it) }
                refreshWidgets()
                val parts = mutableListOf("已导入 ${plan.txs.size} 笔账单")
                if (transfers.isNotEmpty()) parts.add("${transfers.size} 笔转账")
                val missTx = parsed.transfers.size - transfers.size
                if (missTx > 0) {
                    parts.add("$missTx 笔转账因账户在本机找不到已跳过")
                }
                if (newCats > 0) parts.add("新增 $newCats 个分类")
                _toast.value = parts.joinToString("，")
            } catch (e: Exception) {
                _toast.value = "导入失败：${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        /** 支持 "12"、"12.5"、"12.34"，返回「分」；非法返回 null */
        fun parseAmountToCents(input: String): Long? {
            val s = input.trim()
            if (s.isEmpty()) return null
            if (!Regex("^\\d{0,9}(\\.\\d{0,2})?$").matches(s)) return null
            val parts = s.split('.')
            val yuan = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
            val cents = if (parts.size > 1) parts[1].padEnd(2, '0').take(2).toLongOrNull() ?: 0L else 0L
            return yuan * 100 + cents
        }

        fun formatCents(cents: Long): String {
            val sign = if (cents < 0) "-" else ""
            val abs = kotlin.math.abs(cents)
            return "%s%d.%02d".format(sign, abs / 100, abs % 100)
        }

        /** 月份区间 [start, end) */
        fun monthRange(year: Int, month: Int): Pair<Long, Long> {
            val cal = Calendar.getInstance()
            cal.set(year, month - 1, 1, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.add(Calendar.MONTH, 1)
            return start to cal.timeInMillis
        }

        /** 季度区间 [start, end) */
        fun quarterRange(year: Int, quarter: Int): Pair<Long, Long> {
            val firstMonth = (quarter - 1) * 3 + 1
            val cal = Calendar.getInstance()
            cal.set(year, firstMonth - 1, 1, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.add(Calendar.MONTH, 3)
            return start to cal.timeInMillis
        }

        /** 全年区间 [start, end) */
        fun yearRange(year: Int): Pair<Long, Long> {
            val cal = Calendar.getInstance()
            cal.set(year, 0, 1, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.add(Calendar.YEAR, 1)
            return start to cal.timeInMillis
        }

        private fun rangeLabelOf(mode: RangeMode, year: Int, unit: Int): String = when (mode) {
            RangeMode.YEAR -> "$year 年"
            RangeMode.QUARTER -> "$year 年 Q$unit"
            RangeMode.MONTH -> "$year 年 $unit 月"
        }
    }
}
