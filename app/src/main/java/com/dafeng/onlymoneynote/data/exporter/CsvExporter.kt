package com.dafeng.onlymoneynote.data.exporter

import com.dafeng.onlymoneynote.data.local.TxType
import com.dafeng.onlymoneynote.data.local.TxWithCategory
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 把账单导出成 CSV，给 Excel / 其他记账软件看。
 *
 * 表头跟 [com.dafeng.onlymoneynote.data.importer.RecordsCsvImporter] 对齐：
 * `日期, 一级分类, 二级分类, 收支, 金额, 备注, 报销, 账户`
 * 导出的文件能原样再导回本 App（账户按名字匹配现有账户，认不出来归「未指定」）。
 *
 * **一级、二级分成两列**，方便在 Excel 里做透视表或按大类汇总。
 * 账单直接挂在一级上（没选二级）时，二级列留空。
 *
 * 转义按 RFC 4180：含逗号/引号/换行的字段用双引号包起来，里面的 `"` 翻倍。
 */
object CsvExporter {

    /**
     * 表头。
     *
     * 末尾三列「账户→ / 转出账户 / 转入账户」是给转账用的：
     * 转账要同时表达「钱从哪来、到哪去」，单靠一个「账户」列装不下。
     * 收支行这三列留空，转账行「账户」列写「甲 → 乙」方便人看。
     *
     * 导入侧（[RecordsCsvImporter]）认这三列，所以**导出的文件能原样导回来**。
     */
    private const val HEADER =
        "日期,一级分类,二级分类,收支,金额,备注,报销,账户,账户→,转出账户,转入账户"
    private const val BOM = "\uFEFF"

    private val dateFmt = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.CHINA)
    }

    /** 生成 CSV 文本（带 BOM，Excel 打开中文不乱码）。 */
    fun build(rows: List<TxWithCategory>): String {
        val sb = StringBuilder()
        sb.append(BOM).append(HEADER).append('\n')
        for (r in rows) {
            // 一级 = 父分类名；二级 = 具体那个分类名。
            // 账单直接挂在一级上时（没选二级），二级留空。
            sb.append(dateFmt.get()!!.format(r.dateMillis)).append(',')
            sb.append(esc(r.parentName.orEmpty())).append(',')
            sb.append(esc(r.categoryName)).append(',')
            // 转账不是收支，单列一个值：导出去在别的软件里也不会被当成收入/支出
            sb.append(
                when (r.type) {
                    TxType.INCOME.value -> "收入"
                    TxType.TRANSFER.value -> "转账"
                    else -> "支出"
                }
            ).append(',')
            sb.append(yuan(r.amountCents)).append(',')
            sb.append(esc(r.note)).append(',')
            sb.append(if (r.reimbursed) "是" else "").append(',')

            if (r.type == TxType.TRANSFER.value) {
                // 转账：账户列写「甲 → 乙」给人看，同时把两边拆进
                // 「转出账户 / 转入账户」两列 —— 导入侧靠这两列还原，
                // 只靠带箭头的字符串拆是靠不住的（账户名里可能有箭头）
                sb.append(esc(r.accountName + " → " + r.toAccountName)).append(',')
                sb.append(esc(r.accountName)).append(',')
                sb.append(esc(r.toAccountName)).append('\n')
            } else {
                sb.append(esc(r.accountName)).append(",,,\n")
            }
        }
        return sb.toString()
    }

    /** 分 -> 元，保留两位小数。不要用浮点数，直接走整数格式化。 */
    private fun yuan(cents: Long): String {
        val neg = cents < 0
        val abs = if (neg) -cents else cents
        val s = "${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
        return if (neg) "-$s" else s
    }

    private fun esc(s: String): String {
        val clean = s.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim()
        return if (clean.any { it == ',' || it == '"' }) {
            "\"" + clean.replace("\"", "\"\"") + "\""
        } else clean
    }
}
