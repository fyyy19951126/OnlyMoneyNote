package com.dafeng.onlymoneynote.data.importer

import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 极简 xlsx 读取器：把一张工作表读成「行 → 单元格文本」。
 *
 * **为什么不引 Apache POI**：那个库有好几 MB，还拖一堆传递依赖；
 * 而记账软件导出的 xlsx 结构极简（sharedStrings + sheet1，没有公式、没有合并单元格），
 * 用 ZipInputStream + 手写扫描就能读出来，跟项目「不引第三方库」的基调一致。
 *
 * xlsx 本身就是个 zip：
 * - `xl/sharedStrings.xml` 里存所有**字符串**，单元格用 `t="s"` 引用下标
 * - `xl/worksheets/sheet1.xml` 里是格子，`<v>` 是值；数字直接是数字，字符串是下标
 * - **空的 `<c>` 格子在文件里可能整个不出现**，所以按列号（A/B/C…）补齐，否则会错位
 *
 * 只读第一张表：记账软件导出账单都在第一张。
 *
 * ---
 *
 * ## 为什么是手写扫描，不是正则（2026-10-11 真机 ANR 复盘）
 *
 * 最早这文件是用正则扫的，比如 `Regex("<row[^>]*>(.*?)</row>", DOT_MATCHES_ALL).findAll(xml)`。
 * 看着简洁，实测**一份 508KB 的钱迹导出要跑 2 分多钟 CPU**，App 直接吃到 119% CPU。
 * 原因有两个，都是正则的固有代价：
 *
 * 1. `DOT_MATCHES_ALL` 让 `.` 能吃换行，于是 `(.*?)` 在整份 XML 上**逐字符回溯**找下一个 `</row>`。
 *    每个字符都要试一遍，复杂度 O(字符数 × 行数)。
 * 2. 每次 `find` 都 new 一个 `Matcher`，`Matcher.reset()` 要把整份输入拷进 native（`GetStringChars`），
 *    几十万次就是几千万次字符拷贝。
 *
 * 手写扫描是**单趟 O(字符数)**：从左往右找 `<row` 起点，跳到对应 `</row>` 终点，
 * 中间什么都不做。同样的文件是毫秒级。
 *
 * 这里不引 XmlPullParser（会多一个依赖，而且 xlsx 的 XML 命名空间/实体处理对它来说是负担），
 * 手写 `indexOf` 更短也更快 —— 记账软件导出的 xlsx 结构稳定，不会有 CDATA 和自定义实体。
 */
object XlsxReader {

    /** 防御性上限：xlsx 里一行不可能有几 MB 文本，撞到就说明文件结构不认，丢弃而不是撑爆内存 */
    private const val MAX_TEXT = 1 shl 20

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
        // 原来是 Regex("""xl/worksheets/sheet(\d+)\.xml""").find(name)。
        // 这条路径每个 zip entry 走一次，条目多的时候也是无谓的构造开销，
        // 而且 entry 名短到不需要正则。手写下标更快也更直白。
        val prefix = "xl/worksheets/sheet"
        if (!name.startsWith(prefix) || !name.endsWith(".xml")) return null
        var i = prefix.length
        var digits = 0
        val sb = StringBuilder()
        while (i < name.length) {
            val ch = name[i]
            if (ch !in '0'..'9') break
            sb.append(ch)
            i++
            digits++
        }
        if (digits == 0 || i != name.length - 4) return null
        return sb.toString().toIntOrNull()
    }

    /**
     * sharedStrings：`<si>` 里可能有多个 `<t>`（富文本分段），要把它们拼起来。
     * 直接取第一个 `<t>` 会丢掉后半段，所以这里按 `<t>` 全部收集后拼接。
     */
    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val xml = bytes.toString(Charsets.UTF_8)
        val out = ArrayList<String>()
        var i = 0
        while (true) {
            val s = xml.indexOf("<si>", i)
            if (s < 0) break
            val e = xml.indexOf("</si>", s + 4)
            if (e < 0) break
            // 富文本：si 里可能有好几段 <t>，全拼起来
            val sb = StringBuilder()
            var p = s + 4
            while (true) {
                val ts = xml.indexOf("<t", p)
                // "<t " 带属性、"<t>" 不带；碰到 "</si>" 或 "<t/" 都说明没有正文了
                if (ts < 0 || ts > e) break
                val afterTag = ts + 2
                if (afterTag < xml.length && xml[afterTag] == '/') {
                    p = afterTag + 1
                    continue
                }
                val open = xml.indexOf('>', afterTag)
                if (open < 0 || open >= e) break
                val close = xml.indexOf("</t>", open + 1)
                if (close < 0 || close > e) break
                sb.append(xml, open + 1, close)
                p = close + 4
            }
            out.add(sb.toString().unescapeXml())
            i = e + 5
        }
        return out
    }

    /** 一行 `<row>` → List<列文本>，按列号补齐空格子 */
    private fun parseSheet(xml: String, shared: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        if (xml.isEmpty()) return rows

        var i = 0
        while (true) {
            // 找 `<row` 开标签。注意要跳过 `<rowBreaks>` 这类同前缀的元素，
            // 所以确认下一个字符是 `>`、`/` 或空格。
            val rs = nextTag(xml, i, "row")
            if (rs < 0) break
            val tagEnd = xml.indexOf('>', rs)
            if (tagEnd < 0) break
            // 自闭合的 <row/>：空行，跳过
            if (xml[tagEnd - 1] == '/') {
                i = tagEnd + 1
                continue
            }
            val re = xml.indexOf("</row>", tagEnd + 1)
            if (re < 0) break

            val cells = HashMap<Int, String>()
            var p = tagEnd + 1
            while (true) {
                val cs = nextTag(xml, p, "c")
                if (cs < 0 || cs >= re) break
                val cOpen = xml.indexOf('>', cs)
                if (cOpen < 0) break
                if (xml[cOpen - 1] == '/') {
                    // <c r="B2" t="s"/>：有列号没正文，值就是空字符串
                    parseCellAttrs(xml.substring(cs + 2, cOpen - 1))?.let { ref ->
                        cells[colIndex(ref)] = ""
                    }
                    p = cOpen + 1
                    continue
                }
                val ce = xml.indexOf("</c>", cOpen + 1)
                if (ce < 0) break
                val attrs = xml.substring(cs + 2, cOpen)
                val body = xml.substring(cOpen + 1, ce)

                val ref = attrRef(attrs)
                if (ref != null) {
                    val col = colIndex(ref)
                    val isShared = attrIsShared(attrs)
                    val isInline = attrIsInline(attrs)
                    val text = when {
                        isShared -> {
                            val v = tagValue(body, "v")
                            v.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                        }
                        isInline -> tagValue(body, "t").unescapeXml()
                        else -> tagValue(body, "v").unescapeXml()
                    }
                    cells[col] = text
                }
                p = ce + 4
            }

            val maxCol = cells.keys.maxOrNull() ?: -1
            if (maxCol >= 0) {
                rows.add((0..maxCol).map { cells[it].orEmpty() })
            }
            i = re + 6
        }
        return rows
    }

    /**
     * 从 [from] 起找 `<name` 的开标签起始下标；找不到返回 -1。
     *
     * 必须确认标签名后面是 `>`、`/` 或空格，否则 `<rowBreaks>` 会被当成 `<row`。
     * 这是原来正则 `<row[^>]*>` 没处理好的地方 —— `[^>]*` 会把 `Breaks` 吃进去。
     */
    private fun nextTag(s: String, from: Int, name: String): Int {
        var i = from
        while (true) {
            val at = s.indexOf("<$name", i)
            if (at < 0) return -1
            val after = at + 1 + name.length
            if (after >= s.length) return -1
            val ch = s[after]
            if (ch == '>' || ch == '/' || ch == ' ') return at
            i = at + 1
        }
    }

    /** 取出 `<tag ...>正文</tag>` 里的正文；没有则空串 */
    private fun tagValue(body: String, tag: String): String {
        val at = nextTag(body, 0, tag)
        if (at < 0) return ""
        val open = body.indexOf('>', at)
        if (open < 0) return ""
        if (body[open - 1] == '/') return ""
        val close = body.indexOf("</$tag>", open + 1)
        if (close < 0) return ""
        val s = open + 1
        val e = close
        if (e - s > MAX_TEXT) return body.substring(s, s + MAX_TEXT)
        return body.substring(s, e)
    }

    /** 自闭合 <c .../> 的属性串里取 r="XX"；没有就 null */
    private fun parseCellAttrs(attrs: String): String? = attrRef(attrs)

    /** r="B2" 里的列字母部分；没有 r 属性返回 null */
    private fun attrRef(attrs: String): String? {
        val at = attrs.indexOf("r=\"")
        if (at < 0) return null
        val q = at + 3
        val end = attrs.indexOf('"', q)
        if (end < 0) return null
        val ref = attrs.substring(q, end)
        // 只要列字母部分，r="B12" → B
        var i = 0
        while (i < ref.length && ref[i] in 'A'..'Z') i++
        return if (i == 0) null else ref.substring(0, i)
    }

    private fun attrIsShared(attrs: String) = attrs.contains("""t="s"""")

    private fun attrIsInline(attrs: String) = attrs.contains("""t="inlineStr"""")

    /** "A" → 0，"AB" → 27 */
    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch !in 'A'..'Z') break
            n = n * 26 + (ch - 'A' + 1)
        }
        return n - 1
    }

    private fun String.unescapeXml(): String {
        // 绝大多数文本里没有实体，先 in 判断再 replace，省掉 5 次无谓的字符串分配
        if ('&' !in this) return this
        return this
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}