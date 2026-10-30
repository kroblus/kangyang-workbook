# 轻量试用与性能检查

使用者反馈 ux-motion 测试版操作偏重。此次围绕视频中可见的键盘/面板位移、成功提示推动整页，以及代码中重复读取和主线程图片处理做小范围修正。

## 实现方向

- 长列表按可见区域绘制，条目使用稳定标识；单位卡片、关联对象和附件使用快照内的只读索引，筛选排序按输入变化计算。
- 取消通用按钮缩放与长表单的逐帧高度动画，保留点击反馈、短淡入出和必要的列表补位。成功提示浮在内容之上，避免推挤页面。
- 正文保存只校验实际关联对象；提醒只观察任务和活动，正文更新不再重建提醒。时钟只因任务日期/时刻/状态等相关变化重新安排。
- 文件完整性校验和图片采样解码在后台执行，每次打开只校验一次；图片按实际预览尺寸加载，上限为 1280 像素边长。
- 保留现有保存时效、事务、编辑版本与恢复语义。没有通过减少保存频率或取消数据保护换取流畅。

## 用正确的构建检查手感

```powershell
.\gradlew.bat :app:assembleQaPerformance
adb devices -l
adb -s <实际模拟器序列号> install -r app\build\outputs\apk\qa\performance\app-qa-performance.apk
```

`performance` 继承 Release 优化，启用 R8 和资源压缩，`debuggable=false`、`profileable=true`。包名为 `cn.kangyang.workbook.qa.perf`，保留独立 QA 数据空间，可与个人包和原 QA 包并列安装。签名沿用本机的开发签名；使用另一台电脑生成的同包名 APK 时，需确认签名兼容，不要卸载已有业务应用解决签名冲突。

Debug 适合功能调试，但有额外运行开销；性能判断用上述优化包。保持设备动画设置、分辨率和数据量一致，使前后比较有意义。

## 一次短操作就能定位下一步

在已有模拟器上依次体验：打开记录并输入、正文与跟进面板切换、展开设置并保存、搜索结果返回、连续滚动较长列表、完成跟进、打开大照片。输入和滚动期间观察是否被保存状态或提示打断。涉及真实低端手机的体验结论，需要在相应设备上实际复测。

需要数据时使用 `scripts/measure-performance.ps1`：先打开已安装的应用，再执行 Begin，手动走上述相关操作，结束执行 End。脚本只读取设备信息、渲染统计与内存，不清数据，不改变动画或设备设置。输出保留原始统计，无自动 PASS 阈值。

```powershell
.\scripts\measure-performance.ps1 -Mode Begin -Serial <实际模拟器序列号>
# 在模拟器上完成本次要观察的操作
.\scripts\measure-performance.ps1 -Mode End -Serial <同一序列号>
```

若比较修改前后，使用同一设备、相同数据量、相同构建类型与相同操作，分开记录冷启动和已经使用过的状态。报告掉帧数量/比例、帧耗时分位、操作后的内存占用与具体卡顿位置；需要峰值内存时另做区间采样。不要把 30 fps 录屏当作应用只运行在 30 fps，也不要把 Debug 与优化构建的差异全部归因于某一项代码修改。需要进一步归因时再抓一次相应区间的 Perfetto。

## 本轮验证

2026-10-30 在已有 `emulator-5554`（Android 16 / API 36、x86_64、1080×2400、420 dpi）实际运行。基线是 `af08d810df7178b084be2dcdc548c46c9692a78b`，前后均使用 R8/资源压缩的 `cn.kangyang.workbook.qa.perf`，动画设置保持 1.0。原目录未提交修改留在原处，本次修改在独立的 `Workbook-perf` 工作目录中。

通过应用的恢复界面准备了相同的虚构测试资料：200 个单位、60 个客户、402 条沟通、80 条跟进和一张 6000×4000 / 13,104,644 字节的 JPEG，共 743 条记录。只恢复独立性能空间，没有清除个人资料。两轮都用 `measure-performance.ps1` 的 Begin/End 包住实际按键、点击与滑动；各轮开始前重新恢复同一份资料，冷启动单独记录。操作覆盖正文输入/移动光标、跟进面板、展开设置并保存、200 项单位列表连续滚动、搜索详情返回、完成跟进和大照片预览。

| 设备报告的整段统计 | 基线优化包 | 本次优化包 |
| --- | ---: | ---: |
| 总帧数 | 951 | 1010 |
| 卡顿帧 | 170（17.88%） | 158（15.64%） |
| P50 / P90 / P95 / P99 | 19 / 34 / 40 / 109 ms | 19 / 32 / 42 / 93 ms |
| 操作后 PSS | 54,574 KiB | 49,645 KiB |
| 操作后 RSS | 163,608 KiB | 158,928 KiB |
| 单独冷启动 `am start -W` TotalTime | 756 ms | 767 ms |

这是各一次操作采样，输入修复后实际处理的字符/光标更新也有差异，不能据此认定整体性能稳定提升。P95 和冷启动没有改善；内存是关闭预览后的快照，不是峰值。原始设备报告保留在 `output/perf-20261030/before` 和 `after`，汇总在 `output/perf-20261030/comparison.json`。没有根据录屏帧率计算应用帧率。

本次直接修复的是快速输入时丢字符、光标插入位置被旧值覆盖的问题。正文改用当前编辑会话持有的 `TextFieldState`；只在文本变化时同步保存模型，移动光标不增加保存版本，保存和退出前读取最新输入。350 ms 停顿保存、1500 ms 持续输入调度、事务及旧版本防覆盖保持不变。优化包实际操作中输入与插入内容完整、焦点与键盘保留；跟进设置展开时保存按钮在键盘上方可见；成功提示未推动待办页标题，详情返回保留搜索和滚动位置，大照片已实际打开。

仍慢的位置主要是跟进面板/键盘切换，以及照片预览窗口首次出现。本次分段统计中，跟进面板段 55/213 个卡顿帧、近似 P95 73 ms；大照片段 14/67、近似 P95 61 ms；长列表滚动段为 21/350、近似 P95 30 ms。分段分位由相邻累计直方图相减得到，包含页面和键盘过渡，不能当作某个函数的执行时间。

另对跟进面板打开、输入、设置展开/收起和关闭抓了一次 `atrace`，不混入上述比较。应用主线程的 `Choreographer#doFrame` 区间最高 128.9 ms，`Recomposer:recompose` 最高 48.1 ms、`AndroidOwner:onMeasure` 最高 35.3 ms，渲染线程 `DrawFrames` 最高 35.2 ms。说明这段仍有组合、测量和窗口渲染的尖峰；这些是包含子区间的墙钟时间，不相加，也不能直接归因成某个业务函数的纯 CPU 耗时。原始跟踪和区间汇总位于 `output/perf-20261030/panel-trace.txt`、`panel-trace-summary.json`。本轮没有在缺少进一步归因时大改面板结构。

已构建 `assembleQaPerformance`、`assembleQaDebug` 和 `assembleQaDebugAndroidTest`。相关功能回归仅在 QA Debug 跑了三项：`rapidNoteTypingAndCursorMovementKeepLatestText`、`immediateBackAndExistingBodyCanBeCleared`、`visibleSaveFailureRetryAndSlowRevisionState`，均通过；这些结果不用于性能判断。本轮没有重跑全量历史验收或 JVM 套件。低端真机没有接入，尚未实测，不从模拟器结果推断真机表现。

## 轻量试用发布

发布标签为 [`v1.1.0-perf.1`](https://github.com/kroblus/kangyang-workbook/releases/tag/v1.1.0-perf.1)，为轻量试用预发布版。附件 `kangyang-workbook-1.1.0-perf-v5.apk` 使用本页实际验证的同一份 APK，版本名 `1.1.0-perf`、versionCode 5、包名 `cn.kangyang.workbook.qa.perf`。APK 为 2,293,307 bytes，SHA-256 为 `07425d5d8abb1c27c6e025aa90bda87ad2d741997fb6df03e7c15f210c70b114`。

发布前使用者补充反馈：“这个轻量版本现在不卡顿了，其他都没啥问题。”这是本次试用反馈，设备型号未附；与上面的模拟器帧耗时采样分开记录，不据此宣称低端真机专项测量通过。

## 参考

- [Compose 性能与优化构建](https://developer.android.com/develop/ui/compose/performance)
- [低开销性能分析](https://developer.android.com/guide/topics/manifest/profileable-element)
- [按显示尺寸加载图片](https://developer.android.com/topic/performance/graphics/load-bitmap)
