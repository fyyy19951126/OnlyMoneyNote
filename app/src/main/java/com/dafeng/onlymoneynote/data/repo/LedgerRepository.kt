package com.dafeng.onlymoneynote.data.repo

import com.dafeng.onlymoneynote.data.importer.RecordsCsvImporter
import androidx.room.withTransaction
import com.dafeng.onlymoneynote.data.local.AccountDao
import com.dafeng.onlymoneynote.data.local.AccountEntity
import com.dafeng.onlymoneynote.data.local.AccountPeriodStat
import com.dafeng.onlymoneynote.data.local.CategoryDao
import com.dafeng.onlymoneynote.data.local.CategoryEntity
import com.dafeng.onlymoneynote.data.local.DefaultAccounts
import com.dafeng.onlymoneynote.data.local.AppDatabase
import com.dafeng.onlymoneynote.data.local.DefaultCategories
import com.dafeng.onlymoneynote.data.local.MonthlySummary
import com.dafeng.onlymoneynote.data.local.TransactionDao
import com.dafeng.onlymoneynote.data.local.TransactionEntity
import com.dafeng.onlymoneynote.data.local.TxType
import com.dafeng.onlymoneynote.data.local.TxWithCategory
import kotlinx.coroutines.flow.Flow
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 冲销账单的 note 前缀，后面紧跟原账单 id（见 [LedgerRepository.reimburseWithOffset]）。
 * 放顶层而不是 companion：Repository 本身不是 object，companion const 会有编译问题。
 */
const val OFFSET_MARK = "报销冲销"

/**
 * 拼冲销账单的 note：`报销冲销#<原账单id>（报销于 yyyy-MM-dd）`。
 *
 * 拆两截的原因：前缀 + 原 id 给程序认（撤销、级联删除都靠它精确定位），
 * 括号里的日期给人看 —— 什么时候报的，是核对报销单据时最该看的信息。
 * 没有报销日期时（刚点报销、日期未知）就不加括号，避免留下空壳。
 */
fun offsetNote(originId: Long, reimburseDateMillis: Long): String {
    val prefix = "$OFFSET_MARK#$originId"
    if (reimburseDateMillis <= 0L) return prefix
    val y = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
        .format(java.util.Date(reimburseDateMillis))
    return "$prefix（报销于 $y）"
}

/**
 * 从冲销账单 note 里解析出原账单 id。不是冲销行就返回 null。
 *
 * 格式是 `报销冲销#<id>（报销于 yyyy-MM-dd）`，所以只取「#」到「（」之间的数字，
 * 不能整段 toLongOrNull —— 后面挂着日期会解析失败。
 */
fun originIdFromNote(note: String): Long? {
    if (!note.startsWith("$OFFSET_MARK#")) return null
    val body = note.removePrefix("$OFFSET_MARK#")
    val digits = body.takeWhile { it.isDigit() }
    if (digits.isEmpty()) return null
    return digits.toLongOrNull()
}

@Singleton
class LedgerRepository @Inject constructor(
    private val txDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val accountDao: AccountDao,
    /**
     * 报销/撤销报销要「标记 + 插冲销账单」两步原子完成，
     * Room 的 @Transaction 需要 database 实例，Dao 里拿不到。
     */
    private val db: AppDatabase
) {

    /**
 * 首页账单列表，**含冲销行**。
 *
 * 冲销行必须留着：它把已报销的钱从总支出里扣回来，导出 CSV 也是走这条流，
 * 排掉的话导出给 Excel 的支出总额会虚高（实测差 75988.81）。行数翻倍是这笔钱
 * 本来就花出去又收回来的如实反映 —— 日期跟原单一致，备注写清冲销日期。
 */
    fun observeTransactions(): Flow<List<TxWithCategory>> = txDao.observeAll()

    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    // ============ 账户 ============

    fun observeAccounts(): Flow<List<AccountEntity>> = accountDao.observeAll()

    /** 每个账户的全时段净流水（余额 = 期初 + 这个值） */
    fun observeAccountNet() = txDao.observeAccountNet()

    /** 本月每个账户的收入 / 支出 */
    fun observeAccountMonthStats(): Flow<List<AccountPeriodStat>> {
        val (start, end) = currentMonthRange()
        return txDao.observeAccountPeriodStats(start, end)
    }

    /** 全时段转账列表（转账页） */
    fun observeTransfers() = txDao.observeTransfers()

    suspend fun addAccount(account: AccountEntity): Long = accountDao.insert(account)

    suspend fun updateAccount(account: AccountEntity) = accountDao.update(account)

    suspend fun deleteAccount(id: Long) = accountDao.deleteById(id)

    suspend fun countTransactionsInAccount(id: Long) = txDao.countTransactionsInAccount(id)

    /** 删账户前把它名下的账单转移到目标账户（一般是「未指定」）。 */
    suspend fun moveTransactionsToAccount(from: Long, to: Long) =
        txDao.moveTransactionsToAccount(from, to)

    /** 删账户时清掉它作为**转账转入方**的指向 */
    suspend fun clearToAccount(accountId: Long) = txDao.clearToAccount(accountId)

    /**
     * 批量把选中的一批账单改到目标账户下，返回实际改动条数。
     *
     * 只改 accountId 一列 —— 金额、分类、报销标记一律不碰，
     * 免得批量操作顺手把别的东西改坏。余额是现算的，改完自动重算。
     */
    suspend fun assignTransactionsToAccount(ids: List<Long>, toAccountId: Long): Int {
        if (ids.isEmpty()) return 0
        // 走分批版本：全选几千条时单条 IN (:ids) 会撞上 SQLite 的 999 变量上限
        return txDao.assignTransactionsToAccountSafely(ids, toAccountId)
    }

    /** 批量把一批账单改到某个分类下，返回实际改动条数 */
    suspend fun assignTransactionsToCategory(ids: List<Long>, toCategoryId: Long): Int {
        if (ids.isEmpty() || toCategoryId <= 0L) return 0
        return txDao.assignTransactionsToCategorySafely(ids, toCategoryId)
    }

    suspend fun accountsOnce(): List<AccountEntity> = accountDao.getAllOnce()

    /**
     * 按名字找账户 id；找不到返回 null。
     *
     * 导入第三方账单时用来把「支付宝」这类**账户名**对应到本机的 accountId。
     * 故意**不新建账户**：文件里叫「现金」的不代表用户想要一个叫「现金」的账户，
     * 认不出来就归「未指定」，之后可以用「整理」批量改。
     */
    suspend fun findAccountByName(name: String): Long? {
        val n = name.trim()
        if (n.isEmpty()) return null
        return accountDao.getAllOnce().firstOrNull { it.name == n }?.id
    }

    suspend fun clearAccounts() = accountDao.clearAll()

    /**
     * 首次启动写入内置账户。「未指定」用固定 id=[AccountEntity.UNSPECIFIED_ID] 插入，
     * 和 v4→v5 迁移里那条完全一致，老账单的默认 accountId 才指得对。
     */
    suspend fun seedAccountsIfEmpty() {
        if (accountDao.count() > 0) return
        // ⚠️ 不能用「builtIn 就写 UNSPECIFIED_ID、否则写 0」这种写法：
        // 万一以后在 DefaultAccounts 里加了第二个 builtIn = true，
        // 两行都会插 id=1，主键冲突直接崩。这里显式保证「固定 id 只用一次」。
        var fixedIdUsed = false
        DefaultAccounts.all().forEachIndexed { i, seed ->
            val wantFixedId = seed.builtIn && !fixedIdUsed
            if (wantFixedId) fixedIdUsed = true
            accountDao.insert(
                AccountEntity(
                    id = if (wantFixedId) AccountEntity.UNSPECIFIED_ID else 0,
                    name = seed.name,
                    iconKey = seed.iconKey,
                    sortOrder = i,
                    builtIn = seed.builtIn,
                    colorKey = seed.colorKey
                )
            )
        }
    }

    fun observeRange(start: Long, end: Long) = txDao.observeRange(start, end)

    fun observeRangeWithCategory(start: Long, end: Long) = txDao.observeRangeWithCategory(start, end)

    fun observeCategoryStats(start: Long, end: Long, type: Int) =
        txDao.observeCategoryStats(start, end, type)

    fun observeDateBounds() = txDao.observeDateBounds()

    /** 报销池里的全部账单（不管报没报过）。报销页两个 tab 都从这里切。 */
    fun observeReimbursePool() = txDao.observeReimbursePool()

    /**
     * 未报销总额（结余后面括号里的那个数）。参数跟统计页的筛选范围一致。
     * `catsEmpty=1` 时忽略 `cats`，避开 SQLite「IN ()」非法语法。
     */
    fun observeUnreimbursedCents(
        from: Long?,
        to: Long?,
        cats: List<Long>
    ) = txDao.observeUnreimbursedCents(from, to, if (cats.isEmpty()) 1 else 0, cats)

    /** 标记一笔已报销 + 记下实际报销日期。右滑报销时调。 */
    suspend fun markReimbursed(id: Long, at: Long) = txDao.markReimbursed(id, at)

    /** 退回未报销。删掉冲销记录后要跟着调，否则状态和账单对不上。 */
    suspend fun unmarkReimbursed(id: Long) = txDao.unmarkReimbursed(id)

    /**
     * 报销：标记原账单 + 插入一条冲销账单，**一个事务里做完**。
     *
     * 冲销账单 = 当天 / 类型支出 / 金额取负 / 分类跟原账单一样 / note 带 [OFFSET_MARK] 前缀。
     * 带上原账单 id 是为了能精确找回它——靠 id 认，不靠分类+金额猜
     * （同一个分类同一天两笔一样的金额是常事，靠猜会删错）。
     */
    suspend fun reimburseWithOffset(tx: TxWithCategory, now: Long) {
        db.withTransaction {
            txDao.markReimbursed(tx.id, now)
            txDao.insert(
                TransactionEntity(
                    amountCents = -tx.amountCents,
                    type = TxType.EXPENSE.value,
                    categoryId = tx.categoryId,
                    dateMillis = tx.dateMillis,
                    note = offsetNote(tx.id, now),
                    reimbursed = true,
                    // 冲销行留在报销池（isReimburse = true）：报销页「已报销」tab
                    // 展示的就是这些负支出行，用户一眼能看到「报回来多少钱」。
                    // 原账单那一行在 UI 层过滤掉，避免同一笔在 tab 里出现两次。
                    isReimburse = true,
                    reimburseDateMillis = now,
                    accountId = tx.accountId,
                    toAccountId = 0L
                )
            )
        }
    }

    /**
     * 撤销报销：删掉那条冲销账单，原账单退回未报销。也在一个事务里。
     *
     * [tx] 是**冲销行**（报销页「已报销」tab 展示的就是它，金额为负）。
     * 它的 id 不是原账单的 id，原 id 存在 note 里 —— 这里解析出来再操作，
     * 否则会拿冲销行的 id 去 mark/unmark，原账单的报销状态永远退不回来。
     */
    suspend fun unreimburseAndDropOffset(tx: TxWithCategory) {
        db.withTransaction {
            val originId = originIdOfOffset(tx) ?: tx.id
            txDao.deleteById(tx.id)
            txDao.deleteByNotePrefix("$OFFSET_MARK#$originId")
            txDao.unmarkReimbursed(originId)
        }
    }

    /**
     * 从冲销行的 note 里解析出原账单 id。解析不出来返回 null
     * （比如用户手记了一笔负数支出、note 没这个前缀，那就不是冲销行）。
     */
    fun originIdOfOffset(tx: TxWithCategory): Long? = originIdFromNote(tx.note)

    /** 报销汇总，按收支类型分组。 */
    fun observeReimburseStats() = txDao.observeReimburseStats()

    suspend fun updateCategory(category: CategoryEntity) = categoryDao.update(category)

    fun observeMonthSummary(): Flow<List<MonthlySummary>> {
        val (start, end) = currentMonthRange()
        return txDao.observeSummary(start, end)
    }

    suspend fun addTransaction(tx: TransactionEntity) = txDao.insert(tx)

    suspend fun updateTransaction(tx: TransactionEntity) = txDao.update(tx)

    /**
 * 改了原账单的金额 / 分类 / 日期后，把配对的冲销行同步过去。
 *
 * 冲销行是靠 note 里的原 id 认出来的（[originIdFromNote]），所以它能精确找到。
 * 不跟着改的话，原单改成 200 元、冲销行还挂着 -100，账就散了，
 * 撤销报销也找不出个对得上的记录。
 */
suspend fun syncOffsetOf(
        originId: Long,
        newAmountCents: Long,
        newCategoryId: Long,
        newDateMillis: Long
    ) {
        db.withTransaction {
            val offset = txDao.getByNotePrefix("$OFFSET_MARK#$originId") ?: return@withTransaction
            txDao.update(
                offset.copy(
                    amountCents = -newAmountCents,
                    categoryId = newCategoryId,
                    dateMillis = newDateMillis
                )
            )
        }
    }

    suspend fun deleteTransaction(id: Long) = txDao.deleteById(id)

    /**
     * 删账单，但先看它是不是冲销行。
     *
     * 是冲销行（note 带 [OFFSET_MARK] 前缀）就顺手把原账单退回未报销：
     * 报销页「已报销」tab 里能删到的都是冲销行，只删它会让原账单永远标着
     * 「已报销」，可支出没被冲回来 —— 统计金额凭空少一块，越用越对不上。
     * 一个事务里做完，失败一起回滚。
     */
    suspend fun deleteTransactionCascadeOffset(id: Long) {
        db.withTransaction {
            val tx = txDao.getById(id)
            if (tx != null && tx.note.startsWith("$OFFSET_MARK#")) {
                originIdFromNote(tx.note)
                    ?.let { originId ->
                        txDao.deleteByNotePrefix("$OFFSET_MARK#$originId")
                        txDao.unmarkReimbursed(originId)
                    }
            }
            txDao.deleteById(id)
        }
    }

    suspend fun addCategory(category: CategoryEntity) = categoryDao.insert(category)

    suspend fun deleteCategory(id: Long) = categoryDao.deleteById(id)

    suspend fun countTransactionsInCategory(id: Long) = txDao.countTransactionsInCategory(id)

    /** 把一个分类名下的账单全部转移到另一个分类（删分类前的转移）。 */
    suspend fun moveTransactions(fromCategoryId: Long, toCategoryId: Long) =
        txDao.moveTransactions(fromCategoryId, toCategoryId)

    suspend fun categoriesOnce(): List<CategoryEntity> = categoryDao.getAllOnce()

    suspend fun transactionsOnce(): List<TransactionEntity> = txDao.getAllOnce()

    suspend fun clearTransactions() = txDao.clearAll()

    suspend fun clearCategories() = categoryDao.clearAll()

    /** 首次启动写入默认分类；已有数据则跳过。 */
    suspend fun seedIfEmpty() {
        if (categoryDao.count() > 0) return
        var order = 0
        for (seed in DefaultCategories.all()) {
            val parentId = categoryDao.insert(
                CategoryEntity(
                    name = seed.name,
                    iconKey = seed.iconKey,
                    parentId = null,
                    sortOrder = order++,
                    builtIn = true,
                    type = seed.type
                )
            )
            seed.children.forEachIndexed { i, (childName, childIcon) ->
                categoryDao.insert(
                    CategoryEntity(
                        name = childName,
                        iconKey = childIcon,
                        parentId = parentId,
                        sortOrder = i,
                        builtIn = true,
                        type = seed.type
                    )
                )
            }
        }
    }

    /**
     * 用导入方案替换账单：清账单 → 写账单，分类只补缺不覆盖
     * （已存在的同名分类保持原图标、原配色、原顺序）。
     * 事务逻辑下放到 TransactionDao.importPlan（一个 @Transaction 方法），
     * 这样仓库层不用直接持有 AppDatabase，避免触发 Hilt 组件里那个
     * D8 生成失败、导致启动 NoSuchMethodError 的 synthetic 访问器。
     *
     * @return 本次新建的分类数（一级 + 二级）
     */
    suspend fun importPlan(plan: RecordsCsvImporter.Plan): Int = txDao.importPlan(plan)

    companion object {
        fun currentMonthRange(): Pair<Long, Long> {
            val cal = Calendar.getInstance()
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.add(Calendar.MONTH, 1)
            return start to cal.timeInMillis
        }
    }
}
