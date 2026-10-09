package com.dafeng.onlymoneynote.data.local

/**
 * 内置账户种子。全新首次启动时写入，用户之后可自由增删改。
 *
 * 「未指定」排在最前且 **id 固定为 [AccountEntity.UNSPECIFIED_ID]**：
 * v4→v5 迁移也是按这个顺序插的，两条路径下 id 对得上，老账单默认归它才不会有孤儿。
 * 期初金额一律 0 —— 余额 = 期初 + 净流水，用户自己在账户编辑里填。
 */
object DefaultAccounts {

    data class Seed(
        val name: String,
        val iconKey: String,
        val colorKey: String = "",
        val builtIn: Boolean = false
    )

    fun all(): List<Seed> = listOf(
        Seed(AccountEntity.UNSPECIFIED_NAME, "emoji:💳", builtIn = true),
        Seed("支付宝", AccountIconPresets.PRESETS[1].iconKey, AccountIconPresets.PRESETS[1].colorKey),
        Seed("微信", AccountIconPresets.PRESETS[0].iconKey, AccountIconPresets.PRESETS[0].colorKey),
        Seed("微信分身", "emoji:微", "#4CAF50"),
        Seed("工行", AccountIconPresets.PRESETS[2].iconKey, AccountIconPresets.PRESETS[2].colorKey),
        Seed("农行", AccountIconPresets.PRESETS[3].iconKey, AccountIconPresets.PRESETS[3].colorKey),
        Seed("建行", AccountIconPresets.PRESETS[5].iconKey, AccountIconPresets.PRESETS[5].colorKey)
    )
}

/**
 * 账户图标预设：微信、支付宝 + 8 个主要银行 + 10 个不同颜色的钱包，共 20 个。
 *
 * 银行没有真正的 logo（也不该塞进包里），统一用「品牌色块 + 行名首字」，
 * 一眼能分就行；配色跟着图标一起给，选中即换色，后面还能自己再调。
 */
object AccountIconPresets {

    data class Preset(val label: String, val iconKey: String, val colorKey: String)

    private val wallets = listOf(
        "#1677FF", "#07C160", "#E60012", "#FF8A00", "#9C27B0",
        "#00BCD4", "#795548", "#546E7A", "#F9A825", "#EC407A"
    ).map { Preset("钱包", "emoji:👛", it) }

    val PRESETS: List<Preset> = listOf(
        Preset("微信", "emoji:微", "#07C160"),
        Preset("支付宝", "emoji:支", "#1677FF"),
        Preset("工商银行", "emoji:工", "#D40000"),
        Preset("农业银行", "emoji:农", "#009B75"),
        Preset("中国银行", "emoji:中", "#E0413E"),
        Preset("建设银行", "emoji:建", "#0066B3"),
        Preset("交通银行", "emoji:交", "#004C9B"),
        Preset("邮储银行", "emoji:邮", "#00793C"),
        Preset("招商银行", "emoji:招", "#C8102E"),
        Preset("民生银行", "emoji:民", "#6BA539")
    ) + wallets
}
