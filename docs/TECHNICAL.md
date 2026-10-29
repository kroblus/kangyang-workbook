# V1.1 工程与环境

唯一工程为 `D:\Desktop\Codex\Workbook`，审计基线是 `d5d671022d2c0ec6001d30cc882d60f67640923a`。本轮开始时分支与该基线一致，工作区没有未提交修改。继续原 Kotlin / Compose / Room 工程，不换包名、不复制新应用、不以网页代替安装包。

## 构建环境

- 构建 JDK 路径：`C:\Program Files\Android\Android Studio1\jbr`，Java/Kotlin目标17。
- Android SDK：`C:\Users\16562\AppData\Local\Android\Sdk`，compileSdk/targetSdk36，minSdk26。
- 工程锁定 AGP8.13.2、Gradle8.13、Kotlin2.3.21、KSP2.3.4、Compose BOM2026.04.01、Room2.8.2、DataStore1.2.0、Coroutines1.11.0、serialization1.11.0。
- V1.1 不为视觉改版升级技术栈或增添新的 UI/数据库依赖。实际精确版本以构建文件为准。

个人包 `cn.kangyang.workbook`，验收包 `cn.kangyang.workbook.qa`，版本名1.1.0。开发验证包与最终交付包按递增 versionCode 区分；最终版本号、安装签名、SHA-256 与源码提交对应关系集中记录在 [ACCEPTANCE](ACCEPTANCE.md)，不能用中间构建证据替代最终包验证。

目前继续使用本机 Android debug 测试签名，不是应用商店发布签名。覆盖升级必须沿用相容签名与包名，版本号高于已安装版本；不得通过卸载/清数据绕过签名或升级问题。换机器重新生成的默认 debug keystore 通常不同，不能直接用于覆盖本次安装。

## 交互与组件

`NoteEditorScreen` 独立承担沟通记录，通用 `EntryEditor` 只处理其他档案/活动/跟进。正文使用可保留选择范围的 TextFieldValue，无必填标题或对象；初次进入编辑聚焦，普通提交不重置光标。阅读页不自动开键盘。

工作台“记一笔”位于日期下方，今日/逾期只显示少量重要事项，最近记录紧随其后。单位/客户详情优先沟通和跟进，联系人、服务对象和其他资料分组保留。活动表单先名称与方案，时间地点和执行复盘渐进展开。设置拆分备份/恢复，导出使用可读预览。

主题沿用 V1 已核对的养老项目 `admin-web/src/evergreen/tokens.ts` 绿色来源；本轮以现有 `Theme.kt` 和用户给定规范为基准，不另建亮绿配色：Brand #0F514F、Green #176C65、Mint #E7F5EE、Canvas #F7FBF8、Ink #213A36、Muted #526761。

Theme 显式覆盖 surfaceContainerLowest/Low/Container/High/Highest、surfaceBright/Dim、onSurfaceVariant、outline、inverse、error、scrim 等角色。按钮禁用色由共享组件定义；弹窗、面板、日期/时刻选择器使用同一绿色 ColorScheme。Android 系统照片/文件选择器仍由系统管理，不把系统界面当作应用自有面板改色。

`AppShapes` 集中12dp控件、16dp卡片、24dp面板/弹窗与8dp状态标记。PrimaryAction/SecondaryAction最小52dp，图标动作最小48dp，输入最小56dp；高度允许随文字增大。正文17sp/27–28sp行高，次级文字14sp，页面左右通常16–20dp。`Components.kt` 提供统一动作、输入、筛选、对象搜索及日期/时刻面板。口令框仅因需要遮罩采用专用包装，仍使用相同形状和最小尺寸。

根 Scaffold 应用并消费系统 padding，非根 BackTitle 设置零窗口 inset；编辑区域处理 IME，底部表单操作不再重复加导航栏 padding。日期选择器在窄屏/大字号使用 Material 输入模式。Token统一和静态检查不代表布局已验收，实际宽度、字号、键盘和横屏证据见验收报告。

## 数据变更

Room schema2 通过增量迁移增加连续笔记会话和草稿恢复元数据。正文350ms防抖、持续输入1500ms调度，版本号与数据代次共同约束提交；返回/完成等待最新落盘。TASK 是跟进事实来源，正文不重复生成任务。

旧草稿在启动、V1备份导入和回滚路径复用幂等转换；冲突/非法内容保留供恢复。新备份数据语义版本2仍使用原加密封装并可读取合法V1包。恢复由应用层协调任务生命周期，底层推进代次并保留附件校验、事务替换和回滚。详见 [DATA](DATA.md) 与 [CONTRACT](CONTRACT.md)。

没有 INTERNET、通讯录、通话、全盘存储、定位或麦克风权限。通知只在主动启用时请求；分享使用 FileProvider 临时授权；保存使用 SAF。标准系统云备份和设备迁移配置排除应用资料，换机使用完整口令备份；厂商自有迁移工具不在本项目控制范围。

## 设备与验收约束

本轮初查 `adb devices -l` 确认已有 `emulator-5554`，`emulator -list-avds` 确认 `ShengliFlowApi36`。这是既有 Android16/API36模拟器，所有 adb 命令必须明确 `-s emulator-5554`。不得创建替代AVD、wipe、卸载或清除原资料来证明升级成功。

恢复/回滚、故障注入、强停等风险验证在既有 `.qa` 空间和虚构资料上执行。个人交付包另做相同签名的实际覆盖安装、启动、录入、重开查找和原数据保留验证。布局配置测后恢复原分辨率、密度、字体及旋转设置。朋友真实手机未运行的项目应保留 NOT_RUN，不将模拟器结果扩展成所有安卓版本或真实使用者认可。

PowerShell 示例（是复现方式，执行状态以验收报告为准）：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
$env:ANDROID_SERIAL='emulator-5554'
.\gradlew.bat :app:assembleQaDebug :app:assembleQaDebugAndroidTest :app:assemblePersonalDebug :app:testQaDebugUnitTest :app:lintPersonalDebug --console=plain
& 'C:\Users\16562\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5554 install -r <对应APK>
& 'C:\Users\16562\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5554 shell am instrument -w -e class <明确测试类或方法> cn.kangyang.workbook.qa.test/androidx.test.runner.AndroidJUnitRunner
```

存在首次空库、升级前置数据、进程中断、个人包冒烟及特定字号测试，不能把整个 instrumentation 目录无差别运行。原始日志和个人/QA资料备份保留本机，仓库只纳入无敏感源码、文档和精选证据。
