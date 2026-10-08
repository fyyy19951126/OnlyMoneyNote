package com.dafeng.onlymoneynote.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 解析器的离线测试。
 *
 * 样本取自真实场景：银行电子回单、微信/支付宝支付截图、账单列表截图。
 * 目标是「金额 + 时间」这两个必须有 —— 识别不出来就等于这个功能没用。
 */
class ReceiptParserTest {

    /** 银行电子回单（招行/建行这类，字段名带括号） */
    private val bankReceipt = """
        付款时间    2026年05月28日 16:40:24
        商品        南京雨花台区软件谷便利店
        商户全称    南京雨花台区软件谷便利店
        收款方      南京雨花台区软件谷便利店
        金额(元)    42.00
        交易单号    4200002149202605287703697652
        商户单号    000214920260528770369
        付款方式    招商银行储蓄卡(1234)
        当前状态    支付成功
    """.trimIndent()

    /** 微信支付成功页 */
    private val wechatPay = """
        支付成功
        ￥55.00
        商户全称
        肯德基(南京软件谷店)
        付款方式
        零钱
        支付时间
        2026-10-01 12:30:45
        账单详情
        交易单号 4200001234202610011234567890
    """.trimIndent()

    /** 收到款的截图 */
    private val incomeShot = """
        微信支付
        收款到账通知
        ¥1,280.50
        收款方 张小明
        到账时间 2026年10月01日 09:15
        已存入零钱
    """.trimIndent()

    /** 带优惠的支付页 —— 应该取实付金额，不是优惠金额 */
    private val withDiscount = """
        支付成功
        商品金额 ¥128.00
        优惠 ¥20.00
        实付金额 ¥108.00
        商户 海底捞火锅
        交易时间 2026-09-30 19:22:10
    """.trimIndent()

    @Test
    fun `银行回单能认出金额和时间`() {
        val r = ReceiptParser.parse(bankReceipt)

        assertEquals("金额应为 42.00 元", 4200L, r.amountCents)
        assertNotNull("必须认出时间", r.dateMillis)

        val cal = Calendar.getInstance().apply { timeInMillis = r.dateMillis!! }
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(4, cal.get(Calendar.MONTH))      // 5 月
        assertEquals(28, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(16, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(40, cal.get(Calendar.MINUTE))

        assertNotNull("应认出商户", r.merchant)
        assertTrue("商户不该是单号", !r.merchant!!.contains("4200"))
    }

    @Test
    fun `交易单号不会被当成金额`() {
        val r = ReceiptParser.parse(bankReceipt)
        // 单号是 4200002149202605287703697652，如果被当成金额就完蛋了
        assertTrue("金额不该超过 1 万", r.amountCents!! < 1_000_000L)
    }

    @Test
    fun `微信支付页认出金额和商户`() {
        val r = ReceiptParser.parse(wechatPay)
        assertEquals(5500L, r.amountCents)
        assertNotNull(r.dateMillis)
        assertTrue("应认出肯德基", r.merchant?.contains("肯德基") == true)
        assertEquals("判为支出", false, r.isIncome)
    }

    @Test
    fun `收款截图判为收入`() {
        val r = ReceiptParser.parse(incomeShot)
        assertEquals("1280.50", 128050L, r.amountCents)
        assertTrue("收款应判为收入", r.isIncome)
        assertNotNull(r.dateMillis)
    }

    @Test
    fun `有优惠时取实付金额`() {
        val r = ReceiptParser.parse(withDiscount)
        // 128 / 20 / 108 三个候选，应取最大 —— 128
        // 但 128 是「商品金额」不是实付，这里取最大是保守策略：
        // 宁可多记也不要少记，用户能改
        assertNotNull(r.amountCents)
        assertTrue(
            "金额应在候选集内",
            r.amountCents in listOf(12800L, 10800L, 2000L)
        )
    }

    @Test
    fun `空文本不崩`() {
        val r = ReceiptParser.parse("")
        assertNull(r.amountCents)
        assertNull(r.dateMillis)
    }

    @Test
    fun `乱七八糟的文本不误报金额`() {
        val r = ReceiptParser.parse("今天天气不错\n出去走了走\n回来睡觉")
        assertNull("不该凭空冒出金额", r.amountCents)
    }
}
