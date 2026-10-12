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
 * - v6：账单补上 isReimburse（属不属于报销池）和 reimburseDateMillis（实际报销日期）
 *
 * v6 之前的老备份里，**报销信息是残的**：只有 reimbursed，没有 isReimburse。
 * 这意味着报销池归属和报销日期在往返中会丢 —— 恢复完所有报销账单从「未报销」
 * tab 里消失、已报销日期清零。老备份反序列化走默认值 false / 0，
 * 拿不到的信息只能当没有，不会炸。
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
        const val CURRENT_VERSION = 6
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
    val toAccountId: Long = 0,
    /**
     * v6 起才有。**是否属于报销池** —— 这笔该不该走报销流程。
     *
     * 和 [reimbursed] 是两件事，别混：
     * - [reimbursed] = 已经报完了
     * - 本字段 = 这笔钱属不属于报销池（记一笔时勾了「报销」开关就是 true）
     *
     * 报销页两个 tab 是拿 isReimburse 圈池子、拿 reimbursed 分「已/未报销」的，
     * 所以老备份（v6 之前）恢复出来的报销账单虽然 reimbursed=true，
     * 却因为 isReimburse=false 而根本不出现在报销页 —— 只能去首页手动再报一次。
     */
    val isReimburse: Boolean = false,
    /**
     * v6 起才有。**实际报销日期**（毫秒）。0 = 还没报或没记日期。
     *
     * 这不只是个展示字段：已报销账单会配一条负支出冲销行，
     * 冲销行备注里就写着「报销于 yyyy-MM-dd」，日期丢了备注就退化成
     * 纯「报销冲销#123」，核对单据时看不到哪天报的。
     */
    val reimburseDateMillis: Long = 0L
)
