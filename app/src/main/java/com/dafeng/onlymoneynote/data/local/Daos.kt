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
    val accountName: String = AccountEntity.UNSPECIFIED_NAME,
    /** 账户头像：列表里金额左边画这个小图标，不带名字 */
    val accountIconKey: String = "emoji:💳",
    val accountColorKey: String = "",
    /**
     * 转账的转入方。非转账是 0，列表里画成「箭头 → 账户头像」。
     *
     * 加这三个字段而不是新开一张表：转账在列表、编辑、备份里都和普通账单同路，
     * 只是渲染方式不一样（不显示分类，多一个目标账户）。
     */
    val toAccountId: Long = 0L,
    val toAccountName: String = "",
    val toAccountIconKey: String = "",
    val toAccountColorKey: String = "",
    /**
     * 原始创建时间。编辑时必须带回 —— 不带的话 [TransactionEntity.createdAt]
     * 会走默认值 `System.currentTimeMillis()`，等于每编辑一次就把
     * 「创建于什么时候」改成现在。
     */
    val createdAt: Long = 0L
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
               t.createdAt AS createdAt,
               t.accountId AS accountId,
               COALESCE(a.name, '未指定') AS accountName,
               COALESCE(a.iconKey, 'emoji:💳') AS accountIconKey,
               COALESCE(a.colorKey, '') AS accountColorKey,
               t.toAccountId AS toAccountId,
               COALESCE(a2.name, '') AS toAccountName,
               COALESCE(a2.iconKey, '') AS toAccountIconKey,
               COALESCE(a2.colorKey, '') AS toAccountColorKey
        FROM `transaction` t
        LEFT JOIN category c ON c.id = t.categoryId
        LEFT JOIN category p ON p.id = c.parentId
        LEFT JOIN account a ON a.id = t.accountId
        LEFT JOIN account a2 ON a2.id = t.toAccountId
        ORDER BY t.dateMillis DESC, t.id DESC
        """
    )
    fun observeAll(): Flow<List<TxWithCategory>>

    @Query(
        """
        SELECT type AS type, COALESCE(SUM(amountCents), 0) AS totalCents
        FROM `transaction`
        WHERE dateMillis >= :startMillis AND dateMillis < :endMillis
          AND type != 2
        GROUP BY type
        """
    )
    fun observeSummary(startMillis: Long, endMillis: Long): Flow<List<MonthlySummary>>

    /** 区间内的分类聚合，按金额倒序。用于统计页占比排行。只统计支出 / 收入，转账没有分类 */
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

    /**
     * 全时段转账列表（转账页用）。
     *
     * 转账不参与收支统计，但要有地方看得见 —— 否则用户记了一笔转账
     * 在账单列表里找不到，会以为没记上。
     */
    @Query(
        """
        SELECT t.id AS id, t.amountCents AS amountCents, t.type AS type,
               t.categoryId AS categoryId,
               '' AS categoryName,
               NULL AS parentName,
               'more_horiz' AS iconKey,
               t.dateMillis AS dateMillis, t.note AS note,
               t.reimbursed AS reimbursed,
               t.accountId AS accountId,
               COALESCE(a.name, '未指定') AS accountName,
               COALESCE(a.iconKey, 'emoji:💳') AS accountIconKey,
               COALESCE(a.colorKey, '') AS accountColorKey,
               t.toAccountId AS toAccountId,
               COALESCE(a2.name, '') AS toAccountName,
               COALESCE(a2.iconKey, 'emoji:💳') AS toAccountIconKey,
               COALESCE(a2.colorKey, '') AS toAccountColorKey
        FROM `transaction` t
        LEFT JOIN account a ON a.id = t.accountId
        LEFT JOIN account a2 ON a2.id = t.toAccountId
        WHERE t.type = 2
        ORDER BY t.dateMillis DESC, t.id DESC
        """
    )
    fun observeTransfers(): Flow<List<TransferWithAccount>>

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
               t.createdAt AS createdAt,
               t.accountId AS accountId,
               COALESCE(a.name, '未指定') AS accountName,
               COALESCE(a.iconKey, 'emoji:💳') AS accountIconKey,
               COALESCE(a.colorKey, '') AS accountColorKey,
               t.toAccountId AS toAccountId,
               COALESCE(a2.name, '') AS toAccountName,
               COALESCE(a2.iconKey, '') AS toAccountIconKey,
               COALESCE(a2.colorKey, '') AS toAccountColorKey
        FROM `transaction` t
        LEFT JOIN category c ON c.id = t.categoryId
        LEFT JOIN category p ON p.id = c.parentId
        LEFT JOIN account a ON a.id = t.accountId
        LEFT JOIN account a2 ON a2.id = t.toAccountId
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
               t.createdAt AS createdAt,
               t.accountId AS accountId,
               COALESCE(a.name, '未指定') AS accountName,
               COALESCE(a.iconKey, 'emoji:💳') AS accountIconKey,
               COALESCE(a.colorKey, '') AS accountColorKey,
               t.toAccountId AS toAccountId,
               COALESCE(a2.name, '') AS toAccountName,
               COALESCE(a2.iconKey, '') AS toAccountIconKey,
               COALESCE(a2.colorKey, '') AS toAccountColorKey
        FROM `transaction` t
        LEFT JOIN category c ON c.id = t.categoryId
        LEFT JOIN category p ON p.id = c.parentId
        LEFT JOIN account a ON a.id = t.accountId
        LEFT JOIN account a2 ON a2.id = t.toAccountId
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
     * 每个账户名下的净流水（收入加、支出减、**转账按方向一加一减**）+ 笔数，全时段。
     * 当前余额 = account.initialCents + netCents，所以删改账单会自动反映，无需补偿。
     *
     * 转账不能跟收支混在一个 GROUP BY 里：转出方的钱要减、转入方的钱要加，
     * 同一行的 accountId / toAccountId 是两个不同账户，靠 `account` 表当驱动表
     * 才能让每个账户既算到「自己转出的」又算到「别人转进来的」。
     *
     * 对总资产没有影响（转出 −X、转入 +X 相抵），但单账户余额准了。
     */
    @Query(
        """
        SELECT acc.id AS accountId,
               COALESCE(f.net, 0) + COALESCE(tin.net, 0) - COALESCE(tout.net, 0) AS netCents,
               COALESCE(c.cnt, 0) AS count
        FROM `account` acc
        LEFT JOIN (
            -- 收支：转出方记在 accountId，收入加、支出减。转账（type=2）不算在这里
            SELECT accountId AS id,
                   SUM(CASE WHEN type = 1 THEN amountCents ELSE -amountCents END) AS net
            FROM `transaction`
            WHERE type != 2
            GROUP BY accountId
        ) f ON f.id = acc.id
        LEFT JOIN (
            -- 转进这个账户的钱（别名不能叫 into，INTO 是 SQL 关键字）
            SELECT toAccountId AS id, SUM(amountCents) AS net
            FROM `transaction`
            WHERE type = 2 AND toAccountId != 0
            GROUP BY toAccountId
        ) tin ON tin.id = acc.id
        LEFT JOIN (
            -- 从这个账户转出的钱
            SELECT accountId AS id, SUM(amountCents) AS net
            FROM `transaction`
            WHERE type = 2
            GROUP BY accountId
        ) tout ON tout.id = acc.id
        LEFT JOIN (
            SELECT accountId AS id, COUNT(*) AS cnt
            FROM `transaction`
            GROUP BY accountId
        ) c ON c.id = acc.id
        ORDER BY acc.sortOrder ASC, acc.id ASC
        """
    )
    fun observeAccountNet(): Flow<List<AccountNet>>

    /** 某个账户在区间内的收入 / 支出，账户统计页的「本月」两列。转账不算在内 */
    @Query(
        """
        SELECT t.accountId AS accountId,
               COALESCE(SUM(CASE WHEN t.type = 0 THEN t.amountCents ELSE 0 END), 0) AS expenseCents,
               COALESCE(SUM(CASE WHEN t.type = 1 THEN t.amountCents ELSE 0 END), 0) AS incomeCents,
               COUNT(*) AS count
        FROM `transaction` t
        WHERE t.dateMillis >= :startMillis AND t.dateMillis < :endMillis
          AND t.type != 2
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

    /**
     * 清掉某账户作为**转账转入方**的所有指向。
     *
     * 删账户时必须一起做：转账的 `accountId` 是转出方、`toAccountId` 是转入方，
     * 只处理前者的���，那些「转进这个账户」的转账就会指向一个不存在的 id，
     * 列表里 LEFT JOIN 查不到名字，渲染成「甲 → 」。
     * 置 0 = 不再是转账（余额计算会把这一列忽略掉）。
     */
    @Query("UPDATE `transaction` SET toAccountId = 0 WHERE toAccountId = :accountId")
    suspend fun clearToAccount(accountId: Long)

    /**
     * 批量改账户：把选中的这一批账单一次性挂到目标账户下。
     *
     * 2026-10-10 用户有 8000 条历史账单全部挂在「未指定」，逐条改不现实，
     * 所以账单列表加了多选 + 「设为账户」，一次 UPDATE 搞定几百上千条。
     *
     * 余额是现算的（期初 + 净流水），所以改完账户余额自动重算，不需要补偿。
     *
     * ⚠️ [ids] 会展开成 N 个占位符，而老版本 SQLite 的**变量上限是 999**。
     * 用户「全选」8000 条会直接 `SQLiteBindOrColumnIndexOutOfRangeException`。
     * 所以这里按 [SQL_MAX_VARIABLE_NUMBER] 分批 —— 见 [assignTransactionsToAccountSafely]。
     */
    @Query("UPDATE `transaction` SET accountId = :toAccountId WHERE id IN (:ids)")
    suspend fun assignTransactionsToAccount(ids: List<Long>, toAccountId: Long): Int

    /**
     * 批量改分类：把选中的一批账单挂到 [toCategoryId] 下。
     *
     * 只改 categoryId 一列，金额/账户/报销都不动。
     * 账单本来就都挂在二级分类上，所以 [toCategoryId] 应该是某个二级。
     */
    @Query("UPDATE `transaction` SET categoryId = :toCategoryId WHERE id IN (:ids)")
    suspend fun assignTransactionsToCategory(ids: List<Long>, toCategoryId: Long): Int

    /**
     * 分批跑 [assignTransactionsToAccount]，绕开 SQLite 的 999 变量上限。
     *
     * 老版本 SQLite（Android 上 3.8 之前那套）单条语句最多 999 个绑定变量，
     * `IN (:ids)` 展开后每个 id 都是一个。用户「全选」8000 条时会直接抛
     * `SQLiteBindOrColumnIndexOutOfRangeException`。分批是最省事的绕法，
     * 不用去查 SQLite 版本再决定策略。
     *
     * 每批 500：留足余量，将来 SQL 里再加几个别的绑定变量也不会顶到上限。
     */
    suspend fun assignTransactionsToAccountSafely(ids: List<Long>, toAccountId: Long): Int {
        var total = 0
        for (chunk in ids.chunked(SQL_MAX_VARIABLE_NUMBER)) {
            total += assignTransactionsToAccount(chunk, toAccountId)
        }
        return total
    }

    /** 同上，为「批量改分类」准备 */
    suspend fun assignTransactionsToCategorySafely(ids: List<Long>, toCategoryId: Long): Int {
        var total = 0
        for (chunk in ids.chunked(SQL_MAX_VARIABLE_NUMBER)) {
            total += assignTransactionsToCategory(chunk, toCategoryId)
        }
        return total
    }

    companion object {
        /** 单条 SQL 的绑定变量上限（保守取 500，远低于 SQLite 的 999 硬限） */
        const val SQL_MAX_VARIABLE_NUMBER = 500
    }
}

data class DateBounds(val minAt: Long?, val maxAt: Long?)

/** 某个账户名下的净流水：收入记正、支出记负（单位「分」） */
data class AccountNet(
    val accountId: Long,
    val netCents: Long,
    val count: Int
)

/** 某个账户在区间内的收入 / 支出（都是正数，单位「分」）。转账不算在内 */
data class AccountPeriodStat(
    val accountId: Long,
    val expenseCents: Long,
    val incomeCents: Long,
    val count: Int
)

/**
 * 一条转账：转出方 + 转入方。
 *
 * 转账没有分类（categoryId 固定 0），所以这里 category 相关字段是占位的。
 */
data class TransferWithAccount(
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
    /** 转出方 */
    val accountId: Long,
    val accountName: String,
    val accountIconKey: String,
    val accountColorKey: String,
    /** 转入方 */
    val toAccountId: Long,
    val toAccountName: String,
    val toAccountIconKey: String,
    val toAccountColorKey: String
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
