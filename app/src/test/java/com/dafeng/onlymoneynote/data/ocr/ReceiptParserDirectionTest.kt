package com.dafeng.onlymoneynote.data.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收支方向判定的回归测试。
 *
 * 起因（2026-10-11 用户反馈）：样本首行是「微信支付」，命中 EXPENSE_WORDS 里的
 * 「支付」，把一笔**收款**截图直接否成了支出。
 *
 * 根因：EXPENSE_WORDS 是**动作词表**（「支付」「付款」「扣款」），
 * 而「微信支付 / 支付宝 / 云闪付」是**渠道名**、不是动作 ——
 * 而且它们出现在每一张支付截图上，等于收入永远判不出来。
 *
 * 修法：判支出前先把渠道名从文本里剥掉，再找动作词。
 */
class ReceiptParserDirectionTest {

    @Test
    fun `收款截图里带微信支付时仍应判为收入`() {
        val text = """
            微信支付
            已收款
            金额 ¥50.00
            收款方 床前明月光
        """.trimIndent()
        val r = ReceiptParser.parse(text)
        assertTrue("带「微信支付」的收款应是收入，实际被判成了支出", r.isIncome)
    }

    @Test
    fun `支付宝渠道名不会否掉收入`() {
        val text = """
            支付宝
            收款成功
            金额 ¥100.00
        """.trimIndent()
        assertTrue(ReceiptParser.parse(text).isIncome)
    }

    @Test
    fun `云闪付渠道名不会否掉收入`() {
        val text = """
            云闪付
            已收款 ¥20.00
        """.trimIndent()
        assertTrue(ReceiptParser.parse(text).isIncome)
    }

    @Test
    fun `真正的支付动作仍应判为支出`() {
        val text = """
            支付成功
            付款金额 ¥35.50
            付款方式 零钱
        """.trimIndent()
        assertFalse(ReceiptParser.parse(text).isIncome)
    }

    @Test
    fun `收款和支付同时出现时以支出为准`() {
        // 「收付款方」这类周边文案会命中收入词，但有明确支付动作 → 支出
        val text = """
            支付成功
            收付款方
            付款金额 ¥8.00
        """.trimIndent()
        assertFalse(ReceiptParser.parse(text).isIncome)
    }
}
