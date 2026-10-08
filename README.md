# Discuz App Android

独立的 Kotlin / Android WebView 容器。最低 Android 8.0（API 26），编译及目标 API 35。

完整原生功能还要求设备的 WebView 提供 `WEB_MESSAGE_LISTENER`。Android 系统版本达标不代表浏览器内核达标；使用旧版 Android 的设备应更新 Android System WebView／Chrome。

此仓库包含 Android 源码、配置示例、JVM 测试和模拟器测试。业务网页、PHP API 和论坛桥接插件由站点维护者另行部署，不包含在本仓库中。APK 运行时加载配置站点的 `/app/` 页面。

## 构建调试版

安装 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。设置 `JAVA_HOME`、`ANDROID_HOME`；也可以用 Android Studio 打开仓库。

```bash
cp site.properties.example site.properties
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows 使用 `gradlew.bat`。APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。调试版包名追加 `.debug`；站点地址留空时可在首次启动输入，支持局域网 HTTP 和 HTTPS。手机访问开发电脑时请填写电脑的局域网地址，而非手机自身的 localhost。

`siteUrl` 为论坛根地址；主备站点共最多 8 个，`siteUrls` 在同一行用英文逗号分隔。例如：

```properties
siteUrl=https://forum.example.com/
siteUrls=https://backup.example.org/discuz/
appName=社区
applicationId=com.example.community
versionCode=18
versionName=1.12.1
```

这只是格式示例。版本序号应大于该包名已经发布的 APK；包名和签名在后续覆盖升级时保持一致。仓库默认值以 `app/build.gradle.kts` 为准。

## 构建正式版

正式版要求所有配置站点使用 HTTPS，并提供自己的签名。真实配置必须保存在本地或仓库外：

```bash
export DISCUZ_SITE_PROPERTIES=/private/site.properties
export DISCUZ_SIGNING_PROPERTIES=/private/signing.properties
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

站点配置格式参见 `production.properties.example`，签名配置格式参见 `signing.properties.example`。输出为 `app/build/outputs/apk/release/app-release.apk`。使用 Android SDK 的 `apksigner verify` 检查最终 APK。

配置优先级为 Gradle `-P` 参数、站点配置、签名配置、仓库默认值。`DISCUZ_SITE_PROPERTIES` 未指定时只读取 `site.properties`，不会自动读取 `production.properties`。构建正式版缺少签名或 HTTPS 站点时会失败。

只提交 `.properties.example` 示例文件；真实站点配置、签名和密钥不得提交。工作流使用默认调试配置，不读取生产凭据、不签发生产 APK。

## 容器功能

- 同源、主框架和 App 路径限定的 AndroidX WebKit 消息桥。
- 系统返回、文件／相册选择、相机、附件保存、图片缓存、扫码、复制与分享。
- 站点目录与连接检测，不复制跨域 Cookie、不自动重放业务写入。
- 原生安全区和键盘处理；Android 系统主题状态直接通知网页，支持系统／日间／夜间模式。
- HTTPS 证书校验、站外链接交给浏览器，图片直链下载不向外站发送论坛 Cookie。

业务页面需实现对应桥接约定。主题通知为 `discuz:system-theme`，事件 `detail.dark` 是布尔值；前台恢复为 `discuz:resume`。外观设置通过 `appearance` 桥接操作传递 `mode`（`system`、`light`、`dark`）和 `theme`（`light`、`dark`）。系统模式由原生 `Configuration.uiMode` 决定。

## 自动测试

GitHub Actions 使用标准 Ubuntu 运行器：

- 编译、lint 和现有 31 项 JVM 单元测试。
- Android 8、12、13、15、16 模拟器上的原生容器测试。

设备测试使用设备内临时 HTTP 服务和专门编写的最小页面，检查启动、桥接版本信息、系统主题通知、手动模式优先，以及主题切换不重建页面或清空输入。测试页面不是业务前端，未包含站点 API 或论坛插件实现。

```bash
# 先连接模拟器或测试设备，再执行：
./gradlew :app:connectedDebugAndroidTest
```

模拟器结果不能替代厂商真机、实际相册／相机、支付应用和真实论坛业务联调。Actions 的测试报告短期保留；不会自动上传生产配置或发布正式 APK。

### 首轮 CI 结果（2026-10-08）

[首次运行](https://github.com/drch90/discuz-app-android/actions/runs/37731292827)验证提交 `eebf924`：编译、lint、31 项 JVM 测试通过；Android 12、13、15、16 各 3 项设备测试通过，无跳过。

Android 8（API 26）镜像内置 `com.android.chrome 69.0.3497.100`，不支持安全消息桥，测试页面出现 `DiscuzNative is not defined`，3 项设备测试均失败。因此本轮工作流整体为失败，不能称为全部版本兼容通过。已保留 API 26 测试及失败结果；后续需要为该镜像配置支持消息桥的 WebView 再验证。目前没有验证 Android 8 搭配更新内核后的结果，也没有为旧内核添加权限较宽的桥接回退。
