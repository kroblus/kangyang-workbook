# V1.1 数据与实现契约

唯一工程根 `D:/Desktop/Codex/Workbook`。个人包 `cn.kangyang.workbook`，独立验收包 `cn.kangyang.workbook.qa`。继续 Kotlin、Compose、Room 与既有关系模型，不复制另一套应用。

## 持久对象与时间

`Kind` 为 ORGANIZATION、CUSTOMER、CONTACT、RECIPIENT、INTERACTION、ACTIVITY、TASK、PROFILE。Entry 的身份与关系为稳定 ID，不依赖名称。

`Entry(id, kind, title, parentId, sourceId, occurredAt, createdAt, updatedAt, archived, fields)` 的表结构保持不变。联系人 parentId 指单位或客户；服务对象 parentId 指客户；沟通 parentId 可空或指单位/客户；TASK parentId 可指单位/客户/活动，sourceId 可指沟通且对象归属必须一致。PROFILE 单例 ID 为 `profile`。

`occurredAt` 是发生时间，用于时间线、日期筛选和导出；`createdAt` 是首次创建时间；`updatedAt` 是最近提交时间，工作台最近记录按它排列并明确标注排序。默认发生时间在首次有效正文/附件时确定，只有主动修改日期/时刻才更新。业务时区固定 Asia/Shanghai，全部日期解析集中在 `TimeRules`：严格 `uuuu-MM-dd`、`HH:mm`、`uuuu-MM-dd HH:mm`，不支持秒。

Room schema 2 在 V1 schema 1 上增加三列：Draft.isNoteBuffer（默认 false）、baseUpdatedAt（可空）、recoveryReason（默认空），以及 `note_sessions(entryId, sessionId, revision)` 表。通过 `WORKBOOK_MIGRATION_1_2` 使用 ALTER TABLE / CREATE TABLE 迁移，不清空旧表，不启用 destructiveMigration。

`Draft(id, entry, revision, updatedAt, isNoteBuffer, baseUpdatedAt, recoveryReason)` 是编辑缓冲或待整理表单。`NoteSession` 是连续沟通编辑会话的持久版本闸门；`sealed_drafts` 是已结束/消费的会话历史，二者用途不同。`Attachment(id, ownerId, name, mime, relativePath, size, sha256)` 归属稳定 Entry ID，正式发布不搬迁 ID。

`Snapshot(entries, drafts, attachments, sealedDraftIds)` 包含可备份的业务事实。正在使用的笔记编辑会话 `note_sessions` 不作为跨设备会话导入；恢复会清除旧会话，后续编辑重新建立身份。DataStore 仅保存界面提示偏好，业务标签和本人资料存在 PROFILE 中。

## 连续正文保存

- `beginNoteSession(entryId, sessionId, expectedGeneration)` 建立或恢复一个持久编辑会话，拒绝封存会话。
- `saveNoteRevision(entry, sessionId, revision, expectedGeneration): Entry?` 在事务内校验会话、版本、关系和时间，再同时写编辑缓冲与可检索的正式记录。旧 revision 或同版本不同内容不能覆盖较新记录。
- 新建没有正文/有效附件时返回 null，仅保留内部编辑状态；不会生成首页空卡。已有正式记录清空后仍保留同一 ID。派生标题只用于展示，不回写成残留正文索引。
- ViewModel 采用350ms短防抖，并在连续输入时每1500ms请求保存最新版本；这不是存储耗时的上限。只有当前 revision 事务提交后才显示“已保存”。失败显示未保存并允许重试。
- 应用内返回/完成等待最后一次提交；成功后 `finishNoteSession` 封存该编辑会话并清除缓冲。下一次编辑使用新会话，不封死笔记本身。屏幕恢复通过 TextFieldValue/SavedStateHandle 恢复选择位置与记录身份。
- 正文保存没有隐式新建/改期/重开 TASK 的副作用。唯一必要的关系维护是在主动补关联时同步已有来源 TASK 的 parentId，保留日期、提醒、状态、备注和 ID。

## 跟进事实与明确 patch

TASK 是后续跟进的事实来源。`createTaskIfAbsent` 以稳定 ID 保证重试幂等；`patchTask` 先读取当前 TASK，只应用明确改变的 title/fields。通用任务表单通过 `saveTaskChanges(entry, original)` 计算相对打开时版本的变化，避免把原表单未改动的日期/状态覆盖回去。

`setTaskStatus(id, status, note: String? = null, expectedGeneration)` 中 null 表示保留完成备注；空字符串表示用户明确清空。编辑已完成事项的正文或标题不重开，重新打开是独立动作。

旧沟通 next* 字段继续保留用于追溯，但不再自动生成或覆盖待办。没有关联 TASK 时展示待确认建议，主动确认后才创建。空白新沟通编辑器中直接加跟进时建立独立 TASK，不制造空沟通。

## 业务字段

字段均为 String；列表按换行分隔，布尔值为 `true` / `false`。

| 对象 | 字段 |
| --- | --- |
| Organization | type, nature, area, address, tags, stage, notes |
| Contact | role, phone, wechat, primary |
| Recipient | relationship, ageBand, care, notes |
| Customer | source, area, tags, needs, intentDate, urgency, budget, stage, linkedOrganization, linkedActivity, notes |
| Interaction | mode, participants, purpose, needs, content, terms, promises, obstacles, conclusion, certainty, tags；历史 nextStep/nextDate/nextTime/nextReminder/nextPriority 保留 |
| Activity | type, start, end, location, organizationId, goal, audience, expectedCount, plan, division, materials, precautions, status, remindMinutes, actualCount, execution, review, followup |
| Task | date, time, reminder, priority, status, completionNote |
| Profile | role, company, phone, wechat, appName, tags |

沟通 occurredDate/occurredTime 仅在与 timestamp 一致时提交；旧正式记录中不一致的输入文本保留为 legacyOccurredDate/legacyOccurredTime，真实 occurredAt 不重新解释。非法旧草稿保留原文本与恢复原因，等待使用者修正。

活动 start/end 同时空且状态为筹备中（或保存已取消的待定历史）是合法状态；其他已安排活动必须有完整合法起止时间，end >= start。待定活动没有开始提醒，筹备 TASK 仍可拥有自己的日期。取消已完成活动会被拒绝。

## 恢复隔离、旧资料与导出

所有业务写入口都在 mutationMutex/Room 事务下核对 expectedGeneration。应用层 `restoreData` 取消并等待在途操作，清理 editor、页面历史和导出缓冲，并使旧附件选择会话失效；底层恢复/回滚先推进 generation，再等待写锁。旧代次在提交前会再次检查，迟到正文或附件不能写入新的数据会话。generation 是同进程隔离标识；跨进程由持久 note_sessions、草稿 revision 和封存历史继续约束。

`LegacyNotes.convert` 是启动、导入旧备份和回滚复用的幂等转换。有效新沟通草稿用原 ID 发布；已有记录编辑草稿若与较新的正式内容冲突，保留双方可恢复内容，不自动覆盖；非法元数据继续待整理；普通客户等表单不自动变为正式档案。迁移不创建 TASK、不请求通知权限。

备份内部语义版本升为2；加密外层 framing 仍为1。V1 导入先按 V1 原始规则检查合法性、关系和附件完整性，再转换并按当前契约重新检查，最后提交。`BackupManager.restore` 仍保留文件先暂存并核验、数据库事务整体替换与一次回滚机制。

CSV 和文本摘要从正式记录与当前 TASK 生成；附件-only 记录使用附件名，待定活动显示“时间待定”。CSV 保留 UTF-8 BOM、双引号/换行转义和公式前缀防护；默认不导出详细正文及结构化个人信息。UI 固定同一预览快照和导出字节，经用户选择保存或分享。

此文描述实现契约，不能替代执行证据。逐项实际结果和最终 APK 身份见 [ACCEPTANCE](ACCEPTANCE.md)。
