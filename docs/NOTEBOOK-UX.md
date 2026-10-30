# 手记体验：内容优先，按需补充

2026-10-30。基于使用者已反馈流畅的轻量版 `f8551f11ad533d689c059f75cf50e5613cf99fbe` 继续迭代。使用者希望保留绿色，增加自然的过渡与温度，并明确不希望整个应用出现大段表单。本轮保留该基线的 TextFieldState 快速输入修复和已有性能优化。

## 这次怎么用

- **笔记**：仍然点“记一笔”就写正文。已有标题在正文上方显示；时间、对象、标签在下方呈现为短摘要。“补充”先选择标题、标签、沟通背景或单独留意的要点，再进入一个编辑项。点完成才写回，直接关闭不更改原值。已补的要点回到笔记里阅读。
- **跟进**：先写一句要做的事，点今天、明天或选日期。时刻、优先级、提醒用标签操作，备注点“补一句”后再写。所有设置仍在主面板点击“加入跟进／保存跟进”时正式提交。
- **单位、客户、服务对象和活动**：名称与主要内容直接写在页面上；类型、阶段、时间等常用信息作为小标签。“再补一点”按主题选择局部编辑。单位的主要了解和客户的需求在保存后的档案首页直接阅读，不必展开详细资料。联系人和本人资料使用相同方式。
- **搜索、导出**：日期和对象范围用摘要标签点选。备份与恢复保留其必要的口令输入，不增加额外步骤。

旧字段、关联对象、任务来源和历史内容均继续保留；没有数据库或备份格式迁移。主要正文、草稿和自动保存语义保持原状。单项文字编辑的临时值在按“完成”前不会写回；这与正文的自动保存是两种明确的操作。日期、枚举等点选项在选择后更新当前编辑内容，资料与跟进仍沿用各自主页面的完成／保存动作。

## 温度与动效

`Theme.kt` 和主题颜色常量未改。首页使用现有浅绿和白色，减少重复描边，保持中文正文行距；“手记”“最近手记”“补一句”等文案贴近日常记录。

页面进入使用 220 ms、8 dp 的轻淡入和位移，动画只更新当前页面的绘制图层，不同时组合前后两个编辑器。动画标识只依赖路由和编辑会话，不依赖正文、保存状态或数据刷新。小标签在空值变为有值时使用 120 ms 颜色过渡，面板沿用 Material 的出现与关闭过程。标准 Compose 动画跟随系统动画时长设置。

标题、正文输入不做逐字动画；没有长表单高度插值、循环背景、纸张纹理或新动画依赖。打开跟进的子面板时，使用该 Modal 自己的焦点和实际键盘 inset，避免边退键盘边弹出另一个面板。

## 构建与本机试用

```powershell
.\gradlew.bat :app:assembleQaWarm
adb devices -l
adb -s <已有模拟器的序列号> install -r app\build\outputs\apk\qa\warm\app-qa-warm.apk
```

手记试用包继承轻量版的 R8、资源压缩和非 Debug 配置，包名 `cn.kangyang.workbook.qa.warm`，与原轻量包并列安装。它使用独立数据空间，需要比较相同资料时，在试用包中导入自己的备份。

在自己的电脑上继续开发时，也可以构建 `assembleQaPerformance`，用本机既有签名更新原轻量包。跨电脑生成的测试签名不保证兼容，不要卸载已有资料应用来解决签名不同。

本轮最有价值的操作验证：写一段正文，打开补充要点并保存一个字段，再次修改后取消；确认正文、光标和原字段保留。然后走一次跟进日期／备注，以及单位、客户、活动的局部编辑。现有设备测试新增 `noteDetailsEditOneThingAndCancelKeepsBody`，并适配相关入口；原快速输入回归保留。

如需采样，使用已有 `scripts/measure-performance.ps1 -Package cn.kangyang.workbook.qa.warm` 的 Begin/End。新包的动画感受与帧耗时需在设备上复测，不能沿用上一轮的测量结果。

## 本轮实际验证

2026-10-30 在本机先运行 `adb devices -l`，使用已有的 `emulator-5554`（Android 16 / API 36，1080 × 2400）安装并打开 `qaWarm`。它是非 Debug、R8 与资源压缩构建，与原轻量 APK 的签名证书一致；没有清除个人空间或原轻量包的数据。

在优化包上实际走过：新建笔记直接输入、连续按键和移动光标插入；单项补充确认／取消后检查正文及原值；跟进日期与备注、主面板正式保存后重开；单位地址、客户预算、活动地点的局部补充与取消；活动选日期后取消时刻仍保持时间待定。通过实际画面和操作录屏检查了页面进入、返回、键盘收起及父子面板切换，提交按钮可以操作，没有发现保存刷新抢走输入焦点。

走查发现单位主要了解与客户需求虽已保存，却只在展开“详细资料”后可读。已将这两段主要内容放回档案首页，并增加 `dossierPrimaryTextRemainsVisibleAfterSaving`，验证保存、局部补充及重新编辑后正文仍直接可读。

本次实际完成 `assembleQaWarm`、`assembleQaDebug`、`assembleQaDebugAndroidTest`。在同一模拟器的 QA Debug 空间单独运行并通过 3 个相关用例：`rapidNoteTypingAndCursorMovementKeepLatestText`、`noteDetailsEditOneThingAndCancelKeepsBody`、`dossierPrimaryTextRemainsVisibleAfterSaving`。QA Debug 的用例结果用于功能回归，界面走查使用优化包。原工作目录的未提交修改保留在原处，本次修正在独立 worktree 中进行。

未运行全量历史验收、低端真机测试或本轮帧耗时采样；本轮操作体验不能换算为帧率，也不沿用上一轮的性能数字。

## 手记试用发布

发布标签为 [`v1.1.0-warm.1`](https://github.com/kroblus/kangyang-workbook/releases/tag/v1.1.0-warm.1)，沿用仓库的试用预发布方式。附件 `kangyang-workbook-1.1.0-warm-v5.apk` 是上面实际安装验证并交付使用者的同一文件，没有因发布重新打包：版本名 `1.1.0-warm`、versionCode 5、包名 `cn.kangyang.workbook.qa.warm`，大小 2,309,691 bytes，SHA-256 `0c8cc25925525fca9512abd0258a9674dae8397b8527076d53c8f655ccf8144f`。签名证书 SHA-256 为 `f1a230a1f20d3041c808fabd8c150985949e6d830ce53b96cab00aa5e1efa5a5`，与此前轻量包相同；手记包可与轻量包并列安装。

使用者在发布前反馈“我是用起来完全没问题，页面切换，动画都很流畅”，并明确要求发布。该反馈没有附设备型号，与本页模拟器验证及未运行的专项性能测量分开记录。Release 附件同时提供简短操作录屏与 `SHA256SUMS`。

## 参考与取舍

以下为官方资料提供的交互启发，具体布局与动效参数为本项目设计，并非声称复刻竞品或测得竞品的动画时长。

- [Bear 入门](https://blog.bear.app/2023/07/get-started-with-bear/)：创建即写，信息与工具按需打开。
- [Bear 排版选项](https://bear.app/faq/typography-options/)：重视字号、行高与段间距。
- [Craft 的组织方式](https://www.craft.do/organize)：先捕获内容，需要时再整理。
- [UpNote](https://getupnote.com/)：写作区域与组织工具分工，专注模式减少界面干扰。
- [Notesnook 创建笔记](https://notesnook.com/help/create-a-note-in-notesnook)：输入与自动保存优先。
- [Quillpad 开源仓库](https://github.com/quillpad/quillpad)：标签、提醒与附件围绕笔记附加。
- [Compose 动画指南](https://developer.android.com/develop/ui/compose/animation/quick-guide)：用绘制图层动画减少每帧的组合与布局工作。
