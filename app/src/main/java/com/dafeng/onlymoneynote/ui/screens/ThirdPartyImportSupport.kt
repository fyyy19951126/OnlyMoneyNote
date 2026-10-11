package com.dafeng.onlymoneynote.ui.screens

import com.dafeng.onlymoneynote.data.importer.QianJiImporter
import com.dafeng.onlymoneynote.data.importer.RecordsCsvImporter

/** 支持导入的第三方记账软件 */
enum class ThirdPartyKind(val label: String, val hint: String) {
    QIANJI("钱迹 QianJi", "导出 Excel（xlsx）账单明细"),
    /** 已有 [RecordsCsvImporter] 那条路径就是大象记账的表头，直接复用 */
    ELEPHANT("大象记账", "导出 CSV 账单明细")
}

/** 选中软件 + 文件之后、确认之前暂存的东西 */
data class ThirdPartyPick(
    val kind: ThirdPartyKind,
    val name: String,
    val bytes: ByteArray
) {
    // data class + ByteArray 会用引用相等，Compose 的 remember 键会失效
    override fun equals(other: Any?): Boolean =
        other is ThirdPartyPick && kind == other.kind && name == other.name &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        (kind.hashCode() * 31 + name.hashCode()) * 31 + bytes.contentHashCode()
}

/** 统一解析结果：不管哪个软件，出来的东西形状一样 */
data class ThirdPartyParse(
    val rows: List<RecordsCsvImporter.RawRow>,
    val transfers: List<QianJiImporter.Transfer>,
    val skippedCurrency: Int,
    val skippedBad: Int,
    /**
     * 已被对方软件标记「报过了」的账单序号（对应 [rows] 的下标）。
     * 大象记账是 CSV，没有这个概念，恒为空集。
     */
    val reimbursedIndexes: Set<Int> = emptySet(),
    /** 实际报销日期，key 同行号。0 = 对方数据里查不到 */
    val reimburseDates: Map<Int, Long> = emptyMap()
)

/**
 * 第三方账单导入的调度层：把「哪个软件」映射到「用哪个解析器」。
 *
 * 归类到一级/二级这一步**完全复用 [RecordsCsvImporter]** ——
 * 所以从钱迹导进来的「话费网费」也会自动挂到「通信」下面，
 * 不需要为每个软件各写一套分类映射。
 */
object ThirdPartyImportSupport {

    fun parse(kind: ThirdPartyKind, bytes: ByteArray): ThirdPartyParse = when (kind) {
        ThirdPartyKind.QIANJI -> {
            val r = QianJiImporter.parse(bytes)
            ThirdPartyParse(
                rows = r.rows,
                transfers = r.transfers,
                skippedCurrency = r.skippedCurrency,
                skippedBad = r.skippedBad,
                reimbursedIndexes = r.reimbursedIndexes,
                reimburseDates = r.reimburseDates
            )
        }
        ThirdPartyKind.ELEPHANT -> {
            // CSV 那条路径本来就吃大象记账的表头；这里把结果包成同一个形状
            val rows = RecordsCsvImporter.parse(RecordsCsvImporter.decode(bytes))
            ThirdPartyParse(rows, emptyList(), 0, 0)
        }
    }

    /**
     * 把解析结果喂给 CSV 那套映射表，算出要建哪些分类，
     * 并按序号把「实际报销日期」回填到对应账单上。
     *
     * 报销日期只能在归类**之前**靠原始行序找（对方文件里是「报销记录行关联到支出行」），
     * 归类完那层关系就没了。所以先 buildPlanFromRows，再按 indexes 补日期。
     */
    fun planOf(parsed: ThirdPartyParse): RecordsCsvImporter.Plan =
        RecordsCsvImporter.applyReimburseDates(
            RecordsCsvImporter.buildPlanFromRows(parsed.rows),
            parsed.reimbursedIndexes,
            parsed.reimburseDates
        )
}
