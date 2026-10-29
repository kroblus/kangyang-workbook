package cn.kangyang.workbook.ui

import cn.kangyang.workbook.data.Kind

val OrganizationTypes = listOf("养老机构", "综合医院", "康复医院", "社区卫生服务机构", "街道办", "社区/居委会", "器具供应商", "培训/其他合作单位")
val OrganizationNatures = listOf("暂不清楚", "机关", "事业单位", "企业", "社会组织", "其他")
val OrganizationStages = listOf("待联系", "已接触", "持续沟通", "合作中", "暂缓", "已结束")
val CustomerStages = listOf("新咨询", "需求了解中", "方案沟通中", "服务对接中", "已完成", "暂缓", "已结束")
val ActivityStatuses = listOf("筹备中", "待开展", "已完成", "已取消")
val TaskStatuses = listOf("未完成", "已完成", "已取消")
val TaskPriorities = listOf("普通", "重要", "紧急")

fun fieldLabel(key: String): String = mapOf(
    "type" to "类型", "nature" to "主体性质", "area" to "服务区域", "address" to "地址",
    "tags" to "业务需求", "stage" to "进展阶段", "notes" to "备注", "role" to "角色 / 职务",
    "phone" to "电话", "wechat" to "微信备注", "primary" to "主要联系人", "relationship" to "与联系人关系",
    "ageBand" to "年龄段", "care" to "照护情况", "source" to "来源", "needs" to "需求说明",
    "intentDate" to "意向时间", "urgency" to "紧急程度", "budget" to "预算（选填）",
    "linkedOrganization" to "关联单位", "linkedActivity" to "来源活动", "mode" to "方式",
    "participants" to "参与人", "purpose" to "目的", "content" to "重要内容", "terms" to "拟议价格与合作条件",
    "promises" to "双方承诺", "obstacles" to "障碍与待解决问题", "conclusion" to "结论",
    "certainty" to "事项确认程度", "nextStep" to "下一步", "nextDate" to "跟进日期",
    "nextTime" to "跟进时刻", "nextReminder" to "跟进提醒", "nextPriority" to "跟进优先级",
    "start" to "开始时间", "end" to "结束时间", "location" to "地点", "organizationId" to "关联单位",
    "goal" to "活动目标", "audience" to "面向人群", "expectedCount" to "预计人数",
    "plan" to "方案与议程", "division" to "人员分工", "materials" to "物料清单",
    "precautions" to "注意事项", "status" to "状态", "remindMinutes" to "提前提醒（分钟）",
    "actualCount" to "实际人数", "execution" to "执行情况", "review" to "活动复盘",
    "followup" to "后续跟进", "date" to "截止日期", "time" to "截止时刻", "reminder" to "本地提醒",
    "priority" to "优先级", "completionNote" to "完成备注", "company" to "公司",
    "appName" to "应用显示名称", "occurredDate" to "发生日期", "occurredTime" to "发生时刻",
).getOrDefault(key, key)

internal fun editorHeading(kind: Kind): String = when (kind) {
    Kind.ORGANIZATION -> "单位档案"
    Kind.CUSTOMER -> "客户档案"
    Kind.CONTACT -> "联系人"
    Kind.RECIPIENT -> "服务对象"
    Kind.INTERACTION -> "记一笔"
    Kind.ACTIVITY -> "活动安排"
    Kind.TASK -> "跟进事项"
    Kind.PROFILE -> "本人资料"
}
