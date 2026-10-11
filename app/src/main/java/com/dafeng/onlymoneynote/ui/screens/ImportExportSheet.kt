package com.dafeng.onlymoneynote.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeng.onlymoneynote.data.importer.RecordsCsvImporter
import com.dafeng.onlymoneynote.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 导入导出面板：首页顶栏「导入导出」图标点开就是这个。
 *
 * 三件事——导出账单 CSV、导出 JSON 备份、从别的记账软件导入。
 * 外加一个危险区的「清空全部账单」。
 *
 * 导入是破坏性操作（会清空现有分类和账单），所以选完文件先弹确认框，
 * 把「要导入多少笔、会重建几个分类」讲清楚再让用户点确认。
 */
@Composable
fun ImportExportSheet(
    txCount: Int,
    categoryCount: Int,
    /** 长按确认后回调：三个开关决定除账单外还要不要一起清掉 */
    onClearData: (theme: Boolean, categories: Boolean, settings: Boolean) -> Unit,
    onImportCsv: (ByteArray) -> Unit,
    /** 2026-10-11：导入钱迹等第三方软件的账单 */
    onImportThirdParty: (ThirdPartyKind, ByteArray) -> Unit,
    /** 选了本地 JSON 备份并确认覆盖后回调（Uri 交给 ViewModel 去读） */
    onImportJson: (Uri) -> Unit,
    onExportJson: (Uri) -> Unit,
    onExportCsv: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    var confirmClear by remember { mutableStateOf(false) }
    // 第一步点了「下一步」才置 true，进入第二步（长按确认）
    var clearArmed by remember { mutableStateOf(false) }
    // 第一步里问的三个「顺便一起删」开关，默认全不勾
    var wipeTheme by remember { mutableStateOf(false) }
    var wipeCategories by remember { mutableStateOf(false) }
    var wipeSettings by remember { mutableStateOf(false) }
    // 选好的 CSV 先存着，等用户确认了再导入
    var pendingCsv by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var csvError by remember { mutableStateOf<String?>(null) }
    // 2026-10-11 用户要求：本地备份里加「导入各类账单」，覆盖市面常见记账软件。
    // 和 CSV 那条路径分开：xlsx 要走 [QianJiImporter]，而且转账要单独落。
    var pendingThird by remember { mutableStateOf<ThirdPartyPick?>(null) }
    var thirdError by remember { mutableStateOf<String?>(null) }
    // 正在选哪个软件的导出文件；选完立刻弹系统文件选择器
    var pendingThirdKind by remember { mutableStateOf<ThirdPartyKind?>(null) }
    // 「选哪个软件」这一层菜单
    var showThirdMenu by remember { mutableStateOf(false) }
    // 选好的 JSON 备份：先弹确认，确认了才真的覆盖本机
    var pendingJson by remember { mutableStateOf<Uri?>(null) }
    var pendingJsonName by remember { mutableStateOf("") }

    val pickCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                csvError = "读不到文件内容，换一个文件试试"
            } else {
                pendingCsv = (uri.lastPathSegment?.substringAfterLast('/') ?: "导入文件.csv") to bytes
            }
        }
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) onExportJson(uri) }

    // 第三方记账软件账单：先选软件，再选它导出的文件。
    // 「先选软件」是因为各家的表头和字段语义都不同（钱迹的账户在「记账者」列，
    // 大象记账在「账户」列），让用户先认一下自己导的是哪个软件的，解析才对得上。
    val thirdPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val kind = pendingThirdKind ?: return@rememberLauncherForActivityResult
        pendingThirdKind = null
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            thirdError = "读不到文件内容，换一个文件试试"
        } else {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "导入文件"
            pendingThird = ThirdPartyPick(kind, name, bytes)
        }
    }

    // MIME 用 */* 而不是 text/csv —— 部分国产 ROM 的文件选择器不认 text/csv，会选不到位置
    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri -> if (uri != null) onExportCsv(uri) }

    val pickJson = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingJsonName = uri.lastPathSegment?.substringAfterLast('/') ?: "备份文件.json"
            pendingJson = uri
        }
    }

    // 2026-10-09 用户明确要求：「右上角的分类管理、webdav备份、导入导出、以及账户功能，
    // 都改成统计页面一样的左上角带返回的界面，不要弹出框」。
    // 所以这里从 AlertDialog 弹层改成整页内容本体，标题和返回由外层 OverlayTopBar 画。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
            Column {
                IoRow(
                    icon = Icons.Outlined.Download,
                    title = "导入各类账单",
                    subtitle = "支持钱迹等记账软件导出的 Excel / CSV",
                    onClick = { showThirdMenu = true }
                )
                Spacer(Modifier.height(8.dp))
                IoRow(
                    icon = Icons.Outlined.Download,
                    title = "导入账单 CSV",
                    subtitle = "",
                    onClick = { pickCsv.launch(arrayOf("*/*")) }
                )
                Spacer(Modifier.height(8.dp))
                IoRow(
                    icon = Icons.Outlined.UploadFile,
                    title = "导出账单 CSV",
                    subtitle = "",
                    onClick = {
                        val ts = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA)
                            .format(java.util.Date())
                        exportCsvLauncher.launch("moneynote-$ts.csv")
                    }
                )
                Spacer(Modifier.height(8.dp))
                IoRow(
                    icon = Icons.Outlined.Restore,
                    title = "导入 JSON 备份文件",
                    subtitle = "",
                    onClick = { pickJson.launch(arrayOf("*/*")) }
                )
                Spacer(Modifier.height(8.dp))
                IoRow(
                    icon = Icons.Outlined.UploadFile,
                    title = "导出 JSON 备份文件",
                    subtitle = "",
                    onClick = {
                        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.CHINA)
                            .format(java.util.Date())
                        exportJsonLauncher.launch("moneynote-backup-$ts.json")
                    }
                )

                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                )
                Spacer(Modifier.height(14.dp))
                IoRow(
                    icon = Icons.Outlined.DeleteOutline,
                    title = "清空全部账单",
                    subtitle = "",
                    danger = true,
                    onClick = { confirmClear = true }
                )
            }
    }

    /* ---------------- 选哪个记账软件 ---------------- */
    if (showThirdMenu) {
        AlertDialog(
            onDismissRequest = { showThirdMenu = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("从哪个软件导入？", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    ThirdPartyKind.entries.forEach { k ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    showThirdMenu = false
                                    pendingThirdKind = k
                                    thirdPicker.launch(arrayOf("*/*"))
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(k.label, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    k.hint,
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                Text(
                    "取消",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { showThirdMenu = false }.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        )
    }

    /* ---------------- 第三方账单：解析结果确认 ---------------- */
    pendingThird?.let { pick ->
        val parsed = remember(pick.kind, pick.bytes) {
            runCatching { ThirdPartyImportSupport.parse(pick.kind, pick.bytes) }.getOrNull()
        }
        if (parsed == null || (parsed.rows.isEmpty() && parsed.transfers.isEmpty())) {
            AlertDialog(
                onDismissRequest = { pendingThird = null },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("没解析出账单", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                text = {
                    Text(
                        "「${pick.name}」里没有可用的账单。\n\n" +
                            "确认是在${pick.kind.label}里导出的账单文件（不是账本备份）。",
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Text(
                        "知道了",
                        color = AppTheme.primary,
                        modifier = Modifier.clickable { pendingThird = null }.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            )
        } else {
            val plan = remember(parsed) { ThirdPartyImportSupport.planOf(parsed) }
            val df = remember { java.text.SimpleDateFormat("yyyy/M/d", java.util.Locale.CHINA) }
            AlertDialog(
                onDismissRequest = { pendingThird = null },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                title = {
                    Text(
                        "导入 ${parsed.rows.size + parsed.transfers.size} 笔？",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                text = {
                    Text(
                        buildString {
                            append("来源：").append(pick.kind.label).append('\n')
                            append("文件：").append(pick.name).append('\n')
                            if (plan.minDate != null && plan.maxDate != null) {
                                append("时间范围：")
                                append(df.format(java.util.Date(plan.minDate!!)))
                                append(" ~ ")
                                append(df.format(java.util.Date(plan.maxDate!!)))
                                append('\n')
                            }
                            append("其中收支 ").append(parsed.rows.size).append(" 笔")
                            if (parsed.transfers.isNotEmpty()) {
                                append("、转账 ").append(parsed.transfers.size).append(" 笔")
                            }
                            append('\n')
                            append("会清空现有 ").append(txCount).append(" 笔账单和 ")
                            append(categoryCount).append(" 个分类，按文件重建 ")
                            append(plan.parentCount).append(" 个一级、")
                            append(plan.childCount).append(" 个二级分类。\n")
                            if (parsed.skippedCurrency > 0) {
                                append("⚠ 有 ").append(parsed.skippedCurrency)
                                append(" 笔是非人民币，已跳过（本 App 不换算汇率）。\n")
                            }
                            if (parsed.skippedBad > 0) {
                                append("⚠ 有 ").append(parsed.skippedBad).append(" 笔读不出来，已跳过。\n")
                            }
                            val noAccount = parsed.rows.count { it.accountName.isBlank() }
                            if (noAccount > 0) {
                                append("其中 ").append(noAccount)
                                append(" 笔原文件没写账户，会归「未指定」。\n")
                            }
                            append("这一步不能撤销。")
                        },
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp
                    )
                },
                confirmButton = {
                    Text(
                        "覆盖导入",
                        color = AppTheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable {
                                pendingThird = null
                                onImportThirdParty(pick.kind, pick.bytes)
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                },
                dismissButton = {
                    Text(
                        "取消",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { pendingThird = null }.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            )
        }
    }

    thirdError?.let { msg ->
        AlertDialog(
            onDismissRequest = { thirdError = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("这个文件读不了", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text(msg, fontSize = 14.sp) },
            confirmButton = {
                Text(
                    "知道了",
                    color = AppTheme.primary,
                    modifier = Modifier.clickable { thirdError = null }.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        )
    }

    /* ---------------- 清空确认：两步 ---------------- */
    // 第一步：讲清楚后果，让人先意识到在点什么东西
    // 第二步：必须长按按钮 1.2 秒才真正执行 —— 手滑点一下不会误删
    if (confirmClear) {
        if (!clearArmed) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                title = {
                    Text("清空全部账单？", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                },
                text = {
                    Column {
                        Text(
                            "本机 $txCount 笔账单会被删除，删掉不能撤销，也不进回收站。\n" +
                                "下面三项默认不动，要一起清就打开开关。",
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        WipeOption("主题", "配色和外观回到默认（支付宝蓝 / 跟随系统）", wipeTheme) { wipeTheme = it }
                        WipeOption("分类", "删掉的分类由内置默认分类顶上，账单本来就先清空了", wipeCategories) { wipeCategories = it }
                        WipeOption("设置", "云端备份地址、主页标题、上次记账用的分类", wipeSettings) { wipeSettings = it }
                    }
                },
                confirmButton = {
                    Text(
                        "下一步",
                        color = AppTheme.primary,
                        modifier = Modifier
                            .clickable { clearArmed = true }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                },
                dismissButton = {
                    Text(
                        "取消",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { confirmClear = false }.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { confirmClear = false; clearArmed = false },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                title = {
                    Text("最后确认", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.primary)
                },
                text = {
                    val also = listOfNotNull(
                        if (wipeTheme) "主题" else null,
                        if (wipeCategories) "分类" else null,
                        if (wipeSettings) "设置" else null
                    )
                    Text(
                        "要删的是：$txCount 笔账单" +
                            (if (also.isEmpty()) "" else "，外加" + also.joinToString("、")) +
                            "\n\n下面这个按钮要按住不放满 1.2 秒才会真的执行。\n中途松手就取消，什么都不会发生。",
                        fontSize = 14.sp,
                        lineHeight = 21.sp
                    )
                },
                confirmButton = {
                    // 必须**按住 1.2 秒**才删。轻点 / 快滑都不会触发。
                    // 思路：按下（onPress 回调）时置 holding=true，Compose 侧用 LaunchedEffect
                    // 起一个 1.2s 的计时协程；抬手（onPress 的 finally / onTap）时读标志位。
                    // 这样完全避开底层 pointer 事件的 restricted-suspend 限制。
                    val holding = remember { mutableStateOf(false) }
                    val reached = remember { mutableStateOf(false) }
                    val total = 1200L

                    // 按住时开始计时，1.2s 后置 reached；没按够就被 onTap 里取消
                    LaunchedEffect(holding.value) {
                        if (holding.value) {
                            reached.value = false
                            delay(total)
                            reached.value = true
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                // 完全跟随主题色（用户要求）：危险程度靠「按住 1.2 秒」这个
                                // 交互本身保证，不靠颜色吓人。
                                if (reached.value) AppTheme.primary
                                else AppTheme.primary.copy(alpha = 0.75f)
                            )
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        holding.value = true
                                        val ok = tryAwaitRelease()   // 一直挂到手指抬起
                                        holding.value = false
                                        // 抬手瞬间：如果计时已到，就执行删除
                                        if (reached.value) {
                                            reached.value = false
                                            clearArmed = false
                                            confirmClear = false
                                            onClearData(wipeTheme, wipeCategories, wipeSettings)
                                        }
                                    }
                                )
                            }
                            .padding(horizontal = 22.dp, vertical = 11.dp)
                    ) {
                        Text(
                            text = if (reached.value) "松开即删除" else "按住不放 1.2 秒",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                dismissButton = {
                    Text(
                        "我点错了",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { clearArmed = false; confirmClear = false }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            )
        }
    }

    /* ---------------- 从 JSON 备份恢复：先讲清楚会覆盖什么 ---------------- */
    pendingJson?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingJson = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("用这份备份覆盖本机？", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                // 空行用 Char(10) 拼，不写转义符
                val gap = Char(10).toString() + Char(10)
                Text(
                    "文件：$pendingJsonName$gap" +
                        "会先清空现在的 $categoryCount 个分类和 $txCount 笔账单，" +
                        "再按备份里的内容原样重建（分类 id、收支类型、配色、主页标题都跟着回来）。$gap" +
                        "这一步不能撤销。",
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                )
            },
            confirmButton = {
                Text(
                    "覆盖恢复",
                    color = AppTheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable {
                            pendingJson = null
                            onImportJson(uri)
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            },
            dismissButton = {
                Text(
                    "取消",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { pendingJson = null }.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        )
    }

    /* ---------------- 导入读文件失败 ---------------- */
    csvError?.let { msg ->
        AlertDialog(
            onDismissRequest = { csvError = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("这个文件读不了", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text(msg, fontSize = 14.sp) },
            confirmButton = {
                Text(
                    "知道了",
                    color = AppTheme.primary,
                    modifier = Modifier.clickable { csvError = null }.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        )
    }

    /* ---------------- 导入确认 ---------------- */
    pendingCsv?.let { picked ->
        val fileName = picked.first
        val bytes = picked.second
        val plan = remember(bytes) {
            runCatching { RecordsCsvImporter.buildPlan(RecordsCsvImporter.decode(bytes)) }.getOrNull()
        }
        if (plan == null || plan.txs.isEmpty()) {
            AlertDialog(
                onDismissRequest = { pendingCsv = null },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("没解析出账单", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                text = {
                    Text(
                        "「$fileName」里没有可用的账单。\n" +
                            "确认是记账软件导出的 CSV，并且有「日期 / 分类 / 收支 / 金额」这几列。",
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                },
                confirmButton = {
                    Text(
                        "知道了",
                        color = AppTheme.primary,
                        modifier = Modifier.clickable { pendingCsv = null }.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            )
        } else {
            val df = remember { java.text.SimpleDateFormat("yyyy/M/d", java.util.Locale.CHINA) }
            AlertDialog(
                onDismissRequest = { pendingCsv = null },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                title = {
                    Text(
                        "导入 ${plan.txs.size} 笔账单？",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                text = {
                    Text(
                        buildString {
                            append("文件：").append(fileName).append('\n')
                            if (plan.minDate != null && plan.maxDate != null) {
                                append("时间范围：")
                                append(df.format(java.util.Date(plan.minDate!!)))
                                append(" ~ ")
                                append(df.format(java.util.Date(plan.maxDate!!)))
                                append('\n')
                            }
                            append("会清空现有 ").append(txCount).append(" 笔账单和 ")
                            append(categoryCount).append(" 个分类，按文件重建 ")
                            append(plan.parentCount).append(" 个一级、")
                            append(plan.childCount).append(" 个二级分类。\n")
                            append("图标是自动挑的，导入后可以自己改。")
                        },
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                },
                confirmButton = {
                    Text(
                        "导入",
                        color = AppTheme.danger,
                        modifier = Modifier
                            .clickable { pendingCsv = null; onImportCsv(bytes) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                },
                dismissButton = {
                    Text(
                        "取消",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { pendingCsv = null }.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            )
        }
    }
}

/* ------------------------------------------------------------------ */

@Composable
private fun IoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    /** 语义标记：危险操作。配色上不再单独用红 —— 按用户要求**完全跟随主题色**，
     *  靠「危险区」分割线 + 垃圾桶图标 + 两步确认来区分，而不是靠一块硬红。 */
    danger: Boolean = false,
    onClick: () -> Unit
) {
    // 支付宝式行：中性底 + 实底彩色图标块，图标块统一主题色。
    val tint = AppTheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(tint),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, modifier = Modifier.size(18.dp), tint = Color.White)
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            // 副标题给空串就整行不渲染（清空那一行按用户要求不带备注）
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }

}
/** 清空确认里的一行「顺便一起删」开关 */
@Composable
private fun WipeOption(
    title: String,
    hint: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable { onChecked(!checked) }
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                hint,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedTrackColor = AppTheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary
            )
        )
    }
}
