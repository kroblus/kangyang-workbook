# 康养工作簿 V1 验收记录

日期：2026-10-29（Asia/Shanghai）。唯一工程根：`D:\Desktop\Codex\Workbook`。

本文件按已落盘的日志、实际安装应用的表单操作和文件校验记录编写。`PASS` 只表示列明范围已通过；`PENDING` 表示仍在执行或等待证据，不计为通过；`NOT_RUN` 表示未执行。旧失败日志保留，不能用早期截图替代重新构建后 APK 的验证。

**最终设备验收：** E01–E15 的列明场景已通过，包括最终 QA APK 综合复验、个人 APK 实装、真实后台通知点击、六档原生屏幕/字号、离线草稿、备份往返和保数据覆盖更新。朋友真实手机为 NOT_RUN，不能把单一 API 36 模拟器结果扩展为全设备保证。原始日志/备份/导出文件仅保留本机；GitHub 上可查看本文结论及精选截图，本地原始证据链接需在该工作区打开。

## APK 与签名对应关系

| 用途 | 包名 / 构建 | 版本 | 文件 |
|---|---|---|---|
| 朋友试用的个人版 | `cn.kangyang.workbook` / `personalDebug` | `1.0.0` / code `3` | [康养工作簿 APK](../output/apk/kangyang-workbook-1.0.0-v3.apk) |
| 独立验收空间 | `cn.kangyang.workbook.qa` / `qaDebug` | `1.0.0` / code `3` | [QA APK](../output/apk/kangyang-workbook-qa-1.0.0-v3.apk) |

个人版绝对路径：`D:\Desktop\Codex\Workbook\output\apk\kangyang-workbook-1.0.0-v3.apk`。

个人版 SHA-256：`EA73FE6949E638E88805AB4216F9A32286FC4ADA2447DE0E82C50C8A3DD3FFC9`。

QA 版 SHA-256：`1367E8A807442BDCB64DE33B5291EC831BB60B5D74D46AEF2FF0E39628E91378`。

两者均为 **Android 测试签名**，不是正式发布签名。证书 DN 为 `C=US, O=Android, CN=Android Debug`，证书 SHA-256 为 `f1a230a1f20d3041c808fabd8c150985949e6d830ce53b96cab00aa5e1efa5a5`，见 [签名输出](../output/verification/apk-signature.txt)。以后覆盖更新必须保持同一包名与相容签名，并增加 versionCode；不得靠卸载或清数据解决签名冲突。

个人版最终 APK 的“安装、空数据首次打开、新建记录、退出重开查看”状态：**PASS**。见 [安装](../output/verification/personal-install.log)、[新建](../output/verification/personal-create.log)、[结束进程](../output/verification/personal-reopen-process.json)、[重开](../output/verification/personal-reopen.log)。个人包没有内置 QA 示例数据；模拟器实装后创建的检查记录只属于该模拟器的本地数据。最终个人 APK 还实际验证了普通后台通知及点击详情。

最终 QA APK 综合业务复验状态：**PASS（3/3）**，日志 [e02-e05-exact-apk.log](../output/verification/e02-e05-exact-apk.log) 记录本次耗时 135.802 秒。此前综合业务已通过，随后主程序只调整系统栏图标；本次重新安装最终文件并再次完成三项综合流程，没有用旧文件的安装结果替代最终 APK 验证。

## 设备、环境与隔离

| 项目 | 实测值 |
|---|---|
| 现有 AVD | `ShengliFlowApi36` |
| Serial | `emulator-5554` |
| Android / API / ABI | Android 16 / API 36 / `x86_64` |
| 初始像素 / 密度 | `1080 × 2400` / `420 dpi` |
| 初始逻辑宽度 / 字体倍率 | 约 `411.43 dp` / `1.0` |
| SDK | `C:\Users\16562\AppData\Local\Android\Sdk` |
| 构建 JDK | `C:\Program Files\Android\Android Studio1\jbr` |
| ADB / AGP / Gradle | `37.0.0-14910828` / `8.13.2` / `8.13` |
| SDK 配置 | compileSdk / targetSdk `36`；minSdk `26` |

原始环境记录：[environment.json](../output/verification/environment.json)。复用现有 AVD，没有新建、下载新系统镜像、重置或清空模拟器。所有主机设备命令固定 `-s emulator-5554`；没有操作真实手机。设备测试使用显式 instrumentation runner，未让 Gradle 自动选择全部连接设备。

核心业务由真实 Compose 表单输入、保存、编辑和搜索；系统授权、照片选择、SAF 保存、系统分享由 UI Automator 操作。Repository 检查只辅助核对 ID、字段和关系。数据安全测试另用 `.qa` 内 UUID 命名的独立数据库/存储目录；没有替换正常 QA 或个人资料库。

## E01–E15 结果与证据

表内路径均相对于工程，链接从本文所在 `docs/` 指向实际文件。与最终 APK 的总体对应范围还须结合上方交付检查点。

| 用例 | 当前结果 | 实际步骤与核对范围 | 证据 |
|---|---|---|---|
| E01 首次使用与权限 | PASS | QA 首次空库打开，今日计数为 0；通过界面新建待办并请求通知权限，在系统拒绝后仍保存成功。没有登录或真实客户数据。 | [最新通过日志](../output/verification/e01-first-launch-r4.log)；[空工作台](../output/verification/qa-business-final/e01-empty-home.png)；[拒绝后保存](../output/verification/qa-business-final/e01-denied-still-saved.png) |
| E02 单位拜访 | PASS；最终 QA 文件复验 PASS | 表单新建示例养老中心、两位联系人；首条谈判与三天后跟进保存，未来事项可找到且不计入今日；第二条历史独立保留。连续两次保存操作与编辑后核对同一记录/任务 ID，没有重复跟进。 | [综合流程 3/3](../output/verification/e02-e05-business-final.log)；[逐步结果](../output/verification/qa-business-final/business-flow-results.txt)；[单位时间线](../output/verification/qa-business-final/E02-unit-timeline-c973ae4.png)；[未来待办](../output/verification/qa-business-final/E02-future-followup-c973ae4.png) |
| E03 单位分类 | PASS；最终 QA 文件复验 PASS | 表单分别创建街道、社区和康复医院，按类型筛选；核对医院主体性质保持“暂不清楚”，没有自动判为政府单位。同名机构以区域区分并能分别保存。 | [综合流程](../output/verification/e02-e05-business-final.log)；[分类截图](../output/verification/qa-business-final/E03-rehabilitation-category-c973ae4.png)；[重名截图](../output/verification/qa-business-final/E09-same-name-organizations-c973ae4.png) |
| E04 客户角色与不同业务 | PASS；最终 QA 文件复验 PASS | 表单创建家庭档案，女儿为独立联系人、母亲为独立服务对象，保存两次沟通和一次跟进。另建器具咨询、培训咨询，不填写入住/老人照护资料也能保存并沟通。 | [综合流程](../output/verification/e02-e05-business-final.log)；[角色与历史](../output/verification/qa-business-final/E04-family-roles-and-history-895cdd3.png)；[逐步结果](../output/verification/qa-business-final/business-flow-results.txt) |
| E05 活动全过程 | PASS；最终 QA 文件复验 PASS | 表单创建活动时间、地点、方案、物料、筹备任务，开启提前提醒后改期；提醒计划随活动变动，独立日期任务保持原日期。保存执行结果并完成；另外分别测试取消活动时保留或取消未完成任务，活动历史保留；主动创建来源客户。 | [综合流程](../output/verification/e02-e05-business-final.log)；[改期方案](../output/verification/qa-business-final/E05-rescheduled-plan-fa3a8ac.png)；[完成结果](../output/verification/qa-business-final/E05-completed-activity-fa3a8ac.png)；[取消历史](../output/verification/qa-business-final/E05-cancelled-history-fa3a8ac.png)；[系统调度核对](../output/verification/e08-alarm-scheduling.txt) |
| E06 草稿防丢 | PASS | 实际中文多段输入，等“草稿已保存”；取消一次照片选择后再成功导入测试图片。返回、重进、旋转后内容保留；普通后台 `am kill` 与用户等价 `am force-stop` 分别结束 QA 进程，再由外部驱动启动验证，草稿与图片可恢复/预览且未变正式记录。已有记录的编辑草稿也在进程死亡后恢复，正式旧内容未提前覆盖。 | [照片/导航/旋转](../output/verification/e06-prepare-photo-r2.log)；[后台死亡 PID](../output/verification/e06-background-process.json) / [重开](../output/verification/e06-background-reopen.log)；[强停 PID](../output/verification/e06-force-stop.json) / [重开](../output/verification/e06-force-stop-reopen.log)；[编辑草稿 PID](../output/verification/e06-edit-process.json) / [重开](../output/verification/e06-existing-edit-reopened.log) |
| E07 离线与时间 | PASS | 关闭测试模拟器 Wi-Fi/移动数据后，通过表单新增、编辑、搜索、完成仅日期待办。补记前两天发生的拜访，核对创建时间与发生时间不同、时间线和日期筛选按发生时间工作。北京时间午夜边界、仅日期/具体时刻/无日期分类由 JVM 测试覆盖。最终恢复原无线设置。 | [离线新增/编辑](../output/verification/e07-offline-v1.log)；[完成与日期筛选](../output/verification/e07-offline-completion-dates.log)；[原网络](../output/verification/network-original.json) / [恢复值](../output/verification/e07-network-restored.json)；[TimeRules 9 项](../app/build/test-results/testQaDebugUnitTest/TEST-cn.kangyang.workbook.TimeRulesTest.xml) |
| E08 本地提醒 | PASS | QA 真实 AlarmManager 注册核对重复去重、改期、完成/取消撤销、重开及独立日期保留。最终个人 APK 通过实际表单启用提醒，允许通知后独立启动并 HOME 到普通后台；计划北京时间14:36:00，实际14:36:43.672触发，延后43.672秒，在12分钟观察窗口内。点击系统通知，已发送的 PendingIntent 指向同一稳定ID，页面标题、截止日期/时间对应；重开后仍只出现一次DELIVER。没有直接调用Receiver、改系统时间或强制触发闹钟。 | [系统调度](../output/verification/e08-alarm-integration-final.log) / [闹钟明细](../output/verification/e08-alarm-scheduling.txt)；[真实表单设置](../output/verification/e08-personal-schedule-ui-r2.log)；[后台状态](../output/verification/e08-background-observation-r2.json)；[实际触发与点击结果](../output/verification/e08-real-reminder-result.json)；[触发日志](../output/verification/e08-real-delivery.log)；[打开详情](screenshots/reminder-opened.png) |
| E09 附件、搜索与长文本 | PASS | UI 搜索中文、电话、标签、沟通内容和空结果，重名单位可保存；保存 4,638 字中文多段内容。实际系统照片选择取消/成功、进程重开预览均通过；选择内容与 PDF 格式不匹配的测试文件得到错误反馈，已有草稿保留、没有成功附件记录。日期范围另见 E07/E10。 | [综合业务结果](../output/verification/qa-business-final/business-flow-results.txt)；[长文本](../output/verification/qa-business-final/E09-long-Chinese-saved-895cdd3.png)；[电话搜索](../output/verification/qa-business-final/E09-contact-phone-search-895cdd3.png)；[系统照片选择](../output/verification/qa-business-final/e09-system-photo-picker.png)；[异常文件](../output/verification/e09-malformed-file.log) |
| E10 导出与分享 | PASS | 真实表单创建含中文、逗号、双引号、换行、公式前缀的记录；预览摘要并打开系统分享面板后退出，没有发送给联系人。通过 SAF 保存实际 CSV，校验 UTF-8 BOM、13 行业务记录、公式防护及与预期字节完全一致。另在 UI 按日期+对象筛选至 1 条，切换对象/重置全量；活动摘要默认不含正文/敏感资料，主动选择正文后方案与物料准确。 | [系统导出/备份界面流程](../output/verification/e10-e11-system-export-backup.log)；[实际 CSV](../output/verification/actual-export.csv)；[校验 JSON](../output/verification/e10-csv-verification.json)；[范围/活动摘要](../output/verification/e10-filtered-exports.log) |
| E11 完整备份与恢复 | PASS | 实际 UI/SAF 导出加密包，在独立空数据库恢复，按 ID 比全部字段、关系、草稿及附件 SHA，原 QA 数据保持不变。错误口令、密文损坏、未来版本、路径穿越、缺失/损坏附件、恢复失败由关键测试覆盖。真实恢复进程在文件落盘、数据库提交前被外部强停，重启验证完整旧状态；不是仅用异常替代进程中断。 | [UI 备份往返日志](../output/verification/e11-ui-backup-roundtrip.log) / [数量与校验报告](../output/verification/e11-ui-backup-roundtrip.txt)；[独立空间安全 3/3](../output/verification/e11-repository-safety.log)；[中断 PID/阶段](../output/verification/e11-process-interruption.json) / [重启验证](../output/verification/e11-interrupt-verify.log) / [结论](../output/verification/e11-interrupt-result.txt)；[BackupAndExport 5 项](../app/build/test-results/testQaDebugUnitTest/TEST-cn.kangyang.workbook.BackupAndExportTest.xml) |
| E12 覆盖安装与迁移 | PASS（更新）；NOT_RUN（不适用的 schema 升级） | QA versionCode 1 中经真实表单保存数据，以同包名/相容签名 code 2 覆盖安装；记录仍存在、可继续编辑，同一 ID 保留。不卸载、不清资料。当前 Room 为新项目 schema 1，本轮没有 schema 版本升级，不宣称迁移已验证。 | [更新前记录](../output/verification/e07-offline-v1.log)；[覆盖安装成功及版本](../output/verification/e12-update-install.log)；[更新后编辑](../output/verification/e12-after-update.log)；[截图](../output/verification/qa-business-final/e12-update-retained-editable.png) |
| E13 归档与删除关系 | PASS | 单位及客户分别经UI归档，默认列表隐藏，包含归档仍可查；子联系人、服务对象、历史沟通与任务保留。有依赖对象的物理删除被Repository拒绝，不产生悬空关系。增强数据测试另核对“草稿附件→正式记录→归档”后附件ownerId与SHA-256不变。 | [单位归档](../output/verification/qa-final/business-flow-results.txt)；[客户归档](../output/verification/e13-customer-archive.log) / [结果](../output/verification/qa-final/customer-archive.txt)；[附件关系增强回归3/3](../output/verification/e11-e13-repository-final.log) |
| E14 原生 Android 小屏/大字号 | PASS（6/6） | 实际320/360/430dp×fontScale1.0/1.5；每档检查工作台、列表、双拜访时间线、4638字长文、表单、键盘输入保存、活动方案、备份及真实SAF恢复预览后取消。关键操作可见且在横向视口内。代表截图人工复核未见横向溢出或不可达按钮，长页纵向滚动。结束恢复1080×2400/420dpi/fontScale1.0并释放测试旋转锁。六档均为同一API36。 | [脚本](../scripts/verify-layouts.ps1)；[320/1.0](../output/verification/e14-320dp-font1.0-r3.log)、[320/1.5](../output/verification/e14-320dp-font1.5-r3.log)、[360/1.0](../output/verification/e14-360dp-font1.0-r3.log)、[360/1.5](../output/verification/e14-360dp-font1.5-r3.log)、[430/1.0](../output/verification/e14-430dp-font1.0-r3.log)、[430/1.5](../output/verification/e14-430dp-font1.5-r3.log)；[视觉复核](../output/verification/e14-visual-review.md)；[恢复设置](../output/verification/e14-restored-settings.json) |
| E15 工程质量与崩溃 | PASS（lint保留22条warning） | 个人/QA APK及测试APK构建通过，14项JVM测试通过。个人版lint为0 error / 22 warning。最终按实际UID10216(QA)/10220(个人)读取crash buffer为空；系统lastanr报告本次启动后无ANR。旧测试定位失败及故意结束恢复进程的日志保留，没有当成产品崩溃或掩盖。 | [构建/JVM/lint](../output/verification/build-final.log)；[测试构建](../output/verification/build-verification-final.log)；[lint XML](../app/build/reports/lint-results-personalDebug.xml)；[JVM XML](../app/build/test-results/testQaDebugUnitTest/)；[应用crash日志](../output/verification/e15-app-crash-log.txt)；[lastanr](../output/verification/e15-last-anr.txt) |

## 数据与进程验证的具体结果

E06 普通后台进程验证记录 PID `7193 → 空`；用户等价强停为 `7380 → 空`；已有记录编辑草稿为 `7931 → 空`。三种重开验证均有独立 instrumentation 通过日志。旋转是附加检查，不用它代替进程死亡。所有结束进程操作只针对 `cn.kangyang.workbook.qa`。这些强停测试也不代表普通后台提醒已通过。

E10 实际导出文件和预期文件 SHA-256 同为 `0A7E16873ACDAB3F6C289EEB0950464487E816F6347DB080A51A7B16F4BF8454`。JSON 校验记录 13 行业务数据、UTF-8 BOM 与公式前缀防护通过。数据量是该次导出时点的范围；后续测试新增记录后数量可以增加，不把不同时间点的计数混称相同。

E11 UI 备份为 17,478 字节，SHA-256 `852b0f998cd22ae7676e9556df8bd31f7199fa0903ff7a5e4bb5e243f917175e`。恢复快照包含：13 个单位、9 个客户、6 位联系人、2 位服务对象、13 条沟通、6 场活动、12 条待办、1 份本人资料、8 个草稿、2 个附件和 65 个已提交草稿标识。恢复目标最初为空；恢复后原 QA 库及附件未改动。两个实际图片附件均为 10,850 字节，其 SHA-256 为 `ed76d9f9fe58963a53273e9682ef4dca7afd6a1bc97b694ee68f43b8b30ab156`。

E11 真实中断发生在 `files_staged` 检查点、数据库事务提交之前，PID `7505 → 空`。新进程打开独立测试目标，验证只有 `old-intact` 的完整旧资料，没有半份草稿/附件引用。恢复后的记录/附件比对与错误口令拒绝测试是另一组证据，不与中断测试相互替代。

lint 的 22 个 warning 分类：11 个 `GradleDependency`、8 个 `UseKtx`、2 个 `UsableSpace`、1 个 `ApplySharedPref`。依赖锁定兼容版本；提醒状态使用同步持久提交避免“已调度/已发送”状态未落盘。最终crash/ANR检查通过，不把非零lint告警改写为零。

## 实际执行的构建、安装与测试命令

工作目录为 `D:\Desktop\Codex\Workbook`。主代理确认最终完整构建实际使用下面命令，无 `--offline`、无 `-PappVersionCode`；构建配置默认 code 3：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:assembleQaDebug :app:assembleQaDebugAndroidTest :app:assemblePersonalDebug :app:assemblePersonalDebugAndroidTest :app:lintPersonalDebug :app:testQaDebugUnitTest --console=plain > output\verification\build-final.log 2>&1
```

之后只改设备测试时使用：

```powershell
.\gradlew.bat :app:assembleQaDebugAndroidTest :app:assemblePersonalDebugAndroidTest :app:lintPersonalDebug --console=plain > output\verification\build-final-tests.log 2>&1
```

QA 安装目标固定为现有模拟器：

```powershell
adb -s emulator-5554 install -r app\build\outputs\apk\qa\debug\app-qa-debug.apk
adb -s emulator-5554 install -r app\build\outputs\apk\androidTest\qa\debug\app-qa-debug-androidTest.apk
```

实际使用的 instrumentation 目标为 `cn.kangyang.workbook.qa.test/androidx.test.runner.AndroidJUnitRunner`。综合业务、数据安全与 UI 备份往返分别执行其测试类：

```powershell
adb -s emulator-5554 shell am instrument -w -e class cn.kangyang.workbook.BusinessFlowTest cn.kangyang.workbook.qa.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -e class cn.kangyang.workbook.RepositorySafetyTest cn.kangyang.workbook.qa.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -e class cn.kangyang.workbook.UiBackupRoundTripTest cn.kangyang.workbook.qa.test/androidx.test.runner.AndroidJUnitRunner
```

进程中断由 [verify-restore-interruption.ps1](../scripts/verify-restore-interruption.ps1) 负责。它真实执行的关键命令为：

```powershell
adb -s emulator-5554 shell am instrument -w -e class 'cn.kangyang.workbook.RestoreProcessRecoveryTest#prepareInterruption' -e interruptRestore true cn.kangyang.workbook.qa.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell run-as cn.kangyang.workbook.qa cat files/restore-interruption-ready.txt
adb -s emulator-5554 shell am force-stop cn.kangyang.workbook.qa
adb -s emulator-5554 shell am instrument -w -e class 'cn.kangyang.workbook.RestoreProcessRecoveryTest#verifyAfterInterruption' -e interruptRestore true cn.kangyang.workbook.qa.test/androidx.test.runner.AndroidJUnitRunner
```

只有脚本读到本次新的 `files_staged:` 标记后才强停；不能脱离这个条件盲目运行强停命令。小屏脚本逐档使用精确 serial 的 `wm size`、`wm density 320`、`settings put system font_scale` 与 `LayoutFlowTest#captureConfiguredLayoutAndKeyboardSave`，完整参数及恢复分支见 [verify-layouts.ps1](../scripts/verify-layouts.ps1)。

最终个人版实际执行：

```powershell
adb -s emulator-5554 install -r output\apk\kangyang-workbook-1.0.0-v3.apk
adb -s emulator-5554 install -r app\build\outputs\apk\androidTest\personal\debug\app-personal-debug-androidTest.apk
adb -s emulator-5554 shell am start -W -n cn.kangyang.workbook/cn.kangyang.workbook.MainActivity
adb -s emulator-5554 shell am instrument -w -e class cn.kangyang.workbook.DeviceFlowTest#finalPersonalSmoke cn.kangyang.workbook.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am force-stop cn.kangyang.workbook
adb -s emulator-5554 shell am instrument -w -e class cn.kangyang.workbook.DeviceFlowTest#finalPersonalReopen cn.kangyang.workbook.test/androidx.test.runner.AndroidJUnitRunner
.\scripts\verify-layouts.ps1 -RunLabel '-r3'
```

提醒实测另外执行 `DeviceFlowTest#scheduleRealBackgroundReminder -e personalReminder true`，之后独立启动应用并按HOME；在观察中没有强停。系统通知通过实际通知栏点击，已发送PendingIntent及目标标题/截止时间共同核对正确对象。

精选截图：[个人空工作台](screenshots/personal-home.png)、[单位时间线](screenshots/unit-timeline.png)、[活动结果](screenshots/activity-results.png)、[320dp/1.5字号键盘保存](screenshots/large-font-keyboard.png)、[通知进入对应待办](screenshots/reminder-opened.png)。

实际录屏：[workbook-business-flow.mp4](../output/verification/workbook-business-flow.mp4)，720×1600，59.659秒，17,303,633字节，来自安装后的真实业务测试；[文件校验](../output/verification/video-verification.json)。录制命令为 `adb -s emulator-5554 shell screenrecord --size 720x1600 --bit-rate 3000000 --time-limit 60 /sdcard/workbook-business-flow.mp4`，随后pull到本工程。原始截图与视频未重绘。

GitHub仓库与Release：发布后补入。

## 当前限制与尚未验证的范围

- 目前只有现有 API 36 模拟器的运行证据；minSdk 26 是兼容目标，不表示 Android 8–15 已逐版本验收。朋友手机型号/系统版本未知，朋友与 Owner 真实手机均为 **NOT_RUN**。
- 普通本地提醒为非精确调度，可能因 Android 批处理、节电、Doze 或厂商策略延后；不承诺分钟级准点，不请求精确闹钟权限。系统强制停止与普通后台不同，重新打开应用才恢复调度；本轮实际到达延后43.672秒，不代表其他设备的延迟上限。
- 单附件上限 20 MB；完整备份解压总量上限 64 MB，密文包读取上限 80 MB。包含大量资料时须先整理；这不是无限附件库。
- 备份口令至少 8 个字符，标准 PBKDF2-HMAC-SHA256 + AES-256-GCM 加密，口令不可找回、不能依赖旧手机独有密钥。恢复是完整替换，保留一次逻辑回滚快照；不可变旧附件暂不自动清理，长期反复添加/删除会占用额外空间。
- 本人资料、业务标签、所有业务实体/关联/草稿/附件包含在完整备份中。DataStore 仅保存界面提示等非业务偏好，**不在备份中**；提醒调度按恢复后的有效业务数据重建，不恢复旧系统通知实例。
- CSV 不具有单元格类型，电话号码/编号在电子表格软件导入时应选文本列，避免前导零丢失。CSV 不是 XLSX。摘要和 CSV 默认不含自由正文及结构化敏感字段，主动包含后仍需使用者检查预览。
- 没有账号、云同步、远程分析、广告或 AI 请求。系统自动云备份和设备迁移配置均显式排除业务目录；不据此宣称可控制所有厂商自有迁移工具。
- 本轮为测试签名本地 APK。GitHub 仓库/Release 发布虽已获用户后续授权，其完成结果在本文编写时仍为 **PENDING**；未上传应用商店，未给真实联系人发送文件。

## 总体状态检查点

| 状态项 | 当前结论 |
|---|---|
| IMPLEMENTATION | PASS（V1 功能已实现；仍以具体用例范围和限制为准） |
| APK_BUILD | PASS（个人/QA APK 已生成，哈希如上） |
| EXISTING_EMULATOR_E2E | PASS（最终QA全业务、个人实装、真实通知、6档布局、crash/ANR检查） |
| OFFLINE_AND_DRAFTS | PASS（含独立普通后台死亡与强停重开） |
| BACKUP_RESTORE | PASS（实际 UI 包恢复至独立空空间、完整性核对和真实进程中断） |
| DATA_PRESERVING_UPDATE | PASS（QA code 1→2；无 schema 升级，不声称迁移测试） |
| FINAL_PERSONAL_APK_SMOKE | PASS（安装、空数据、新建、强停重开；另加真实通知点击） |
| GITHUB_REPOSITORY_AND_RELEASE | PENDING |
| OWNER_OR_FRIEND_REAL_DEVICE | NOT_RUN |
