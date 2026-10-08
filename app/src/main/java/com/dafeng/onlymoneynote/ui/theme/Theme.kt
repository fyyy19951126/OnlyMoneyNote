package com.dafeng.onlymoneynote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/* ------------------------------------------------------------------ */
/* 配色系统                                                            */
/*                                                                     */
/* 两层解耦：                                                          */
/* 1. **强调色（Accent）**：用户挑颜色主题，共 8 套                       */
/* 2. **明暗（mode）**：白天 / 夜间 / 跟随手机，决定用浅底还是深底         */
/*                                                                     */
/* 同一个强调色，在浅色和深色两套底子上各有一份色值，由 mode 决定取哪套。  */
/* 界面统一通过 AppTheme.xxx 取色，不硬编码。                            */
/*                                                                     */
/* 涨跌沿用中国习惯：支出红、收入绿。                                     */
/* ------------------------------------------------------------------ */

@Immutable
data class AccentSpec(
    val id: String,
    val label: String,
    /** 浅色底下的主色 */
    val light: Color,
    /** 深色底下的主色（要更亮，保证对比度） */
    val dark: Color
)

object Accents {
    /** 蚂蚁蓝：默认主题，对齐支付宝 12.x 的品牌蓝 #1677FF */
    val Alipay = AccentSpec("alipay", "支付宝蓝", Color(0xFF1677FF), Color(0xFF4D9BFF))
    val Cyan = AccentSpec("cyan", "青", Color(0xFF0FA7AE), Color(0xFF4CC8CE))
    val Teal = AccentSpec("teal", "青蓝", Color(0xFF2E7D8B), Color(0xFF54B8C8))
    val Jade = AccentSpec("jade", "翠绿", Color(0xFF00A868), Color(0xFF3FCF92))
    val Green = AccentSpec("green", "墨绿", Color(0xFF2F7D5E), Color(0xFF57C99A))
    val Indigo = AccentSpec("indigo", "靛蓝", Color(0xFF4763C4), Color(0xFF8BA0F0))
    val Purple = AccentSpec("purple", "紫", Color(0xFF7A5CC4), Color(0xFFB49AF0))
    val Rose = AccentSpec("rose", "玫瑰", Color(0xFFD1537E), Color(0xFFF08BB0))
    val Red = AccentSpec("red", "红", Color(0xFFE53935), Color(0xFFEF5350))
    val Coral = AccentSpec("coral", "珊瑚", Color(0xFFF0704A), Color(0xFFFF9A7B))
    val Orange = AccentSpec("orange", "暖橙", Color(0xFFD97A3B), Color(0xFFF0A260))
    val Amber = AccentSpec("amber", "琥珀", Color(0xFFB8860B), Color(0xFFE0B050))
    val Brown = AccentSpec("brown", "棕", Color(0xFF8D6E63), Color(0xFFBCAAA4))
    val Slate = AccentSpec("slate", "石墨", Color(0xFF546E7A), Color(0xFF90A4AE))

    val all: List<AccentSpec> = listOf(
        Alipay, Cyan, Teal, Jade, Green,
        Indigo, Purple, Rose, Red, Coral,
        Orange, Amber, Brown, Slate
    )

    fun byId(id: String?): AccentSpec = all.firstOrNull { it.id == id } ?: Alipay
}

/** 外观模式 */
enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "跟随手机"),
    LIGHT("light", "白天"),
    DARK("dark", "夜间");

    companion object {
        fun from(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** 一套解析后的完整配色（强调色 + 明暗已经合成） */
@Immutable
data class AppPalette(
    val accentId: String,
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val expense: Color,
    val income: Color,
    /** 危险色：固定红，专门给删除这类操作。不跟着 expense 走（expense 现在是绿色）。 */
    val danger: Color,
    val glassTop: Color,
    val glassBottom: Color,
    /** 支付宝式页头渐变：状态栏到页头底部，从深到浅 */
    val headerTop: Color,
    val headerBottom: Color
)

/** 浅色底的一组中性色（支付宝：灰底白卡片） */
private val LightNeutral = Neutral(
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF2F3F5),
    onSurface = Color(0xFF333333),
    onSurfaceVariant = Color(0xFF999999),
    outline = Color(0xFFE5E6EB),
    outlineVariant = Color(0xFFF0F1F2),
    glassTop = Color(0xFFFFFFFF).copy(alpha = 0.94f),
    glassBottom = Color(0xFFF4F5F7).copy(alpha = 0.985f)
)

/** 深色底的一组中性色 */
private val DarkNeutral = Neutral(
    background = Color(0xFF121316),
    surface = Color(0xFF1D1F23),
    surfaceVariant = Color(0xFF2A2D33),
    onSurface = Color(0xFFE8EAED),
    onSurfaceVariant = Color(0xFF9AA0A8),
    outline = Color(0xFF34373D),
    outlineVariant = Color(0xFF24262B),
    glassTop = Color(0xFF26292E).copy(alpha = 0.94f),
    glassBottom = Color(0xFF191B1F).copy(alpha = 0.985f)
)

private data class Neutral(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val glassTop: Color,
    val glassBottom: Color
)

/** 把强调色 + 明暗合成一份完整配色 */
fun resolvePalette(accentId: String, isDark: Boolean, customColor: Int = 0): AppPalette {
    val primary = when {
        // 自定义色：用户自己挑的，直接拿来用（dark 下稍微提亮保证对比度）
        accentId == "custom" && customColor != 0 -> {
            val c = Color(customColor)
            if (isDark) lighten(c, 0.15f) else c
        }
        else -> {
            val accent = Accents.byId(accentId)
            if (isDark) accent.dark else accent.light
        }
    }
    val n = if (isDark) DarkNeutral else LightNeutral
    // 用户定的规矩：**花出去的钱显示绿色，挣进来的钱显示红色**
    // （和国内股市涨跌色习惯相反，按用户要求来）
    val expense = if (isDark) Color(0xFF57D69B) else Color(0xFF2E8B6A)
    val income = if (isDark) Color(0xFFFF7A7A) else Color(0xFFC0392B)
    val onPrimary = if (isDark) Color(0xFF1A1214) else Color.White
    // 页头渐变：浅色从主色渐变到提亮版；深色反过来（下端用主色，上端压暗），
    // 保证深色下页头不会亮得刺眼，白色文字对比度也够。
    val headerTop = if (isDark) darken(primary, 0.28f) else primary
    val headerBottom = if (isDark) primary else lighten(primary, 0.20f)
    return AppPalette(
        accentId = accentId,
        isDark = isDark,
        background = n.background,
        surface = n.surface,
        surfaceVariant = n.surfaceVariant,
        onSurface = n.onSurface,
        onSurfaceVariant = n.onSurfaceVariant,
        outline = n.outline,
        outlineVariant = n.outlineVariant,
        primary = primary,
        onPrimary = onPrimary,
        expense = expense,
        income = income,
        danger = if (isDark) Color(0xFFFF7875) else Color(0xFFFA5151),
        glassTop = n.glassTop,
        glassBottom = n.glassBottom,
        headerTop = headerTop,
        headerBottom = headerBottom
    )
}

/** 把颜色往白色方向提亮一点 */
private fun lighten(c: Color, amount: Float): Color {
    val r = (c.red + (1f - c.red) * amount).coerceAtMost(1f)
    val g = (c.green + (1f - c.green) * amount).coerceAtMost(1f)
    val b = (c.blue + (1f - c.blue) * amount).coerceAtMost(1f)
    return Color(r, g, b, c.alpha)
}

/** 把颜色往黑色方向压暗一点 */
private fun darken(c: Color, amount: Float): Color {
    val r = (c.red * (1f - amount)).coerceAtLeast(0f)
    val g = (c.green * (1f - amount)).coerceAtLeast(0f)
    val b = (c.blue * (1f - amount)).coerceAtLeast(0f)
    return Color(r, g, b, c.alpha)
}

/** 把 AppPalette 铺成 Material3 的 ColorScheme */
fun AppPalette.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primary.copy(alpha = if (isDark) 0.26f else 0.16f),
        onPrimaryContainer = if (isDark) onSurface else primary,
        secondary = primary,
        onSecondary = onPrimary,
        secondaryContainer = primary.copy(alpha = if (isDark) 0.22f else 0.14f),
        onSecondaryContainer = if (isDark) onSurface else primary,
        tertiary = primary,
        onTertiary = onPrimary,
        tertiaryContainer = primary.copy(alpha = if (isDark) 0.22f else 0.14f),
        onTertiaryContainer = if (isDark) onSurface else primary,
        background = background,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceContainerLowest = if (isDark) background else surface,
        surfaceContainerLow = surface,
        surfaceContainer = surfaceVariant,
        surfaceContainerHigh = surfaceVariant,
        surfaceContainerHighest = surfaceVariant,
        surfaceBright = if (isDark) surfaceVariant else surface,
        surfaceDim = background,
        outline = outline,
        outlineVariant = outlineVariant,
        // 错误色固定用红，不跟着 expense 走（expense 现在是绿色，不能拿来当错误提示）
        error = if (isDark) Color(0xFFFF7875) else Color(0xFFFA5151),
        onError = Color.White,
        inverseSurface = if (isDark) onSurface else Color(0xFF2A2523),
        inverseOnSurface = if (isDark) background else Color(0xFFF7F3F1),
        inversePrimary = primary,
        scrim = Color.Black
    )
}

/** 界面统一从这里取色 */
val LocalAppPalette = staticCompositionLocalOf { resolvePalette("alipay", false) }

object AppTheme {
    val palette: AppPalette
        @Composable get() = LocalAppPalette.current

    val expense: Color
        @Composable get() = LocalAppPalette.current.expense

    val income: Color
        @Composable get() = LocalAppPalette.current.income

    /** 危险操作（删除等）用，永远是红 */
    val danger: Color
        @Composable get() = LocalAppPalette.current.danger

    val primary: Color
        @Composable get() = LocalAppPalette.current.primary

    val isDark: Boolean
        @Composable get() = LocalAppPalette.current.isDark

    @Composable
    fun byType(isExpense: Boolean): Color = if (isExpense) expense else income
}

/**
 * @param accentId 强调色主题 id
 * @param mode 外观模式（跟随/白天/夜间）
 */
@Composable
fun OnlyMoneyNoteTheme(
    accentId: String = "alipay",
    mode: ThemeMode = ThemeMode.SYSTEM,
    customColor: Int = 0,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val palette = resolvePalette(accentId, isDark, customColor)
    CompositionLocalProvider(LocalAppPalette provides palette) {
        MaterialTheme(
            colorScheme = palette.toColorScheme(),
            content = content
        )
    }
}
