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

    /** 按预设的名字取图标/配色。用下标太脆（预设列表一改就错位），所以按 label 找 */
    private fun preset(label: String): AccountIconPresets.Preset =
        AccountIconPresets.PRESETS.first { it.label == label }

    fun all(): List<Seed> = listOf(
        Seed(AccountEntity.UNSPECIFIED_NAME, "emoji:💳", builtIn = true),
        Seed("支付宝", preset("支付宝").iconKey, preset("支付宝").colorKey),
        Seed("微信", preset("微信").iconKey, preset("微信").colorKey),
        Seed("微信分身", "emoji:微", "#4CAF50"),
        Seed("工行", preset("工商银行").iconKey, preset("工商银行").colorKey),
        Seed("农行", preset("农业银行").iconKey, preset("农业银行").colorKey),
        Seed("建行", preset("建设银行").iconKey, preset("建设银行").colorKey)
    )
}

/**
 * 账户图标预设：微信 / 支付宝 + 8 家常用银行。
 *
 * 2026-10-10 起**全部统一成「品牌色块 + 单字」**：微信「微」、支付宝「支」、
 * 工招建交邮中信农。改这一版的直接原因是那个手绘微信标 —— Canvas 近似画出来
 * 看着不像微信，用户以为是别的模型生成的图；而且支付宝用「支」而微信用图形，
 * 两者风格本来就对不上。统一成汉字图标后整套列表视觉一致，也不用再纠结
 * 官方 logo 的商标问题。
 *
 * 「微信分身」也已经是「微」字，两者只靠颜色区分（正微信 #07C160 / 分身 #4CAF50）。
 */
object AccountIconPresets {

    data class Preset(val label: String, val iconKey: String, val colorKey: String)

    val PRESETS: List<Preset> = listOf(
        Preset("微信", "emoji:微", "#07C160"),
        Preset("支付宝", "emoji:支", "#1677FF"),
        Preset("工商银行", "emoji:工", "#D40000"),
        Preset("招商银行", "emoji:招", "#C8102E"),
        Preset("建设银行", "emoji:建", "#0066B3"),
        Preset("交通银行", "emoji:交", "#004C9B"),
        Preset("邮储银行", "emoji:邮", "#00793C"),
        Preset("中国银行", "emoji:中", "#E0413E"),
        Preset("中信银行", "emoji:信", "#B01F24"),
        Preset("农业银行", "emoji:农", "#009B75")
    )
}
