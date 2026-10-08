package com.dafeng.onlymoneynote.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dafeng.onlymoneynote.data.remote.WebDavClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "moneynote_settings")

@Singleton
class SettingsRepository @Inject constructor(
    private val context: Context
) {
    private val keyUrl = stringPreferencesKey("webdav_url")
    private val keyUser = stringPreferencesKey("webdav_user")
    private val keyPass = stringPreferencesKey("webdav_pass")
    private val keyPath = stringPreferencesKey("webdav_path")
    private val keyTheme = stringPreferencesKey("app_theme_id")
    private val keyThemeMigrated = booleanPreferencesKey("app_theme_migrated_v2")
    private val keyTitleMigrated = booleanPreferencesKey("app_title_migrated_v2")
    private val keyMode = stringPreferencesKey("app_mode")
    private val keyCustomColor = intPreferencesKey("app_custom_color")
    private val keyLastCategory = longPreferencesKey("last_category_id")
    private val keyLastType = intPreferencesKey("last_type")
    private val keyAppTitle = stringPreferencesKey("app_title")

    data class WebDavSettings(
        val baseUrl: String = "",
        val username: String = "",
        val password: String = "",
        val remotePath: String = "moneynote/backup.json"
    ) {
        val isConfigured: Boolean
            get() = baseUrl.isNotBlank() && username.isNotBlank()

        fun toClientConfig() = WebDavClient.Config(baseUrl, username, password, remotePath)
    }

    val settings: Flow<WebDavSettings> = context.dataStore.data.map { prefs ->
        WebDavSettings(
            baseUrl = prefs[keyUrl] ?: "",
            username = prefs[keyUser] ?: "",
            password = prefs[keyPass] ?: "",
            remotePath = prefs[keyPath] ?: "moneynote/backup.json"
        )
    }

    suspend fun save(s: WebDavSettings) {
        context.dataStore.edit { prefs ->
            prefs[keyUrl] = s.baseUrl.trim()
            prefs[keyUser] = s.username.trim()
            prefs[keyPass] = s.password
            prefs[keyPath] = s.remotePath.trim().ifBlank { "moneynote/backup.json" }
        }
    }

    /** 非 WebDAV 的应用设置（配色、外观模式等）。拆出来是因为这些要能随备份一起走。 */
    data class AppSettings(
        val themeId: String = "alipay",
        /** 外观模式：system / light / dark */
        val mode: String = "system",
        /** 自定义强调色（ARGB），0 表示没设 */
        val customColor: Int = 0,
        /** 主页大标题文字，默认「Only记账」 */
        val appTitle: String = "Only记账"
    )

    val appSettings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeId = prefs[keyTheme] ?: "alipay",
            mode = prefs[keyMode] ?: "system",
            customColor = prefs[keyCustomColor] ?: 0,
            appTitle = prefs[keyAppTitle] ?: "Only记账"
        )
    }

    suspend fun saveTheme(themeId: String) {
        context.dataStore.edit { it[keyTheme] = themeId }
    }

    /** 旧版默认主题是 "red"，新版换成 "alipay"：全生命周期只迁移一次（有标记位） */
    suspend fun migrateLegacyTheme() {
        context.dataStore.edit { prefs ->
            if (prefs[keyThemeMigrated] != true) {
                if (prefs[keyTheme] == "red") prefs[keyTheme] = "alipay"
                prefs[keyThemeMigrated] = true
            }
        }
    }

    /**
     * 有一版把默认标题写成了英文 "OnlyMoneyNote"，现在统一成「Only记账」。
     * 只迁移一次（有标记位），之后用户自己起的名字不会再被动。
     */
    suspend fun migrateLegacyTitle() {
        context.dataStore.edit { prefs ->
            if (prefs[keyTitleMigrated] != true) {
                if (prefs[keyAppTitle] == "OnlyMoneyNote") prefs[keyAppTitle] = "Only记账"
                prefs[keyTitleMigrated] = true
            }
        }
    }

    /** 主题回到默认：支付宝蓝 + 跟随系统 + 去掉自定义色 */
    suspend fun clearTheme() {
        context.dataStore.edit { prefs ->
            prefs[keyTheme] = "alipay"
            prefs[keyMode] = "system"
            prefs.remove(keyCustomColor)
        }
    }

    /** 除主题以外的本机配置全清：WebDAV、主页标题、上次记账用的分类 */
    suspend fun clearAppConfig() {
        context.dataStore.edit { prefs ->
            prefs.remove(keyUrl)
            prefs.remove(keyUser)
            prefs.remove(keyPass)
            prefs.remove(keyPath)
            prefs.remove(keyAppTitle)
            prefs.remove(keyLastCategory)
            prefs.remove(keyLastType)
        }
    }

    suspend fun saveMode(mode: String) {
        context.dataStore.edit { it[keyMode] = mode }
    }

    suspend fun saveCustomColor(argb: Int) {
        context.dataStore.edit {
            it[keyCustomColor] = argb
            it[keyTheme] = "custom"
        }
    }

    /** 上次记账用的分类与收支类型 —— 记一笔时自动带出来，省得每次重选 */
    data class LastUsed(val categoryId: Long = 0, val type: Int = 0)

    val lastUsed: Flow<LastUsed> = context.dataStore.data.map { prefs ->
        LastUsed(
            categoryId = prefs[keyLastCategory] ?: 0L,
            type = prefs[keyLastType] ?: 0
        )
    }

    suspend fun saveLastUsed(categoryId: Long, type: Int) {
        context.dataStore.edit {
            it[keyLastCategory] = categoryId
            it[keyLastType] = type
        }
    }

    /** 恢复备份时整个覆盖 */
    suspend fun saveAppSettings(s: AppSettings) {
        context.dataStore.edit {
            it[keyTheme] = s.themeId
            it[keyMode] = s.mode
            it[keyCustomColor] = s.customColor
            it[keyAppTitle] = s.appTitle
        }
    }

    /** 主页标题（自定义名称） */
    suspend fun saveAppTitle(title: String) {
        context.dataStore.edit { it[keyAppTitle] = title }
    }

    /** 一次性读取（导出备份时用） */
    suspend fun settingsFlowOnce(): WebDavSettings = settings.first()

    suspend fun appSettingsFlowOnce(): AppSettings = appSettings.first()
}
