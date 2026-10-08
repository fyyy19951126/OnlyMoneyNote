package com.dafeng.onlymoneynote.ui

import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dafeng.onlymoneynote.data.local.CategoryEntity
import com.dafeng.onlymoneynote.data.local.CategoryStat
import com.dafeng.onlymoneynote.data.local.TxType
import com.dafeng.onlymoneynote.data.local.TransactionEntity
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
    val summary: StateFlow<Pair<Long, Long>> =
        ledger.observeMonthSummary()
            .combine(MutableStateFlow(Unit)) { list, _ ->
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

    fun saveLastUsed(categoryId: Long, type: Int) {
        viewModelScope.launch { settings.saveLastUsed(categoryId, type) }
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

    /** 检查更新的结果：只有「有新版」才进这个 state（要弹窗），其余走 toast */
    private val _updateResult = MutableStateFlow<UpdateChecker.Result?>(null)
    val updateResult: StateFlow<UpdateChecker.Result?> = _updateResult.asStateFlow()

    fun clearUpdateResult() {
        _updateResult.value = null
    }

    /**
     * 启动时静默检查更新：有新版就记进 updateResult（关于图标亮红点），
     * 没配地址 / 连不上 / 已是最新都**不出声** —— 开屏弹 toast 很烦。
     */
    fun checkUpdateSilently() {
        viewModelScope.launch {
            val current = runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
                    ?: "0"
            }.getOrDefault("0")
            val r = UpdateChecker.check(current)
            if (r is UpdateChecker.Result.Newer) _updateResult.value = r
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
            val barsSource: kotlinx.coroutines.flow.Flow<List<BarPoint>> = when (mode) {
                RangeMode.MONTH -> {
                    val ys = yearRange(year)
                    ledger.observeRange(ys.first, ys.second).let { rangeFlow ->
                        combine(rangeFlow, ledger.observeRange(start, end)) { all, _ ->
                            (1..12).map { m ->
                                val (s, e) = monthRange(year, m)
                                val inMonth = all.filter { it.dateMillis in s until e }
                                BarPoint(
                                    label = "${m}月",
                                    expense = inMonth.filter { it.type == TxType.EXPENSE.value }.sumOf { it.amountCents },
                                    income = inMonth.filter { it.type == TxType.INCOME.value }.sumOf { it.amountCents }
                                )
                            }
                        }
                    }
                }
                RangeMode.QUARTER -> {
                    val ys = yearRange(year)
                    ledger.observeRange(ys.first, ys.second).let { rangeFlow ->
                        combine(rangeFlow, ledger.observeRange(start, end)) { all, _ ->
                            (1..4).map { q ->
                                val (s, e) = quarterRange(year, q)
                                val inQ = all.filter { it.dateMillis in s until e }
                                BarPoint(
                                    label = "Q$q",
                                    expense = inQ.filter { it.type == TxType.EXPENSE.value }.sumOf { it.amountCents },
                                    income = inQ.filter { it.type == TxType.INCOME.value }.sumOf { it.amountCents }
                                )
                            }
                        }
                    }
                }
                RangeMode.YEAR -> {
                    val startY = year - 4
                    val (s, e) = yearRange(startY).first to yearRange(year).second
                    ledger.observeRange(s, e).let { rangeFlow ->
                        combine(rangeFlow, ledger.observeRange(start, end)) { all, _ ->
                            (startY..year).map { y ->
                                val (ys, ye) = yearRange(y)
                                val inY = all.filter { it.dateMillis in ys until ye }
                                BarPoint(
                                    label = "$y",
                                    expense = inY.filter { it.type == TxType.EXPENSE.value }.sumOf { it.amountCents },
                                    income = inY.filter { it.type == TxType.INCOME.value }.sumOf { it.amountCents }
                                )
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
        reimbursed: Boolean = false
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
                    reimbursed = reimbursed
                )
            )
            // 记住这次用的分类，下次记一笔自动带出来
            settings.saveLastUsed(categoryId, type.value)
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
        reimbursed: Boolean = false
    ) {
        val amount = parseAmountToCents(amountYuan)
        if (amount == null || amount <= 0) {
            showToast("金额填写不对，检查一下")
            return
        }
        viewModelScope.launch {
            ledger.updateTransaction(
                TransactionEntity(
                    id = id,
                    amountCents = amount,
                    type = type.value,
                    categoryId = categoryId,
                    dateMillis = dateMillis,
                    note = note.trim(),
                    reimbursed = reimbursed
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
                appContext.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
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
                val plan = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.dafeng.onlymoneynote.data.importer.RecordsCsvImporter
                        .buildPlan(com.dafeng.onlymoneynote.data.importer.RecordsCsvImporter.decode(bytes))
                }
                if (plan.txs.isEmpty()) {
                    _toast.value = "这个文件里没解析出账单，确认是记账软件导出的 CSV"
                    return@launch
                }
                val newCats = ledger.importPlan(plan)
                refreshWidgets()
                _toast.value = if (newCats > 0)
                    "已导入 ${plan.txs.size} 笔账单，新增 $newCats 个分类"
                else
                    "已导入 ${plan.txs.size} 笔账单，分类全部命中、未改动"
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
