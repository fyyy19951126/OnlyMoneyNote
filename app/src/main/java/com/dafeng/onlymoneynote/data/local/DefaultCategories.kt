package com.dafeng.onlymoneynote.data.local

/**
 * 首次启动时写入的默认分类。
 *
 * 这套分类是从作者真机上导出来原样内置的（2026-10-03 的 JSON 备份），
 * 别人装上新版 App，一二级分类和顺序就跟作者手机里的一样。
 *
 * 图标全部用 emoji（key 前缀 `emoji:`，util/AppIcons.kt 里的 CategoryIcon
 * 看到前缀就直接画文字）：emoji 自带颜色、跨机型统一，不依赖矢量图标库，
 * 也不跟「分类组配色」那套逻辑打架。
 *
 * type：0 = 支出，1 = 收入。顺序 = 记一笔里一级胶囊的排列顺序。
 */
object DefaultCategories {

    data class Seed(
        val name: String,
        val iconKey: String,
        val type: Int,
        val children: List<Pair<String, String>>
    )

    val EXPENSE: List<Seed> = listOf(
        Seed("餐饮", "emoji:🍜", 0, listOf(
            "早饭" to "emoji:🥐", "午饭" to "emoji:🍚", "夜宵" to "emoji:🌙",
            "晚饭" to "emoji:🍲", "零食" to "emoji:🍿", "饮料" to "emoji:🥤",
            "肉蛋菜奶" to "emoji:🥗", "水果" to "emoji:🍎", "坚果" to "emoji:🥜"
        )),
        Seed("交通", "emoji:🚌", 0, listOf(
            "地铁" to "emoji:🚇", "公交" to "emoji:🚍", "快车" to "emoji:🚗",
            "出租车" to "emoji:🚕", "高铁" to "emoji:🚄", "大巴" to "emoji:🚐",
            "机票" to "emoji:✈️", "自行车" to "emoji:🚲"
        )),
        Seed("购物", "emoji:🛍️", 0, listOf(
            "衣服" to "emoji:👕", "鞋袜" to "emoji:👟", "数码" to "emoji:📱",
            "护肤" to "emoji:🧴", "超市" to "emoji:🛒", "日常" to "emoji:🧻",
            "纪念品" to "emoji:🎁", "家用" to "emoji:🧹", "快递" to "emoji:📦",
            "理发" to "emoji:✂️"
        )),
        Seed("日常", "emoji:🏠", 0, listOf(
            "水电燃气" to "emoji:💡", "房租" to "emoji:🏢", "通信" to "emoji:📶"
        )),
        Seed("娱乐", "emoji:🎮", 0, listOf(
            "电影" to "emoji:🎬", "门票" to "emoji:🎫", "游戏" to "emoji:🕹️",
            "会员" to "emoji:👑", "运动" to "emoji:🏋️", "彩票" to "emoji:🎰",
            "住宿" to "emoji:🏨", "麻将" to "emoji:🀄"
        )),
        Seed("医疗", "emoji:🏥", 0, listOf(
            "挂号" to "emoji:🩺"
        )),
        Seed("教育", "emoji:📚", 0, listOf(
            "学习" to "emoji:✏️"
        )),
        Seed("理财", "emoji:📈", 0, listOf(
            "股票" to "emoji:📊", "基金" to "emoji:💹", "理财" to "emoji:🏦"
        )),
        Seed("其他", "emoji:🧩", 0, listOf(
            "其他" to "emoji:📎", "报销" to "emoji:🧾", "红包" to "emoji:🧧"
        ))
    )

    val INCOME: List<Seed> = listOf(
        Seed("工资", "emoji:💼", 1, listOf(
            "月工资" to "emoji:💵", "外勤费" to "emoji:🚶", "过节费" to "emoji:🎉",
            "奖金" to "emoji:🏆", "其他奖励" to "emoji:🎖️"
        )),
        Seed("理财", "emoji:📈", 1, listOf(
            "股票" to "emoji:📊", "基金" to "emoji:💹", "理财" to "emoji:🏦"
        )),
        Seed("其他", "emoji:🧩", 1, listOf(
            "报销款" to "emoji:🧾", "退款" to "emoji:↩️", "现金" to "emoji:💰",
            "会员收入" to "emoji:👑"
        ))
    )

    fun all(): List<Seed> = EXPENSE + INCOME

    /** 内置表里对不上名字时的通用兜底图标（与查询里的 COALESCE(..., 'more_horiz') 一致） */
    const val FALLBACK_ICON = "more_horiz"

    /**
     * 给一个新建的分类挑默认图标：内置表里有同名的就沿用它的 emoji，
     * 对不上再退到在全部二级里找同名，最后才是通用兜底图标。
     *
     * @param parentName 二级分类所属的一级名；一级分类传 null
     */
    fun defaultIconKey(name: String, type: Int, parentName: String? = null): String {
        val seeds = if (type == 1) INCOME else EXPENSE
        if (parentName == null) {
            seeds.firstOrNull { it.name == name }?.let { return it.iconKey }
        } else {
            seeds.firstOrNull { it.name == parentName }
                ?.children?.firstOrNull { it.first == name }
                ?.let { return it.second }
        }
        return seeds.flatMap { it.children }.firstOrNull { it.first == name }?.second ?: FALLBACK_ICON
    }
}
