package com.dafeng.onlymoneynote.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.dafeng.onlymoneynote.data.importer.RecordsCsvImporter
import kotlinx.coroutines.flow.Flow

data class TxWithCategory(
    val id: Long,
    val amountCents: Long,
    val type: Int,
    val categoryId: Long,
    val categoryName: String,
    val parentName: String?,
    val iconKey: String,
    val dateMillis: Long,
    val note: String,
    val reimbursed: Boolean,
    /** 图标块配色 key：优先取一级分类的自定义色；空 = 按一级图标的分组色 */
    val colorKey: String = "",
    /** 一级分类的图标 key（本身是一级分类时就是自己的），用来兜底算分组色 */
    val parentIconKey: String = "more_horiz",
    /** 资金账户 id + 名称（编辑账单时要带回原账户，列表里也要显示） */
    val accountId: Long = AccountEntity.UNSPECIFIED_ID,
    val accountName: String = AccountEntity.UNSPECIFIED_NAME
)

/** 报销汇总：按收支类型分组的报销金额 + 笔数 */
data class ReimburseStat(
    val type: Int,
    val totalCents: Long,
    val count: Int
)

data class MonthlySummary(
    val type: Int,
    val totalCents: Long
)

/** 按分类聚合的结果，用于统计页的占比排行 */
data class CategoryStat(
    val categoryId: Long,
    val name: String,
    val iconKey: String,
    val parentName: String?,
    val totalCents: Long,
    val count: Int,
    /** 图标块配色 key：优先取一级分类的自定义色；空 = 按一级图标的分组色 */
    val colorKey: String = "",
    /** 一级分类的图标 key（本身是一级分类时就是自己的） */
    val parentIconKey: String = "more_horiz"
)

/** 按时间桶聚合的结果：bucketKey 由调用方定义（如 "2026-10" 或 "2026-Q4"） */
data class TimeBucketStat(
    val bucketKey: String,
    val type: Int,
    val totalCents: Long
)

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    @Insert
    suspend fun insertAll(list: List<TransactionEntity>)

    @Update
    suspend fun update(tx: TransactionEntity)

    @Delete
    suspend fun delete(tx: TransactionEntity)

    @Query("DELETE FROM `transaction` WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM `transaction` ORDER BY dateMillis DESC, id DESC")
    suspend fun getAllOnce(): List<TransactionEntity>

    @Query("DELETE FROM `transaction`")
    suspend fun clearAll()

    @Query("SELECT * FROM category")
    suspend fun getAllCategoriesOnce(): List<CategoryEntity>

    @Insert
    suspend fun insertCategory(category: CategoryEntity): Long

    @Query("SELECT * FROM account")
    suspend fun getAllAccountsOnce(): List<AccountEntity>

    @Insert
    suspend fun insertTransactions(list: List<TransactionEntity>)

    /**
     * 导入账单：清掉旧账单、写入文件里的账单，**分类只补缺、不覆盖**。
     *
     * 同类型同层级同名的分类视为已存在，原样保留 —— 用户自己改过的 emoji 图标、
     * 配色、排序都不会被文件里那套静态图标冲掉。文件里有、库里没有的才新建，
     * 新分类用 [DefaultCategories.defaultIconKey] 挑的默认图标。
     * 整段在**一个事务**里，中间失败整体回滚。
     * @return 本次新建的分类数（一级 + 二级）
     */
    @Transaction
    suspend fun importPlan(plan: RecordsCsvImporter.Plan): Int {
        clearAll()
        // 库里已有的 + 这一轮刚建出来的，一起当「是否已存在」的判断依据
        val live = getAllCategoriesOnce().toMutableList()
        val accounts = getAllAccountsOnce()
        val idByKey = HashMap<String, Long>()
        var created = 0

        suspend fun create(name: String, parentId: Long?, type: Int, parentName: String?): CategoryEntity {
            val icon = DefaultCategories.defaultIconKey(name, type, parentName)
            val sortOrder = live.filter { it.parentId == parentId && it.type == type }
                .maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
            val row = CategoryEntity(
                name = name, iconKey = icon, parentId = parentId,
                sortOrder = sortOrder, builtIn = false, type = type
            )
            val saved = row.copy(id = insertCategory(row))
            live.add(saved)
            return saved
        }

        plan.parents.forEach { p ->
            val parent = live.firstOrNull {
                it.parentId == null && it.type == p.type && it.name == p.name
            } ?: create(p.name, null, p.type, null).also { created++ }

            p.children.forEach { c ->
                val child = live.firstOrNull {
                    it.parentId == parent.id && it.type == p.type && it.name == c.name
                } ?: create(c.name, parent.id, p.type, p.name).also { created++ }
                idByKey["${p.type}|${p.name}|${c.name}"] = child.id
            }
        }

        val rows = plan.txs.mapNotNull { t ->
            val cid = idByKey["${t.type}|${t.parent}|${t.child}"] ?: return@mapNotNull null
            TransactionEntity(
                amountCents = t.cents,
                type = t.type,
                categoryId = cid,
                dateMillis = t.dateMillis,
                note = t.note,
                reimbursed = t.reimbursed,
                // 账户按名字认，认不出来归「未指定」：导入不该顺手造出一堆同名账户
                accountId = t.accountName.trim()
                    .let { name -> accounts.firstOrNull { it.name == name }?.id }
                    ?: AccountEntity.UNSPECIFIED_ID
            )
        }
        insertTransactions(rows)
        return created
    }

    @Query(
        """
        SELECT t.id AS id, t.amountCents AS amountCents, t.type AS type,
               t.categoryId AS categoryId,
               COALESCE(c.name, '未分类') AS categoryName,
               p.name AS parentName,
               COALESCE(c.iconKey, 'more_horiz') AS iconKey,
               COALESCE(p.colorKey, c.colorKey, '') AS colorKey,
               COALESCE(p.iconKey, c.iconKey, 'more_horiz') AS parentIconKey,
               t.dateMillis AS dateMillis, t.note AS note,
               t.reimbursed AS reimbursed,
               t.accountId AS accountId,
               COALESCE(a.name, '未指定') AS accountName
        FROM `transaction` t
        LEFT JOIN category c ON c.id = t.categoryId
        LEFT JOIN category p ON p.id = c.parentId
        LEFT JOIN account a ON a.id = t.accountId
        ORDER BY t.dateMillis DESC, t.id DESC
        """
    )
    fun observeAll(): Flow<List<TxWithCategory>>

    @Query(
        """
        SELECT type AS type, COALESCE(SUM(amountCents), 0) AS totalCents
        FROM `transaction`
        WHERE dateMillis >= :startMillis AND dateMillis < :endMillis
        GROUP BY type
        """
    )
    fun observeSummary(startMillis: Long, endMillis: Long): Flow<List<MonthlySummary>>

    /** 区间内的分类聚合，按金额倒序。用于统计页占比排行。 */
    @Query(
        """
        SELECT t.categoryId AS categoryId,
               COALESCE(c.name, '未分类') AS name,
               COALESCE(c.iconKey, 'more_horiz') AS iconKey,
               p.name AS parentName,
               COALESCE(p.colorKey, c.colorKey, '') AS colorKey,
               COALESCE(p.iconKey, c.iconKey, 'more_horiz') AS parentIconKey,
               COALESCE(SUM(t.amountCents), 0) AS totalCents, COUNT(*) AS count
        FROM `transaction` t
        LEFT JOIN category c ON c.id = t.categoryId
        LEFT JOIN category p ON p.id = c.parentId
        WHERE t.dateMillis >= :startMillis AND t.dateMillis < :endMillis
          AND t.type = :type
        GROUP BY t.categoryId
        ORDER BY totalCents DESC
        """
    )
    fun observeCategoryStats(
        startMillis: Long,
        endMillis: Long,
        type: Int
    ): Flow<List<CategoryStat>>

    /** 区间内的原始账单，交给上层按年/季/月自行分桶（SQLite 的日期函数对时区不友好）。 */
    @Query(
        """
        SELECT * FROM `transaction`
        WHERE dateMillis >= :startMillis AND dateMillis < :endMillis
        ORDER BY dateMillis ASC
        """
    )
    fun observeRange(startMillis: Long, endMillis: Long): Flow<List<TransactionEntity>>

    /** 区间内的账单，带分类名（统计页展开看单笔明细用）。 */
    @Query(
        """
        SELECT t.id AS id, t.amountCents AS amountCents, t.type AS type,
               t.categoryId AS categoryId,
               COALESCE(c.name, '未分类') AS categoryName,
               p.name AS parentName,
               COALESCE(c.iconKey, 'more_horiz') AS iconKey,
               COALESCE(p.colorKey, c.colorKey, '') AS colorKey,
               COALESCE(p.iconKey, c.iconKey, 'more_horiz') AS parentIconKey,
               t.dateMillis AS dateMillis, t.note AS note,
               t.reimbursed AS reimbursed,
               t.accountId AS accountId,
               COALESCE(a.name, '未指定') AS accountName
        FROM `transaction` t
        LEFT JOIN category c ON c.id = t.categoryId
        LEFT JOIN category p ON p.id = c.parentId
        LEFT JOIN account a ON a.id = t.accountId
        WHERE t.dateMillis >= :startMillis AND t.dateMillis < :endMillis
        ORDER BY t.dateMillis DESC, t.id DESC
        """
    )
    fun observeRangeWithCategory(startMillis: Long, endMillis: Long): Flow<List<TxWithCategory>>

    /** 某个分类（含其子分类）名下的账单数。删分类前先查，避免产生孤儿账单。 */
    @Query(
        """
        SELECT COUNT(*) FROM `transaction`
        WHERE categoryId = :categoryId
           OR categoryId IN (SELECT id FROM category WHERE parentId = :categoryId)
        """
    )
    suspend fun countTransactionsInCategory(categoryId: Long): Int

    /** 把一个分类名下的账单全部转移到另一个分类（删分类前的转移）。 */
    @Query(
        "UPDATE `transaction` SET categoryId = :toCategoryId WHERE categoryId = :fromCategoryId"
    )
    suspend fun moveTransactions(fromCategoryId: Long, toCategoryId: Long)

    /** 有账单数据的时间范围，用于统计页定位年份。 */
    @Query("SELECT MIN(dateMillis) AS minAt, MAX(dateMillis) AS maxAt FROM `transaction`")
    fun observeDateBounds(): Flow<DateBounds>

    /** 全部报销账单，带分类名。首页「报销」入口点进来就看这个。 */
    @Query(
        """
        SELECT t.id AS id, t.amountCents AS amountCents, t.type AS type,
               t.categoryId AS categoryId,
               COALESCE(c.name, '未分类') AS categoryName,
               p.name AS parentName,
               COALESCE(c.iconKey, 'more_horiz') AS iconKey,
               COALESCE(p.colorKey, c.colorKey, '') AS colorKey,
               COALESCE(p.iconKey, c.iconKey, 'more_horiz') AS parentIconKey,
               t.dateMillis AS dateMillis, t.note AS note,
               t.reimbursed AS reimbursed,
               t.accountId AS accountId,
               COALESCE(a.name, '未指定') AS accountName
        FROM `transaction` t
        LEFT JOIN category c ON c.id = t.categoryId
        LEFT JOIN category p ON p.id = c.parentId
        LEFT JOIN account a ON a.id = t.accountId
        WHERE t.reimbursed = 1
        ORDER BY t.dateMillis DESC, t.id DESC
        """
    )
    fun observeReimbursed(): Flow<List<TxWithCategory>>

    /** 报销汇总：按收支类型分组。首页那块「报销」数字就是它。 */
    @Query(
        """
        SELECT type AS type,
               COALESCE(SUM(amountCents), 0) AS totalCents,
               COUNT(*) AS count
        FROM `transaction`
        WHERE reimbursed = 1
        GROUP BY type
        """
    )
    fun observeReimburseStats(): Flow<List<ReimburseStat>>

    /**
     * 每个账户名下的净流水（收入加、支出减）+ 笔数，全时段。
     * 当前余额 = account.initialCents + netCents，所以删改账单会自动反映，无需补偿。
     */
    @Query(
        """
        SELECT t.accountId AS accountId,
               COALESCE(SUM(CASE WHEN t.type = 1 THEN t.amountCents ELSE -t.amountCents END), 0) AS netCents,
               COUNT(*) AS count
        FROM `transaction` t
        GROUP BY t.accountId
        """
    )
    fun observeAccountNet(): Flow<List<AccountNet>>

    /** 每个账户在区间内的收入 / 支出，账户统计页的「本月」两列。 */
    @Query(
        """
        SELECT t.accountId AS accountId,
               COALESCE(SUM(CASE WHEN t.type = 0 THEN t.amountCents ELSE 0 END), 0) AS expenseCents,
               COALESCE(SUM(CASE WHEN t.type = 1 THEN t.amountCents ELSE 0 END), 0) AS incomeCents,
               COUNT(*) AS count
        FROM `transaction` t
        WHERE t.dateMillis >= :startMillis AND t.dateMillis < :endMillis
        GROUP BY t.accountId
        """
    )
    fun observeAccountPeriodStats(startMillis: Long, endMillis: Long): Flow<List<AccountPeriodStat>>

    /** 删账户前先看它名下有没有账单。 */
    @Query("SELECT COUNT(*) FROM `transaction` WHERE accountId = :accountId")
    suspend fun countTransactionsInAccount(accountId: Long): Int

    /** 把一个账户名下的账单转移到另一个账户（删账户前的转移）。 */
    @Query("UPDATE `transaction` SET accountId = :toAccountId WHERE accountId = :fromAccountId")
    suspend fun moveTransactionsToAccount(fromAccountId: Long, toAccountId: Long)
}

data class DateBounds(val minAt: Long?, val maxAt: Long?)

/** 某个账户名下的净流水：收入记正、支出记负（单位「分」） */
data class AccountNet(
    val accountId: Long,
    val netCents: Long,
    val count: Int
)

/** 某个账户在区间内的收入 / 支出（都是正数，单位「分」） */
data class AccountPeriodStat(
    val accountId: Long,
    val expenseCents: Long,
    val incomeCents: Long,
    val count: Int
)

@Dao
interface AccountDao {

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Query("DELETE FROM account WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM account ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM account ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllOnce(): List<AccountEntity>

    @Query("SELECT COUNT(*) FROM account")
    suspend fun count(): Int

    @Query("DELETE FROM account")
    suspend fun clearAll()
}

@Dao
interface CategoryDao {

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("DELETE FROM category WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM category ORDER BY parentId IS NOT NULL, sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category ORDER BY parentId IS NOT NULL, sortOrder ASC, id ASC")
    suspend fun getAllOnce(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM category")
    suspend fun count(): Int

    @Query("DELETE FROM category")
    suspend fun clearAll()
}
