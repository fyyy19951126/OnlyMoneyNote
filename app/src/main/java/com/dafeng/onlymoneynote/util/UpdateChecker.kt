package com.dafeng.onlymoneynote.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 检查更新。
 *
 * 远程放一个小 JSON 就够了，格式：
 *
 * ```json
 * { "versionName": "1.1", "url": "https://xxxx.lanzouy.com/xxxxx", "note": "这版改了什么" }
 * ```
 *
 * **下载链接（蓝奏云）写在 JSON 里而不是代码里**：发新版只改这个文件，
 * 不用重新编包、不用让用户装新包才能看到新链接。
 *
 * **只在用户点「关于 → 版本号」时调用一次**，App 平时完全不联网。
 * [UPDATE_JSON_URLS] 全是占位值时 [check] 直接返回 [NotConfigured]，不会去请求假地址。
 */
object UpdateChecker {

    /**
     * 更新信息 JSON 的公网地址，**按顺序试，谁先通用谁**。
     *
     * 2026-10-11 用户提的：「鉴于用户可能直连 github 失败」。
     * jsDelivr 在国内移动网络经常被 DNS 污染，所以把 Gitee（国内直连）放第一位，
     * GitHub 的 CDN 留作兜底 —— Gitee 那份要记得跟 GitHub 一起 push。
     */
    val UPDATE_JSON_URLS = listOf(
        "https://gitee.com/fyyy19951126/only-money-note/raw/main/update/update.json",
        "https://cdn.jsdelivr.net/gh/fyyy19951126/OnlyMoneyNote@main/update/update.json"
    )

    private const val PLACEHOLDER_HOST = "example.com"

    data class UpdateInfo(
        val versionName: String,
        /** 蓝奏云（或任何）下载页链接 */
        val url: String,
        /** 直接指向 .apk 文件的地址，能空着 —— 空着就走 [url] 手动下载 */
        val apkUrl: String = "",
        /** 下载页的访问密码。界面上不显示，只在需要时复制进剪贴板 */
        val pwd: String = "",
        val note: String = ""
    )

    sealed interface Result {
        /** 没配更新地址 */
        object NotConfigured : Result
        /** 已经是最新。[info] 仍然带回来，因为下载密码在里面 */
        data class UpToDate(val current: String, val info: UpdateInfo? = null) : Result
        /** 有新版 */
        data class Newer(val info: UpdateInfo, val current: String) : Result
        /** 检查失败（网络 / 格式） */
        data class Failed(val reason: String) : Result
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    suspend fun check(currentVersionName: String): Result = withContext(Dispatchers.IO) {
        val candidates = UPDATE_JSON_URLS.filter { it.isNotBlank() && !it.contains(PLACEHOLDER_HOST) }
        if (candidates.isEmpty()) return@withContext Result.NotConfigured

        // 依次试，第一个通的就用；全都不通才报失败（失败原因留最后一条）
        var text: String? = null
        var failReason = "网络错误"
        for (u in candidates) {
            if (text != null) break
            try {
                text = client.newCall(Request.Builder().url(u).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("服务器返回 ${resp.code}")
                    resp.body?.string().orEmpty()
                }
            } catch (e: Exception) {
                failReason = e.message ?: "网络错误"
            }
        }
        val body = text ?: return@withContext Result.Failed(failReason)

        val info = runCatching {
            val o = JSONObject(body)
            UpdateInfo(
                versionName = o.optString("versionName", ""),
                url = o.optString("url", ""),
                apkUrl = o.optString("apkUrl", ""),
                pwd = o.optString("pwd", ""),
                note = o.optString("note", "")
            )
        }.getOrElse { return@withContext Result.Failed("更新信息格式不对") }

        if (info.versionName.isBlank() || info.url.isBlank()) {
            return@withContext Result.Failed("更新信息里缺 versionName 或 url")
        }
        if (compareVersion(info.versionName, currentVersionName) > 0) {
            Result.Newer(info, currentVersionName)
        } else {
            Result.UpToDate(currentVersionName, info)
        }
    }

    /** 「1.10.2」对「1.9」这种按段比大小，比不出来的段当 0 */
    fun compareVersion(a: String, b: String): Int {
        val pa = a.trim().removePrefix("v").split('.')
        val pb = b.trim().removePrefix("v").split('.')
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val va = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val vb = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (va != vb) return if (va > vb) 1 else -1
        }
        return 0
    }
}
