package com.dafeng.onlymoneynote.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeng.onlymoneynote.ui.theme.AppTheme

/**
 * 关于弹窗：首页顶栏「关于」图标点开。
 *
 * 原来这些内容在设置页里，现在设置页已经没了，搬到这里。
 */
@Composable
fun AboutDialog(
    onDismiss: () -> Unit,
    /** 正在联网检查更新（点版本号触发）：行尾显示「检查中…」并挡住重复点击 */
    checking: Boolean = false,
    onTapVersion: () -> Unit = {}
) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1"
        }.getOrDefault("0.1")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("关于", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                // 平时不联网；点了这一行才去远程查一次有没有新版
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !checking, onClick = onTapVersion)
                        .background(AppTheme.primary.copy(alpha = 0.06f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("版本号", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (checking) "检查中…" else "点我检查更新",
                        fontSize = 12.sp,
                        color = AppTheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(version, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(12.dp))
                // 源码仓库：只写「GitHub / Gitee」两个名字，**不把仓库路径拼在界面上**。
                // 整行可点，点一下用系统浏览器打开 —— 链接属于实现细节，摆出来既占地方
                // 又让人以为得手动去抄。2026-10-10 用户要求去掉那串 owner/repo 文字。
                RepoLink("GitHub", REPO_GITHUB) { openInBrowser(context, REPO_GITHUB) }
                Spacer(Modifier.height(2.dp))
                RepoLink("Gitee", REPO_GITEE) { openInBrowser(context, REPO_GITEE) }
            }
        },
        confirmButton = {
            Text(
                "知道了",
                color = AppTheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    )
}

/* ------------------------------------------------------------------ */

/**
 * 关于页里的一行仓库入口。
 *
 * 左边写名字，右边一个「查看」箭头 —— 不显示 URL，点了直接开浏览器。
 * 整行是可点的热区（不是只有右边那几个字），手指按在行中间空白处也有反应。
 */
@Composable
private fun RepoLink(name: String, url: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            "查看",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = AppTheme.primary
        )
        Spacer(Modifier.width(2.dp))
        // 一个小的右箭头，比纯文字更明确表示「点了会跳出去」
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = AppTheme.primary
        )
    }
}

/** 源码仓库地址（关于页点对应那一行就打开它） */
private const val REPO_GITHUB = "https://github.com/fyyy19951126/OnlyMoneyNote"
private const val REPO_GITEE = "https://gitee.com/fyyy19951126/only-money-note"

/** 用系统浏览器打开链接；没装浏览器也不能崩 */
private fun openInBrowser(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
