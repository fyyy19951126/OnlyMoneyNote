# 更新说明目录

App 里「关于 → 版本号」检查更新时读的就是这个目录下的 `update.json`：

- `versionName`：线上最新版本号，比手机装的大才会提示更新
- `apkUrl`：**指向 .apk 文件本身的直链**，App 里「下载并安装」走的就是它（Gitee 发行版的附件地址）
- `url`：蓝奏云下载页。`apkUrl` 没配、下载失败、或者用户不想授权安装时，退回这个手动地址
- `note`：这一版更新了什么

## 两个下载地址

- 应用内直链（Gitee 发行版附件）：
  `https://gitee.com/fyyy19951126/only-money-note-apk/releases/download/v<版本号>/OnlyMoneyNote-<版本号>.apk`
  注意**附件文件名用 ASCII**，中文文件名会进 URL 编码，容易出 404
- 蓝奏云（手动兜底）：https://wwazc.lanzoum.com/b0fqq8nze
  **访问密码：41dv**（这个目录设了密码，进去先输密码再点 APK）

## 发新版本的步骤

1. 改 `app/build.gradle.kts` 里的 `versionCode`（必须比上一版大）和 `versionName`
2. 打 release 包，复制一份 ASCII 文件名的：`dist/OnlyMoneyNote-x.y.z.apk`
3. 传到 `only-money-note-apk` 仓库的**发行版**（Tag 用 `v<版本号>`），拿到
   `.../releases/download/v<版本号>/OnlyMoneyNote-x.y.z.apk` 这条地址，
   用 `curl -L -o /dev/null -w "%{http_code} %{size_download}"` 验一下匿名能下整包
4. 顺手把同一个包传到蓝奏云目录（手动兜底用）
5. 改这个目录里的 `update.json`：`versionName`、`apkUrl` 换成新的，`url` 不用动
6. **GitHub 和 Gitee 两个远端都要推** —— App 检查更新优先读 Gitee，
   Gitee 忘了推就等于没发版
7. 用旧版本点一次「版本号」验证：应该出现「发现新版本」+「下载并安装」按钮

第一次装这个包（1.2.0）的时候应用内更新还不存在，所以它只能手动装；从它往后的版本才走应用内更新。
