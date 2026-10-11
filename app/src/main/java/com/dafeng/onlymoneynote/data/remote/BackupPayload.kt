package com.dafeng.onlymoneynote.data.remote

import kotlinx.serialization.Serializable

/**
 * 备份文件结构。
 *
 * version 用于兼容旧备份：
 * - v1：只有 categories + transactions
 * - v2：多了 settings（配色 + WebDAV 连接信息）
 * - v3：分类补上「收支类型」、设置补上主页标题
 * - v4：多了 accounts（资金账户），账单补上 accountId
 * - v5：账单补上 toAccountId（转账的转入方）
 *
 * 旧文件缺字段时反序列化自动用默认值，不会炸；
 * v2 及更早的备份没有分类 type，恢复时会用账单自己的收支反推（见 BackupRepository.inferTypes）。
 */
@Serializable
data class BackupPayload(
    /** 写文件时用 [CURRENT_VERSION]；反序列化时读到的是文件自己的版本 */
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long = 0,
    val deviceName: String = "",
    val settings: BackupSettings = BackupSettings(),
    val categories: List<BackupCategory> = emptyList(),
    /** v4 起才有。老备份没这个字段 = 空表，恢复时沿用本机现有账户 */
    val accounts: List<BackupAccount> = emptyList(),
    val transactions: List<BackupTransaction> = emptyList()
) {
    companion object {
        /**
         * 当前备份格式版本。
         *
         * 之前导出侧写死 `version = 4`、而这里默认值是 5、实际又带着 v5 的
         * `toAccountId`，三处对不上。现在**只有一个来源**：改版本时只动这个常量。
         */
        const val CURRENT_VERSION = 5
    }
}

/**
 * 随备份走的设置。
 *
 * **故意不含密码**：备份文件要传到云端，密码写进去等于给文件配了把钥匙，
 * 万一文件泄露就是账号泄露。恢复后重新输一次密码，成本很低。
 */
@Serializable
data class BackupSettings(
    val themeId: String = "alipay",
    val mode: String = "system",
    val customColor: Int = 0,
    val webDavUrl: String = "",
    val webDavUser: String = "",
    val webDavPath: String = "moneynote/backup.json",
    /** 主页大标题（v3 起才存）。老备份没这个字段 → 空串 = 保持本机现在的标题不动 */
    val appTitle: String = ""
)

@Serializable
data class BackupCategory(
    val id: Long,
    val name: String,
    val iconKey: String,
    val parentId: Long? = null,
    val sortOrder: Int = 0,
    val builtIn: Boolean = false,
    /** 老备份没这个字段，反序列化走空串 = 跟随图标分组色 */
    val colorKey: String = "",
    /** 收支类型：0 = 支出，1 = 收入。v2 及更早的备份没存，恢复时按账单反推 */
    val type: Int = 0
)

@Serializable
data class BackupAccount(
    val id: Long,
    val name: String,
    val iconKey: String,
    val sortOrder: Int = 0,
    /** 期初金额（分）。余额本身不存，恢复后由期初 + 流水重新算出来 */
    val initialCents: Long = 0,
    val builtIn: Boolean = false,
    val colorKey: String = ""
)

@Serializable
data class BackupTransaction(
    val id: Long,
    val amountCents: Long,
    val type: Int,
    val categoryId: Long,
    val dateMillis: Long,
    val note: String = "",
    val createdAt: Long = 0,
    /** 老备份没这个字段，反序列化时走默认值 false，不算报销 */
    val reimbursed: Boolean = false,
    /** v4 起才有。老备份没这个字段 → 归「未指定」 */
    val accountId: Long = 1,
    /**
     * v5 起才有。转账的**转入方**账户 id。
     *
     * 老备份没这个字段 → 0 = 非转账，行为和以前完全一样。
     * 注意它是**按 id 存的**，恢复时可能指向已经不存在的账户
     * （对方备份比你新、或那条账户被删了），所以恢复逻辑必须兜底归「未指定」。
     */
    val toAccountId: Long = 0
)
