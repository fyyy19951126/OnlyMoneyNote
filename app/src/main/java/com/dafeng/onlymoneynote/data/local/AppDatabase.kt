package com.dafeng.onlymoneynote.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CategoryEntity::class, TransactionEntity::class, AccountEntity::class],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun accountDao(): AccountDao

    companion object {
        /**
         * v1 -> v2：category 表加 type 列（0=支出，1=收入）。
         * 旧数据按名字回填：内置的四个收入大类（及其子分类）标成收入，其余标支出。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `category` ADD COLUMN type INTEGER NOT NULL DEFAULT 0")
                // 收入大类：按名字找 id，把它和它的子分类都标成 1
                db.execSQL(
                    """
                    UPDATE `category` SET type = 1 WHERE
                        name IN ('职业收入', '投资理财', '副业', '其他收入')
                        OR parentId IN (SELECT id FROM `category` WHERE name IN ('职业收入', '投资理财', '副业', '其他收入'))
                    """.trimIndent()
                )
            }
        }

        /**
         * v2 -> v3：transaction 表加 reimbursed 列。
         * 旧账单一律当作「不是报销」，从 0 开始。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `transaction` ADD COLUMN reimbursed INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_reimbursed` ON `transaction` (`reimbursed`)")
            }
        }

        /**
         * v3 -> v4：category 表加 colorKey 列（自定义图标块配色）。
         * 旧数据全是空串 = 跟随图标分组色，观感跟迁移前一致。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `category` ADD COLUMN colorKey TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v4 -> v5：新增 account 表，transaction 加 accountId。
         *
         * 账户按 [DefaultAccounts.all] 的顺序插入，id 从 1 开始连续 —— 「未指定」排第一，
         * 正好是 [AccountEntity.UNSPECIFIED_ID]，跟全新安装的种子走同一套 id。
         * 老账单一律归「未指定」（DEFAULT 1），一条都不动。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `account` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `iconKey` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `initialCents` INTEGER NOT NULL,
                        `builtIn` INTEGER NOT NULL,
                        `colorKey` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                DefaultAccounts.all().forEachIndexed { i, seed ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO `account` " +
                            "(`id`, `name`, `iconKey`, `sortOrder`, `initialCents`, `builtIn`, `colorKey`) " +
                            "VALUES (${i + 1}, '${seed.name.replace("'", "''")}', " +
                            "'${seed.iconKey.replace("'", "''")}', $i, 0, ${if (seed.builtIn) 1 else 0}, " +
                            "'${seed.colorKey}')"
                    )
                }
                db.execSQL(
                    "ALTER TABLE `transaction` ADD COLUMN `accountId` INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transaction_accountId` ON `transaction` (`accountId`)"
                )
            }
        }

        /**
         * v5 -> v6：transaction 加 toAccountId，新增「转账」。
         *
         * 转账记在 type=2 这一行里：accountId 是转出方，toAccountId 是转入方。
         *
         * **老账单一条都不动**：已有的 type 只有 0/1，语义没变，
         * toAccountId 默认 0（非转账用不到）。所以这次迁移是纯加列，零风险。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `transaction` ADD COLUMN `toAccountId` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transaction_toAccountId` ON `transaction` (`toAccountId`)"
                )

                // 顺手修掉老用户那个手绘微信标：v4→v5 迁移时给「微信」账户插的是
                // `brand:wechat`（Canvas 近似画），用户反馈看着不像微信，
                // 已统一改成和支付宝、8 家银行同一套的「微」字 + 微信绿。
                //
                // **只按 iconKey 精确匹配**，所以用户自己手动改过的图标、
                // 名字碰巧叫「微信」的其它账户都不会被误改。
                db.execSQL(
                    "UPDATE `account` SET iconKey = 'emoji:微' WHERE iconKey = 'brand:wechat'"
                )
            }
        }
    /**
         * v6 -> v7：报销模型重构 —— 拆成「属于报销池」和「已报销」两件事。
         *
         * 原来只有一个 `reimbursed`，语义是「这笔跟报销有关」，报销页 `WHERE reimbursed=1`
         * 把报销池全列出来。现在报销页要分「已报销 / 未报销」两个 tab，
         * 一个布尔表达不了，必须拆：
         * - `isReimburse`：进不进报销池（= 老的 reimbursed）
         * - `reimbursed`：报没报过（新增，老数据一律 false）
         * - `reimburseDateMillis`：实际报销日期
         *
         * ⚠️ 迁移策略：老的 reimbursed=1 → isReimburse=1 且 reimbursed=0。
         * 也就是**全部退回未报销**。为什么不猜哪些报过了？
         * 老数据只有一个布尔，报销状态本来就是这个布尔，
         * 猜「已报」等于凭空造一笔收入冲销，账目会错。
         * 退回未报销只是让用户多点一次右滑，不会丢钱。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `transaction` ADD COLUMN `isReimburse` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `transaction` ADD COLUMN `reimburseDateMillis` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transaction_isReimburse` ON `transaction` (`isReimburse`)"
                )
                // 老的 reimbursed=1 全是「属于报销池但不知道报没报」
                db.execSQL("UPDATE `transaction` SET `isReimburse` = 1 WHERE `reimbursed` = 1")
                // 并把 reimbursed 归零，表示「都还没报」
                db.execSQL("UPDATE `transaction` SET `reimbursed` = 0")
            }
        }
    }
}
