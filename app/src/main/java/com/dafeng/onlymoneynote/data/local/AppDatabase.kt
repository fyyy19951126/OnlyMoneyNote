package com.dafeng.onlymoneynote.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CategoryEntity::class, TransactionEntity::class, AccountEntity::class],
    version = 5,
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
                            "'${seed.iconKey.replace("'", "''")}', $i, 0, ${if (seed.builtIn) 1 else 0}, '')"
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
    }
}
