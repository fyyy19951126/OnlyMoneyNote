package com.dafeng.onlymoneynote

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.dafeng.onlymoneynote.data.local.TxWithCategory
import com.dafeng.onlymoneynote.data.ocr.OcrEngine
import com.dafeng.onlymoneynote.ui.LedgerViewModel
import com.dafeng.onlymoneynote.ui.components.OverlayTopBar
import com.dafeng.onlymoneynote.ui.components.SheetDialog
import com.dafeng.onlymoneynote.ui.screens.AccountStatsSheet
import com.dafeng.onlymoneynote.ui.screens.AddTransactionScreen
import com.dafeng.onlymoneynote.ui.screens.CategoryScreen
import com.dafeng.onlymoneynote.ui.screens.AboutDialog
import com.dafeng.onlymoneynote.ui.screens.ThemeDialog
import com.dafeng.onlymoneynote.ui.screens.ImportExportSheet
import com.dafeng.onlymoneynote.ui.screens.ReimburseScreen
import com.dafeng.onlymoneynote.ui.screens.StatsScreen
import com.dafeng.onlymoneynote.ui.screens.TransactionListScreen
import com.dafeng.onlymoneynote.ui.screens.WebDavScreen
import com.dafeng.onlymoneynote.ui.theme.AppTheme
import com.dafeng.onlymoneynote.ui.theme.OnlyMoneyNoteTheme
import com.dafeng.onlymoneynote.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var ocrEngine: OcrEngine

    private var sharedImage: Uri? = null

    /** 桌面「快速记一笔」插件点进来时置 true，进了记账页就回 false */
    private val openAdd = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sharedImage = extractSharedImage(intent)
        openAdd.value = intent?.getBooleanExtra(EXTRA_OPEN_ADD, false) == true
        // 打开 App 就把桌面插件的数据刷一遍（账单可能被云端恢复等旁路改过）
        com.dafeng.onlymoneynote.widget.WidgetRefresher.refreshAll(this)
        setContent {
            val vm: LedgerViewModel = hiltViewModel()
            val appSettings by vm.appSettings.collectAsState()
            OnlyMoneyNoteTheme(
                accentId = appSettings.themeId,
                mode = ThemeMode.from(appSettings.mode),
                customColor = appSettings.customColor
            ) {
                AppRoot(
                    vm = vm,
                    sharedImage = sharedImage,
                    ocr = ocrEngine,
                    openAdd = openAdd.value,
                    onOpenAddHandled = { openAdd.value = false }
                )
            }
        }
    }

    /** 应用已在后台时又分享了一张图进来 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedImage = extractSharedImage(intent)
        if (intent?.getBooleanExtra(EXTRA_OPEN_ADD, false) == true) openAdd.value = true
    }

    companion object {
        const val EXTRA_OPEN_ADD = "extra_open_add"
    }

    /**
     * 从外部分享意图里取出图片 URI。
     * 支持三种：ACTION_SEND（单图）、ACTION_SEND_MULTIPLE（多图取第一张）、ACTION_VIEW（从图库直接打开）。
     */
    private fun extractSharedImage(intent: Intent?): Uri? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
            Intent.ACTION_SEND_MULTIPLE ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.firstOrNull()
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
                }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }
}

/* ------------------------------------------------------------------ */
/* 导航模型（无底栏版）                                                  */
/*                                                                     */
/* 只有**一个主页**（账单列表）。底栏整条去掉了：                          */
/*  - 记一笔 = 主页右下角浮空「＋」                                       */
/*  - 统计 / 报销 = 页头「结余」右边的两个文字入口，点开是盖在主页上的二级页  */
/*  - 分类管理 / 云端备份 / 导入导出 / 主题外观 / 关于 = 页头标题右边 5 个图标 */
/* 二级页用 stack 管理（非空时盖在主页上），返回键逐层退。                  */
/* 记账页：盖在所有内容之上的弹层，用完就关。                              */
/* ------------------------------------------------------------------ */

@Composable
private fun AppRoot(
    vm: LedgerViewModel,
    sharedImage: Uri?,
    ocr: OcrEngine,
    openAdd: Boolean,
    onOpenAddHandled: () -> Unit
) {
    // 二级页栈（主页 → 统计 / 报销 / 分类管理 / 云端备份）。非空时盖在主页上。
    var stack by remember { mutableStateOf<List<Overlay>>(emptyList()) }

    // 记账/编辑层：null 表示没打开
    var editor by remember { mutableStateOf<EditorTarget?>(null) }

    // 关于 / 主题外观：这两个还是弹层
    var showAbout by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }

    val transactions by vm.transactions.collectAsState()
    val categories by vm.categories.collectAsState()
    val summary by vm.summary.collectAsState()
    val webdav by vm.webDavSettings.collectAsState()
    val appSettings by vm.appSettings.collectAsState()
    val reimbursedTxs by vm.reimbursedTxs.collectAsState()
    val toast by vm.toast.collectAsState()
    val busy by vm.busy.collectAsState()
    val accountOverviews by vm.accountOverviews.collectAsState()

    // 提示条：显示 1.8 秒自动消失。
    // 用 LaunchedEffect(toast) 做延时 —— toast 变了协程就重启，不会误清下一条。
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(1800)
            vm.clearToast()
        }
    }

    // 分享进来的图片：直接打开记账页并跑识别。
    var handledUri by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(sharedImage) {
        if (sharedImage != null && sharedImage != handledUri) {
            handledUri = sharedImage
            editor = EditorTarget.New(sharedImage)
        }
    }

    // 检查更新只在用户点「关于 → 版本号」时发生，App 平时完全不联网

    // 桌面「快速记一笔」插件：进来直接弹记账页
    LaunchedEffect(openAdd) {
        if (openAdd) {
            editor = EditorTarget.New(null)
            onOpenAddHandled()
        }
    }

    // 状态栏图标颜色：现在每个界面顶部都是蓝色渐变页头（分类管理 / 云端备份
    // 已经改成盖在主页上的弹层，不再有白底顶栏那一页）→ 图标永远白色。
    val view = LocalView.current
    val top = stack.lastOrNull()
    LaunchedEffect(top) {
        val window = (view.context as ComponentActivity).window
        WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = false
    }

    // 返回键：记账层 → 二级页 → 交回系统（退出 App）
    BackHandler(enabled = editor != null || stack.isNotEmpty()) {
        when {
            editor != null -> editor = null
            stack.isNotEmpty() -> stack = stack.dropLast(1)
        }
    }

    // 底栏整条去掉了（用户要求）：主页靠右下角浮空「＋」记一笔，
    // 统计 / 报销 从页头进，设置项从页头标题后的 5 个图标进。
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            when (top) {
                // 2026-10-09 用户确认：分类管理 / 云端备份不要弹层，改成统计页那样左上角带返回的整页
                Overlay.CATEGORY -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    OverlayTopBar(title = "分类管理", onBack = { stack = stack.dropLast(1) })
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        CategoryScreen(
                            categories = categories,
                            transactions = transactions,
                            onAdd = { name, icon, pid, type, ck -> vm.addCategory(name, icon, pid, type, ck) },
                            onDelete = vm::deleteCategory,
                            onMoveAndDelete = vm::deleteCategoryReassign,
                            onUpdate = vm::updateCategory,
                            onMove = vm::moveCategory,
                            onReorder = vm::reorderCategories
                        )
                    }
                }
                Overlay.BACKUP -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    OverlayTopBar(title = "云端备份", onBack = { stack = stack.dropLast(1) })
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        WebDavScreen(vm, webdav, busy)
                    }
                }
                // 2026-10-09 用户要求：导入导出 / 账户也改成统计页那样「左上角带返回」的整页
                Overlay.IO -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    OverlayTopBar(title = "导入导出", onBack = { stack = stack.dropLast(1) })
                    ImportExportSheet(
                        txCount = transactions.size,
                        categoryCount = categories.size,
                        onClearData = vm::clearAllData,
                        onImportCsv = vm::importCsv,
                        onImportJson = vm::restoreFromJson,
                        onExportJson = { uri -> vm.exportToUri(uri) },
                        onExportCsv = { uri -> vm.exportCsv(uri) },
                        onDismiss = { stack = stack.dropLast(1) }
                    )
                }
                Overlay.ACCOUNT -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    OverlayTopBar(title = "账户", onBack = { stack = stack.dropLast(1) })
                    AccountStatsSheet(
                        overviews = accountOverviews,
                        onAdd = vm::addAccount,
                        onUpdate = vm::updateAccount,
                        onDelete = vm::deleteAccount
                    )
                }
                // 统计 / 报销：这两页自带蓝色渐变页头，不能再套白底 OverlayPage
                // （两个页头叠一起很难看），返回键画在它们自己的页头里。
                Overlay.STATS -> StatsScreen(
                    vm = vm,
                    onEditTx = { editor = EditorTarget.Edit(it) },
                    onBack = { stack = stack.dropLast(1) }
                )
                Overlay.REIMBURSE -> ReimburseScreen(
                    transactions = reimbursedTxs,
                    onEdit = { editor = EditorTarget.Edit(it) },
                    onDelete = vm::deleteTransaction,
                    onBack = { stack = stack.dropLast(1) }
                )

                null -> TransactionListScreen(
                    transactions = transactions,
                    categories = categories,
                    monthExpense = summary.first,
                    monthIncome = summary.second,
                    appTitle = appSettings.appTitle,
                    onSetTitle = vm::setAppTitle,
                    reimburseCount = reimbursedTxs.size,
                    onDelete = vm::deleteTransaction,
                    onEdit = { editor = EditorTarget.Edit(it) },
                    onAdd = { editor = EditorTarget.New(null) },
                    onOpenStats = { stack = stack + Overlay.STATS },
                    onOpenReimburse = { stack = stack + Overlay.REIMBURSE },
                    onOpenCategory = { stack = stack + Overlay.CATEGORY },
                    onOpenBackup = { stack = stack + Overlay.BACKUP },
                    onOpenIo = { stack = stack + Overlay.IO },
                    onOpenTheme = { showTheme = true },
                    onOpenAbout = { showAbout = true },
                    onOpenAccounts = { stack = stack + Overlay.ACCOUNT }
                )
            }

            // 记账 / 编辑：弹出式底部表单，浮在内容之上
            editor?.let { target ->
                AddTransactionScreen(
                    vm = vm,
                    categories = categories,
                    editing = (target as? EditorTarget.Edit)?.tx,
                    initialImage = (target as? EditorTarget.New)?.imageUri,
                    onDone = { editor = null }
                )
            }

            // 关于：仍是弹层（用户只要求把分类管理 / 备份 / 导入导出 / 账户改成整页）
            if (showAbout) {
                AboutDialog(
                    onDismiss = { showAbout = false },
                    checking = busy,
                    // 2026-10-09 用户要求：平时不联网；点这一行才查，有新版直接跳浏览器下载页
                    onTapVersion = { vm.checkUpdateNow() }
                )
            }
            if (showTheme) {
                ThemeDialog(
                    paletteId = appSettings.themeId,
                    mode = appSettings.mode,
                    customColor = appSettings.customColor,
                    onPickPalette = vm::setTheme,
                    onPickMode = vm::setMode,
                    onPickCustomColor = vm::setCustomColor,
                    onDismiss = { showTheme = false }
                )
            }

            if (busy) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.25f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Card(shape = RoundedCornerShape(16.dp)) {
                            Column(
                                modifier = Modifier.padding(26.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    color = AppTheme.primary,
                                    strokeWidth = 3.dp
                                )
                                Spacer(Modifier.height(14.dp))
                                Text("处理中…", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // 提示条：支付宝式 —— 居中深色半透明胶囊，白字，纯渐入渐出
            AnimatedVisibility(
                visible = toast != null,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(160)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF1F2124).copy(alpha = 0.92f)
                ) {
                    Text(
                        toast.orEmpty(),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            }
        }
    }
}

private enum class Overlay { CATEGORY, BACKUP, STATS, REIMBURSE, IO, ACCOUNT }

/** 二级页 = 白底顶栏 + 内容。顶栏带返回箭头、标题居中。 */
@Composable
private fun OverlayPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OverlayTopBar(title = title, onBack = onBack)
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}

private sealed interface EditorTarget {
    /** 新建，可带一张待识别的图 */
    data class New(val imageUri: Uri?) : EditorTarget
    /** 编辑已有账单 */
    data class Edit(val tx: TxWithCategory) : EditorTarget
}
