# 工程与环境

唯一工程：`D:\Desktop\Codex\Workbook`。原养老小程序、Reader、Voice Memos 均只读，未改业务代码。未创建多个工程副本。原项目只提供工具版本、Gradle wrapper 和颜色参考。

## 实际环境

- Android Studio：`C:\Program Files\Android\Android Studio1`，build AI-261.23567.138.2611.15646644。
- 构建 JDK：Studio `jbr`，OpenJDK21.0.10；Java/Kotlin目标17。系统 PATH 另有 Oracle17.0.12。
- SDK：`C:\Users\16562\AppData\Local\Android\Sdk`，现有 android-36、build-tools35.0.0/36.0.0。
- ADB：37.0.0-14910828。
- AGP8.13.2 / Gradle8.13 / Kotlin2.3.21 / KSP2.3.4，Compose BOM2026.04.01、Material3、Room2.8.2、DataStore1.2.0、Coroutines1.11.0、serialization1.11.0。精确版本锁在构建文件中。
- compileSdk/targetSdk36，复用现有 SDK。minSdk26（Android8.0），使用原生 java.time 和应用依赖兼容范围；未假设朋友手机版本，也未验证所有 Android 版本。
- API兼容依据：[AGP8.13官方兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes)支持API36.1、Gradle8.13、JDK17。

初次 KAPT 依赖请求遇 Maven Central TLS handshake 失败；切换到本机已有的 KSP2.3.4 后实际构建成功。没有降低 TLS 校验或修改全局网络配置。

## 绿色来源

只读核对的实际文件，根为 `C:\Users\16562\WeChatProjects\wuhan-eldercare-match`：

- `docs/phases/admin-visual-refinement-v2-20261028/SUMMARY.md`：记录旧橙色后台，不作为本轮主色。
- `admin-web/src/evergreen/tokens.ts`：实际绿色 #0F514F / #176C65 / #34A48A / #E7F5EE / #F7FBF8。
- `admin-web/src/main.tsx`：当前入口实际导入上述 tokens 并写入 CSS变量。
- `admin-web/src/theme.css`、`admin-web/src/evergreen/tokens.css`、`admin-web/src/evergreen/mobile.css`：白卡、浅绿画布、清晰排版、移动间距与按钮秩序。
- `miniprogram/styles/tokens.less`：仍混有旧暖色，不覆盖最新绿色要求。
- 已实际查看 `output/admin-evergreen-v3-20261028/gui/final-requests-a-375.jpg`。

Android 的颜色来源是实际绿色 token，非把候选色冒称旧版已落地。转译为16dp页面边距、14–18dp圆角、16sp正文、48dp以上触控；次级文字与边线选用户本轮指定 #526761 / #D9E8E0 提升对比。不启用系统动态取色。图标为原生 Material 线性图标及本轮矢量书本图标。

## 数据与安全

Room保存按Kind区分的类型化业务聚合、独立草稿、附件引用、草稿提交封存、恢复回滚快照。所有关联使用稳定ID，由保存事务与全量备份校验验证关系。详见 [CONTRACT](CONTRACT.md) 与 [DATA](DATA.md)。DataStore只保存可再生成的界面提示偏好；业务必要设置和资料纳入Room快照。

草稿通过ViewModel节流、版本号和持久化提交封存抵御并发迟到写入。正式数据不依赖内存页面状态。无破坏性迁移，Room schema v1导出在 app/schemas。当前没有schema升级，不宣称迁移实测。

提醒用非精确 AlarmManager，保存/改期/取消/重新启动后重建，通知锁屏不显示电话或照护细节。普通后台与用户强制停止是不同状态，详见 [REMINDERS](REMINDERS.md) 和 [官方非精确提醒说明](https://developer.android.com/develop/background-work/services/alarms)。Android12+非省电场景仍可能延后至一小时，不能承诺分钟级。

无INTERNET、通讯录、通话、全盘存储、定位或麦克风权限。通知仅在主动启用时请求。分享使用FileProvider临时授权，保存通过SAF。标准系统云备份/设备迁移排除所有本应用数据域；厂商工具可能另有行为，详见[官方系统备份说明](https://developer.android.com/identity/data/autobackup)。

包名个人 `cn.kangyang.workbook`，验收 `.qa`；版本1.0.0，最终versionCode3，覆盖更新基线versionCode1。当前使用本机Android debug测试签名；后续覆盖更新必须保存同一签名，不应卸载清数据来绕过冲突。

## 构建与设备

PowerShell 在工程根，命令均由本轮实际执行后写入验收记录。基础形式：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:assembleQaDebug :app:assembleQaDebugAndroidTest :app:testQaDebugUnitTest :app:lintQaDebug --console=plain
adb -s emulator-5554 install -r <本轮APK>
adb -s emulator-5554 shell am instrument -w -e class <明确测试类或方法> cn.kangyang.workbook.qa.test/androidx.test.runner.AndroidJUnitRunner
```

只选现有AVD ShengliFlowApi36，serial emulator-5554，Android16/API36 x86_64，基线1080×2400 / 420dpi = 411.43dp，fontScale1.0。初始无运行实例，已启动现有AVD；未创建、下载镜像、重置或清数据。未发现运行的第三方模拟器或真实手机连接。完整初始值在 output/verification/environment.json。
