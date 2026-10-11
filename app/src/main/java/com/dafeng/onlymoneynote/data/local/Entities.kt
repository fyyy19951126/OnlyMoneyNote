package com.dafeng.onlymoneynote.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一级分类 / 二级分类，用 parentId 自关联，null 表示一级。
 * sortOrder 控制显示顺序；builtIn 标记内置分类（不可删）。
 * type 区分这套分类属于支出还是收入（0 = 支出，1 = 收入）。
 */
@Entity(
    tableName = "category",
    indices = [Index("parentId")]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconKey: String,
    val parentId: Long? = null,
    val sortOrder: Int = 0,
    val builtIn: Boolean = false,
    val type: Int = 0,
    /** 自定义图标块配色（"#RRGGBB"）。空 = 跟随图标分组色；二级分类继承一级的配色 */
    val colorKey: String = ""
)

/** 收支类型：0 = 支出，1 = 收入，2 = 转账 */
enum class TxType(val value: Int) {
    EXPENSE(0),
    INCOME(1),

    /**
     * 转账：钱在两个自己的账户之间挪位置，**不是**收入也不是支出。
     *
     * 存在的原因是「总资产」：转账不改变总资产，只是从一个账户挪到另一个。
     * 没有它的话，银行卡转 500 到支付宝这件事在 App 里根本记不下来，
     * 支付宝账户的余额就会莫名其妙变负。
     *
     * 转账用 [TransactionEntity.toAccountId] 记转入方（转出方就是 accountId）。
     * categoryId 对转账没有意义，统一存 0。
     */
    TRANSFER(2);

    /** 统计、报销、CSV 都只认收支两类，转账一律排除在外 */
    val isFlow: Boolean get() = this == EXPENSE || this == INCOME

    companion object {
        fun from(value: Int): TxType = when (value) {
            1 -> INCOME
            2 -> TRANSFER
            else -> EXPENSE
        }
    }
}

/**
 * 资金账户（支付宝 / 微信 / 银行卡 …）。
 *
 * **余额不单独存**：当前余额 = [initialCents] + 该账户名下账单的净流水
 * （收入加、支出减）。这样删改账单天然就回滚了，不需要补偿逻辑。
 * [initialCents] 是「期初金额」，用户在账户管理里自己填。
 */
@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconKey: String,
    val sortOrder: Int = 0,
    /** 期初金额，单位「分」，可以为负（比如信用卡欠款） */
    val initialCents: Long = 0,
    /** 内置账户（「未指定」）不可删 */
    val builtIn: Boolean = false,
    /** 图标块配色（"#RRGGBB"）。空 = 按图标分组色 */
    val colorKey: String = ""
) {
    companion object {
        /** 老账单和没选账户的账单都归它；它的 id 由迁移和种子共同固定 */
        const val UNSPECIFIED_ID = 1L
        const val UNSPECIFIED_NAME = "未指定"
    }
}

@Entity(
    tableName = "transaction",
    indices = [
        Index("categoryId"),
        Index("dateMillis"),
        Index("reimbursed"),
        Index("isReimburse"),
        Index("accountId"),
        Index("toAccountId")
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 金额，单位「分」，避免浮点误差。转账时是转账金额（正数） */
    val amountCents: Long,
    /** [TxType.value]：0 支出 / 1 收入 / 2 转账 */
    val type: Int = 0,
    /** 转账没有分类，统一存 0 */
    val categoryId: Long,
    val dateMillis: Long,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * 是否**已经报过销**。
     *
     * ⚠️ 2026-10-11 语义变了。原来这个字段是「这笔跟报销有关」（报销页
     * `WHERE reimbursed = 1` 把所有报销账单都列出来），现在改成「这笔报过了」——
     * 因为报销页要分「已报销 / 未报销」两个 tab，光靠一个布尔不够用，
     * 得把「属于报销池」和「已报销」拆成两件事。
     *
     * 现在：[isReimburse] 决定进不进报销池，本字段决定进哪个 tab。
     * 老数据全按「isReimburse=true 且 reimbursed=false」迁移，
     * 也就是全部退回未报销 —— 宁可多报一次，不能凭空消失。
     */
    val reimbursed: Boolean = false,
    /**
     * 是否属于**报销池**（这笔钱是可以/正在走报销流程的）。
     *
     * 跟 [reimbursed] 的区别：报销池里有「还没报的」也有「报完的」，
     * 报销页只列 isReimburse=true 的，再按 reimbursed 分到两个 tab。
     *
     * 钱迹导入时 F 列「类型」= 报销 的行就是 true。
     */
    val isReimburse: Boolean = false,
    /**
     * 实际报销日期（毫秒）。0 = 还没报。已报销的行这里有值，
     * 用于在列表里显示「10-08 花的钱，09-27 报的」。
     *
     * 单独存字段而不是塞 note：note 会被用户编辑，塞进去会被覆盖掉。
     */
    val reimburseDateMillis: Long = 0L,
    /** 资金账户。默认「未指定」，导入老数据时不用改。转账时这是**转出方** */
    val accountId: Long = AccountEntity.UNSPECIFIED_ID,
    /**
     * 转账的**转入方**账户 id。非转账一律 0。
     *
     * 余额计算靠它：转账对转出方是减、对转入方是加，对总额是 0。
     */
    val toAccountId: Long = 0L
)
