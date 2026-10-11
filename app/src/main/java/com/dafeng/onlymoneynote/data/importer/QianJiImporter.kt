package com.dafeng.onlymoneynote.data.importer

/**
 * 钱迹（QianJi）等第三方记账软件的导出文件导入。
 *
 * ## 为什么单独一个类而不并进 [RecordsCsvImporter]
 * 钱迹导出的是 **xlsx**，不是 CSV。解析分两层：
 * - [XlsxReader] 只负责「xlsx → 单元格文本」，跟记账语义无关
 * - 这里负责「这些列是什么意思 → [RecordsCsvImporter.RawRow]」
 *
 * 之后**归类到一级/二级**那一步完全复用 CSV 那套映射表和 [RecordsCsvImporter.buildPlan]，
 * 所以从钱迹导进来的分类，也能享受「学习 → 教育」这种自动挂载。
 *
 * ## 钱迹的表头（对着真实导出文件核对过，别猜）
 * `ID, 时间, 账本, 分类, 二级分类, 类型, 金额, 币种, 账户1, 账户2,
 *   备注, 已报销, 手续费, 优惠券, 记账者, 账单标记, 标签, 账单图片, 关联账单`
 *
 * 几个容易踩的点（都是对着真实导出文件逐列核对出来的，**别凭印象改**）：
 * 1. **「分类」列才是细类**，「二级分类」列在真实导出里**整列为空**。
 *    所以取「分类」，不是「二级分类」。（一开始按表头字面理解成两级，
 *    结果真实文件里分类全是空的，是逐列 dump 才发现的。）
 * 2. **分类名可能自带斜杠**：「汽车/加油」「请客送礼」这类写法很常见。
 *    斜杠后面才是真正的细类名，本 App 的层级靠父子表表达，
 *    名字里再带斜杠会显示得很怪，所以靠 [normalizeChildName] 洗掉。
 * 3. **账户名不在「账户1」列**，那两列在真实导出里是空的；
 *    账户在**「记账者」**列（值形如「床前明月光知道吃」）。
 *    「账户1/账户2」只在**转账**行里才有值，所以收支取「记账者」、
 *    转账取「账户1 → 账户2」。
 * 4. **金额是「元」**（xlsx 里是数字 `66.0`），本 App 存「分」，要 ×100。
 * 5. **「类型」除了支出/收入，还有「转账」** —— 正好对上本 App 的
 *    [com.dafeng.onlymoneynote.data.local.TxType.TRANSFER]，
 *    转账导入后两个账户余额就准了，不用手动补。
 * 6. **币种**：只认 CNY/人民币。别的币种（美元等）本 App 没有汇率概念，
 *    混进来会让总资产算错，所以跳过并在结果里报数。
 */
object QianJiImporter {

    /** 表头里这些列可能缺失，所以全部按名字找，不按下标 */
    private val HEAD_TIME = setOf("时间", "日期", "交易时间")
    private val HEAD_CAT = setOf("分类", "一级分类", "分类名称", "二级分类")
    private val HEAD_TYPE = setOf("类型", "收支", "收/支")
    private val HEAD_AMOUNT = setOf("金额", "金额(元)", "发生额")
    private val HEAD_ACCOUNT1 = setOf("账户1", "账户", "账户名称")
    private val HEAD_ACCOUNT2 = setOf("账户2", "转入账户", "对方账户")
    /** 真实导出里收支行的账户名在这一列（见类注释第 3 条） */
    private val HEAD_KEEPER = setOf("记账者", "账户名")
    private val HEAD_NOTE = setOf("备注", "说明")
    private val HEAD_REIMB = setOf("已报销", "报销")
    private val HEAD_CURRENCY = setOf("币种", "货币")
    /** 2026-10-11：钱迹用这一列把「报销记录」行连回它报销的那笔支出 */
    private val HEAD_LINK = setOf("关联账单", "关联", "关联ID")
    /** 钱的唯一标识，用来解析「关联账单」指向谁 */
    private val HEAD_ID = setOf("ID", "id", "编号")

    data class Result(
        val rows: List<RecordsCsvImporter.RawRow>,
        /** 转账行：钱从 account1 到 account2 */
        val transfers: List<Transfer>,
        /** 跳过原因 + 条数，用于导入后给用户一句话交代 */
        val skippedCurrency: Int = 0,
        val skippedBad: Int = 0,
        /**
         * 已被钱迹标记为「报过了」的账单，key 是 [RecordsCsvImporter.RawRow] 的序号
         * （也就是这个 list 的下标）。
         *
         * 怎么来的：钱迹把每笔已报销的钱另外写了一行「报销记录」，
         * 那行的「关联账单」列指向原来那笔支出的 ID。我们先扫一遍所有「报销记录」行，
         * 建 `被关联的ID -> 报销日期` 的映射，再处理支出/报销行时查表。
         *
         * 为什么不用「已报销」列（L 列）判断：那列只在「报销」行上有值，
         * 而且真实文件里 1525 条只有 1492 条标了「是」，剩下 33 条是空的。
         * 用「关联账单」反查才是可靠的 —— 有报销记录行就说明报过了。
         */
        val reimbursedIndexes: Set<Int> = emptySet(),
        /** 已报销的**实际报销日期**，key 同行号。0 = 数据里查不到 */
        val reimburseDates: Map<Int, Long> = emptyMap()
    )

    data class Transfer(
        val cents: Long,
        val dateMillis: Long,
        val note: String,
        val fromAccountName: String,
        val toAccountName: String
    )

    /** 表头识别：至少要有时间 / 金额 / 类型 之一组，否则不是这个软件的导出 */
    fun looksLikeQianJi(rows: List<List<String>>): Boolean {
        val h = rows.firstOrNull()?.map { it.trim() } ?: return false
        val hasTime = h.any { it in HEAD_TIME }
        val hasAmount = h.any { it in HEAD_AMOUNT }
        return hasTime && hasAmount
    }

    fun parse(bytes: ByteArray): Result {
        val rows = XlsxReader.readFirstSheet(bytes)
        if (rows.isEmpty()) return Result(emptyList(), emptyList(), skippedBad = 1)

        val header = rows.first().map { it.trim() }
        fun find(names: Set<String>): Int =
            header.indexOfFirst { it in names }.let { if (it >= 0) it else -1 }

        val iTime = find(HEAD_TIME)
        val iAmount = find(HEAD_AMOUNT)
        val iType = find(HEAD_TYPE)
        if (iTime < 0 || iAmount < 0 || iType < 0) return Result(emptyList(), emptyList(), skippedBad = rows.size)

        val iCat = find(HEAD_CAT)
        val iAcc1 = find(HEAD_ACCOUNT1)
        val iAcc2 = find(HEAD_ACCOUNT2)
        val iKeeper = find(HEAD_KEEPER)
        val iNote = find(HEAD_NOTE)
        val iReimb = find(HEAD_REIMB)
        val iCur = find(HEAD_CURRENCY)
        val iId = find(HEAD_ID)
        val iLink = find(HEAD_LINK)

        val data = rows.drop(1)
        fun at(r: List<String>, i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""

        // ------------------------------------------------------------------
        // 第 1 趟：扫「报销记录」行，建「被报销的账单 ID -> 实际报销日期」
        //
        // ⚠️ 2026-10-11 用户真实导出诊断出来的关键结构，别改成一遍过：
        // 钱迹的「报销」是**两行一组**表达同一件事：
        //   一行「报销」      = 花钱那天记的待报销支出，关联账单列是空的
        //   一行「报销记录」  = 实际报销那天记的收据，**关联账单列指向上面那行**
        // 也就是说，判断「报没报过」唯一可靠的依据是**有没有对应的报销记录行**，
        // 而不是「已报销」列（真实文件里 1525 条只有 1492 条标了「是」，剩 33 条空着）。
        //
        // 所以必须先扫一遍收集映射，才能在处理支出/报销行时知道它报没报。
        // ------------------------------------------------------------------
        val reimbursedAt = HashMap<String, Long>()
        data.forEach { r ->
            if (at(r, iType) != "报销记录") return@forEach
            val link = at(r, iLink)
            if (link.isEmpty()) return@forEach
            // 同一笔理论上只有一条报销记录；万一有多条，取**最早**那次，
            // 跟「实际报销日期」的字面意思一致（第一次拿到报销款那天）。
            val at0 = toMillis(at(r, iTime)) ?: 0L
            val prev = reimbursedAt[link]
            if (prev == null || (at0 != 0L && (prev == 0L || at0 < prev))) {
                reimbursedAt[link] = at0
            }
        }

        val out = ArrayList<RecordsCsvImporter.RawRow>()
        val transfers = ArrayList<Transfer>()
        val reimbursedIdx = HashSet<Int>()
        val reimburseDates = HashMap<Int, Long>()
        var skipCur = 0
        var skipBad = 0

        data.forEach { r ->
            val cur = at(r, iCur)
            if (cur.isNotEmpty() && cur.uppercase() !in setOf("CNY", "RMB", "人民币", "元")) {
                skipCur++
                return@forEach
            }

            val cents = toCents(at(r, iAmount))
            if (cents == null || cents <= 0L) { skipBad++; return@forEach }
            val date = toMillis(at(r, iTime)) ?: run { skipBad++; return@forEach }
            val note = at(r, iNote)
            val accountName = at(r, iAcc1).ifBlank { at(r, iKeeper) }
            val type = at(r, iType)

            when (type) {
                // 「报销记录」行本身**不导**。
                // 它不是一笔新支出，而是「原来那笔支出已经被报了」的凭据 ——
                // 再导一遍等于把同一笔钱算两次。真要导的话 6628 行会多出 1492 笔重复收入。
                // 它携带的信息（谁报了、什么时候报的）已经进到 reimbursedAt 映射里了。
                "报销记录" -> return@forEach

                // 「支出」和「报销」都是支出，只是后者属于报销池。
                // 分类照旧取「分类」列（真实导出里「二级分类」整列为空）。
                "支出", "报销" -> {
                    val idx = out.size
                    // 是不是被报销记录行关联上了？关联得上就是已报销
                    val linkHit = reimbursedAt[at(r, iId)]
                    val isReimbursed = linkHit != null
                    if (isReimbursed) {
                        reimbursedIdx.add(idx)
                        val d = linkHit!!
                        if (d != 0L) reimburseDates[idx] = d
                    }
                    out.add(
                        RecordsCsvImporter.RawRow(
                            dateMillis = date,
                            category = normalizeChildName(at(r, iCat)).ifBlank { "其他" },
                            type = 0,
                            cents = cents,
                            // 已报销的，把实际报销日期写进备注（用户要求：
                            // 账单日期保持消费日期不动，报销日期以文字形式可见）
                            note = if (isReimbursed && linkHit != null && linkHit != 0L) {
                                val y = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                                    .format(java.util.Date(linkHit))
                                if (note.isBlank()) "报销于 $y" else "$note（报销于 $y）"
                            } else note,
                            // 只有「报销」类型进报销池；普通支出 reimbursed=false
                            reimbursed = isReimbursed,
                            isReimburse = type == "报销",
                            accountName = accountName
                        )
                    )
                }

                "收入" -> out.add(
                    RecordsCsvImporter.RawRow(
                        dateMillis = date,
                        category = normalizeChildName(at(r, iCat)).ifBlank { "其他收入" },
                        type = 1,
                        cents = cents,
                        note = note,
                        accountName = accountName
                    )
                )

                "转账", "转出", "转入" -> {
                    val from = at(r, iAcc1)
                    val to = at(r, iAcc2)
                    transfers.add(
                        Transfer(
                            cents = cents,
                            dateMillis = date,
                            note = note,
                            fromAccountName = from.ifBlank { to },
                            toAccountName = to.ifBlank { from }
                        )
                    )
                }

                else -> skipBad++
            }
        }
        return Result(out, transfers, skipCur, skipBad, reimbursedIdx, reimburseDates)
    }

    /**
     * 洗一下二级分类名。
     *
     * 钱迹的二级里带斜杠的写法很常见（「汽车/加油」「餐饮/外卖」），
     * 斜杠后面才是真正的细类名。本 App 的层级靠父子表表达，
     * 名字里再带斜杠会显示得很怪，所以只取**斜杠后**的部分；
     * 取不到就原样返回。
     */
    fun normalizeChildName(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        val slash = s.lastIndexOf('/')
        if (slash >= 0 && slash < s.length - 1) return s.substring(slash + 1).trim()
        return s
    }

    private fun toCents(s: String): Long? {
        if (s.isBlank()) return null
        val clean = s.replace(",", "").replace("¥", "").replace("￥", "").trim()
        return try {
            java.math.BigDecimal(clean)
                .movePointRight(2)
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .toLong()
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** 钱迹的时间是 `2026-10-11 09:24:59`，另外容忍几种常见写法 */
    private val formats = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy-MM-dd",
        "yyyy/MM/dd"
    )

    private fun toMillis(s: String): Long? {
        if (s.isBlank()) return null
        for (p in formats) {
            try {
                val f = java.text.SimpleDateFormat(p, java.util.Locale.CHINA)
                    .apply { isLenient = false }
                val t = f.parse(s.trim())?.time ?: continue
                return t
            } catch (e: Exception) {
                // 换下一种格式试
            }
        }
        return null
    }
}
