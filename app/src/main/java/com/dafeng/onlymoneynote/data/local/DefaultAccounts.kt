package com.dafeng.onlymoneynote.data.local

/**
 * 内置账户种子。全新首次启动时写入，用户之后可自由增删改。
 *
 * 「未指定」排在最前且 **id 固定为 [AccountEntity.UNSPECIFIED_ID]**：
 * v4→v5 迁移也是插这一条，两条路径下 id 对得上，老账单默认归它才不会有孤儿。
 * 期初金额一律 0 —— 余额 = 期初 + 净流水，用户自己在账户管理里填期初。
 */
object DefaultAccounts {

    data class Seed(
        val name: String,
        val iconKey: String,
        val builtIn: Boolean = false
    )

    fun all(): List<Seed> = listOf(
        Seed(AccountEntity.UNSPECIFIED_NAME, "emoji:💳", builtIn = true),
        Seed("支付宝", "emoji:💰"),
        Seed("微信", "emoji:💬"),
        Seed("微信分身", "emoji:🗨️"),
        Seed("工行", "emoji:🏦"),
        Seed("农行", "emoji:🌾"),
        Seed("建行", "emoji:🧱")
    )
}
