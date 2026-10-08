package com.dafeng.onlymoneynote.data.repo

import com.dafeng.onlymoneynote.data.local.CategoryEntity
import com.dafeng.onlymoneynote.data.local.TransactionEntity
import com.dafeng.onlymoneynote.data.remote.BackupCategory
import com.dafeng.onlymoneynote.data.remote.BackupPayload
import com.dafeng.onlymoneynote.data.remote.BackupSettings
import com.dafeng.onlymoneynote.data.remote.BackupTransaction
import com.dafeng.onlymoneynote.data.remote.WebDavClient
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepository @Inject constructor(
    private val ledger: LedgerRepository,
    private val webDav: WebDavClient,
    private val settings: SettingsRepository
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 导出为 JSON 文本。分类、账单、配色、WebDAV 连接信息都在里面。 */
    suspend fun buildPayload(deviceName: String): String {
        val categories = ledger.categoriesOnce()
        val transactions = ledger.transactionsOnce()
        val dav = settings.settingsFlowOnce()

        val payload = BackupPayload(
            version = 3,
            exportedAt = System.currentTimeMillis(),
            deviceName = deviceName,
            settings = BackupSettings(
                themeId = settings.appSettingsFlowOnce().themeId,
                mode = settings.appSettingsFlowOnce().mode,
                customColor = settings.appSettingsFlowOnce().customColor,
                webDavUrl = dav.baseUrl,
                webDavUser = dav.username,
                webDavPath = dav.remotePath,
                appTitle = settings.appSettingsFlowOnce().appTitle
            ),
            categories = categories.map {
                BackupCategory(
                    it.id, it.name, it.iconKey, it.parentId, it.sortOrder,
                    it.builtIn, it.colorKey, it.type
                )
            },
            transactions = transactions.map {
                BackupTransaction(it.id, it.amountCents, it.type, it.categoryId, it.dateMillis, it.note, it.createdAt, it.reimbursed)
            }
        )
        return json.encodeToString(BackupPayload.serializer(), payload)
    }

    /** 上传备份。会先尝试建目录。 */
    suspend fun upload(config: WebDavClient.Config, deviceName: String): Result<String> {
        webDav.ensureDirectories(config)
        val content = buildPayload(deviceName)
        val result = webDav.put(config, content)
        return result.map {
            val kb = content.toByteArray().size / 1024.0
            "上传成功，共 ${"%.1f".format(kb)} KB（含分类、账单、配色）"
        }
    }

    /** 从云端下载并覆盖本地数据。 */
    suspend fun restore(config: WebDavClient.Config): Result<String> {
        val downloaded = webDav.get(config).getOrElse { return Result.failure(it) }
        return restoreFromJson(downloaded, config)
    }

    /**
     * 从一份 JSON 备份文本恢复 —— 「导入导出 → 从 JSON 备份恢复」走这里，
     * 不依赖云端、也不用重输 WebDAV 密码。
     */
    suspend fun restoreFromJson(text: String): Result<String> =
        restoreFromJson(text, null)

    /**
     * 真正干的地方：清空本机 → 按备份里的原 id 重建分类和账单 → 同步设置。
     *
     * 保留原 id 是关键：父子关系和账单外键都对得上，恢复完跟备份前一模一样。
     * fallback 是备份里没有的 WebDAV 信息时沿用的本机配置（云端恢复时就是它自己）。
     */
    private suspend fun restoreFromJson(
        text: String,
        fallback: WebDavClient.Config?
    ): Result<String> {
        val payload = runCatching {
            json.decodeFromString(BackupPayload.serializer(), firstJsonDocument(text))
        }.getOrElse { return Result.failure(Exception("备份文件解析失败：${it.message}")) }
        if (payload.categories.isEmpty() && payload.transactions.isEmpty()) {
            return Result.failure(Exception("这个备份里没有分类也没有账单，确认选的是导出的 JSON"))
        }

        val dav = settings.settingsFlowOnce()
        val app = settings.appSettingsFlowOnce()
        val cats = inferTypes(payload)

        ledger.clearTransactions()
        ledger.clearCategories()

        for (c in cats) {
            ledger.addCategory(
                CategoryEntity(
                    id = c.id,
                    name = c.name,
                    iconKey = c.iconKey,
                    parentId = c.parentId,
                    sortOrder = c.sortOrder,
                    builtIn = c.builtIn,
                    type = c.type,
                    colorKey = c.colorKey
                )
            )
        }
        for (t in payload.transactions) {
            ledger.addTransaction(
                TransactionEntity(
                    id = t.id,
                    amountCents = t.amountCents,
                    type = t.type,
                    categoryId = t.categoryId,
                    dateMillis = t.dateMillis,
                    note = t.note,
                    createdAt = t.createdAt,
                    reimbursed = t.reimbursed
                )
            )
        }

        // 设置也一并恢复。密码不在备份里，保持本机已填的那个不动。
        val s = payload.settings
        settings.save(
            SettingsRepository.WebDavSettings(
                baseUrl = s.webDavUrl.ifBlank { fallback?.baseUrl ?: dav.baseUrl },
                username = s.webDavUser.ifBlank { fallback?.username ?: dav.username },
                password = fallback?.password ?: dav.password,
                remotePath = s.webDavPath.ifBlank { fallback?.remotePath ?: dav.remotePath }
            )
        )
        settings.saveTheme(s.themeId)
        settings.saveMode(s.mode)
        if (s.customColor != 0) settings.saveCustomColor(s.customColor)
        // 主页标题：老备份没这个字段（空串），就别把用户自己起的名字冲掉
        if (s.appTitle.isNotBlank() && s.appTitle != app.appTitle) settings.saveAppTitle(s.appTitle)

        return Result.success(
            "恢复成功：${payload.categories.size} 个分类，${payload.transactions.size} 笔账单，设置已同步"
        )
    }

    /**
     * v2 及更早的备份**没有分类的收支类型**（老代码漏了这个字段），照原样恢复会把
     * 收入分类全建成支出分类。这里用账单自己带的 type 反推：
     * 一个分类名下的账单多数是收入，它就归收入；一级分类跟着它子分类的账单一起投票。
     */
    private fun inferTypes(p: BackupPayload): List<BackupCategory> {
        if (p.version >= 3) return p.categories
        val byId = p.categories.associateBy { it.id }
        // [支出笔数, 收入笔数]
        val votes = HashMap<Long, IntArray>()
        p.transactions.forEach { t ->
            val cat = byId[t.categoryId] ?: return@forEach
            val own = if (t.type == 1) 1 else 0
            votes.getOrPut(cat.id) { IntArray(2) }[own]++
            cat.parentId?.let { pid -> votes.getOrPut(pid) { IntArray(2) }[own]++ }
        }
        return p.categories.map { c ->
            if (c.type != 0) c
            else {
                val v = votes[c.id]
                c.copy(type = if (v != null && v[1] > v[0]) 1 else 0)
            }
        }
    }

    suspend fun test(config: WebDavClient.Config): Result<String> = webDav.testConnection(config)

    /**
     * 只取文本里第一个配平的 JSON 文档。
     *
     * 针对上面那个坑：老版本导出没截断，同一个文件名重复导出后，文件变成
     * 「这一份 + 上一份的尾巴」，整段解析会抛 Extra data。这里按括号配平扫到
     * 第一个文档结束就截断，让这种文件照样能恢复。
     */
    private fun firstJsonDocument(text: String): String {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val ch = text[i]
            when {
                inString -> when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                ch == '"' -> inString = true
                ch == '{' || ch == '[' -> depth++
                ch == '}' || ch == ']' -> {
                    depth--
                    if (depth == 0) return text.substring(0, i + 1)
                }
            }
        }
        return text
    }
}
