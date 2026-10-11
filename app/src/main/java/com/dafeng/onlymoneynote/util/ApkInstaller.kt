package com.dafeng.onlymoneynote.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 应用内更新：把新 APK 下到 cacheDir，再拉起系统安装页。
 *
 * 2026-10-11 用户要求：「是否有可能在软件内部更新，而不是点进蓝奏云更新」。
 * 前提是 `update.json` 里的 `apkUrl` 指向一个**匿名可直连的 .apk 文件**
 * （蓝奏云那种网页 + 密码的不行），拿不到就直接退回开浏览器。
 */
object ApkInstaller {

    /** 下载进度：percent 为 null 表示服务端没给 content-length，只能显示「下载中」 */
    data class Progress(val percent: Int?, val doneBytes: Long, val totalBytes: Long)

    // 16MB 的包在移动网络上要跑十几秒，检查更新那个 8 秒超时的 client 不够用，单开一个
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun targetFile(ctx: Context, versionName: String): File {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        // 每次先清掉上一次下的，免得几个版本堆在缓存里
        dir.listFiles()?.forEach { it.delete() }
        val safe = versionName.filter { it.isDigit() || it == '.' }.ifBlank { "new" }
        return File(dir, "moneynote-$safe.apk")
    }

    /** 下载 APK。失败抛异常，由调用方兜成提示 */
    suspend fun download(
        ctx: Context,
        url: String,
        versionName: String,
        onProgress: (Progress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dest = targetFile(ctx, versionName)
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("服务器返回 ${resp.code}")
            val body = resp.body ?: error("响应没有内容")
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(
                            Progress(total?.let { (done * 100 / it).toInt() }, done, total ?: 0L)
                        )
                    }
                    output.flush()
                }
            }
        }
        if (dest.length() <= 0L) error("下载到的文件是空的")
        dest
    }

    /** 拉起系统安装页。要用户在系统设置里允许过「安装未知应用」，否则这一步会被拒 */
    fun launchInstall(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updatefile", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }
}
