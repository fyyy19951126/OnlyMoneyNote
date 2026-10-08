package com.dafeng.onlymoneynote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeng.onlymoneynote.data.repo.SettingsRepository
import com.dafeng.onlymoneynote.ui.LedgerViewModel

/** 云端备份（WebDAV）。文案刻意精简：设置区一目了然，说明压成三行。 */
@Composable
fun WebDavScreen(
    vm: LedgerViewModel,
    settings: SettingsRepository.WebDavSettings,
    busy: Boolean
) {
    var url by remember { mutableStateOf(settings.baseUrl) }
    var user by remember { mutableStateOf(settings.username) }
    var pass by remember { mutableStateOf(settings.password) }
    var path by remember { mutableStateOf(settings.remotePath) }
    var loaded by remember { mutableStateOf(false) }

    // 首次拿到磁盘设置后填进输入框
    LaunchedEffect(settings) {
        if (!loaded && (settings.baseUrl.isNotEmpty() || settings.username.isNotEmpty())) {
            url = settings.baseUrl
            user = settings.username
            pass = settings.password
            path = settings.remotePath
            loaded = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
    ) {
        // 状态条
        Surface(
            // Surface 默认按内容自适应宽度，不写 fillMaxWidth 就会比输入框短一截
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = if (settings.isConfigured) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (settings.isConfigured) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                    null,
                    tint = if (settings.isConfigured) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (settings.isConfigured) "已连接 ${settings.baseUrl}"
                    else "未配置，填好下面几项即可备份",
                    fontSize = 13.sp,
                    maxLines = 1,
                    color = if (settings.isConfigured) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Field("服务器地址", url, { url = it }, "https://dav.jianguoyun.com/dav/")
        Spacer(Modifier.height(8.dp))
        Field("账号", user, { user = it }, "一般是登录邮箱")
        Spacer(Modifier.height(8.dp))
        Field("密码", pass, { pass = it }, "坚果云等服务填「应用密码」", isPassword = true)
        Spacer(Modifier.height(8.dp))
        Field("远端路径", path, { path = it }, "moneynote/backup.json")

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    vm.saveWebDav(url, user, pass, path)
                    vm.uploadBackup()
                },
                modifier = Modifier.weight(1f).height(44.dp),
                enabled = !busy,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.CloudUpload, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("上传备份")
            }
            OutlinedButton(
                onClick = {
                    vm.saveWebDav(url, user, pass, path)
                    vm.restoreBackup()
                },
                modifier = Modifier.weight(1f).height(44.dp),
                enabled = !busy,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.CloudDownload, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("恢复备份")
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { vm.saveWebDav(url, user, pass, path); vm.testConnection() },
            modifier = Modifier.fillMaxWidth().height(42.dp),
            enabled = !busy,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.CloudDone, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("保存并测试连接")
        }

        Spacer(Modifier.height(14.dp))

        // 说明：三行说清
        Surface(
            // 这里就是宽度不齐的元凶：说明卡没撑满，按最长那行自适应，
            // 于是比上面四个输入框短一截
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Bullet("恢复会先清空本机数据，以云端为准。")
                Bullet("备份含分类、账单、设置；密码不进备份文件。")
                Bullet("坚果云需用「应用密码」；群晖地址带端口。")
            }
        }

    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false
) {
    // 标签用 OutlinedTextField 自己的浮动 label，不再单独占一行 ——
    // 一个框省掉 ~26dp，四个框就省出 100dp 多，弹层里就不用往下拉了。
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, fontSize = 12.5.sp) },
        placeholder = { Text(placeholder, fontSize = 13.sp) },
        singleLine = true,
        // 圆角 12dp：Material 默认几乎是直角，跟这屏的圆角卡片、按钮不是一套观感
        shape = RoundedCornerShape(12.dp),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
    )
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 1.5.dp)) {
        Text("· ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
