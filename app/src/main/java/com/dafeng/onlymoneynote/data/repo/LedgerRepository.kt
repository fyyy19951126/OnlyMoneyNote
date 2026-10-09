package com.dafeng.onlymoneynote.data.repo

import com.dafeng.onlymoneynote.data.importer.RecordsCsvImporter
import com.dafeng.onlymoneynote.data.local.AccountDao
import com.dafeng.onlymoneynote.data.local.AccountEntity
import com.dafeng.onlymoneynote.data.local.AccountPeriodStat
import com.dafeng.onlymoneynote.data.local.CategoryDao
import com.dafeng.onlymoneynote.data.local.CategoryEntity
import com.dafeng.onlymoneynote.data.local.DefaultAccounts
import com.dafeng.onlymoneynote.data.local.DefaultCategories
import com.dafeng.onlymoneynote.data.local.MonthlySummary
import com.dafeng.onlymoneynote.data.local.TransactionDao
import com.dafeng.onlymoneynote.data.local.TransactionEntity
import com.dafeng.onlymoneynote.data.local.TxWithCategory
import kotlinx.coroutines.flow.Flow
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LedgerRepository @Inject constructor(
    private val txDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val accountDao: AccountDao
) {

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

    suspend fun addAccount(account: AccountEntity): Long = accountDao.insert(account)

    suspend fun updateAccount(account: AccountEntity) = accountDao.update(account)

    suspend fun deleteAccount(id: Long) = accountDao.deleteById(id)

    suspend fun countTransactionsInAccount(id: Long) = txDao.countTransactionsInAccount(id)

    /** 删账户前把它名下的账单转移到目标账户（一般是「未指定」）。 */
    suspend fun moveTransactionsToAccount(from: Long, to: Long) =
        txDao.moveTransactionsToAccount(from, to)

    suspend fun accountsOnce(): List<AccountEntity> = accountDao.getAllOnce()

    suspend fun clearAccounts() = accountDao.clearAll()

    /**
     * 首次启动写入内置账户。「未指定」用固定 id=[AccountEntity.UNSPECIFIED_ID] 插入，
     * 和 v4→v5 迁移里那条完全一致，老账单的默认 accountId 才指得对。
     */
    suspend fun seedAccountsIfEmpty() {
        if (accountDao.count() > 0) return
        DefaultAccounts.all().forEachIndexed { i, seed ->
            accountDao.insert(
                AccountEntity(
                    id = if (seed.builtIn) AccountEntity.UNSPECIFIED_ID else 0,
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

    /** 全部报销账单（首页「报销」入口点进来就显示这个）。 */
    fun observeReimbursed() = txDao.observeReimbursed()

    /** 报销汇总，按收支类型分组。 */
    fun observeReimburseStats() = txDao.observeReimburseStats()

    suspend fun updateCategory(category: CategoryEntity) = categoryDao.update(category)

    fun observeMonthSummary(): Flow<List<MonthlySummary>> {
        val (start, end) = currentMonthRange()
        return txDao.observeSummary(start, end)
    }

    suspend fun addTransaction(tx: TransactionEntity) = txDao.insert(tx)

    suspend fun updateTransaction(tx: TransactionEntity) = txDao.update(tx)

    suspend fun deleteTransaction(id: Long) = txDao.deleteById(id)

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
