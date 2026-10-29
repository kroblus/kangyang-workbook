# V1.1 真实模拟器截图

全部来自本机已有 `ShengliFlowApi36 / emulator-5554 / Android16 API36` 中实际安装的 QA 应用，内容均为虚构验收资料，未重绘或合成。连续验收会保留多条相似示例，不代表安装包内置业务数据。

- applicationId：`cn.kangyang.workbook.qa`
- versionName / versionCode：`1.1.0 / 5`
- APK SHA-256：`C0DC3ACB553DBB51A64184208B441E4F41061763781358C03C64D0F32B5E9483`
- 应用源码 commit：`18df88549a4c8ed231cf99ba594d6d7631412402`

| 画面 | 实际配置 |
|---|---|
| [未滚动首页](home.png) | 1080×2400、420dpi、fontScale1.0 |
| [点一次后的正文与键盘](editor-keyboard.png) | 同上，实际正文焦点与系统输入法 |
| [一句话保存后返回首页](recent-note.png) | 同上 |
| [正文优先详情](detail.png) | 同上 |
| [后补关联对象](association.png) | 同上 |
| [主动加明天跟进](tomorrow-followup.png) | 同上 |
| [同一笔记录与独立跟进](note-followup.png) | 同上 |
| [筹备中／时间待定活动](pending-activity.png) | 同上 |
| [备份页面](backup.png) | 860×1910、320dpi、430dp、fontScale1.0 |
| [320dp大字号与键盘](narrow-large-font.png) | 640×1422、320dpi、fontScale1.5 |
| [横屏大字号与键盘](landscape-large-font.png) | 实际1600×720、320dpi、800×360dp、fontScale1.5 |

前七张来自完整通过的 `finalDeliveryWalkthroughAndEvidence-final-r4`；同一次操作录成41.57秒的原始MP4，编码画幅720×1600。活动画面来自最终包的T08；另外三张来自最终包八档布局验收。完整运行记录与哈希映射见 [ACCEPTANCE](../ACCEPTANCE.md)。模拟器状态栏沿用原系统时区，应用业务时间使用北京时间。
