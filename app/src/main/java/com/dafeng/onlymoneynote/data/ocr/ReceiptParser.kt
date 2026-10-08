package com.dafeng.onlymoneynote.data.ocr

import java.util.Calendar
import java.util.regex.Pattern

/**
 * 从 OCR 文本里解析账单要素。
 *
 * 目标场景：微信/支付宝/银行的支付成功截图、账单列表截图。
 * 解析不出就返回空值，让用户手填 —— 宁可少猜，不要瞎猜。
 */
object ReceiptParser {

    data class Parsed(
        val amountCents: Long? = null,
        val merchant: String? = null,
        val dateMillis: Long? = null,
        val isIncome: Boolean = false,
        /** 识别到的支付方式（零钱 / 招商银行储蓄卡(1234) / 抖音月付…） */
        val payMethod: String? = null,
        /**
         * 推荐的一级分类**名字**（不是 id）。
         * 用名字而不是 id，是因为 id 依赖数据库当前状态；名字可以在 UI 层
         * 现查分类表反查 id，查不到就退回「按名字新建」。见 [CategoryGuess]。
         */
        val suggestParentName: String? = null,
        /** 推荐的二级分类名字（可选，用户没手动选时一并带上） */
        val suggestChildName: String? = null,
        val rawText: String = "",
        /** 命中的线索，给用户看「我为什么这么填」 */
        val hints: List<String> = emptyList()
    )

    // 支付方式：字段名 + 已知渠道关键词。截图里几乎必有这一项（微信/支付宝付款详情页）。
    private val PAY_METHOD_KEYS = listOf(
        "支付方式", "付款方式", "扣款方式", "资金方式", "结算方式", "扣款渠道"
    )
    private val PAY_METHODS = listOf(
        "零钱通", "零钱", "余额", "零钱支付",
        "微信零钱", "花呗", "信用卡", "储蓄卡", "借记卡", "工资卡", "银行卡",
        "支付宝余额", "余额宝", "花呗分期", "分期付款",
        "云闪付", "闪付", "Apple Pay", "微信支付", "支付宝", "现金", "现金支付"
    )
    // 卡号尾号：招商银行(1234) / **1234
    private val CARD_TAIL = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z]{2,10}(?:银行|信用卡|储蓄卡|支付)?)\\s*[（(]\\s*([*＊]{0,4}\\d{2,4})\\s*[）)]")

    // 金额：分四档优先级。核心思路见文件头「三条核心策略」。

    /**
     * ⓿ **最高优先级：整行只有一个金额数**。
     *
     * 这是 5 张截图里 4 张的实付金额形态 —— 支付宝/微信账单详情页把实付金额
     * 单独一行用最大字号显示，前缀只有负号或 ¥：
     * ```
     * -30.00
     * -6.00
     * -29.55
     * ¥183.99
     * ```
     * 这种行没有任何字段名，也没有第二个数字，**不可能是原价/优惠/单号**，
     * 所以最可信，优先于所有带字段名的规则。
     *
     * 注意排除「订单金额 5.90」那种同行有字段名的 —— 上面 `^...$` 的锚点已经排除了。
     */
    private val AMOUNT_LONE_LINE = Pattern.compile(
        "^[\\s\\-−－¥￥]?([0-9][0-9,]*\\.[0-9]{2})[\\s]*$"
    )

    /**
     * ① 强字段：语义就是「实际付了多少钱」。
     * 抖音弹窗没有这种字段，但支付宝「付款金额」、银行回单「交易金额」都是它。
     */
    private val AMOUNT_STRONG = Pattern.compile(
        "(?:实付款?|实收款?|实付金额|实收金额|付款金额|收款金额|交易金额|支付金额|扣款金额|消费金额|转账金额)" +
            "(?:\\(元\\)|（元）)?\\s*[:：]?\\s*[¥￥]?\\s*(-?[0-9][0-9,]*\\.?[0-9]{0,2})"
    )

    /**
     * ② 弱字段：有金额含义但可能不是实付（「订单金额」在有优惠时 ≠ 实付）。
     * 只在没有强字段时才用。
     */
    private val AMOUNT_WEAK = Pattern.compile(
        "(?:订单金额|商品金额|订单总价|合计|总计|金额)" +
            "(?:\\(元\\)|（元）)?\\s*[:：]?\\s*[¥￥]?\\s*(-?[0-9][0-9,]*\\.?[0-9]{0,2})"
    )

    /**
     * ③ 兜底：货币符号 / 数字+元。
     * 截图 1（抖音）金额是独占一行的「¥183.99」，只能靠这条。
     * 截图 4/5 的「原价 ¥29.90」「订单金额 5.90」也会被这里捞到 → 所以要在**行级**先排除。
     */
    private val AMOUNT_LOOSE = Pattern.compile(
        "(?:[¥￥]|RMB|人民币)\\s*(-?[0-9][0-9,]*\\.?[0-9]{0,2})" +
            "|(-?[0-9][0-9,]*\\.[0-9]{2})\\s*元"
    )

    /**
     * 这些词所在的行，里面的数字一律不当金额。
     * 覆盖截图 1「立减优惠 -¥0.01」、截图 4「原价 ¥29.90 / 优惠 ¥0.35」、
     * 截图 5「碰友日立减 -0.23」、各处的余额/额度/分期。
     */
    private val AMOUNT_EXCLUDE_LINE = listOf(
        "原价", "划线价", "市场价", "参考价",
        "立减", "优惠", "折扣", "红包", "券", "抵扣", "积分", "减免", "已优惠", "省",
        "配送费", "运费", "包装费", "服务费", "小计",
        "余额", "可用", "额度", "信用", "分期", "手续费", "利息", "已还", "待还"
    )

    /** 这些字段后面跟的是单号/账号，绝不是金额 */
    private val ID_FIELDS = listOf(
        "单号", "订单号", "流水号", "序列号", "卡号", "账号", "手机号", "电话",
        "纳税人识别号", "发票号码", "发票", "笔数", "次数", "条码", "编号", "凭证号"
    )

    // 日期：2026-10-01 / 2026年10月1日 / 2026年05月28日 16:40:24 / 10-01 12:30
    private val DATE_FULL = Pattern.compile(
        "(20[0-9]{2})\\s*[-/年.]\\s*([0-1]?[0-9])\\s*[-/月.]\\s*([0-3]?[0-9])\\s*日?" +
            "(?:\\s*([0-2]?[0-9])\\s*[:：]\\s*([0-5][0-9]))?(?:\\s*[:：]\\s*([0-5][0-9]))?"
    )
    private val DATE_SHORT = Pattern.compile(
        "([0-1]?[0-9])\\s*[-/月]\\s*([0-3]?[0-9])\\s*日?" +
            "(?:\\s*([0-2]?[0-9])\\s*[:：]\\s*([0-5][0-9]))?(?:\\s*[:：]\\s*([0-5][0-9]))?"
    )

    // 收入关键词
    private val INCOME_WORDS = listOf("收款", "收入", "已收", "到账", "退款", "红包已收", "转入", "存入", "提现到账", "退货")
    /**
     * 支出关键词。**优先级高于收入词**。
     * 截图里「付款方式」「收钱码收款」都会命中「收」，
     * 光看 INCOME_WORDS 会把正常消费判成收入。
     */
    private val EXPENSE_WORDS = listOf(
        "付款金额", "付款方式", "支付成功", "付款成功", "交易成功",
        "转账成功", "消费", "支出", "扣款", "付款", "支付"
    )

    // 商户噪音词 —— 命中说明这不是商户名
    private val MERCHANT_NOISE = listOf(
        "支付成功", "交易成功", "付款成功", "转账成功", "已完成", "账单详情",
        "交易详情", "订单号", "交易单号", "商户单号", "支付方式", "付款方式",
        "当前状态", "创建时间", "完成时间", "商品说明", "收款方", "付款方",
        "微信支付", "支付宝", "云闪付", "详情", "更多", "返回", "确定",
        "付款时间", "交易时间", "商户全称", "收付款方", "金额", "备注",
        // 支付宝/微信账单页的周边控件文案
        "账单管理", "账单服务", "全部账单", "反馈与投诉", "开票", "发票",
        "电子凭证", "联系收款方", "往来流水证明", "申请电子回单", "服务",
        "支付奖励", "立即领取", "前往小程序", "喜欢", "附近门店", "原价",
        "优惠", "立减", "分期", "还款", "账单分类", "标签", "备注", "更多"
    )

    /**
     * 这些字段后面的值是**机构**，不是消费商户。
     * 截图 3 关键：商户是「南京南虾北羊餐饮管理有限公司」，
     * 而「易宝支付有限公司」是收单机构、「中国建设银行…山西省分行」是清算机构 —— 都不能当商户。
     */
    private val ORG_FIELDS = listOf(
        "收单机构", "清算机构", "支付机构", "结算机构", "收款机构", "付款机构",
        "收款方全称", "付款方全称", "发卡行", "清算方", "收单方"
    )

    /** 商户字段名，按优先级。截图 3/4/5 都有「商户全称」。 */
    private val MERCHANT_FIELDS = listOf(
        "商户全称", "商户名称", "商户名", "门店名称", "商家名称", "收款商户", "商户"
    )

    // 常见商户后缀，命中加权
    private val MERCHANT_SUFFIX = listOf(
        "有限公司", "有限责任公司", "超市", "便利店", "餐厅", "饭店", "小吃",
        "咖啡", "奶茶", "书店", "药店", "医院", "商场", "百货", "菜市场",
        "水果店", "早餐店", "火锅", "烧烤", "面馆", "蛋糕店", "加油站",
        "停车场", "洗衣店", "理发店", "网咖", "影城", "影院", "酒店", "宾馆"
    )

    /**
     * 关键词 → (一级分类, 二级分类)。
     *
     * 顺序即优先级，**越具体的放越前面** —— 「超市」要在「百货」前面，
     * 「咖啡」要在「餐」前面，否则「星巴克咖啡」会被后面的宽泛词抢走。
     * 命中第一个就返回。
     */
    private val CATEGORY_RULES: List<Triple<String, String, String>> = listOf(
        // ---- 餐饮（最需要细分，词最多）----
        Triple("肯德基", "餐饮", "快餐"),
        Triple("麦当劳", "餐饮", "快餐"),
        Triple("星巴克", "餐饮", "咖啡"),
        Triple("瑞幸", "餐饮", "咖啡"),
        Triple("咖啡", "餐饮", "咖啡"),
        Triple("奶茶", "餐饮", "饮料"),
        Triple("茶饮", "餐饮", "饮料"),
        Triple("火锅", "餐饮", "晚饭"),
        Triple("烧烤", "餐饮", "晚饭"),
        Triple("自助", "餐饮", "晚饭"),
        Triple("百汇", "餐饮", "晚饭"),
        Triple("餐厅", "餐饮", "晚饭"),
        Triple("饭店", "餐饮", "晚饭"),
        Triple("餐饮", "餐饮", "晚饭"),
        Triple("面馆", "餐饮", "晚饭"),
        Triple("快餐", "餐饮", "午饭"),
        Triple("小吃", "餐饮", "夜宵"),
        Triple("美食", "餐饮", "晚饭"),
        Triple("面", "餐饮", "晚饭"),
        // ---- 超市 / 购物 ----
        Triple("生鲜", "购物", "超市"),
        Triple("超市", "购物", "超市"),
        Triple("便利店", "购物", "日常"),
        Triple("百货", "购物", "日用百货"),
        Triple("商场", "购物", "日用百货"),
        Triple("购物中心", "购物", "日用百货"),
        // ---- 交通 ----
        Triple("加油", "交通", "加油"),
        Triple("停车", "交通", "停车费"),
        Triple("地铁", "交通", "地铁"),
        Triple("公交", "交通", "公交"),
        Triple("打车", "交通", "出租车"),
        Triple("滴滴", "交通", "出租车"),
        Triple("出行", "交通", "打车"),
        Triple("航空", "交通", "机票"),
        Triple("铁路", "交通", "高铁"),
        Triple("车票", "交通", "高铁"),
        // ---- 医疗 ----
        Triple("医院", "医疗", "医疗"),
        Triple("药房", "医疗", "药品"),
        Triple("药店", "医疗", "药品"),
        Triple("诊所", "医疗", "医疗"),
        // ---- 娱乐 ----
        Triple("影城", "娱乐", "电影"),
        Triple("影院", "娱乐", "电影"),
        Triple("电影", "娱乐", "电影"),
        Triple("游戏", "娱乐", "游戏"),
        Triple("健身", "娱乐", "运动"),
        Triple("酒店", "娱乐", "住宿"),
        Triple("宾馆", "娱乐", "住宿"),
        Triple("民宿", "娱乐", "住宿"),
        Triple("门票", "娱乐", "门票"),
        // ---- 居住 ----
        Triple("物业", "居住", "物业费"),
        Triple("房租", "居住", "房租"),
        Triple("水电", "居住", "水电燃气"),
        Triple("燃气", "居住", "水电燃气"),
        Triple("电费", "居住", "水电燃气"),
        Triple("水费", "居住", "水电燃气"),
        Triple("话费", "居住", "通信"),
        // ---- 购物细分 ----
        Triple("服饰", "购物", "衣服"),
        Triple("服装", "购物", "衣服"),
        Triple("鞋", "购物", "鞋袜"),
        Triple("理发", "购物", "理发"),
        Triple("美发", "购物", "理发"),
        Triple("美容", "购物", "护肤"),
        Triple("数码", "购物", "数码"),
        Triple("手机", "购物", "数码"),
        Triple("电脑", "购物", "数码"),
        Triple("电器", "购物", "家用"),
        Triple("快递", "购物", "快递"),
        Triple("菜鸟", "购物", "快递"),
        // ---- 公共服务（截图 2 的「商业服务」）----
        Triple("商业服务", "其他", "商业服务"),
        Triple("收钱码", "其他", "商业服务"),
        Triple("个人收款", "其他", "商业服务"),
        Triple("转账", "其他", "转账"),
        Triple("保险", "其他", "保险"),
        Triple("还款", "其他", "还款"),
        Triple("缴税", "其他", "税费"),
        // ---- 教育 ----
        Triple("书店", "教育", "书籍"),
        Triple("培训", "教育", "学习"),
        Triple("学费", "教育", "学习"),
        // ---- 烟酒 / 蔬果 ----
        Triple("超市发", "购物", "超市"),
        Triple("蔬菜", "购物", "超市"),
        Triple("水果", "购物", "超市"),
        Triple("烟草", "购物", "超市"),
        Triple("酒", "购物", "超市")
    )

    fun parse(text: String): Parsed {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val hints = mutableListOf<String>()

        // ---- 金额 ----
        val amount = pickAmount(lines, hints)

        // ---- 收支方向 ----
        val isIncome = INCOME_WORDS.any { text.contains(it) } &&
            !EXPENSE_WORDS.any { text.contains(it) }
        if (isIncome) hints.add("判断为收入（命中收款类关键词）")

        // ---- 日期 ----
        // 优先在带时间关键词的行里找，命中率更高
        val timeHintLines = lines.filter { line ->
            listOf("时间", "日期", "付款", "交易", "创建", "完成").any { line.contains(it) }
        }
        val dateSources = listOf(timeHintLines.joinToString("\n"), text)

        var dateMillis: Long? = null
        for (src in dateSources) {
            if (src.isBlank()) continue
            DATE_FULL.matcher(src).let { m ->
                while (m.find()) {
                    val y = m.group(1)?.toIntOrNull()
                    val mo = m.group(2)?.toIntOrNull()
                    val d = m.group(3)?.toIntOrNull()
                    val h = m.group(4)?.toIntOrNull()
                    val mi = m.group(5)?.toIntOrNull()
                    // 年份要合理，月份日期要合法，小时（如果有）要合法
                    val hourOk = h == null || h in 0..23
                    if (y != null && y in 2000..2100 && mo != null && mo in 1..12 &&
                        d != null && d in 1..31 && hourOk
                    ) {
                        dateMillis = toMillis(y, mo, d, h ?: 0, mi ?: 0)
                        hints.add("识别到时间 $y-${"%02d".format(mo)}-${"%02d".format(d)}" +
                            if (h != null) " ${"%02d".format(h)}:${"%02d".format(mi ?: 0)}" else "")
                        break
                    }
                }
            }
            if (dateMillis != null) break
        }

        // 兜底：没有年份的短日期，按今年算
        if (dateMillis == null) {
            DATE_SHORT.matcher(text).let { m ->
                if (m.find()) {
                    val mo = m.group(1)?.toIntOrNull()
                    val d = m.group(2)?.toIntOrNull()
                    val h = m.group(3)?.toIntOrNull() ?: 0
                    val mi = m.group(4)?.toIntOrNull() ?: 0
                    if (mo != null && d != null && mo in 1..12 && d in 1..31) {
                        val now = Calendar.getInstance()
                        dateMillis = toMillis(now.get(Calendar.YEAR), mo, d, h, mi)
                        hints.add("识别到日期 $mo-$d（按今年算）")
                    }
                }
            }
        }

        // ---- 商户 ----
        val merchant = pickMerchant(lines, hints)

        // ---- 支付方式 ----
        val payMethod = pickPayMethod(lines, hints)

        // ---- 分类推荐 ----
        val guess = suggestCategory(lines, merchant, hints)

        return Parsed(
            amountCents = amount,
            merchant = merchant,
            dateMillis = dateMillis,
            isIncome = isIncome,
            payMethod = payMethod,
            suggestParentName = guess?.parent,
            suggestChildName = guess?.child,
            rawText = text,
            hints = hints
        )
    }

    /**
     * 金额：整行独金额 → 强字段 → 弱字段 → 兜底，四档。
     *
     * 关键点（都是对着 5 张真实截图调的）：
     * - **整行独金额优先级最高**。支付宝/微信账单页的实付金额独占一行用大字显示，
     *   而「原价 ¥29.90」「订单金额 5.90」都是同行还有别的字 → 用 `^...$` 锚点就能分开。
     * - 弱/兜底档要按**行**排掉「原价/优惠/立减/余额」这些行。
     * - 同一档里多个候选取**最大** —— 真实付款额通常是这屏上最大的数。
     */
    private fun pickAmount(lines: List<String>, hints: MutableList<String>): Long? {
        // ⓿ 整行独金额：截图 2/3/4/5 的实付都是这个形态
        for (line in lines) {
            val m = AMOUNT_LONE_LINE.matcher(line)
            if (m.find()) {
                val cents = toCents(m.group(1) ?: continue) ?: continue
                if (cents > 0) {
                    hints.add("识别到金额 ¥${centsToText(cents)}")
                    return cents
                }
            }
        }
        // ① 强字段不限噪音行：「实付 30.00」这种行本身不可能是优惠行
        pickFirst(lines, AMOUNT_STRONG)?.let {
            hints.add("识别到金额 ¥${centsToText(it)}")
            return it
        }
        // ②③ 弱/兜底档要排噪音
        val clean = lines.filter { line ->
            AMOUNT_EXCLUDE_LINE.none { line.contains(it) } &&
                ID_FIELDS.none { line.contains(it) }
        }
        pickFirst(clean, AMOUNT_WEAK)?.let {
            hints.add("识别到金额 ¥${centsToText(it)}（订单/合计）")
            return it
        }
        pickFirst(clean, AMOUNT_LOOSE)?.let {
            hints.add("识别到金额 ¥${centsToText(it)}")
            return it
        }
        return null
    }

    private fun pickFirst(lines: List<String>, p: Pattern): Long? {
        var best: Long? = null
        for (line in lines) {
            val m = p.matcher(line)
            while (m.find()) {
                val raw = (m.group(1) ?: m.group(2))?.replace(",", "") ?: continue
                val cents = toCents(raw) ?: continue
                if (cents <= 0) continue
                if (best == null || cents > best) best = cents
            }
        }
        return best
    }

    /** 分类推荐结果：一级 + 二级 */
    private data class CategoryGuess(val parent: String, val child: String)

    /**
     * 从商户名和商品说明里猜一级分类。
     *
     * 顺序即优先级，[CATEGORY_RULES] 里越靠前越具体。
     * 「南虾北羊餐饮管理有限公司」→ 命中「餐饮」→ 餐饮；
     * 「北大生鲜超市」→ 命中「生鲜」→ 购物·超市；
     * 「太原肯德基有限公司」→ 命中「肯德基」→ 餐饮·快餐。
     *
     * **匹配源必须是整屏文本，不能只用简化后的商户名** ——
     * `shorten()` 会把「…餐饮管理有限公司」砍成「南虾北羊」，
     * 「餐饮」这个词就丢了，分类会漏判。
     */
    private fun suggestCategory(
        lines: List<String>,
        merchant: String?,
        hints: MutableList<String>
    ): CategoryGuess? {
        // 整屏可读文本（去掉单号/账号那些超长数字行）
        val noise = Pattern.compile("[0-9]{8,}")
        val source = buildString {
            merchant?.let { append(it).append('\n') }
            lines.forEach { line ->
                if (noise.matcher(line).find()) return@forEach
                append(line).append('\n')
            }
        }
        if (source.isBlank()) return null
        for ((kw, parent, child) in CATEGORY_RULES) {
            if (source.contains(kw)) {
                hints.add("按「$kw」推荐：$parent · $child")
                return CategoryGuess(parent, child)
            }
        }
        return null
    }

    /** 支付方式：先找「支付方式：xxx」这种字段名形态，再找已知渠道关键词，最后找带尾号的银行卡。 */
    private fun pickPayMethod(lines: List<String>, hints: MutableList<String>): String? {
        // 1. 字段名形态：支付方式  招商银行储蓄卡(1234)  /  支付方式：零钱
        for (line in lines) {
            for (key in PAY_METHOD_KEYS) {
                val idx = line.indexOf(key)
                if (idx < 0) continue
                var value = line.substring(idx + key.length)
                    .trimStart(':', '：', ' ', '\t')
                // 字段名和值分两行的情况：下一行就是值
                if (value.length < 2) {
                    val li = lines.indexOf(line)
                    value = lines.getOrNull(li + 1)?.trim().orEmpty()
                }
                if (value.length in 2..24) {
                    // 值里混了别的字段名就截断（OCR 常把两行拼一行）
                    val cut = value.indexOfAny(charArrayOf('订', '单', '时', '金', '备', '商'))
                    if (cut in 1 until value.length) value = value.substring(0, cut)
                    if (value.length >= 2) {
                        hints.add("识别到支付方式：$value")
                        return value
                    }
                }
            }
        }

        // 2. 已知渠道关键词（微信/支付宝/云闪付的付款详情页会直接写「零钱」「花呗」）
        for (line in lines) {
            if (line.length > 24) continue
            for (p in PAY_METHODS) {
                if (line.contains(p)) {
                    // 同行还带卡号尾号就一起带上：招商银行储蓄卡(1234)
                    val m = CARD_TAIL.matcher(line)
                    if (m.find()) {
                        val name = m.group(1)?.trim().orEmpty()
                        val tail = m.group(2)?.trim().orEmpty()
                        if (name.length >= 2) {
                            val v = if (tail.isNotEmpty()) "$name($tail)" else name
                            hints.add("识别到支付方式：$v")
                            return v
                        }
                    }
                    hints.add("识别到支付方式：$p")
                    return p
                }
            }
        }

        // 3. 兜底：整段文本里找带尾号的银行卡形态
        CARD_TAIL.matcher(lines.joinToString("\n")).let { m ->
            if (m.find()) {
                val name = m.group(1)?.trim().orEmpty()
                val tail = m.group(2)?.trim().orEmpty()
                if (name.length >= 2) {
                    val v = if (tail.isNotEmpty()) "$name($tail)" else name
                    hints.add("推测支付方式：$v")
                    return v
                }
            }
        }
        return null
    }

    private fun pickMerchant(lines: List<String>, hints: MutableList<String>): String? {
        // 先把「收单机构 / 清算机构」这些行整体剔掉 —— 里面的公司名不是商户。
        // 截图 3 的「易宝支付有限公司」是收单机构、「中国建设银行…山西省分行」是清算机构。
        val orgMarked = lines.filter { line -> ORG_FIELDS.any { line.contains(it) } }
        val candidates = lines.filter { line -> ORG_FIELDS.none { line.contains(it) } }

        // 1. 商户字段名（截图 3/4/5 都有「商户全称」）
        for (key in MERCHANT_FIELDS) {
            for (line in candidates) {
                val idx = line.indexOf(key)
                if (idx < 0) continue
                var value = line.substring(idx + key.length)
                    .trimStart(':', '：', ' ', '\t')
                // 字段名和值分两行的形态：下一行就是值
                if (value.length < 2) {
                    val li = lines.indexOf(line)
                    value = lines.getOrNull(li + 1)?.trim().orEmpty()
                }
                if (isPlausibleMerchant(value)) {
                    val short = shorten(value)
                    hints.add("识别到商户：$short")
                    return short
                }
            }
        }

        // 2. 商品说明（截图 5「北大生鲜超市:消费」；截图 2「收钱码收款」）
        for (key in listOf("商品说明", "商品", "项目")) {
            for (line in candidates) {
                val idx = line.indexOf(key)
                if (idx < 0) continue
                var value = line.substring(idx + key.length).trimStart(':', '：', ' ', '\t')
                // 「北大生鲜超市:消费」取冒号前的店名部分
                val colon = value.indexOf(':')
                if (colon > 1) value = value.substring(0, colon)
                if (isPlausibleMerchant(value)) {
                    val short = shorten(value)
                    hints.add("识别到商户：$short")
                    return short
                }
            }
        }

        // 3. 带商户后缀的短行（含「餐饮」「超市」「有限公司」等）
        for (line in candidates) {
            if (line.length in 2..20 && MERCHANT_SUFFIX.any { line.contains(it) } &&
                isPlausibleMerchant(line)
            ) {
                hints.add("识别到商户：$line")
                return line
            }
        }

        // 4. 兜底：长度合适、不含噪音、不含长数字、不含货币符号的行
        val longDigits = Pattern.compile("[0-9]{4,}")
        val filtered = candidates.filter { line ->
            line.length in 2..18 &&
                MERCHANT_NOISE.none { line.contains(it) } &&
                !longDigits.matcher(line).find() &&
                !line.any { it in "¥￥" }
        }
        val candidate = filtered.firstOrNull()
        if (candidate != null) {
            hints.add("推测商户：$candidate")
            return candidate
        }
        // 5. 最后实在没有，返回机构名里最像店名的一个（总比没有强）
        val org = orgMarked.firstOrNull { it.length in 4..24 }
        if (org != null) hints.add("商户名不确定，用了机构名")
        return org
    }

    /** 6 位以上连续数字 = 订单号/账号/卡号，不是商户名 */
    private val LONG_DIGITS = Pattern.compile("[0-9]{6,}")

    /** 商户名合理性检查：长度、噪音词、长数字、纯字段名 */
    private fun isPlausibleMerchant(v: String): Boolean {        val s = v.trim()
        if (s.length !in 2..40) return false
        if (MERCHANT_NOISE.any { s.contains(it) }) return false
        if (ORG_FIELDS.any { s.contains(it) }) return false
        // 含 6 位以上连续数字 = 订单号/账号
        if (LONG_DIGITS.matcher(s).find()) return false
        // 纯数字
        if (s.all { it.isDigit() || it == '.' || it == '-' }) return false
        return true
    }

    /**
     * 公司名简化成品牌名，记账备注里太长的公司名没意义。
     * 「南京南虾北羊餐饮管理有限公司」→「南虾北羊」
     * 「太原肯德基有限公司」→「肯德基」
     */
    private fun shorten(v: String): String {
        var s = v.trim()
        // 去掉公司后缀
        for (suffix in listOf("股份有限公司", "有限责任公司", "有限公司", "公司")) {
            if (s.endsWith(suffix) && s.length > suffix.length + 1) {
                s = s.dropLast(suffix.length)
                break
            }
        }
        // 去掉常见地域前缀（OCR 常把「南京」识别到最前面）
        for (prefix in CITIES) {
            if (s.startsWith(prefix) && s.length > prefix.length + 1) {
                s = s.drop(prefix.length)
                break
            }
        }
        // 尾部常见的行业后缀，保留品牌但砍掉「餐饮管理」这种
        for (suffix in listOf("餐饮管理", "餐饮服务", "商业管理", "企业管理", "连锁经营")) {
            if (s.endsWith(suffix) && s.length > suffix.length) {
                s = s.dropLast(suffix.length)
                break
            }
        }
        return s.trim().take(20).ifEmpty { v.trim().take(20) }
    }

    private val CITIES = listOf(
        "北京", "上海", "广州", "深圳", "南京", "杭州", "成都", "武汉", "西安",
        "苏州", "天津", "重庆", "青岛", "郑州", "长沙", "济南", "合肥", "福州",
        "厦门", "昆明", "大连", "沈阳", "哈尔滨", "太原", "宁波", "无锡", "石家庄"
    )

    private fun toCents(raw: String): Long? {
        val s = raw.trim().removePrefix("-")
        if (s.isEmpty()) return null
        val parts = s.split('.')
        val yuan = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        if (yuan > 99_999_999L) return null   // 明显是订单号之类，剔除
        val cents = if (parts.size > 1) {
            parts[1].padEnd(2, '0').take(2).toLongOrNull() ?: 0L
        } else 0L
        return yuan * 100 + cents
    }

    private fun toMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, y)
            set(Calendar.MONTH, mo - 1)
            set(Calendar.DAY_OF_MONTH, d)
            set(Calendar.HOUR_OF_DAY, h.coerceIn(0, 23))
            set(Calendar.MINUTE, mi.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun centsToText(cents: Long): String = "%d.%02d".format(cents / 100, cents % 100)
}
