package com.dafeng.onlymoneynote.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 极简 WebDAV 客户端：只用 PUT / GET / MKCOL / DELETE 四个方法。
 * 不引第三方 WebDAV 库，少一层依赖。
 */
@Singleton
class WebDavClient @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    data class Config(
        val baseUrl: String,
        val username: String,
        val password: String,
        val remotePath: String = "moneynote/backup.json"
    )

    /** 上传文本内容（创建/覆盖）。 */
    suspend fun put(config: Config, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = joinUrl(config.baseUrl, config.remotePath)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(config.username, config.password))
                .put(content.toRequestBody(jsonType))
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("上传失败 HTTP ${resp.code} ${resp.message}")
                }
            }
            Unit
        }
    }

    /** 下载文本内容。404 会返回失败结果，调用方可据此判断「远端还没备份」。 */
    suspend fun get(config: Config): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = joinUrl(config.baseUrl, config.remotePath)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(config.username, config.password))
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> resp.body?.string() ?: throw IOException("响应体为空")
                    resp.code == 404 -> throw IOException("远端暂无备份文件（404）")
                    else -> throw IOException("下载失败 HTTP ${resp.code} ${resp.message}")
                }
            }
        }
    }

    /** 逐级创建目录，已存在（405/301）不算失败。 */
    suspend fun ensureDirectories(config: Config): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val segments = config.remotePath.split('/').filter { it.isNotBlank() }.dropLast(1)
            var acc = config.baseUrl.trimEnd('/')
            for (seg in segments) {
                acc = "$acc/${seg.trim('/')}"
                val request = Request.Builder()
                    .url("$acc/")
                    .header("Authorization", Credentials.basic(config.username, config.password))
                    .method("MKCOL", null)
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (resp.code != 201 && resp.code != 405 && resp.code != 301) {
                        throw IOException("创建目录失败 ${resp.code} ${resp.message}")
                    }
                }
            }
            Unit
        }
    }

    /** 连通性测试：对目标文件发一次 HEAD（不支持则退化为 GET 的 404）。 */
    suspend fun testConnection(config: Config): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = joinUrl(config.baseUrl, config.remotePath)
            val head = Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(config.username, config.password))
                .head()
                .build()
            client.newCall(head).execute().use { resp ->
                when (resp.code) {
                    in 200..299 -> "连接正常，远端已有备份（${resp.header("Content-Length") ?: "?"} 字节）"
                    404 -> "连接正常，远端暂无备份文件"
                    401, 403 -> throw IOException("认证失败，请检查用户名和密码")
                    else -> "连接正常（HTTP ${resp.code}）"
                }
            }
        }
    }

    private fun joinUrl(base: String, path: String): String =
        base.trimEnd('/') + "/" + path.trimStart('/')
}
