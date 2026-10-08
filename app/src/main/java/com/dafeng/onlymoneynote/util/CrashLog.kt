package com.dafeng.onlymoneynote.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃落盘。
 *
 * 这台努比亚的 logcat 对 adb shell 是空的（main/system/crash 三个 buffer 都读不到，
 * 只有开机那几行 events），dropbox 也停在好几天前 —— 用户说「闪退了」的时候，
 * 现场是什么都拿不到的。所以自己接管未捕获异常，把堆栈追加写到
 *
 *   /sdcard/Android/data/<包名>/files/logs/crash.log
 *
 * 这个路径 adb 不用 root 就能 pull，下次复现一次就有栈可看。
 * 写完仍然把异常交回系统默认处理器，不改变「崩溃就退出」的原有行为。
 */
object CrashLog {

    private const val FILE_NAME = "crash.log"

    fun install(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "logs")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                dir.mkdirs()
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
                    .format(Date())
                val stack = StringWriter().also { sw ->
                    error.printStackTrace(java.io.PrintWriter(sw))
                }.toString()
                FileWriter(File(dir, FILE_NAME), true).use { w ->
                    w.write("==== $stamp  线程=" + thread.name + " ====" + Char(10))
                    // 不引 BuildConfig（这个模块没开 buildConfig），版本直接问 PackageManager
                    val version = runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: "?"
                    w.write("包名=" + context.packageName + " 版本=" + version + Char(10))
                    w.write("机型=" + Build.MANUFACTURER + " " + Build.MODEL +
                        " Android=" + Build.VERSION.RELEASE + "(api " + Build.VERSION.SDK_INT + ")" +
                        Char(10))
                    w.write(stack)
                    w.write(Char(10).toString())
                }
            }
            // 交回系统：该弹的崩溃框、该退的进程照旧
            previous?.uncaughtException(thread, error)
        }
    }
}
