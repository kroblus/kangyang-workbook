# 本地提醒与业务时间

业务时区固定 `Asia/Shanghai`，日期输入 `yyyy-MM-dd`，时间输入24小时 `HH:mm`。不使用电脑或手机当前时区推断业务日。日期型待办截止到当日最后一毫秒；没有日期的待办不会逾期；指定时间的待办按该时刻计算。

待办提醒主动开启后：有时刻的按该时刻，只有日期的默认 **09:00**。活动提醒默认关闭，设置的提前分钟数从活动开始时间计算。改期只改变活动提醒，不更改独立筹备待办的日期。

`ReminderScheduler` 使用 `AlarmManager.setAndAllowWhileIdle` 普通非精确调度，不请求精确闹钟、不启动常驻服务、不依赖网络或 Google 服务。系统省电、厂商限制及调度批处理可能导致延后，不能承诺分钟级准点。

通知权限只由界面在用户开启提醒时申请。系统通知或此频道被关闭时不安排新闹钟；所有业务数据和首页跟进继续可用。用户重新授权后调用 `rebuild`。提醒内容和锁屏版本都不包括客户姓名、电话或照护信息，点击携带 `entryId` 打开对应待办/活动。

去重和重建：

- PendingIntent 用稳定记录 ID 对应的 URI 标识；改期撤销旧计划，再装入新计划。
- 每次触发从 Room 读取当前快照，对比计划时间、记录状态与权限；旧广播不能凭旧参数发通知。
- 调度与投递状态在应用私有 SharedPreferences 持久保存，数据流重复发射不会重复排程，已投递的同一计划不会因重开/重启再次提醒。
- 通知使用稳定 tag 和 OnlyAlertOnce；完成、取消、关闭提醒会移除已显示通知。
- 开机、系统时间变化、时区变化、应用覆盖更新以及每次进程首次打开时重建有效计划。
- 24小时内错过的未完成跟进可在重开后补提醒；过旧跟进留在工作台逾期列表。活动已经开始的旧提醒不补发。
- Android **强制停止**会阻止系统启动该包；用户重新打开后恢复调度。它与按 Home/正常后台/系统回收进程不同，不能把强停状态当作后台提醒运行条件。

主界面集成：`ReminderScheduler(context).rebuild(snapshot)`；通知授权回调后再调用；恢复备份提交后调用 `rebuild(snapshot, force=true)`。`MainActivity` 的初始 Intent 和 `onNewIntent` 均读取 `ReminderScheduler.EXTRA_ENTRY_ID`。

清单组件：

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<receiver android:name=".reminders.ReminderReceiver" android:exported="false" />
<receiver android:name=".reminders.ReminderBootReceiver" android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.TIME_SET" />
        <action android:name="android.intent.action.TIMEZONE_CHANGED" />
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
    </intent-filter>
</receiver>
```

代码和逻辑测试不等同于实际系统通知验证。`TimeRulesTest` 覆盖仅日期的跨午夜边界、未来分组、精确时刻、已完成/取消、09:00默认值、活动改期与独立待办、无效日期、设备时区变化、错过提醒恢复范围。系统调度日志标记 `WorkbookReminder`，记录计划与实际触发时间，避免记录客户内容。实际后台弹出/点击结果由根代理在统一验收表记录。

官方参考（2026-10-29 读取）：[普通闹钟](https://developer.android.com/develop/background-work/services/alarms)、[通知权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)。Android 12+ 普通非精确闹钟在无省电限制时仍可能在计划后一个小时内才触发，因此模拟器短观察窗口没有出现通知只能据实记为未观察到，不能改用精确调度或直接触发伪造通过。
