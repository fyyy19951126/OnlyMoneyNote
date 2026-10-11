# 更新说明目录

App 里「关于 → 版本号」检查更新时读的就是这个目录下的 `update.json`：

- `versionName`：线上最新版本号，比手机装的大才会提示更新
- `url`：点「点我更新」后用浏览器打开的下载地址
- `note`：这一版更新了什么

## 下载地址

https://wwazc.lanzoum.com/b0fqq8nze

**访问密码：41dv**（蓝奏云这个目录设了密码，进去先输密码再点 APK）

## 发新版本的步骤

1. 改 `app/build.gradle.kts` 里的 `versionCode`（必须比上一版大）和 `versionName`
2. 打 release 包，把 `dist/Only记账-x.y.z.apk` 上传到上面那个蓝奏云目录
3. 改这个目录里的 `update.json`：填新的 `versionName`，`url` 一般不用动
4. 推到 GitHub，等 CDN 生效后用手机点版本号验证一次
