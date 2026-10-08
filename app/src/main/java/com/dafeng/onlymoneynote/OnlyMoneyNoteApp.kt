package com.dafeng.onlymoneynote

import android.app.Application
import com.dafeng.onlymoneynote.util.CrashLog
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OnlyMoneyNoteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 崩溃堆栈落盘。这台努比亚的 logcat 对 adb shell 读不出来，dropbox 也是旧的，
        // 不留这个的话用户说「闪退了」就只能靠猜。日志在
        // /sdcard/Android/data/本包名/files/logs/crash.log，adb 不用 root 就能 pull。
        CrashLog.install(this)
    }
}
