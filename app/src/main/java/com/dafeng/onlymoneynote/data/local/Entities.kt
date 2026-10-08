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

@Entity(
    tableName = "transaction",
    indices = [Index("categoryId"), Index("dateMillis"), Index("reimbursed")]
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
    val reimbursed: Boolean = false
)
