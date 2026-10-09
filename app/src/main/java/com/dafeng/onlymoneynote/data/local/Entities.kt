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

/** 收支类型：0 = 支出，1 = 收入 */
enum class TxType(val value: Int) {
    EXPENSE(0),
    INCOME(1);

    companion object {
        fun from(value: Int): TxType = if (value == 1) INCOME else EXPENSE
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
    indices = [Index("categoryId"), Index("dateMillis"), Index("reimbursed"), Index("accountId")]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 金额，单位「分」，避免浮点误差 */
    val amountCents: Long,
    val type: Int = 0,
    val categoryId: Long,
    val dateMillis: Long,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** 是否是报销相关的账单。首页「报销」入口只统计、只列出这一类。 */
    val reimbursed: Boolean = false,
    /** 资金账户。默认「未指定」，导入老数据时不用改 */
    val accountId: Long = AccountEntity.UNSPECIFIED_ID
)
