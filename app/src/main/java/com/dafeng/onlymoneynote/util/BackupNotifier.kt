package com.dafeng.onlymoneynote.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 极简通知：只用来报「自动备份成功」。
 *
 * 2026-10-11 用户要求：每天第一次打开 App 自动备份后，
 * 「弹出一个不太影响观感的通知」—— 所以只发**成功**这一种，
 * 而且挂在 **最低重要性** 的渠道上：不响、不震、只在通知栏安静地躺着，
 * 甚至可能被系统直接折叠。不做失败通知，免得一天弹一条红字太吵。
 */
object BackupNotifier {

    private const val CHANNEL_ID = "backup_status"
    private const val NOTIFY_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID, "自动备份", NotificationManager.IMPORTANCE_MIN
        ).apply {
            // 最低重要性：不出现横幅、不出声、不震动
            enableVibration(false)
            setShowBadge(false)
            description = "每天首次打开时的自动备份结果"
        }
        nm.createNotificationChannel(ch)
    }

    /** 备份成功后调用。Android 13+ 没授权就静默跳过，不弹权限框 */
    fun notifyBackupOk(context: Context, txCount: Int) {
        ensureChannel(context)
        // Android 13+ 需要 POST_NOTIFICATIONS 运行时权限。
        // 这里**不主动申请**：自动备份不该在用户打开 App 时弹系统授权框，
        // 那是打扰。没授权就跳过通知，备份本身照做。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val n: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("已自动备份")
            .setContentText("$txCount 笔账单已存到云端")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, n)
        }
    }
}
