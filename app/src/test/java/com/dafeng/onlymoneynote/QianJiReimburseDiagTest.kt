package com.dafeng.onlymoneynote

import com.dafeng.onlymoneynote.data.importer.XlsxReader
import org.junit.Test
import java.io.File

/** 临时诊断：摸清钱迹「支出 / 报销 / 报销记录」三者和「关联账单」列的关系 */
class QianJiReimburseDiagTest {

    @Test
    fun dumpReimburseStructure() {
        val f = File("C:/Users/203/Downloads/QianJi_日常账本_2026-10-11_175731.xlsx")
        if (!f.isFile) return
        val rows = XlsxReader.readFirstSheet(f.readBytes())
        val header = rows.first().map { it.trim() }
        println("[d] header:")
        header.forEachIndexed { i, h -> println("[d]   col$i(${(65+i).toChar()}) = $h") }

        fun col(name: String) = header.indexOfFirst { it == name }
        val iId = col("ID"); val iType = col("类型"); val iTime = col("时间")
        val iCat = col("分类"); val iAmt = col("金额"); val iReimb = col("已报销")
        val iLink = col("关联账单"); val iNote = col("备注"); val iKeeper = col("记账者")
        val iAcc1 = col("账户1"); val iAcc2 = col("账户2"); val iBook = col("账本")

        println("[d] idx: id=$iId type=$iType time=$iTime cat=$iCat amt=$iAmt reimb=$iReimb link=$iLink note=$iNote keeper=$iKeeper acc1=$iAcc1 acc2=$iAcc2 book=$iBook")

        fun at(r: List<String>, i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""
        val data = rows.drop(1)

        // 1) 各类型的「已报销」列取值分布
        val reimbByType = HashMap<String, HashMap<String, Int>>()
        val linkByType = HashMap<String, HashMap<String, Int>>()
        data.forEach { r ->
            val t = at(r, iType)
            reimbByType.getOrPut(t) { HashMap() }.let { m ->
                val k = at(r, iReimb).ifBlank { "<空>" }
                m[k] = (m[k] ?: 0) + 1
            }
            linkByType.getOrPut(t) { HashMap() }.let { m ->
                val k = if (at(r, iLink).isBlank()) "<空>" else "有值"
                m[k] = (m[k] ?: 0) + 1
            }
        }
        println("[d] 已报销列分布: $reimbByType")
        println("[d] 关联账单列分布: $linkByType")

        // 2) 三种类型的样本行
        listOf("支出", "报销", "报销记录").forEach { t ->
            println("[d] --- $t 样本 ---")
            data.filter { at(it, iType) == t }.take(4).forEach { r ->
                println("[d]   ID=${at(r,iId)} 时间=${at(r,iTime)} 分类=${at(r,iCat)} 金额=${at(r,iAmt)} 已报销='${at(r,iReimb)}' 关联='${at(r,iLink)}' 备注='${at(r,iNote)}' 记账者=${at(r,iKeeper)} 账户1=${at(r,iAcc1)} 账户2=${at(r,iAcc2)} 账本=${at(r,iBook)}")
            }
        }

        // 3) 「关联账单」指向的 ID 是什么类型的行？
        val byId = HashMap<String, List<String>>()
        data.forEach { r -> byId[at(r, iId)] = r }
        var linkToExpense = 0; var linkToReimb = 0; var linkToOther = 0; var linkMissing = 0
        val linkSamples = mutableListOf<String>()
        data.filter { at(it, iType) != "报销记录" }.forEach { r ->
            val l = at(r, iLink)
            if (l.isBlank()) return@forEach
            val target = byId[l]
            when {
                target == null -> { linkMissing++; if (linkSamples.size < 3) linkSamples.add("找不到 $l") }
                at(target, iType) == "支出" -> linkToExpense++
                at(target, iType) == "报销" -> linkToReimb++
                else -> { linkToOther++; if (linkSamples.size < 3) linkSamples.add("$l -> ${at(target,iType)}") }
            }
        }
        println("[d] 关联账单指向: 支出=$linkToExpense 报销=$linkToReimb 其他=$linkToOther 找不到=$linkMissing")
        linkSamples.forEach { println("[d]   sample: $it") }

        // 4) 「报销记录」行自身有没有关联账单？它的关联指向谁？
        var rbWithLink = 0
        data.filter { at(it, iType) == "报销记录" }.forEach { r ->
            if (at(r, iLink).isNotBlank()) {
                rbWithLink++
                if (rbWithLink <= 4) {
                    val tgt = byId[at(r, iLink)]
                    println("[d] 报销记录 ID=${at(r,iId)} 关联=${at(r,iLink)} -> ${if(tgt==null)"不存在" else "${at(tgt,iType)} 金额=${at(tgt,iAmt)} 时间=${at(tgt,iTime)}"}")
                }
            }
        }
        println("[d] 报销记录中带关联账单的行数=$rbWithLink")

        // 5) 金额是否为负数（区分收支方向）
        val amtSign = HashMap<String, HashMap<String, Int>>()
        data.forEach { r ->
            val t = at(r, iType)
            val a = at(r, iAmt)
            val sign = when {
                a.startsWith("-") -> "负"
                a.startsWith("+") -> "正号"
                a.isEmpty() -> "空"
                else -> "无符号"
            }
            amtSign.getOrPut(t) { HashMap() }.let { m -> m[sign] = (m[sign] ?: 0) + 1 }
        }
        println("[d] 金额符号分布: $amtSign")
    }
}