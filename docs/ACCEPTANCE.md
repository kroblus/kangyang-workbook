# 康养工作簿 V1.1 验收记录

2026-10-29 至 2026-10-30，Asia/Shanghai。工程根 `D:/Desktop/Codex/Workbook`。审计基线 `d5d671022d2c0ec6001d30cc882d60f67640923a`；开始时 main 与基线相同，工作区无未提交修改。没有覆盖用户修改或改动其他项目。

**V1.1 实现、构建、实际模拟器安装与 T01–T12 列明范围已完成。** 下文区分开发阶段验证和最终 APK 复验。朋友/姜宏真实手机及她对体验的认可仍为 **NOT_RUN**，不能由模拟器结果推断。源码设计和迁移决策见 [V1.1-DESIGN](V1.1-DESIGN.md)，V1历史结果单独保留在 [ACCEPTANCE-V1](ACCEPTANCE-V1.md)。

## 交付身份

| 用途 | applicationId / 构建 | 版本 | SHA-256 |
|---|---|---|---|
| [个人试用APK](../output/apk/kangyang-workbook-1.1.0-v5.apk) | `cn.kangyang.workbook / personalDebug` | `1.1.0 / 5` | `72A64D02BA40E784E795D15971309ECF831BB5F363A8F245EF12DD1014173447` |
| [QA独立空间](../output/apk/kangyang-workbook-qa-1.1.0-v5.apk) | `cn.kangyang.workbook.qa / qaDebug` | `1.1.0 / 5` | `C0DC3ACB553DBB51A64184208B441E4F41061763781358C03C64D0F32B5E9483` |

个人文件：`D:/Desktop/Codex/Workbook/output/apk/kangyang-workbook-1.1.0-v5.apk`。应用源码对应 commit **`18df88549a4c8ed231cf99ba594d6d7631412402`**；之后仅更新验收脚本等待、说明与证据。两包保持 V1 的 Android 测试证书，证书SHA-256 `f1a230a1f20d3041c808fabd8c150985949e6d830ce53b96cab00aa5e1efa5a5`。这是测试签名，不是应用商店正式签名。

[构建身份](../output/v1.1/final-build-identity.json) · [个人签名](../output/v1.1/final-personal-signature.txt) · [QA安装](../output/v1.1/final-qa-landscape-install.log) · [个人安装](../output/v1.1/final-personal-install.log)。

开发阶段 code4/r9 的QA哈希为 `bf187c2a2fd2dfce6cb1cbc711ec1e2a39c0341412f27a7d2f3a6542a8313ffe`。早期code5在横屏修复前的身份另留 [记录](../output/v1.1/code5-before-landscape-identity.json)。最终精选截图和录屏均对应表中最终QA哈希，没有用开发版文件替代最终交付操作。

## 设备与资料保护

使用本机已有 **ShengliFlowApi36 / emulator-5554 / Android16 API36 / x86_64**，没有新建AVD、清空模拟器或卸载应用。所有主机命令显式指定serial。初始及最终设置均为1080×2400、420dpi、fontScale1.0、rotation free。

恢复、回滚、故障注入和进程结束只在QA中执行。个人包只做同签名覆盖升级、正常打开、创建/查看与普通重开。个人升级前再次读取快照，业务文件与最初V1快照完全相同；升级后逐字段比较原2条正式资料、1份TASK草稿和2个已提交草稿标识，全部保留，Room1→2成功、SQLite integrity为ok。

[升级前快照](../output/v1.1/preflight/preflight.json) · [个人升级前再次核对](../output/v1.1/personal-v1-immediately-before-install.json) · [个人逐字段比对](../output/v1.1/personal-upgrade/verification.json) · [实际新建/查看](../output/v1.1/final-personal-smoke.log) · [正常重开](../output/v1.1/final-personal-reopen.log) · [最终显示设置](../output/v1.1/final-device-settings.json)。个人新建和重开分别8.125秒、4.527秒完整通过。

QA原有116条正式资料、10份草稿、2份附件，升级前均做双份稳定读取、数据库完整性和附件SHA核验。只在QA增加6种V1迁移草稿。使用**仍安装的原V1 APK真实界面与SAF**导出并先用V1往返验证，生成 `v1-ui-backup.kywb`：23498 bytes，SHA-256 `192baecbf6c08ffab82ff1a23ad51eba210d02450f1cb385d1b0c723c25e0453`。不是用V1.1新导出的文件冒充V1备份。

[原V1界面导出](../output/v1.1/preflight/v1-ui-backup-export.log) · [原V1往返](../output/v1.1/preflight/v1-ui-backup-validate.log)。原始数据库、备份和CSV仅留在本机忽略目录；GitHub中的 `output/` 证据链接需在该工作区打开。

## T01–T12 实际结果

| 用例 | 状态及已执行范围 | 主要证据 |
|---|---|---|
| T01 首屏与输入 | **PASS，最终APK**。先截未滚动首页，再实际点击一次“记一笔”；正文聚焦且真实键盘出现，不先填标题/类型/日期/对象。 | [最终日志](../output/v1.1/oneTouchChineseNoteAndEmptySession-final-r3.log)、[首页](screenshots-v1.1/home.png)、[正文键盘](screenshots-v1.1/editor-keyboard.png) |
| T02 一句话找回 | **PASS，最终APK**。一句中文真实提交后，首页最近记录与搜索找回同ID；空会话不生成卡片/TASK。完整录像再次走同一路径。 | [最终日志](../output/v1.1/oneTouchChineseNoteAndEmptySession-final-r3.log)、[录屏流程日志](../output/v1.1/finalDeliveryWalkthroughAndEvidence-final-r4.log) |
| T03 返回与进程恢复 | **PASS**。立即返回等待提交；最终APK真实普通后台结束进程PID1586→空，重开正文/ID/照片SHA/原时间一致并实际旋转。最终包慢写入旧版本不覆盖最新内容，真实保存失败显示重试。开发版另在未提交阶段外部强停PID27830→空，重开只恢复最后已提交版本。 | [最终进程证据](../output/v1.1/final-normal-process.json)、[最终重开](../output/v1.1/verifyAfterRealProcessDeathAndRotation-final-r3.log)、[最终慢写/失败](../output/v1.1/visibleSaveFailureRetryAndSlowRevisionState-final-r3.log)、[立即返回](../output/v1.1/immediateBackAndExistingBodyCanBeCleared-r8.log)、[未提交强停边界](../output/v1.1/pending-write-process-r9.json) |
| T04 后补整理 | **PASS，最终APK**。正文后补单位和昨天，ID/createdAt/正文保留，发生时间与导出一致，编辑时间不替换发生时间。 | [最终追加整理](../output/v1.1/appendObjectYesterdayAndIndependentFollowup-final-r3.log)、[最终时间/导出](../output/v1.1/occurrenceTimeAndAutomaticMidnightLifecycleRefresh-final-r3.log) |
| T05 空白与附件 | **PASS**。最终包实际照片选择，空笔记慢导入时返回会等待，成功后正式附件-only可从首页/文件名搜索找回并完整备份恢复；取消与导入失败无空卡，等待失败留在编辑器显示反馈。清空已有正文保留同ID、旧关键词不再命中；异常PDF的笔记/活动反馈已实际验证。 | [最终返回/附件/备份](../output/v1.1/backWaitsForEmptyNoteAttachmentAndPublishesOnlySuccessfulImport-final-r3.log)、[清空正文](../output/v1.1/immediateBackAndExistingBodyCanBeCleared-r8.log)、[异常文件](../output/v1.1/malformedAttachmentsKeepNoteAndActivityStateHonest-r9.log)、[最终数据层](../output/v1.1/repository-regression-final.log) |
| T06 跟进一致性 | **PASS，最终APK**。主动加明天跟进，独立改期/关提醒后再改正文和跟进文字，日期/提醒保留；完成→重开→完成保留备注，改已完成文字不重开。旧通用TASK草稿不回填过时日期，旧字段只读保留并可复制。 | [最终独立跟进](../output/v1.1/appendObjectYesterdayAndIndependentFollowup-final-r3.log)、[最终旧TASK草稿](../output/v1.1/staleGenericTaskDraftCannotUndoIndependentScheduleChanges-final-r3.log) |
| T07 时间与刷新 | **PASS，范围明确**。最终包实际选择09:30，timestamp/导出一致；秒输入按统一分钟规则拒绝或在旧草稿中明确修正。最终包受控业务时钟自动跨北京时间午夜、真实HOME/最近任务返回刷新。另在开发版用真实系统时间前台静置跨截止点自动变逾期。 | [最终时间/午夜/前台](../output/v1.1/occurrenceTimeAndAutomaticMidnightLifecycleRefresh-final-r3.log)、[真实分钟静置](../output/v1.1/realMinuteDeadlineRefreshesWithoutInteraction-r8.log)、[旧非法输入修正](../output/v1.1/legacyInvalidFieldsAndConflictAreExplicitlyRecoverableThroughUi-r9.log) |
| T08 待定活动 | **PASS，最终APK**。方案/照片/独立筹备事项在筹备中、时间待定时正式保存且无活动提醒；09:30起/09:00止拒绝且资料保留，10:30止通过；完成活动无主要取消操作。数据层也验证取消待定活动仍可备份。 | [最终实际表单](../output/v1.1/pendingActivityThroughRealForm-final-r3.log)、[最终数据层](../output/v1.1/repository-regression-final.log)、[待定活动](screenshots-v1.1/pending-activity.png) |
| T09 旧草稿/备份升级 | **PASS**。QA真实V1/code3→code4完成Room1→2，两次重开覆盖旧新建/编辑/附件-only/非法时间/普通客户/冲突草稿，ID/附件保留、无重复迁移或隐式TASK；UI明确修正或采用冲突版本，两侧内容仍可恢复。最终个人code3→5逐字段保留；最终QA实际导入原V1包兼容转换。 | [首次QA升级](../output/v1.1/t09-upgrade-first.log)、[再次重开](../output/v1.1/t09-upgrade-second.log)、[冲突/修正UI](../output/v1.1/legacyInvalidFieldsAndConflictAreExplicitlyRecoverableThroughUi-r9.log)、[最终个人升级](../output/v1.1/personal-upgrade/verification.json)、[最终V1恢复](../output/v1.1/v1RestoreAndRollbackThroughUiIsolatesExitedEditors-final-r3.log) |
| T10 恢复隔离 | **PASS，最终APK**。真实SAF选择V1包并确认；退出的客户A草稿在恢复后HOME/onStop/回来不回流；回滚同样隔离。慢正文与真实照片导入的旧回调不能写回新资料，附件SHA通过。 | [最终UI恢复/回滚](../output/v1.1/v1RestoreAndRollbackThroughUiIsolatesExitedEditors-final-r3.log)、[最终慢回调](../output/v1.1/restoreWaitsForSlowSaveAndAttachmentCallbacks-final-r3.log) |
| T11 布局与触控 | **PASS，最终APK8/8**。320/360/430dp×字体1.0/1.5；实际横屏800×360dp×字体1.0/1.5。长名称、长文、正文键盘、日期/时间面板确认与备份页均操作。测试后恢复原设置。 | [八档结果](../output/v1.1/t11-final-r3-results.json)、[恢复设置](../output/v1.1/t11-final-r3-restored-settings.json)、[精选截图](screenshots-v1.1/README.md) |
| T12 原业务与安全 | **PASS，列明范围**。真实表单单位/客户/联系人/服务对象、归档/查询、系统照片/文件、中文CSV实际SAF及逐字节比较；真实权限拒绝后独立TASK仍保存。最终包14项真Room安全/附件/备份错口令与损坏/提醒调度回归通过；最终个人覆盖更新保留资料。不是所有旧UI测试无差别重跑。 | [业务关系/CSV](../output/v1.1/dossiersRolesArchiveAndChineseCsvThroughUi-r8.log)、[CSV校验](../output/v1.1/csv-actual-byte-verification-r8.json)、[实际拒绝权限](../output/v1.1/deniedReminderFromBlankEditorCreatesOnlyIndependentTask-r9.log)、[最终14项](../output/v1.1/repository-regression-final.log)、[个人实装](../output/v1.1/final-personal-smoke.log) |

## 布局、截图与录像

三组竖屏真实像素分别640×1422、720×1600、860×1910，均320dpi，对应320×711、360×800、430×955dp；每组字体1.0/1.5。横屏实际旋转为1600×720、800×360dp，分别字体1.0/1.5。测试先断言Android实际资源宽度与字体，不能把未成功旋转算通过。

人工检查最终代表画面：长中文自然换行，无横向溢出；长页及小高度面板可纵向滚动；横屏大字号+键盘约一行正文可见，顶部保存状态、附件、关联、跟进、更多、完成均保留。系统输入法/照片选择器保留系统外观。

[未滚动首页](screenshots-v1.1/home.png) · [一键正文与键盘](screenshots-v1.1/editor-keyboard.png) · [保存后最近记录](screenshots-v1.1/recent-note.png) · [关联面板](screenshots-v1.1/association.png) · [明天跟进](screenshots-v1.1/tomorrow-followup.png) · [正文详情](screenshots-v1.1/detail.png) · [待定活动](screenshots-v1.1/pending-activity.png) · [备份主题](screenshots-v1.1/backup.png)。完整映射见 [截图说明](screenshots-v1.1/README.md)。

[原始操作录像](../output/v1.1/workbook-v1.1-flow.mp4)：**41.57秒、720×1600、4,425,068 bytes**，SHA-256 `bd1239ea070629358726607b02ea8ba4c673781383c413b314ed0fea4291ef21`。真实流程是“首页→记一句→返回找到→正文详情→编辑关联单位→主动加明天跟进”。对应1080×2400/420dpi/fontScale1.0的最终QA APK，应用内直接计算的已安装APK哈希与交付QA文件一致。

[同次录像流程PASS](../output/v1.1/finalDeliveryWalkthroughAndEvidence-final-r4.log) · [应用内身份/ID](../output/v1.1/final-device/final-walkthrough-identity.json) · [MP4结构/尺寸/时长/哈希](../output/v1.1/video-verification.json)。录屏由 `adb -s emulator-5554 shell screenrecord --size 720x1600 --bit-rate 2500000 --time-limit 120` 产生，流程结束后正常SIGINT收尾，未剪辑或重绘。独立全帧解码检查未执行成功：本机OpenCV/NumPy不兼容，附带FFmpeg仅有image2pipe/WebM解封装；不将容器校验称作逐帧回放验收。

## 工程检查、修复与真实边界

- 最终个人/QA/两种测试APK完整构建成功；**20项JVM**通过。最终设备 RepositoryV11Test10项、RepositorySafetyTest3项、ReminderIntegrationTest1项，**14项全部通过，77.862秒**。lint为**0 error、24 warning、1 hint**，未禁用检查或添加掩盖错误的baseline。[最终完整构建](../output/v1.1/build-final-landscape.log)、[最终数据层](../output/v1.1/repository-regression-final.log)。
- 最终真实UI专项复验：附件返回58.287秒；旧TASK草稿27.953秒；恢复/回滚34.709秒；慢回调33.383秒；失败/慢版本9.081秒；一键记录14.784秒；后补/跟进52.429秒；活动54.985秒；时间31.079秒；最终普通进程重开12.504秒。全部日志为完整 `OK (1 test)`，不是测试结束前截图。
- 保存故障/慢写/慢附件使用可控注入，但走真实生产提交路径、Room事务和UI反馈。普通后台结束进程使用真实 `am kill`；未提交边界使用外部强停，其准备阶段预期 `Process crashed` 不算PASS，只有随后验证算通过。
- 午夜检查用可控业务时钟自动计时，未改设备系统时间。真实系统分钟截止另在开发版观测1793284140000→1793284140035（35ms），这是该次UI状态刷新，不是通知实际准点送达保证。
- 中文CSV为52460 bytes，SHA-256 `02ce6d3351ae338afe42d503df7fddf67d7ae8b03acc2ad9af813dd3eec270d4`，系统实际保存文件与冻结预览逐字节一致；仅对应那次导出范围。
- 首轮构建在lint阶段主机JVM native memory分配失败，改用单worker/1536MB堆/临时daemon后完成；随后修正Compose中StateFlow.value的直接读取，改用订阅快照。所有失败日志保留。
- 自动化曾修正弹窗窗口/屏幕坐标、固定按钮错误滚动、ActivityScenario手动启动退出异常、异步编辑入口/键盘等待及UIAutomator初始化重置旋转。真实横屏测试还发现底部操作被键盘挤出，产品改为按剩余高度显示顶部紧凑栏，重新构建并完整跑八档；没有仅放宽断言。
- 封版复查修复慢附件返回时过早关闭owner、旧TASK草稿回填过时日期两处边界，并以最终包实际UI及数据层定向验证。
- 最终应用UID10216/10220从本次最终测试起点读取crash buffer均为空，系统报告本次启动后无ANR。[QA crash](../output/v1.1/final-crash-10216.log)、[个人crash](../output/v1.1/final-crash-10220.log)、[lastanr](../output/v1.1/final-lastanr.txt)。

## 复验命令与前置条件

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:assembleQaDebug :app:assembleQaDebugAndroidTest :app:assemblePersonalDebug :app:assemblePersonalDebugAndroidTest :app:testQaDebugUnitTest :app:lintPersonalDebug --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' --console=plain
adb -s emulator-5554 install -r output\apk\kangyang-workbook-qa-1.1.0-v5.apk
adb -s emulator-5554 install -r app\build\outputs\apk\androidTest\qa\debug\app-qa-debug-androidTest.apk
.\scripts\v11_run_cases.ps1 -Methods oneTouchChineseNoteAndEmptySession -Run final-r3
.\scripts\verify-v11-layouts.ps1 -RunLabel final-r3
```

V1前置脚本 `v11_preflight.py` / `v11_seed_legacy.py` 在已有证据或版本已升级时拒绝重跑。T09旧fixture被明确修正/采用后，不能再套初始未消费断言。通知拒绝用例须先由主机只撤销QA权限，再传 `permissionDeniedPrepared=true`，结束恢复原权限。待提交强停只能在本次ready标记出现后针对QA进行。个人对比脚本 `v11_verify_personal_upgrade.py` 只读原V1和升级后资料；不停止/恢复/修改个人库。部分场景有严格顺序，不能无差别运行整个instrumentation目录。

## 剩余限制

- 姜宏/朋友实际手机、Android8–15各版本、厂商省电差异均 **NOT_RUN**；minSdk26是支持目标。当前模拟器通过不代表她已经认可设计。
- 本地提醒仍为非精确调度，可能受系统/厂商节电延后。最终验证调度正确，不把UI截止刷新当作通知送达；V1历史真实通知到达实测另见旧报告。
- “已保存”指当前事务已提交。尚在保存时突然结束进程，仅保证最近已提交版本，不能承诺未落盘输入绝不丢失。
- 测试签名，同包名/相容签名直接覆盖，不先卸载。单附件20MB，备份解压总量64MB、密文读取80MB。
- 备份口令至少8字符且不可找回；完整恢复替换全部资料、保留一次逻辑回滚。界面偏好不在业务备份中；提醒依据恢复后业务资料重建。旧内容寻址附件暂不自动清理。
- CSV不是XLSX，电话/编号导入表格时应选文本列。正文/敏感字段需主动选择包含并检查预览。没有云同步、远程AI或上传客户资料。

GitHub发布状态以 [STATUS](../STATUS.md) 为准；发布内容为源码、文档、使用虚构验收资料的真实截图、个人APK与实录，不包含原始数据库、备份、CSV、签名私钥或本机配置。
