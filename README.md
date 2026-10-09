# OnlyMoneyNote（记账本）

安卓记账 App。Kotlin + Jetpack Compose + Room + Hilt，原生开发，不引第三方 UI 库。  
界面按**支付宝的设计语言**整体改版：#1677FF 主色、灰底白卡片、彩色分类图标块、固定底栏。

## 功能

- **记账**：底部弹出表单，填金额、日期、一级分类、二级分类、备注
- **改账**：账单列表**长按**任一条进入修改；也可以在条目右侧「⋮」菜单里改或删
- **自定义分类**：一级、二级都能加删改。**长按分类行**改名字和图标，每行有 `+` 加二级、上下箭头排序、垃圾桶删除
- **统计数据**：按月 / 按季 / 按年三种口径，可前后翻页。含收支汇总、结余、趋势柱状图、分类占比排行
- **截图记账**：从微信/支付宝/相册拿到支付截图 → 分享到本 App 或从相册选图 → 本地 OCR 自动认出金额和时间 → 预填表单 → 核对后保存
- **WebDAV 备份**：填服务器地址 + 账号密码，一键上传/恢复，备份内容是 JSON
- **资金账户**：记一笔时选这笔钱从哪个账户走（支付宝 / 微信 / 银行卡…可自己加）。首页「账户」看总资产和每个账户的余额，**长按某行**改名字、图标、期初金额或删除，右上角 ＋ 新建

## 界面结构

```
┌──────────────────────────────────┐
│ ▓▓ 蓝色渐变页头（白字大数字）▓▓      │  ← 账单/统计/报销/我的 每个Tab都有
│ 记吧                              │
│ 支出 ¥140.50   收入 ¥0.00          │  ← 点支出/收入可筛选
├──────────────────────────────────┤
│  白色圆角面板                      │
│  10月2日 周五      +¥0.00 -¥16.54  │  ← 按天分组，灰色日期行
│  [彩色图标块] 分类·子类   -¥5.67    │
│                        (左滑删除)  │
├──────────────────────────────────┤
│  账单   统计   (＋)   报销   我的   │  ← 白色固定底栏，蓝色＋记一笔
└──────────────────────────────────┘
```

## 环境要求

| 项                  | 版本                                                                |
| ------------------ | ----------------------------------------------------------------- |
| JDK                | 21（`D:\dev\jdk-21`）                                               |
| Android SDK        | platform-tools 37.0.1 / build-tools 36.0.0 / platforms;android-36 |
| Gradle             | 8.9                                                               |
| Kotlin             | 2.0.21                                                            |
| AGP                | 8.6.1                                                             |
| ML Kit             | text-recognition 16.0.1（本地离线 OCR）                                 |
| minSdk / targetSdk | 26 / 36                                                           |

## 构建

```bash
export JAVA_HOME="D:\\dev\\jdk-21"
export ANDROID_HOME="D:\\dev\\android-sdk"
export PATH="/d/dev/jdk-21/bin:$PATH"

GRADLE=~/.gradle/wrapper/dists/gradle-8.9-bin/gradle-8.9/bin/gradle
$GRADLE assembleDebug
```

产物：`app/build/outputs/apk/debug/OnlyMoneyNote-debug.apk`（release 同理，名字由 `base.archivesName` 控制）

跑单测（OCR 解析器）：

```bash
$GRADLE testDebugUnitTest
```

## 装到真机

⚠️ **Git Bash 下必须 `export MSYS_NO_PATHCONV=1`**，否则 `/data/local/tmp` 会被改写成 Windows 路径。

```bash
export PATH="/d/dev/android-sdk/platform-tools:$PATH"
export MSYS_NO_PATHCONV=1

adb push "D:\...\app-debug.apk" "/data/local/tmp/moneynote.apk"
adb shell "pm install -r /data/local/tmp/moneynote.apk"
adb shell "am start -n com.dafeng.moneynote/.MainActivity"
```

⚠️ **努比亚等国产 ROM 对 `adb install` 有限制**（报 `Caller has no access to session -1`），  
必须走 `push + pm install` 这条老路。

⚠️ **`adb shell input tap` 在真机上不可靠**，经常被识别成滑动，甚至点到别的 App 上。  
要做交互验证，优先写单元测试；坐标点击只用来粗略截图。

## 代码结构

```
app/src/main/java/com/dafeng/onlymoneynote/
├── OnlyMoneyNoteApp.kt      # @HiltAndroidApp
├── MainActivity.kt          # 入口 + 导航状态机（顶栏 5 个图标 → 分类管理 / 云端备份 / 本地备份 / 账户 等整页；记一笔仍是底部弹层）
├── data/
│   ├── local/               # Room：实体 / DAO / Database / 默认分类种子
│   ├── ocr/                 # OcrEngine（ML Kit 本地识别） + ReceiptParser（文本→账单要素）
│   ├── repo/                # LedgerRepository / BackupRepository / SettingsRepository
│   └── remote/              # WebDavClient（OkHttp 手写 PUT/GET/MKCOL）
├── di/AppModule.kt          # Hilt 模块
├── ui/
│   ├── LedgerViewModel.kt   # 统一管所有状态（含统计区间、OCR、分类编辑）
│   ├── components/          # AlipayUi：白色底栏(蓝＋) / 蓝色页头 / 彩色图标块 / 二级页顶栏
│   ├── theme/Theme.kt       # 默认蚂蚁蓝 #1677FF；支出绿 / 收入红（中国习惯）
│   └── screens/             # 记账弹层 / 账单列表 / 统计（分类·明细·账户）/ 报销 / 分类管理 / WebDAV 设置 / 本地备份 / 账户页
└── util/AppIcons.kt         # 图标 key → Material 图标 映射 + 分类组配色

app/src/test/java/com/dafeng/moneynote/data/ocr/
└── ReceiptParserTest.kt     # 7 个用例：银行回单 / 微信支付 / 收款 / 优惠 / 空文本 / 乱文本
```

## 几个设计决定

- **金额存「分」**（Long），不用浮点，避免 0.1+0.2 那类误差。
- **分类自关联**：`CategoryEntity.parentId == null` 即一级分类，不另开表。
- **图标存 key 字符串**，不存资源 ID，换图标库不影响已有数据。
- **WebDAV 手写**，只用 PUT/GET/MKCOL，不引第三方库。设置里已开 `usesCleartextTraffic`，支持群晖等内网 http 地址。
- **恢复会先清空本地数据**，界面上有明确提示。
- **录音识别用 Photo Picker**（`ActivityResultContracts.PickVisualMedia`），不需要申请相册读取权限。

### 性能上的取舍

**页头渐变、底栏、彩色图标块全部是静态绘制。**

不用 `Modifier.blur()`——RenderEffect 模糊在滚动时每帧都要重新渲染，中低端机必掉帧。  
页头就是一层渐变背景，底栏白底 + 发丝线，没有逐帧动画和模糊。

同理，列表页的分组（`groupBy`）用 `remember(transactions)` 缓存，避免每次重组重算；  
每日小计用一次 `forEach` 累加，不用 `filter().sumOf()` 扫两遍。

### OCR 解析策略

`ReceiptParser` 的目标是「**宁可少猜，不要瞎猜**」，但金额和时间这两个必须尽力认出来。

1. **先按行过滤噪音**：含「单号 / 卡号 / 账号 / 优惠 / 红包 / 折扣 / 余额」的行整体丢弃，     
   免得把 20 位的交易单号当成金额。
2. **金额按优先级匹配**三种模式：字段名形式（`金额(元) 42.00`）> 货币符号（`¥55.00`）> 数字+元。     
   同一模式内取最大值（支付页常有「优惠 20」「实付 108」这类干扰）。
3. **时间优先在含「时间/日期/付款/交易/创建/完成」的行里找**，命中率明显高于全文乱找。
4. **商户**先按字段名取值（`商户全称 xxx`），其次找带商户后缀的行，最后才兜底猜。

## 资金账户

`account` 表存账户本身（名字、emoji 图标、配色、排序、`initialCents` 期初金额、`builtIn`）。  
**余额不单独存**：当前余额 = 期初金额 + 该账户名下账单的净流水（收入加、支出减），  
由 `TransactionDao.observeAccountNet()` 现算。所以删账单、改账单、改账户都会自动重算，  
不需要任何补偿逻辑，也不会出现余额和流水对不上的情况。

- 内置一个不可删的「未指定」账户，`id` 固定 = 1（`AccountEntity.UNSPECIFIED_ID`）：    
  v4→v5 迁移插的就是这一条，全新安装的种子也按这个 id 插，两条路径 id 对得上。    
  迁移时顺带把 6 个建议账户（支付宝 / 微信 / 微信分身 / 工行 / 农行 / 建行）一起插进去。
- 记一笔里的账户胶囊按**账单笔数**从多到少排，常用的自动排到前面。
- 删账户（长按 → 删除）时它名下的账单先转移到「未指定」，不留外键孤儿。
- 删账户时它名下的账单先转移到「未指定」，不留外键孤儿。
- 备份 JSON 从 v4 起带 `accounts` 和账单的 `accountId`；恢复时按原 id 重建，    
  账单指向一个已经不存在的账户就兜底归「未指定」。老备份没有账户段 → 沿用本机现有账户。
- CSV 多一列「账户」。导入按名字匹配本机账户，认不出来归「未指定」（不会顺手造账户）。

## 检查更新

**App 平时完全不联网**：没有开屏检查、没有红点、没有后台请求。  
唯一入口是「关于」里的版本号行 —— 点它才会去拉一次远程 JSON：

- 远程只放一个小 JSON（本仓库 `update/update.json`），格式：`{ "versionName": "1.1", "url": "蓝奏云链接", "note": "更新说明" }`
- 地址在 `util/UpdateChecker.kt` 的 `UPDATE_JSON_URL` 常量，指向本仓库文件的 jsDelivr CDN 地址（国内可直连）。**还是 example.com 占位时直接返回「还没配置」**，不会请求假地址。
- 有新版 → 直接跳浏览器打开 JSON 里的 url 下载页；已是最新 / 连不上 / 没配置 → toast 说明。
- 下载链接放在 JSON 里而不是写死在代码里：发新版只改那个文件，用户不用装新包就能看到新链接。

## 发布新版本

1. `app/build.gradle.kts` 里 `versionCode` +1、`versionName` 改新版本；
2. APK 传蓝奏云，把下载页链接和更新说明写进 `update/update.json`（`versionName` 与第 1 步一致）；
3. `assembleRelease` 出包，把 APK 和 `update/update.json` 一起 push 到本仓库；
4. 用户下次打开 App 就会看到红点。jsDelivr 对分支文件有缓存，推完若没生效，去 jsdelivr.com 的 purge 工具刷一下该 URL。

出 release 包需要根目录 `keystore.properties`（已 gitignore，仓库里没有）：

```properties
storeFile=moneynote-release.jks
storePassword=……
keyAlias=……
keyPassword=……
```

`moneynote-release.jks` 同样不进仓库，本机另留一份离线备份 —— 丢了就没法给老用户发升级包。

## 已知不足

- 备份是全量覆盖，没有版本历史。
- 统计的趋势柱状图是按年/季/月分桶的，不支持自定义区间。
- OCR 没做源图预览 —— 识别完用户看不到自己选的是哪张图。
