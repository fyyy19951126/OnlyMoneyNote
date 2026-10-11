package com.dafeng.onlymoneynote.data.importer

import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 极简 xlsx 读取器：把一张工作表读成「行 → 单元格文本」。
 *
 * **为什么不引 Apache POI**：那个库有好几 MB，还拖一堆传递依赖；
 * 而记账软件导出的 xlsx 结构极简（sharedStrings + sheet1，没有公式、没有合并单元格），
 * 用 ZipInputStream + 轻量 XML 扫描几百行就能读出来，跟项目「不引第三方库」的基调一致。
 *
 * xlsx 本身就是个 zip：
 * - `xl/sharedStrings.xml` 里存所有**字符串**，单元格用 `t="s"` 引用下标
 * - `xl/worksheets/sheet1.xml` 里是格子，`<v>` 是值；数字直接是数字，字符串是下标
 * - **空的 `<c>` 格子在文件里可能整个不出现**，所以按列号（A/B/C…）补齐，否则会错位
 *
 * 只读第一张表：记账软件导出账单都在第一张。
 */
object XlsxReader {

    /**
     * 读第一张工作表，返回 List<行>，每行是 List<列文本>。
     * 解析不了就返回空表，调用方负责给用户报错。
     */
    fun readFirstSheet(bytes: ByteArray): List<List<String>> {
        var shared: List<String> = emptyList()
        // 收集「编号最小的那个工作表」的内容；放在局部变量里，
        // 避免用静态字段导致重复调用 / 并发时串数据
        var bestIndex = Int.MAX_VALUE
        var bestSheet = ""

        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (entry.name == "xl/sharedStrings.xml") {
                    shared = parseSharedStrings(zis.readBytes())
                } else {
                    val n = sheetFileIndex(entry.name)
                    if (n != null && n < bestIndex) {
                        bestIndex = n
                        bestSheet = zis.readBytes().toString(Charsets.UTF_8)
                    }
                }
                entry = zis.nextEntry
            }
        }
        return parseSheet(bestSheet, shared)
    }

    /** "xl/worksheets/sheet12.xml" → 12；不是工作表返回 null */
    private fun sheetFileIndex(name: String): Int? {
        val m = Regex("""xl/worksheets/sheet(\d+)\.xml""").find(name) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /**
     * sharedStrings：`<si>` 里可能有多个 `<t>`（富文本分段），要把它们拼起来。
     * 直接取第一个 `<t>` 会丢掉后半段，所以这里按 `<t>` 全部收集后拼接。
     */
    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val xml = bytes.toString(Charsets.UTF_8)
        return Regex("""<si>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .map { m ->
                Regex("""<t[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(m.groupValues[1])
                    .joinToString("") { it.groupValues[1] }
                    .unescapeXml()
            }
            .toList()
    }

    /** 一行 `<row>` → List<列文本>，按列号补齐空格子 */
    private fun parseSheet(xml: String, shared: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        Regex("""<row[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .forEach { rowMatch ->
                // 先按列号放进稀疏 map，再补齐 —— 空的 `<c>` 在 xlsx 里常常整个不存在
                val cells = HashMap<Int, String>()
                Regex("""<c\b([^>]*)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(rowMatch.groupValues[1])
                    .forEach { cm ->
                        val ref = Regex("""r="([A-Z]+)\d+"""").find(cm.groupValues[1])?.groupValues?.get(1)
                            ?: return@forEach
                        val col = colIndex(ref)
                        val attrs = cm.groupValues[1]
                        val isShared = attrs.contains("""t="s"""")
                        val isInline = attrs.contains("""t="inlineStr"""")
                        val v = Regex("""<v>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
                            .find(cm.groupValues[2])?.groupValues?.get(1).orEmpty()
                        val text = when {
                            isShared -> v.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                            isInline -> Regex("""<t[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
                                .find(cm.groupValues[2])?.groupValues?.get(1)?.unescapeXml().orEmpty()
                            else -> v.unescapeXml()
                        }
                        cells[col] = text
                    }
                val maxCol = cells.keys.maxOrNull() ?: -1
                if (maxCol >= 0) {
                    rows.add((0..maxCol).map { cells[it].orEmpty() })
                }
            }
        return rows
    }

    /** "A" → 0，"AB" → 27 */
    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch !in 'A'..'Z') break
            n = n * 26 + (ch - 'A' + 1)
        }
        return n - 1
    }

    private fun String.unescapeXml(): String = this
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
