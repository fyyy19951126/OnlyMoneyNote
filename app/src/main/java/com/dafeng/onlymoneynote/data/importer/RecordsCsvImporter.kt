package com.dafeng.onlymoneynote.data.importer

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 从别的记账软件导出的 CSV 导入。
 *
 * 目前对着「大象记账」的导出表头实现：
 * `日期, 分类名称, 收支, 金额, 账户, 备注, 报销`
 * 列是按**表头名**找的，所以列顺序变了也认；多出来的列（账户/报销）直接忽略。
 *
 * 这类导出的分类是**单层**的（只有一个「分类名称」）。要落到本 App 的
 * 一级/二级结构，靠 [taxonomy] 里的映射表：CSV 里的名字一律当二级，
 * 按语义挂到一个一级下面。表里没有的名字兜底挂到「其他 / 其他收入」，
 * 保证一条账单都不会丢。
 *
 * 注意：股票/基金/理财这类名字在源数据里收支两个方向都用，
 * 所以同一个名字会同时出现在「支出 · 投资理财」和「收入 · 投资理财」下面。
 */
object RecordsCsvImporter {

    /* ------------------------- 数据结构 ------------------------- */

    /** CSV 里的一行（已经转成本 App 的口径） */
    data class RawRow(
        val dateMillis: Long,
        val category: String,
        /** 0 = 支出，1 = 收入 */
        val type: Int,
        val cents: Long,
        val note: String,
        /** 「报销」列：是 / 1 / Y / true 都算选中 */
        val reimbursed: Boolean = false
    )

    data class Child(val name: String, val iconKey: String)

    data class Parent(
        val name: String,
        val iconKey: String,
        val type: Int,
        val children: List<Child>
    )

    /** 归类完的账单：直接指向「一级 - 二级」 */
    data class Tx(
        val parent: String,
        val child: String,
        val type: Int,
        val cents: Long,
        val dateMillis: Long,
        val note: String,
        val reimbursed: Boolean = false
    )

    data class Plan(val parents: List<Parent>, val txs: List<Tx>) {
        val categoryCount: Int
            get() = parents.sumOf { 1 + it.children.size }

        val parentCount: Int get() = parents.size

        val childCount: Int get() = parents.sumOf { it.children.size }

        val minDate: Long? get() = txs.minOfOrNull { it.dateMillis }
        val maxDate: Long? get() = txs.maxOfOrNull { it.dateMillis }
    }

    /* ------------------------- 编码 ------------------------- */

    /**
     * 猜编码：先按严格 UTF-8 试，报错就退回 GB18030。
     * 国内记账软件导出经常是 GBK，直接按 UTF-8 读会整片乱码。
     */
    fun decode(bytes: ByteArray): String {
        val utf8 = Charset.forName("UTF-8").newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            String(bytes, Charset.forName("GB18030"))
        }
        return text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    }

    /* ------------------------- CSV 解析 ------------------------- */

    /**
     * 标准 CSV 切分：支持字段用双引号包住、字段里带逗号和换行、两个双引号表示一个引号。
     * 不引第三方库，几十行搞定，也好测。
     */
    fun splitCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            field.append('"'); i++
                        } else inQuotes = false
                    } else field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> { row.add(field.toString()); field.setLength(0) }
                c == '\n' -> {
                    row.add(field.toString()); field.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            if (row.any { it.isNotBlank() }) rows.add(row)
        }
        return rows
    }

    private val dateFormats = listOf(
        "yyyy/MM/dd HH:mm",
        "yyyy/MM/dd H:mm",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd",
        "yyyy-MM-dd"
    )

    /** 解析成行；认不出来的行直接跳过，不抛异常（导入不该被一行脏数据打断） */
    fun parse(text: String): List<RawRow> {
        val rows = splitCsv(text)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim() }
        val iDate = header.indexOf("日期")
        val iType = header.indexOf("收支")
        val iAmount = header.indexOf("金额")
        val iNote = header.indexOf("备注")
        val iReimb = header.indexOf("报销")
        // 分类列有两种表头：大象记账的单列「分类名称」，
        // 以及本 App 导出的双列「一级分类 / 二级分类」。二级优先，没有就退到一级。
        val iCatSingle = header.indexOf("分类名称")
        val iCatL1 = header.indexOf("一级分类")
        val iCatL2 = header.indexOf("二级分类")
        val hasCat = iCatSingle >= 0 || iCatL1 >= 0 || iCatL2 >= 0
        if (iDate < 0 || !hasCat || iType < 0 || iAmount < 0) return emptyList()

        val out = ArrayList<RawRow>(rows.size)
        rows.drop(1).forEach { r ->
            fun at(i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""
            val cents = toCents(at(iAmount)) ?: return@forEach
            if (cents <= 0) return@forEach
            val type = when (at(iType)) {
                "支出" -> 0
                "收入" -> 1
                else -> return@forEach
            }
            val date = toMillis(at(iDate)) ?: return@forEach
            val cat = at(iCatL2).ifBlank { at(iCatSingle) }.ifBlank { at(iCatL1) }.ifBlank { "其他" }
            val reimb = at(iReimb).lowercase() in setOf("是", "1", "y", "yes", "true", "✓")
            out.add(RawRow(date, cat, type, cents, at(iNote), reimb))
        }
        return out
    }

    private fun toCents(s: String): Long? {
        if (s.isBlank()) return null
        val clean = s.replace(",", "").replace("¥", "").trim()
        return try {
            BigDecimal(clean).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun toMillis(s: String): Long? {
        if (s.isBlank()) return null
        for (p in dateFormats) {
            try {
                val f = SimpleDateFormat(p, Locale.CHINA).apply { isLenient = false }
                return f.parse(s)?.time ?: continue
            } catch (e: Exception) {
                // 试下一种
            }
        }
        return null
    }

    /* ------------------------- 一级/二级映射表 ------------------------- */

    private val EXPENSE_PARENTS: List<Parent> = listOf(
        Parent("餐饮", "restaurant", 0, listOf(
            Child("早饭", "breakfast"), Child("午饭", "lunch"), Child("晚饭", "dinner"),
            Child("夜宵", "takeout"), Child("零食", "snack"), Child("饮料", "drink"),
            Child("水果", "basket"), Child("肉蛋菜奶", "rice"), Child("坚果", "bakery"),
            Child("肉肉", "bbq")
        )),
        Parent("交通", "directions_bus", 0, listOf(
            Child("地铁", "subway"), Child("公交", "bus"), Child("快车", "car"),
            Child("出租车", "taxi"), Child("高铁", "train"), Child("大巴", "shuttle"),
            Child("机票", "flight"), Child("自行车", "bike"), Child("交通", "directions_bus")
        )),
        Parent("购物", "shopping_bag", 0, listOf(
            Child("衣服", "clothes"), Child("鞋袜", "clothes"), Child("数码", "device"),
            Child("护肤", "cosmetic"), Child("日常", "daily"), Child("购物", "shopping_bag"),
            Child("超市", "basket"), Child("家用", "cleaning"), Child("纪念品", "gift"),
            Child("快递", "shipping"), Child("理发", "haircut")
        )),
        Parent("居住", "home", 0, listOf(
            Child("房租", "rent"), Child("水电燃气", "utility"), Child("通信", "wifi")
        )),
        Parent("娱乐", "sports_esports", 0, listOf(
            Child("电影", "movie"), Child("门票", "attraction"), Child("游戏", "game"),
            Child("文娱哈哈哈哈", "party"), Child("会员", "card"), Child("运动", "fitness"),
            Child("彩票", "casino"), Child("住宿", "hotel")
        )),
        Parent("医疗", "local_hospital", 0, listOf(Child("医疗", "local_hospital"))),
        Parent("教育", "school", 0, listOf(Child("学习", "book"))),
        Parent("人情", "redeem", 0, listOf(Child("红包", "redpacket"))),
        // 投资出账（买股票/买基金的钱）也算支出，跟「收入 · 投资理财」分开两个一级
        Parent("投资理财", "trending_up", 0, listOf(
            Child("股票", "stock"), Child("基金", "fund"), Child("理财", "savings")
        )),
        Parent("其他", "more_horiz", 0, listOf(
            Child("其他", "more_horiz"), Child("工作报销", "reimburse")
        ))
    )

    private val INCOME_PARENTS: List<Parent> = listOf(
        Parent("职业收入", "work", 1, listOf(
            Child("月工资", "salary"), Child("奖金", "bonus"), Child("其他奖励", "offer")
        )),
        Parent("投资理财", "trending_up", 1, listOf(
            Child("股票", "stock"), Child("基金", "fund"), Child("理财", "savings")
        )),
        Parent("其他收入", "more_horiz", 1, listOf(
            Child("报销款", "reimburse"), Child("退款", "refund"), Child("外勤费", "invoice"),
            Child("现金", "money"), Child("节假日", "gift"), Child("会员收入", "card")
        ))
    )

    fun taxonomy(): List<Parent> = EXPENSE_PARENTS + INCOME_PARENTS

    /** 兜底一级：映射表里没收录的名字挂这儿 */
    const val FALLBACK_EXPENSE_PARENT = "其他"
    const val FALLBACK_INCOME_PARENT = "其他收入"

    private val childIndex: Map<String, String> = taxonomy()
        .flatMap { p -> p.children.map { c -> "${p.type}|${c.name}" to p.name } }
        .toMap()

    /** 一级名 → 该类型下的一级（拿图标用） */
    private fun parentOf(name: String, type: Int): Parent? =
        taxonomy().firstOrNull { it.type == type && it.name == name }

    /** 把一个 CSV 分类名挂到一级下面；没收录的返回 null（调用方兜底） */
    fun resolveParentName(category: String, type: Int): String? = childIndex["$type|$category"]

    /**
     * 生成导入方案：只看**文件里真实出现过**的分类名，
     * 没出现过的二级不建（避免导进来一堆空分类）。
     */
    fun buildPlan(text: String): Plan {
        val rows = parse(text)
        if (rows.isEmpty()) return Plan(emptyList(), emptyList())

        // 一级 + 二级的去重组合，保持映射表里的原始顺序
        val used = rows.map { it.type to it.category }.toHashSet()

        val parents = ArrayList<Parent>()
        for (type in listOf(0, 1)) {
            val declared = if (type == 0) EXPENSE_PARENTS else INCOME_PARENTS
            // 兜底一级必须存在（有未收录名字时要往里塞）
            val fallback = if (type == 0) FALLBACK_EXPENSE_PARENT else FALLBACK_INCOME_PARENT
            val hitNames = rows.filter { it.type == type }.map { it.category }.toHashSet()

            declared.forEach { p ->
                val kids = ArrayList<Child>()
                p.children.forEach { c ->
                    if (used.contains(type to c.name)) kids.add(c)
                }
                // 兜底：文件里有、但映射表没收录的名字，挂到兜底一级下
                if (p.name == fallback) {
                    val known = declared.flatMap { d -> d.children.map { it.name } }.toHashSet()
                    hitNames.filter { it !in known }.sorted().forEach { unknown ->
                        kids.add(Child(unknown, "more_horiz"))
                    }
                }
                if (kids.isNotEmpty()) parents.add(p.copy(children = kids))
            }
        }

        val txs = rows.map { r ->
            val parent = resolveParentName(r.category, r.type)
                ?: if (r.type == 0) FALLBACK_EXPENSE_PARENT else FALLBACK_INCOME_PARENT
            Tx(
                parent = parent,
                child = r.category,
                type = r.type,
                cents = r.cents,
                dateMillis = r.dateMillis,
                note = r.note,
                reimbursed = r.reimbursed
            )
        }
        // 兜底一级本身可能不在 parents 里（比如映射表里那个一级一个已知二级都没命中）
        val fixed = parents.toMutableList()
        txs.map { it.type to it.parent }.distinct().forEach { (type, name) ->
            if (fixed.none { it.type == type && it.name == name }) {
                val icon = parentOf(name, type)?.iconKey ?: "more_horiz"
                val kids = txs.filter { it.type == type && it.parent == name }
                    .map { it.child }.distinct().sorted()
                    .map { Child(it, "more_horiz") }
                fixed.add(Parent(name, icon, type, kids))
            }
        }
        return Plan(fixed, txs)
    }
}
