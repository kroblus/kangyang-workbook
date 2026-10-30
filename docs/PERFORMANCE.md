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

本轮已实际完成 `assembleQaPerformance`、`testQaDebugUnitTest` 和 `compileQaDebugAndroidTestKotlin`。21 项 JVM 测试全部通过，包括快照索引与备份序列化兼容性回归。优化包完成 R8 压缩和打包。当前构建会话没有连接 Android 设备，因此新增 Room/提醒设备回归、模拟器操作和低端真机帧耗时仍待相应设备执行；本文件不宣称已经测得帧率或性能提升百分比。

## 参考

- [Compose 性能与优化构建](https://developer.android.com/develop/ui/compose/performance)
- [低开销性能分析](https://developer.android.com/guide/topics/manifest/profileable-element)
- [按显示尺寸加载图片](https://developer.android.com/topic/performance/graphics/load-bitmap)
