package com.dafeng.onlymoneynote

import com.dafeng.onlymoneynote.data.importer.QianJiImporter
import com.dafeng.onlymoneynote.data.importer.XlsxReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * XlsxReader 性能回归。
 *
 * 2026-10-11 真机 ANR 复盘：老的正则实现跑一份 508KB 的钱迹导出要 2 分多钟 CPU，
 * App 挂在那儿不动（ANR）。手写扫描版应该在**秒级以内**，这里用一条粗阈值卡住回归。
 *
 * 阈值故意放宽到 5 秒：模拟器/JIT 预热波动大，5 秒仍能抓住「退化回O(n²) 正则」这种量级问题，
 * 又不会因为机器慢一点就误报。
 */
class XlsxReaderPerfTest {

    @Test
    fun realMoneyNoteFileParsesInSeconds() {
        val f = pickSampleFile() ?: return // 本机没有样本就跳过，不让 CI 因环境差异红

        val bytes = f.readBytes()
        val t0 = System.nanoTime()
        val rows = XlsxReader.readFirstSheet(bytes)
        val ms = (System.nanoTime() - t0) / 1_000_000

        println("[perf] ${f.name} ${bytes.size} bytes -> ${rows.size} rows in ${ms}ms")

        assertTrue("解析器应读出内容", rows.isNotEmpty())
        assertTrue("508KB 的文件超过 5s 说明又退化成了正则回溯，实测 ${ms}ms", ms < 5_000)
    }

    /** 同一文件解析两次结果必须一致 —— 手写扫描最怕的是下标算错、漏单元格 */
    @Test
    fun parsingIsDeterministic() {
        val f = pickSampleFile() ?: return
        val bytes = f.readBytes()
        val a = XlsxReader.readFirstSheet(bytes)
        val b = XlsxReader.readFirstSheet(bytes)
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals("第 $i 行两次解析不一致", a[i], b[i])
        }
    }

    /** 整条链路（xlsx -> 语义行）也要跑得动，别只测底层扫描 */
    @Test
    fun qianJiImportRunsFast() {
        val f = pickSampleFile() ?: return
        val bytes = f.readBytes()
        val t0 = System.nanoTime()
        val parsed = QianJiImporter.parse(bytes)
        val ms = (System.nanoTime() - t0) / 1_000_000

        println("[perf] QianJiImporter ${bytes.size} bytes -> ${parsed.rows.size} rows / ${parsed.transfers.size} transfers in ${ms}ms")

        assertTrue("导入链路超过 10s 说明有热点，实测 ${ms}ms", ms < 10_000)
    }

    /**
     * 找样本文件。放几个常见位置都试一遍，找不到就返回 null（跳过测试），
     * 免得因为换机器就跑不起来。
     */
    private fun pickSampleFile(): File? {
        val names = listOf(
            "QianJi_日常账本_2026-10-11_175731.xlsx",
            "QianJi_日常账本_2026-10-11_093208.xlsx"
        )
        val dirs = listOf(
            File("C:/Users/203/Downloads"),
            File("C:/Users/203/Desktop"),
            File("D:/dev/app/OnlyMoneyNote/app/src/test/resources")
        )
        for (d in dirs) {
            for (n in names) {
                val f = File(d, n)
                if (f.isFile) return f
            }
        }
        return null
    }
}