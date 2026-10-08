package com.dafeng.onlymoneynote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeng.onlymoneynote.ui.components.AlipayTile
import com.dafeng.onlymoneynote.ui.components.PageHeader
import com.dafeng.onlymoneynote.ui.theme.AppTheme

/**
 * 「我的」页（支付宝我的页式）：
 * - 蓝色渐变页头：App 名称 + 账单/分类统计
 * - 白色圆角面板：设置项列表（彩色图标块 + 标题 + 箭头）
 */
@Composable
fun ProfileScreen(
    appTitle: String,
    txCount: Int,
    categoryCount: Int,
    onOpenCategory: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenIo: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenAbout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        PageHeader {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 30.dp)) {
                Text(
                    appTitle,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "共 $txCount 笔账单 · $categoryCount 个分类",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }
        }

        // 白色面板上移一点压在页头上，支付宝「我的」页的手法
        Surface(
            modifier = Modifier
                .offset(y = (-14).dp)
                .fillMaxSize(),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(top = 6.dp)) {
                ProfileRow(
                    icon = Icons.Outlined.Category,
                    container = AppTheme.primary,
                    title = "分类管理",
                    onClick = onOpenCategory
                )
                RowDivider()
                ProfileRow(
                    icon = Icons.Outlined.Cloud,
                    container = Color(0xFF13B8C4),
                    title = "云端备份",
                    onClick = onOpenBackup
                )
                RowDivider()
                ProfileRow(
                    icon = Icons.Outlined.UploadFile,
                    container = Color(0xFFFF9436),
                    title = "导入导出",
                    onClick = onOpenIo
                )
                RowDivider()
                ProfileRow(
                    icon = Icons.Outlined.Palette,
                    container = Color(0xFF9254DE),
                    title = "主题外观",
                    onClick = onOpenTheme
                )
                RowDivider()
                ProfileRow(
                    icon = Icons.Outlined.Info,
                    container = Color(0xFF8C959F),
                    title = "关于",
                    onClick = onOpenAbout
                )

                Spacer(Modifier.height(30.dp))
                Text(
                    "OnlyMoneyNote · 让每一笔都有迹可循",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ProfileRow(
    icon: ImageVector,
    container: Color,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlipayTile(icon = icon, container = container, size = 32.dp)
        Spacer(Modifier.width(13.dp))
        Text(
            title,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 行间发丝线，从图标后面开始缩进（支付宝列表的样式） */
@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 61.dp)
            .height(0.7.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}
