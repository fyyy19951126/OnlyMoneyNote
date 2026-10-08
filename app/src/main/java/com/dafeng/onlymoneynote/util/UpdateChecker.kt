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
 * 启动时静默调用一次，只有 [Newer] 会浮到界面上（「关于」图标亮红点，
 * 点版本号看更新说明）。[UPDATE_JSON_URL] 还是占位值时 [check] 直接返回
 * [NotConfigured]，不会去请求一个假地址。
 */
object UpdateChecker {

    /** 更新信息 JSON 的公网地址。换成你自己的（坚果云公开链接 / GitHub raw 都行）。 */
    const val UPDATE_JSON_URL = "https://example.com/onlymoneynote/update.json"

    private const val PLACEHOLDER_HOST = "example.com"

    data class UpdateInfo(
        val versionName: String,
        /** 蓝奏云（或任何）下载页链接 */
        val url: String,
        val note: String = ""
    )

    sealed interface Result {
        /** 没配更新地址 */
        object NotConfigured : Result
        /** 已经是最新 */
        data class UpToDate(val current: String) : Result
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
        if (UPDATE_JSON_URL.isBlank() || UPDATE_JSON_URL.contains(PLACEHOLDER_HOST)) {
            return@withContext Result.NotConfigured
        }
        val body = runCatching {
            client.newCall(Request.Builder().url(UPDATE_JSON_URL).build()).execute()
                .use { resp ->
                    if (!resp.isSuccessful) return@withContext Result.Failed("服务器返回 ${resp.code}")
                    resp.body?.string().orEmpty()
                }
        }.getOrElse { return@withContext Result.Failed(it.message ?: "网络错误") }

        val info = runCatching {
            val o = JSONObject(body)
            UpdateInfo(
                versionName = o.optString("versionName", ""),
                url = o.optString("url", ""),
                note = o.optString("note", "")
            )
        }.getOrElse { return@withContext Result.Failed("更新信息格式不对") }

        if (info.versionName.isBlank() || info.url.isBlank()) {
            return@withContext Result.Failed("更新信息里缺 versionName 或 url")
        }
        if (compareVersion(info.versionName, currentVersionName) > 0) {
            Result.Newer(info, currentVersionName)
        } else {
            Result.UpToDate(currentVersionName)
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
