# OnlyMoneyNote（记账本）

安卓记账 App。Kotlin + Jetpack Compose + Room + Hilt，原生开发，不引第三方 UI 库。

界面按**支付宝的设计语言**整体改版：#1677FF 主色、灰底白卡片、彩色分类图标块、固定底栏。

## 功能

- **记账**：底部弹出表单，填金额、日期、一级分类、二级分类、备注、长按修改
- **自定义分类**：一级、二级加删改。**长按分类行**改名字和图标，每行有 `+` 加二级、上下箭头排序、垃圾桶删除
- **统计数据**：按月 / 按季 / 按年三种口径，可前后翻页。含收支汇总、结余、趋势柱状图、分类占比排行
- **截图记账**：从微信/支付宝/相册拿到支付截图 → 分享到本 App 或从相册选图 → 本地 OCR 自动认出金额和时间 → 预填表单 → 核对后保存
- **WebDAV 备份**：填服务器地址 + 账号密码，一键上传/恢复，备份 JSON
- **资金账户**：记一笔时选这笔钱从哪个账户走（支付宝 / 微信 / 银行卡…可自己加）。首页「账户」看总资产和每个账户的余额，**长按**改名、图标、期初金额或删除
- **转账**：记一笔的「转账」页签，选转出 / 转入两个账户。**不影响总资产**，只让两个账户各自的余额变准（银行卡转 500 到支付宝这种以前根本记不下来）
- **批量整理**：账单页头「整理」进入多选，配合「筛选」按分类和日期缩小范围后全选，「设为账户」一次把上千条老账单归到指定账户

## 界面结构

```
┌──────────────────────────────────┐
│ ▓▓ 蓝色渐变页头（白字大数字）▓▓    │  ← 账单/统计/报销/我的 每个Tab都有
│ 记吧                           │
│ 支出 ¥140.50   收入 ¥0.00       │  ← 点支出/收入可筛选
├──────────────────────────────────┤
│  白色圆角面板                    │
│  10月2日 周五     +¥0.00 -¥16.54│  ← 按天分组，灰色日期行
│  [彩色图标块] 分类·子类   -¥5.67  │
│                     (左滑删除)  │
├──────────────────────────────────┤
│ ，蓝色＋记一笔
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

## 总资产为什么可能对不上

```
总资产 = Σ(所有账户期初) + 总收入 - 总支出
```

**总资产只取决于「期初总额」，跟每笔账单挂在哪个账户下无关** —— 各账户净流水加起来时账户分配就被消掉了。

App 是后期才开始用的，**开始之前的钱从来没被记录过**，期初全是 0，显示的就是「这段时间的净现金流」而不是「现在有多少钱」。

修法：用账户编辑里的「**此刻余额**」（不是「期初金额」），填各账户**真实的当前余额**，App 会反算期初（`期初 = 此刻余额 - 净流水`），之后记账就都在这个基础上正确增减。

## 资金账户

`account` 表存账户本身（名字、图标 key、配色、排序、`initialCents` 期初金额、`builtIn`）。

**余额不单独存**：当前余额 = 期初金额 + 该账户的净流水，由 `TransactionDao.observeAccountNet()` 现算。所以删账单、改账单、改账户都会自动重算，不需要任何补偿逻辑，也不会出现余额和流水对不上的情况。

净流水的口径（**转账要单独算，这是最容易错的地方**）：

| 账单类型 | 对转出方账户 | 对转入方账户 | 对总资产 |
| ---- | ------ | ------ | ---- |
| 收入   | +      | —      | +    |
| 支出   | −      | —      | −    |
| 转账   | −      | +      | 0    |

所以转账**不能**跟收支混在同一个 `GROUP BY accountId` 里 —— 那样转出和转入会互相抵消掉，单账户余额就错了。`observeAccountNet()` 用 `account` 表当驱动表做四个 LEFT JOIN（收支 / 转入 / 转出 / 笔数）才能算对。

- `TxType` 三个值：`EXPENSE(0)` / `INCOME(1)` / `TRANSFER(2)`。转账不写分类（`categoryId = 0`），不参与收支统计、不进报销、不影响趋势柱状图。
- 内置一个不可删的「未指定」账户，`id` 固定 = 1（`AccountEntity.UNSPECIFIED_ID`）：

  v4→v5 迁移插的就是这一条，全新安装的种子也按这个 id 插，两条路径 id 对得上。

  迁移时顺带把 6 个建议账户（支付宝 / 微信 / 微信分身 / 工行 / 农行 / 建行）一起插进去。
- 账户图标统一成「**品牌色块 + 单字**」（微 / 支 / 工 / 招 / 建…）。早期版本微信用的是 Canvas 手绘近似标，  
  看着不像微信，已在 v5→v6 迁移里按 `iconKey = 'brand:wechat'` 精确替换掉；  
  函数本身保留，老备份里存着这个 key 的账户还能正常显示。
- 记一笔里的账户胶囊按**最近 20 笔里各账户出现次数**排序，常用的自动靠前。
- 删账户（长按 → 删除）时它名下的账单先转移到「未指定」，不留外键孤儿。
- 备份 JSON 从 v4 起带 `accounts` 和账单的 `accountId`；v5 起加 `toAccountId`（转账转入方），  
  恢复时按原 id 重建，指向已不存在账户的（含转入方）兜底归「未指定」。老备份没有账户段 → 沿用本机现有账户。
- CSV 多一列「账户」。转账写成「甲 → 乙」，收支列写「转账」而不是「支出」；  
  导入按名字匹配本机账户，认不出来归「未指定」（不会顺手造账户）。

## 批量整理历史账单

迁移前的老账单全部落在「未指定」，一条条长按改不现实（用户有 8000 条）。

账单页头「**整理**」进入多选模式：

- 多选时**禁用左滑删除**（滑开露出的删除键和勾选手势冲突，容易误删），右下角「＋」收起来。
- 「全选」只作用于**当前筛选后**的列表 —— 不然会把用户看不见的账单也勾上、被动改了账户。
- 「设为账户」走一条 `UPDATE ... WHERE id IN (...)`，只改 `accountId` 一列，金额、分类、报销标记都不动。
- 配合「筛选」（分类 + 日期区间）可以把 8000 条切成几片处理。

## 检查更新

**App 平时完全不联网**：没有开屏检查、没有红点、没有后台请求。

唯一入口是「关于」里的版本号行 —— 点它才会去拉一次远程 JSON：

- 远程只放一个小 JSON（本仓库 `update/update.json`），格式：`{ "versionName": "1.2.0", "url": "蓝奏云链接", "note": "更新说明" }`
  - ⚠️ **`versionName` 不要带前导零**（别写 `1.03`）。它跟 `versionCode` 是两套独立编号：
    `versionCode` 只管递增（决定能不能覆盖安装），`versionName` 才是更新链路比大小的依据，
    按点分整数逐段比。写 `1.03` 会被当成 `1.3`，比 `1.2` 大，反而误报升级。
- 地址在 `util/UpdateChecker.kt` 的 `UPDATE_JSON_URL` 常量，指向本仓库文件的 jsDelivr CDN 地址（国内可直连）。**还是 example.com 占位时直接返回「还没配置」**，不会请求假地址。
- 有新版 → 直接跳浏览器打开 JSON 里的 url 下载页；已是最新 / 连不上 / 没配置 → toast 说明。
- 下载链接放在 JSON 里而不是写死在代码里：发新版只改那个文件，用户不用装新包就能看到新链接。

## 发布新版本

1. `app/build.gradle.kts` 里 `versionCode` +1、`versionName` 改新版本（两者不必相等，见上面「检查更新」那节的说明）；
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
