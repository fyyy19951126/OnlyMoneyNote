package com.dafeng.onlymoneynote.ui.screens

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
import androidx.compose.material3.AlertDialog
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
                Spacer(Modifier.height(16.dp))
                AboutTip(
                    "1",
                    "本软件所有数据均存在本地或自己的 WebDAV，不经过任何第三方服务器。" +
                        "WebDAV 密码只写进本机设置，不会被打进备份文件。"
                )
                Spacer(Modifier.height(12.dp))
                AboutTip(
                    "2",
                    "相册识图记账功能还不完善，识别率一般，正在持续优化中。"
                )
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

/** 关于里的一条提示：前面一个圆形序号，后面正文字号小一点、行距松一点 */
@Composable
private fun AboutTip(index: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(AppTheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                index,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.primary
            )
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text,
            modifier = Modifier.weight(1f),
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
