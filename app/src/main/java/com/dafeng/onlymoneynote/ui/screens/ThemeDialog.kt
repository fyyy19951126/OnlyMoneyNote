package com.dafeng.onlymoneynote.ui.screens

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeng.onlymoneynote.ui.theme.Accents
import com.dafeng.onlymoneynote.ui.theme.AppTheme
import com.dafeng.onlymoneynote.ui.theme.ThemeMode

/**
 * 主题设置弹窗：主题色 + 外观。
 *
 * 主题色 14 个预设，每行 5 个排 3 行；最后一个格子是调色盘，点开自定义颜色（HSV 三滑杆）。
 */
@Composable
fun ThemeDialog(
    paletteId: String,
    mode: String,
    /** 当前自定义色（ARGB），paletteId == "custom" 时作为选中色展示 */
    customColor: Int = 0,
    onPickPalette: (String) -> Unit,
    onPickMode: (String) -> Unit,
    /** 自定义色确认回调（ARGB） */
    onPickCustomColor: (Int) -> Unit = {},
    onDismiss: () -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("主题", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    "主题色",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                )
                // 14 个预设 + 1 个调色盘 = 15 格，每行 5 个正好 3 行
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val rows = Accents.all.chunked(5)
                    rows.forEachIndexed { rowIdx, rowAccents ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowAccents.forEach { a ->
                                val selected = a.id == paletteId
                                val accentColor = if (AppTheme.isDark) a.dark else a.light
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(CircleShape)
                                        .background(accentColor)
                                        .then(
                                            if (selected) Modifier.border(
                                                2.dp,
                                                MaterialTheme.colorScheme.onSurface,
                                                CircleShape
                                            ) else Modifier
                                        )
                                        .clickable { onPickPalette(a.id) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) Icon(
                                        Icons.Outlined.Check, "已选中",
                                        Modifier.size(13.dp),
                                        tint = Color.White
                                    )
                                }
                            }
                            // 最后一行（4 个预设）后面跟调色盘格子，凑满 5 个
                            if (rowIdx == rows.lastIndex) {
                                val isCustom = paletteId == "custom"
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(CircleShape)
                                        .background(
                                            if (isCustom && customColor != 0) Color(customColor)
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .then(
                                            if (isCustom) Modifier.border(
                                                2.dp,
                                                MaterialTheme.colorScheme.onSurface,
                                                CircleShape
                                            ) else Modifier.border(
                                                1.dp,
                                                MaterialTheme.colorScheme.outline,
                                                CircleShape
                                            )
                                        )
                                        .clickable {
                                            onPickPalette("custom")
                                            showPicker = true
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Outlined.Palette, "自定义颜色",
                                        Modifier.size(15.dp),
                                        tint = if (isCustom && customColor != 0) Color.White
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                repeat(5 - rowAccents.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    "外观",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
                // 跟随手机 / 白天 / 夜间，三选一
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m ->
                        val selected = ThemeMode.from(mode) == m
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) AppTheme.primary.copy(alpha = 0.12f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { onPickMode(m.key) }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                m.label,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) AppTheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Text(
                "完成",
                color = AppTheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    )

    if (showPicker) {
        ColorPickerDialog(
            initial = if (customColor != 0) customColor else 0xFF1677FF.toInt(),
            onDismiss = { showPicker = false },
            onConfirm = { argb ->
                showPicker = false
                onPickCustomColor(argb)
            }
        )
    }
}

/** HSV 调色盘：色相 / 饱和度 / 明度三根滑杆 + 实时预览 */
@Composable
private fun ColorPickerDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val initHsv = FloatArray(3).also { AndroidColor.colorToHSV(initial, it) }
    var hue by remember { mutableFloatStateOf(initHsv[0]) }
    var sat by remember { mutableFloatStateOf(initHsv[1]) }
    var value by remember { mutableFloatStateOf(initHsv[2]) }
    val current = Color.hsv(hue, sat, value)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("自定义颜色", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                // 预览块：色块 + 色值
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(current)
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "#%06X".format(current.toArgb() and 0xFFFFFF),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(14.dp))

                // 色相滑杆：轨道就是彩虹渐变
                Slider(
                    value = hue,
                    onValueChange = { hue = it },
                    valueRange = 0f..360f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.hsv(hue, 1f, 1f),
                        activeTrackColor = Color.hsv(hue, 1f, 1f)
                    )
                )
                Text(
                    "色相",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Slider(value = sat, onValueChange = { sat = it }, valueRange = 0f..1f)
                Text(
                    "饱和度",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Slider(value = value, onValueChange = { value = it }, valueRange = 0f..1f)
                Text(
                    "明度",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(current.toArgb()) }) {
                Text("确定", color = AppTheme.primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
