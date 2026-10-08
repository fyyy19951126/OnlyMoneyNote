package com.dafeng.onlymoneynote.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 金额表达式求值测试。
 *
 * 计算器**只支持加减**（用户明确要求「只有加减功能的简单计算器」），
 * 乘除当作非法表达式返回 null。这块是纯字符串进纯字符串出，最适合单测锁住。
 */
class CalculatorTest {

    @Test
    fun `加法`() {
        assertEquals("3", calculate("1+2"))
        assertEquals("30", calculate("10+20"))
    }

    @Test
    fun `减法支持多步`() {
        assertEquals("5", calculate("10-3-2"))
        // 全角减号也认
        assertEquals("5", calculate("10−3−2"))
        // 减成负数
        assertEquals("-5", calculate("5−10"))
    }

    @Test
    fun `加减混合`() {
        assertEquals("0", calculate("1+2-3"))
        assertEquals("8", calculate("3+10-5"))
    }

    @Test
    fun `小数加减不出现浮点噪声`() {
        // 0.1 + 0.2 在 double 里是 0.30000000000000004，必须格式化掉
        assertEquals("0.3", calculate("0.1+0.2"))
        assertEquals("1.1", calculate("0.7+0.4"))
    }

    @Test
    fun `整数结果不带小数尾巴`() {
        assertEquals("5", calculate("2.5+2.5"))
        assertEquals("100", calculate("50+50"))
    }

    @Test
    fun `乘除已不支持，返回 null`() {
        assertNull(calculate("12×2"))
        assertNull(calculate("100÷4"))
        assertNull(calculate("1+2×3"))
    }

    @Test
    fun `空串和纯运算符返回 null`() {
        assertNull(calculate(""))
        assertNull(calculate("   "))
        assertNull(calculate("+"))
    }

    @Test
    fun `结尾挂着运算符按容错处理`() {
        // 用户按了「1 +」就点保存，意图是 1，不该报错
        assertEquals("1", calculate("1+"))
        assertEquals("5", calculate("5−"))
    }

    @Test
    fun `连续运算符非法`() {
        assertNull(calculate("1++2"))
    }

    @Test
    fun `单个数字原样返回`() {
        assertEquals("12.34", calculate("12.34"))
        assertEquals("8", calculate("8"))
    }

    @Test
    fun `实际记账场景`() {
        // 早餐 12.5 + 打车 23.8
        assertEquals("36.3", calculate("12.5+23.8"))
        // 100 减掉 30 退款
        assertEquals("70", calculate("100-30"))
    }
}
