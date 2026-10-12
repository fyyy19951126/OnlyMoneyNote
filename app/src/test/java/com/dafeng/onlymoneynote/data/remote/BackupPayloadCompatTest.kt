package com.dafeng.onlymoneynote.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份格式的向前兼容测试。
 *
 * 报销功能加进来之后，账单多了两个字段：`isReimburse`（属不属于报销池）和
 * `reimburseDateMillis`（实际报销日期）。这两个字段之前**根本没进备份** ——
 * 于是云端同步和 JSON 导入导出往返一次，所有报销账单从报销页整个消失
 * （池子按 isReimburse 查）、已报销日期清零。数据还在，但等于丢了。
 *
 * 这里只测序列化层：[BackupRepository] 依赖 Room，不好单测；
 * 转换代码本身（传出/回写字段）靠这个测试锁住字段名和默认值就够了。
 */
class BackupPayloadCompatTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `新版本的报销字段会被写出去`() {
        val p = BackupPayload(
            version = BackupPayload.CURRENT_VERSION,
            categories = emptyList(),
            transactions = listOf(
                BackupTransaction(
                    id = 1, amountCents = 8800, type = 0, categoryId = 3,
                    dateMillis = 1_700_000_000_000, note = "出差垫付",
                    reimbursed = true, accountId = 1, toAccountId = 0,
                    isReimburse = true, reimburseDateMillis = 1_700_800_000_000
                )
            )
        )
        val text = json.encodeToString(BackupPayload.serializer(), p)
        assertTrue("isReimburse 必须在 JSON 里", text.contains("isReimburse"))
        assertTrue("reimburseDateMillis 必须在 JSON 里", text.contains("reimburseDateMillis"))

        val back = json.decodeFromString(BackupPayload.serializer(), text)
        val t = back.transactions.first()
        assertTrue(t.isReimburse)
        assertTrue(t.reimbursed)
        assertEquals(1_700_800_000_000L, t.reimburseDateMillis)
    }

    @Test
    fun `v5 老备份缺报销字段时走默认值不会炸`() {
        // 老备份里**只有** reimbursed，没有 isReimburse / reimburseDateMillis。
        // 反序列化必须成功（缺字段 = 默认值），而不是抛异常让整个恢复失败。
        val old = """
            {
              "version": 5,
              "exportedAt": 1,
              "deviceName": "old",
              "categories": [{"id":1,"name":"其他","iconKey":"more_horiz"}],
              "transactions": [
                {"id":1,"amountCents":8800,"type":0,"categoryId":1,
                 "dateMillis":1700000000000,"note":"出差垫付","reimbursed":true,
                 "accountId":1,"toAccountId":0}
              ]
            }
        """.trimIndent()
        val p = json.decodeFromString(BackupPayload.serializer(), old)
        val t = p.transactions.first()
        assertTrue("老备份的已报销标记要认", t.reimbursed)
        assertFalse("老备份拿不到池子归属，默认为 false", t.isReimburse)
        assertEquals("拿不到报销日期就当 0，不能拿消费日期假装", 0L, t.reimburseDateMillis)
    }
}