# 数据与实现契约

唯一工程根 D:/Desktop/Codex/Workbook。包 cn.kangyang.workbook；验收包 .qa。主代理拥有构建、清单、MainActivity、UI 和验收总表。data 代理拥有 data/、storage/ 和对应测试；reminders 代理拥有 reminders/、domain/TimeRules.kt 及相应测试。测试代理后续单独分配。

使用 Room 的类型化 Entry 聚合：Kind = ORGANIZATION, CUSTOMER, CONTACT, RECIPIENT, INTERACTION, ACTIVITY, TASK, PROFILE。联系人/服务对象独立行，以 parentId 关联档案；沟通 parentId 可空，关联单位/客户；Task parentId 关联单位/客户/活动，sourceId 关联沟通。所有关系使用稳定 UUID。不是通过名称拼接。Attachment 和 Draft 独立表。Profile 也是可备份的单例 Entry（id="profile"），DataStore 仅存界面偏好。

Entry 属性：id:String=UUID, kind:Kind, title:String="", parentId:String?=null, sourceId:String?=null, occurredAt:Long=now, createdAt:Long=now, updatedAt:Long=now, archived:Boolean=false, fields:Map<String,String>=emptyMap。get(key) 返回字段或空字符串。Snapshot(entries:List<Entry>, drafts:List<Draft>, attachments:List<Attachment>)。

Draft(id:String, entry:Entry, revision:Long, updatedAt:Long)；Attachment(id:String, ownerId:String, name:String, mime:String, relativePath:String, size:Long, sha256:String)。附件 ownerId 是正式/草稿 entry.id；草稿转正式不变。正式提交的 draftId 持久封存，拒绝迟到写入。

Repository(context) 提供 val state:StateFlow<Snapshot>（或 Flow，可UI stateIn）；suspend snapshot():Snapshot、save(entry:Entry,draftId:String?=null):Entry、saveDraft(draft:Draft):Boolean、discardDraft(id:String)、archive(id:String,archived:Boolean)、setTaskStatus(id:String,status:String,note:String="")、importAttachment(ownerId:String,uri:Uri):Attachment、removeAttachment(id:String)、attachmentFile(attachment):File；启动数据库后自动 Flow 更新。构造后无需 suspend 初始化。工厂单例 get(context) 可加。

字段契约（均 String；列表用换行分隔；true/false 用字符串）：
- Organization: type, nature, area, address, tags, stage, notes。
- Contact: role, phone, wechat, primary。
- Recipient: relationship, ageBand, care, notes。
- Customer: source, area, tags, needs, intentDate, urgency, budget, stage, linkedOrganization, linkedActivity, notes。
- Interaction: mode, participants, purpose, needs, content, terms, promises, obstacles, conclusion, certainty（讨论过/待确认/已明确）, nextStep, nextDate(yyyy-MM-dd), nextTime(HH:mm，可空日期型), nextReminder(true/false), nextPriority。occurredAt 为真实发生时间；fields date/time 仅 UI 初始化/兼容不作为统计依据。save 中以 "followup-"+interaction.id 稳定生成/更新一次 Task，sourceId=interaction.id，parentId=interaction.parentId。编辑不重置已完成状态；nextStep 空且已有待办时保留原待办并提示独立管理。
- Activity: type, start(yyyy-MM-dd HH:mm), end, location, organizationId, goal, audience, expectedCount, plan, division, materials, precautions, status（筹备中/待开展/已完成/已取消）, remindMinutes（空=关闭；如30/1440）, actualCount, execution, review, followup。
- Task: date(yyyy-MM-dd), time(HH:mm；空=仅日期), reminder(true/false), priority（普通/重要/紧急）, status（未完成/已完成/已取消）, completionNote。
- Profile: role, company, phone, wechat, appName, tags（默认五业务标签，换行分隔）。默认资料为姜宏/养老顾问/湖北省大千生活服务有限公司；电话/微信不预填。

TimeRules 对外：zone=Asia/Shanghai；today():LocalDate；now():Long；formatDateTime(epoch):String；parseDateTime(text):Long?；taskDue(entry):Long?；taskBucket(entry,now:Long=...):String（今日/未来/逾期/未设日期/已完成/已取消）；reminderAt(entry):Long?。日期型今日在当天结束前不过期，提醒默认09:00且UI说明。Activity reminderAt=start-提前分钟。

ReminderScheduler(context).rebuild(snapshot:Snapshot) 为 suspend，可每次数据变化调用，但相同计划不重复调度/触发。Receiver 从 Repository 验证状态和计划，锁屏无敏感内容，Intent extra "entryId" 指向相关详情；BootReceiver 重新构建。普通非精确 AlarmManager；不申请精确闹钟。主代理声明组件权限。

BackupManager(repository,context)（代理可告知最终构造参数）提供 export(password):ByteArray、inspect(bytes,password):PreparedRestore（含snapshot和summary，未修改当前数据）、restore(prepared)；采用PBKDF2-HMAC-SHA256/AES-GCM；限制KDF参数、包大小、条数、附件和路径；先验证全包；不可变内容寻址附件先落盘并fsync，再 Room 事务替换所有表，旧文件不删除，内部保留旧完整逻辑快照。中断只产生未引用附件，数据库为完整旧/新之一。测试故障注入点只能测试代码调用，不在生产UI。

Exports.csv(snapshot, interactions, includeSensitive=false):ByteArray 和 Exports.summary(entry,snapshot,includeSensitive=false):String。BOM UTF8，所有字段引号转义，阻断=+-@及控制空白后的公式前缀。UI提供日期/对象过滤和字段预览，SAF保存及FileProvider分享由主代理实现。
