package com.dafeng.onlymoneynote.ui.components

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeng.onlymoneynote.ui.theme.AppTheme
import com.dafeng.onlymoneynote.ui.theme.AppPalette
import com.dafeng.onlymoneynote.util.AppIcons
import com.dafeng.onlymoneynote.util.CategoryIcon

/**
 * 支付宝 12.x 风格的公共组件：
 * - [AlipayBottomBar]：白色固定底栏（4 个 Tab + 中间蓝色「＋」）
 * - [IconTile] / [AlipayTile]：彩色圆角方块图标（支付宝应用宫格观感）
 * - [PageHeader]：蓝色渐变页头（状态栏到底部，从深到浅）
 * - [OverlayTopBar]：二级页白底顶栏（返回箭头 + 居中标题）
 *
 * 全部静态绘制，没有 blur / 每帧动画。
 */

/* ------------------------------------------------------------------ */
/* 底栏                                                                */
/* ------------------------------------------------------------------ */

data class AlipayTab(
    val label: String,
    val icon: ImageVector,
    val iconActive: ImageVector
)

/** 默认四个主 Tab；中间的「记一笔」由底栏自己画，不占这里 */
val MainTabs = listOf(
    AlipayTab("账单", Icons.AutoMirrored.Outlined.ReceiptLong, Icons.AutoMirrored.Filled.ReceiptLong),
    AlipayTab("统计", Icons.Outlined.Insights, Icons.Filled.Insights),
    AlipayTab("报销", Icons.Outlined.FactCheck, Icons.Filled.FactCheck),
    AlipayTab("我的", Icons.Outlined.Person, Icons.Filled.Person)
)

/**
 * 白色固定底栏：选中态蚂蚁蓝（实心图标 + 蓝字），未选中灰。
 * 中间是凸出的蓝色渐变圆钮「＋」，点它直接记一笔。
 */
@Composable
fun AlipayBottomBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onCenterClick: () -> Unit,
    modifier: Modifier = Modifier,
    items: List<AlipayTab> = MainTabs,
    palette: AppPalette = AppTheme.palette
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = palette.surface,
        // 只给极浅的上沿投影，支付宝底栏基本靠一条发丝线分区
        shadowElevation = 0.dp
    ) {
        Column {
            // 顶部发丝线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.7.dp)
                    .background(palette.outlineVariant)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(54.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TabItem(items[0], selectedIndex == 0, Modifier.weight(1f), palette) { onSelect(0) }
                TabItem(items[1], selectedIndex == 1, Modifier.weight(1f), palette) { onSelect(1) }
                // 中间蓝色「＋」
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .offset(y = (-5).dp)
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(palette.headerTop, palette.headerBottom)
                                )
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onCenterClick
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = "记一笔",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                TabItem(items[2], selectedIndex == 2, Modifier.weight(1f), palette) { onSelect(2) }
                TabItem(items[3], selectedIndex == 3, Modifier.weight(1f), palette) { onSelect(3) }
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: AlipayTab,
    selected: Boolean,
    modifier: Modifier = Modifier,
    palette: AppPalette,
    onClick: () -> Unit
) {
    val tint = if (selected) palette.primary else palette.onSurfaceVariant
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            if (selected) tab.iconActive else tab.icon,
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            tab.label,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = tint,
            maxLines = 1
        )
    }
}

/* ------------------------------------------------------------------ */
/* 大弹层（分类管理 / 云端备份）                                        */
/* ------------------------------------------------------------------ */

/**
 * 屏幕（显示区）高度，dp。
 *
 * ⚠️ 这台机器的 **Dialog 窗口比屏幕还高**（实测 2400px 的屏，窗口 2470px 左右）。
 * 所以弹层容器里**不能**直接用 `fillMaxSize()`：那按窗口高算，卡片底边会掉到
 * 屏幕外头，底部安全区垫多少都没用（底部导航栏正好压住最后一行）。
 * 容器高度改用「屏幕高度」，卡片底边就永远贴着屏幕底，再叠加导航栏留白即可。
 *
 * 用 `displayMetrics.heightPixels` 而不是 `LocalConfiguration.screenHeightDp`：
 * 后者在 Dialog 里报的是**窗口**尺寸，正好是被放大的那个值，没法用来纠偏。
 */
@Composable
fun rememberScreenHeightDp(): Dp {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(context, density) {
        val act = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        (act?.resources?.displayMetrics?.heightPixels
            ?: context.resources.displayMetrics.heightPixels)
            .let { with(density) { it.toDp() } }
    }
}

/**
 * 弹层卡片底部要垫多高。
 *
 * ⚠️ 由**两项**相加得到，缺一不可（实测 1080×2400 @420dpi 的机器）：
 *
 * 1. **状态栏高度**（`statusBar` = 132px）—— 弹层的 Dialog 窗口内容区是**从状态栏
 *    下方开始**的（实测卡片占 [611, 2531]，而屏幕只有 2400），所以卡片底边天然
 *    掉到屏幕外 131px。这一段光靠加留白补不回来，必须单独算进去。
 * 2. **导航栏高度**（`navBar` = 126px，三大金刚键）—— 真正要避开的系统栏。
 *
 * ⚠️ 两个值都必须从 **Activity** 的 decorView 读：要把 ContextWrapper 一层层拆到底
 * 才拿得到（`LocalContext.current` 在 Dialog 里拿到的是 Dialog 自己的 wrapper，
 * `as? Activity` 永远是 null —— 这就是之前永远只走 44dp 兜底的原因）。
 *
 * ⚠️ 也不能用 `WindowInsets.navigationBars`：它在 Dialog 内部实测就是 **0**，
 * 弹层自己的窗口拿不到系统栏 inset。
 */
@Composable
fun rememberSheetBottomInset(extra: Dp = 4.dp): Dp {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(context, density) {
        val act = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        val insets = act?.window?.decorView?.let { dv ->
            androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(dv.rootWindowInsets)
        }
        val statusBar = insets?.getInsets(
            androidx.core.view.WindowInsetsCompat.Type.statusBars()
        )?.top ?: 0
        val navBar = insets?.getInsets(
            androidx.core.view.WindowInsetsCompat.Type.navigationBars()
        )?.bottom ?: 0
        // 窗口掉下去的那截 + 导航栏 + 呼吸位
        with(density) { (statusBar + navBar).toDp() } + extra
    }
}

/**
 * 大弹层：内容多、但不值得开一个独立页面的界面（分类管理 / 云端备份）。
 *
 * 跟记一笔那层完全一个模子：全宽、只有顶部圆角、顶部中间一根**横线把手**
 * （点一下关闭、往下拖也关闭），没有标题栏也没有返回箭头。
 *
 * 进场动画按用户要求改成**从上往下落到位**：弹层底边贴在屏幕底部、
 * 顶边停在 (1-heightFraction) 的位置，所以起手把整块往上挪「顶边到屏幕顶的距离」，
 * 也就是先贴在屏幕顶上、再滑进它该在的位置，位移方向和它所在的位置一致。
 */
@Composable
fun SheetDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    heightFraction: Float = 0.92f,
    /**
     * 弹层容器色，同时也是顶部把手那一条的底色。
     * 内容区自己铺了别的颜色的页（比如分类管理用灰底白卡片）要传进来，
     * 否则把手那 34dp 会是一块跟内容对不上的白底。
     */
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    // 容器高度锁屏幕、不锁窗口：Dialog 窗口内容区是从状态栏下方开始的，
    // 用窗口高度会把卡片顶到屏幕外
    val screenH = rememberScreenHeightDp()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val enter = remember { Animatable(0f) }
        LaunchedEffect(Unit) { enter.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(screenH)
                .background(Color.Black.copy(alpha = 0.45f * enter.value))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() },
            contentAlignment = Alignment.BottomCenter
        ) {
            // 往下拖的位移（超过阈值就关闭）
            var dragY by remember { mutableStateOf(0f) }
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .fillMaxHeight(heightFraction)
                    .graphicsLayer {
                        // 进场只做渐入（用户要求：不要从某个方向滑进来）；再叠加用户往下拖的 dragY
                        alpha = enter.value
                        translationY = dragY
                    }
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(containerColor)
                    .clickable(
                        // 吃掉内部点击，别穿透到遮罩把弹层关了
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {}
                    .padding(bottom = rememberSheetBottomInset())
            ) {
                /* ---------- 顶部把手：点一下关闭，往下拖也关闭 ---------- */
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onDismiss() }
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = {},
                                onDragEnd = { if (dragY > 60f) onDismiss() else dragY = 0f },
                                onDragCancel = { dragY = 0f },
                                onVerticalDrag = { _, dy -> dragY = (dragY + dy).coerceAtLeast(0f) }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 38.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
                            )
                    )
                }
                content()
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* 蓝色页头里的返回键                                                  */
/* ------------------------------------------------------------------ */

/**
 * 渐变页头内的返回入口。
 * 底栏去掉后，统计 / 报销 变成从首页进的二级页，返回键直接画在页头里
 * （这两页的页头是蓝色渐变，不能用白底 OverlayTopBar，否则两个页头叠一起）。
 */
@Composable
fun HeaderBackButton(
    /** 返回键旁边要不要带个字（统计页没标题，靠文字说明这是哪儿） */
    label: String? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onBack)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack, "返回",
            modifier = Modifier.size(22.dp),
            tint = Color.White
        )
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* 彩色图标块                                                          */
/* ------------------------------------------------------------------ */

/**
 * 分类图标块：彩色圆角方块 + 白色图标（emoji 自带彩色，用浅灰底 + 原色）。
 * 颜色由 [AppIcons.categoryTileColor] 按分类组固定，全 App 一致；
 * [overrideColor] 用于一级分类自定义了配色的情况（二级分类继承一级）。
 */
@Composable
fun IconTile(
    iconKey: String,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = size * 0.3f,
    overrideColor: Color? = null
) {
    val isEmoji = iconKey.startsWith("emoji:")
    // brand: 前缀的图形自己带底色和圆角，这里再套一层色块会露边
    val isBrand = iconKey.startsWith("brand:")
    val bg = overrideColor
        ?: if (isEmoji) MaterialTheme.colorScheme.surfaceVariant
        else AppIcons.categoryTileColor(iconKey)
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (isBrand) Modifier
                else Modifier.clip(RoundedCornerShape(cornerRadius)).background(bg)
            ),
        contentAlignment = Alignment.Center
    ) {
        CategoryIcon(
            iconKey = iconKey,
            size = if (isEmoji) size * 0.72f else size * 0.54f,
            modifier = Modifier,
            // 深底白字、浅底深字：银行色块是深色，「工 / 招」这类汉字图标才不会糊成一团
            tint = if (bg.luminance() < 0.62f) Color.White else Color(0xFF2B2B2B)
        )
    }
}

/** 固定图标版图标块（设置行、功能入口用） */
@Composable
fun AlipayTile(
    icon: ImageVector,
    container: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = size * 0.3f
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.54f)
        )
    }
}

/* ------------------------------------------------------------------ */
/* 页头                                                                */
/* ------------------------------------------------------------------ */

/**
 * 蓝色渐变页头：铺满宽度，渐变延伸到状态栏底下，内容自己 statusBarsPadding。
 * 浅色模式从主色渐变到提亮版，深色模式反向压暗。
 */
@Composable
fun PageHeader(
    modifier: Modifier = Modifier,
    palette: AppPalette = AppTheme.palette,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(palette.headerTop, palette.headerBottom))
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            content()
        }
    }
}

/* ------------------------------------------------------------------ */
/* 二级页顶栏                                                          */
/* ------------------------------------------------------------------ */

/**
 * 二级页的**主题色**页头：返回箭头在左、标题居中，下面放这一页的重点信息。
 *
 * 统计 / 报销 / 分类管理 / 云端备份 / 导入导出 / 账户 全用它 —— 白顶栏（[OverlayTopBar]）
 * 跟首页的蓝色页头接在一起没有层次，用户要求统一成主题色顶、标题居中。
 */
@Composable
fun OverlayPageHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    PageHeader(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = 6.dp)
        ) {
            HeaderBackButton(onBack = onBack, modifier = Modifier.align(Alignment.CenterStart))
            Text(
                title,
                modifier = Modifier.align(Alignment.Center),
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
        content()
    }
}

/** 二级页白底顶栏：左返回箭头、标题居中加粗，支付宝二级页样式 */
@Composable
fun OverlayTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    palette: AppPalette = AppTheme.palette
) {
    Surface(color = palette.background, modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .height(50.dp)
                .padding(horizontal = 6.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = palette.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                title,
                modifier = Modifier.align(Alignment.Center),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.onSurface,
                maxLines = 1
            )
        }
    }
}
