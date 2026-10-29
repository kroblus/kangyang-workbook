package cn.kangyang.workbook

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cn.kangyang.workbook.data.*
import cn.kangyang.workbook.domain.TimeRules
import cn.kangyang.workbook.storage.Exports
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

@RunWith(AndroidJUnit4::class)
class DeviceFlowTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val device get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val repo get()=Repository.get(ui.activity)
    private fun snap()=runBlocking{repo.snapshot()}
    private fun tag(name:String)=ui.onNodeWithTag(name)
    private fun fill(key:String,value:String){tag("field_$key").performScrollTo().performTextReplacement(value);ui.waitForIdle()}
    private fun clickText(value:String){val node=ui.onNodeWithText(value);runCatching{node.performScrollTo()};node.performClick();ui.waitForIdle()}
    private fun shot(name:String){device.waitForIdle();device.takeScreenshot(File(ui.activity.getExternalFilesDir(null),"evidence/$name.png").apply{parentFile!!.mkdirs()})}
    private fun home(){ui.runOnUiThread{ui.activity.model.navigate("home",true)};ui.waitForIdle()}
    private fun waitDraft(){ui.waitUntil(15_000){ui.onAllNodesWithText("草稿已保存").fetchSemanticsNodes().isNotEmpty()}}
    private fun save(semantic:Boolean=false){if(semantic) tag("save_entry").assertIsEnabled().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()} else tag("save_entry").performClick();try {ui.waitUntil(15_000){ui.onAllNodesWithTag("edit_entry").fetchSemanticsNodes().isNotEmpty()}}catch(e:Throwable){shot("save-failure");File(ui.activity.getExternalFilesDir(null),"evidence/save-failure-tree.txt").writeText(ui.onRoot().printToString()+"\nroute="+ui.activity.model.route+"\nmessage="+ui.activity.model.message+"\neditor="+ui.activity.model.editor);throw e}}

    @Test fun firstLaunchAndDeniedPermission() {
        check(ui.activity.packageName.endsWith(".qa"))
        assertTrue("Run first on a newly installed QA package",snap().entries.isEmpty())
        tag("today_count").assertTextEquals("0")
        shot("e01-empty-home")
        clickText("全部待办");clickText("新增")
        fill("title","示例·通知拒绝仍可保存")
        fill("date",TimeRules.today().plusDays(1).toString())
        tag("field_reminder").performScrollTo().performClick()
        shot("e01-permission-request")
        val deny=device.wait(Until.findObject(By.res(java.util.regex.Pattern.compile("com.android.permissioncontroller:id/permission_deny.*"))),5000)
        deny?.click()
        device.waitForIdle();shot("e01-after-permission");ui.waitForIdle();save()
        assertEquals(1,snap().entries.count{it.title=="示例·通知拒绝仍可保存"})
        shot("e01-denied-still-saved")
    }

    @Test fun scheduleRealBackgroundReminder() {
        check(ui.activity.packageName.endsWith(".qa") ||
            (ui.activity.packageName=="cn.kangyang.workbook" && InstrumentationRegistry.getArguments().getString("personalReminder")=="true"))
        home();clickText("全部待办");clickText("新增")
        val at=Instant.ofEpochMilli(System.currentTimeMillis()).atZone(TimeRules.zone).plusMinutes(2).withSecond(0).withNano(0)
        fill("title","示例·真实后台提醒")
        fill("date",at.toLocalDate().toString());fill("time",at.format(DateTimeFormatter.ofPattern("HH:mm")))
        tag("field_reminder").performScrollTo().performClick()
        device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_allow_button")),5000)?.click()
        ui.waitForIdle();save(semantic=true)
        val e=snap().entries.last{it.title=="示例·真实后台提醒"}
        File(ui.activity.getExternalFilesDir(null),"evidence/reminder-plan.txt").apply{parentFile!!.mkdirs();writeText("package=${ui.activity.packageName}\nid=${e.id}\nplanned=${TimeRules.reminderAt(e)}\ncreated=${System.currentTimeMillis()}\nmode=non-exact-real-ui\n")}
        shot("e08-reminder-saved")
        device.pressHome()
    }

    @Test fun preparePersistentDraftWithPhoto() {
        check(ui.activity.packageName.endsWith(".qa"))
        val ctx=ui.activity
        val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"康养验收图片.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/WorkbookQA");put(MediaStore.Images.Media.IS_PENDING,1)}
        val uri=ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
        ctx.contentResolver.openOutputStream(uri)!!.use{out->
            val bitmap=Bitmap.createBitmap(640,400,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(231,245,238));val p=Paint().apply{color=Color.rgb(23,108,101);textSize=38f;isAntiAlias=true};canvas.drawText("示例活动照片 E06",45f,190f,p);bitmap.compress(Bitmap.CompressFormat.PNG,100,out);bitmap.recycle()
        }
        ctx.contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
        home();tag("quick_record").performClick();tag("quick_临时速记").performClick()
        fill("content","E06持久草稿\n第一段：拜访后等待资料。\n第二段：下次确认方案，中文输入完整保留。")
        waitDraft()
        // Cancellation must leave the existing draft alone.
        tag("add_photo").performScrollTo().performClick();device.waitForIdle();device.pressBack();ui.waitForIdle()
        assertTrue(snap().drafts.any{it.entry["content"].contains("E06持久草稿")})
        tag("add_photo").performScrollTo().performClick()
        device.waitForIdle()
        File(ctx.getExternalFilesDir(null),"evidence/photo-picker.xml").apply{parentFile!!.mkdirs();device.dumpWindowHierarchy(this)}
        shot("e09-system-photo-picker")
        val photo=device.wait(Until.findObject(By.res("com.google.android.providers.media.module:id/icon_thumbnail")),4000)
            ?: device.findObject(By.descContains("Photo taken"))
            ?: device.findObject(By.res("com.android.providers.media.module:id/icon_thumbnail"))
            ?: device.findObjects(By.clazz("android.widget.ImageView")).lastOrNull()
        checkNotNull(photo){"系统图片选择器没有可选测试图片；查看 photo-picker.xml"}.click()
        device.findObject(By.text("Add"))?.click()
        device.findObject(By.text("添加"))?.click()
        ui.waitUntil(15_000){snap().attachments.any{a->snap().drafts.any{it.entry.id==a.ownerId&&it.entry["content"].contains("E06持久草稿")}}}
        waitDraft();shot("e06-draft-photo-saved")
        ui.onNodeWithContentDescription("返回").performClick();ui.waitForIdle()
        val draft=snap().drafts.first{it.entry["content"].contains("E06持久草稿")}
        File(ctx.getExternalFilesDir(null),"evidence/draft-id.txt").writeText(draft.id)
        tag("draft_${draft.id}").performScrollTo().performClick();ui.waitForIdle();tag("field_content").assertTextContains("E06持久草稿",substring=true)
        try { device.setOrientationLeft();ui.waitForIdle();device.setOrientationNatural();ui.waitForIdle() }
        finally { device.unfreezeRotation() }
        ui.onNodeWithContentDescription("返回").performClick();ui.waitForIdle();home()
        shot("e06-after-navigation-rotation")
    }

    @Test fun verifyPersistentDraftAfterProcessDeath() {
        check(ui.activity.packageName.endsWith(".qa"))
        val id=File(ui.activity.getExternalFilesDir(null),"evidence/draft-id.txt").readText()
        home();tag("draft_$id").performScrollTo().performClick()
        tag("field_content").performScrollTo().assertTextContains("第二段：下次确认方案，中文输入完整保留。",substring=true)
        val d=snap().drafts.first{it.id==id}
        assertEquals(1,snap().drafts.count{it.id==id})
        assertTrue(snap().entries.none{it.id==d.entry.id})
        val file=snap().attachments.first{it.ownerId==d.entry.id}
        assertTrue(repo.attachmentFile(file).exists())
        clickText("打开");ui.onNodeWithText("关闭预览").assertExists();shot("e06-reopened-image-preview");ui.onNodeWithText("关闭预览").performClick()
        ui.onNodeWithContentDescription("返回").performClick();home()
        shot("e06-process-restored")
    }

    @Test fun prepareExistingRecordEditDraft() {
        check(ui.activity.packageName.endsWith(".qa"))
        val e=snap().entries.first{it.kind==Kind.INTERACTION}
        home();tag("search_all").performClick();tag("global_search").performTextReplacement(e["content"].lineSequence().first().take(25))
        tag("entry_${e.id}").performScrollTo().performClick();tag("edit_entry").performClick()
        fill("content",e["content"]+"\nE06编辑草稿：尚未正式提交的补充。")
        waitDraft()
        val draft=snap().drafts.first{it.entry.id==e.id}
        File(ui.activity.getExternalFilesDir(null),"evidence/edit-draft-id.txt").writeText(draft.id)
        assertEquals(e["content"],snap().find(e.id)!!["content"])
        shot("e06-existing-edit-draft-saved")
        ui.onNodeWithContentDescription("返回").performClick();home()
    }
    @Test fun verifyExistingRecordEditDraft() {
        val id=File(ui.activity.getExternalFilesDir(null),"evidence/edit-draft-id.txt").readText()
        val draft=snap().drafts.first{it.id==id}
        assertFalse(snap().find(draft.entry.id)!!["content"].contains("E06编辑草稿"))
        home();tag("draft_$id").performScrollTo().performClick();tag("field_content").performScrollTo().assertTextContains("E06编辑草稿：尚未正式提交的补充。",substring=true)
        shot("e06-existing-edit-draft-reopened")
        ui.onNodeWithContentDescription("返回").performClick();home()
    }

    @Test fun exportAndBackupThroughSystemPickers() {
        check(ui.activity.packageName.endsWith(".qa"))
        home();tag("quick_record").performClick();tag("quick_临时速记").performClick()
        fill("content","=1+1,示例\"引号\"\n第二行中文导出检查")
        fill("nextStep","@示例跟进，仅用于测试")
        save();val entry=snap().entries.first{it.kind==Kind.INTERACTION&&it["content"].startsWith("=1+1,")}
        clickText("预览 / 导出摘要");tag("export_body").performClick();tag("summary_preview").assertTextContains("第二行中文导出检查",substring=true)
        tag("share_summary").performClick();device.waitForIdle();shot("e10-system-share-sheet")
        assertFalse("Sharing should show Android chooser",device.currentPackageName==ui.activity.packageName)
        device.pressBack();ui.waitForIdle();ui.onNodeWithText("关闭").performClick()
        home();tag("settings").performClick();tag("open_export").performScrollTo().performClick()
        tag("export_body").performScrollTo().performClick()
        tag("save_csv").performScrollTo().performClick()
        savePicker("康养验收导出.csv")
        ui.waitForIdle()
        val bytes=Exports.csv(snap(),snap().entries.filter{it.kind==Kind.INTERACTION}.sortedBy{it.occurredAt},false,true)
        assertTrue(bytes.take(3)==listOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte()))
        assertTrue(bytes.toString(Charsets.UTF_8).contains("'=1+1,示例\"\"引号\"\"\n第二行"))
        File(ui.activity.getExternalFilesDir(null),"evidence/expected-export.csv").writeBytes(bytes)
        home();tag("settings").performClick();clickText("姜宏");tag("save_entry").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()};ui.waitUntil(10_000){ui.activity.model.route=="settings"};tag("open_backup").performScrollTo().performClick()
        tag("backup_password").performScrollTo().performTextReplacement("WorkbookQA-2026")
        tag("backup_repeat").performScrollTo().performTextReplacement("WorkbookQA-2026")
        tag("create_backup").performScrollTo().performClick()
        savePicker("康养验收备份.kywb")
        ui.waitForIdle();shot("e11-backup-saf-saved")
    }
    @Test fun malformedFileThroughSystemPickerKeepsDraft() {
        check(ui.activity.packageName.endsWith(".qa"))
        val ctx=ui.activity
        val name="Workbook-异常验收-${System.currentTimeMillis()}.pdf"
        val values=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,"application/pdf");put(MediaStore.Downloads.RELATIVE_PATH,"Download")}
        val uri=ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)!!
        ctx.contentResolver.openOutputStream(uri)!!.use{it.write("这不是有效的PDF文件".toByteArray())}
        home();tag("quick_record").performClick();tag("quick_临时速记").performClick();fill("content","E09异常附件不破坏已有中文草稿")
        waitDraft();val id=ui.activity.model.editor!!.entry.id
        tag("add_file").performScrollTo().performClick();device.waitForIdle()
        var file=device.wait(Until.findObject(By.text(name)),5000)
        if(file==null){device.findObject(By.desc("Show roots"))?.click();device.findObject(By.text("Downloads"))?.click();file=device.wait(Until.findObject(By.text(name)),5000)}
        shot("e09-file-selector")
        checkNotNull(file){"异常测试PDF未显示在系统文件选择器"}.click()
        ui.waitUntil(10_000){ui.activity.model.message?.contains("格式")==true||ui.activity.model.message?.contains("损坏")==true}
        shot("e09-malformed-file-feedback")
        assertTrue(snap().drafts.any{it.entry.id==id&&it.entry["content"].contains("E09异常附件")})
        assertTrue(snap().attachments.none{it.ownerId==id})
        ui.onNodeWithContentDescription("返回").performClick();home()
    }
    private fun savePicker(name:String) {
        device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),10000)
        val field=device.wait(Until.findObject(By.res("com.google.android.documentsui:id/title")),5000)
            ?: device.findObject(By.clazz("android.widget.EditText"))
        checkNotNull(field){"系统保存界面未出现"}.text=name
        shot("e10-system-save-picker")
        (device.findObject(By.res("com.google.android.documentsui:id/action_menu_save")) ?: device.findObject(By.text("SAVE")) ?: device.findObject(By.text("Save")) ?: device.findObject(By.text("保存")))!!.click()
        device.waitForIdle();device.findObject(By.text("REPLACE"))?.click();device.waitForIdle()
    }

    @Test fun offlineBusinessAndUpdateSeed() {
        check(ui.activity.packageName.endsWith(".qa"))
        home();tag("nav_orgs").performClick();clickText("新增单位")
        fill("title","示例·离线与覆盖更新");fill("notes","离线创建后仍可修改。")
        save();tag("edit_entry").performClick();fill("notes","离线修改已保存，覆盖更新必须保留。")
        save();home();tag("search_all").performClick();tag("global_search").performTextReplacement("离线与覆盖更新");ui.waitForIdle()
        assertTrue(snap().entries.any{it.title=="示例·离线与覆盖更新"&&it["notes"].contains("离线修改")})
        shot("e07-offline-search")
    }
    @Test fun verifyAfterUpdate() {
        check(ui.activity.packageName.endsWith(".qa"))
        val e=snap().entries.first{it.title=="示例·离线与覆盖更新"}
        assertTrue(e["notes"].contains("离线修改"))
        home();tag("nav_orgs").performClick();tag("list_search").performTextReplacement("离线与覆盖更新");tag("entry_${e.id}").performScrollTo().performClick()
        tag("edit_entry").performClick();fill("notes","覆盖更新后继续编辑成功。原始资料和关系保留。")
        save();assertTrue(snap().find(e.id)!!["notes"].contains("覆盖更新后"));shot("e12-update-retained-editable")
    }
    @Test fun finalPersonalSmoke() {
        check(ui.activity.packageName=="cn.kangyang.workbook")
        assertTrue("The delivered APK starts with no business records",snap().entries.isEmpty())
        assertTrue(snap().drafts.isEmpty())
        home();tag("today_count").assertTextEquals("0");shot("personal-empty-first-launch")
        home();tag("quick_record").performClick();tag("quick_临时速记").performClick();fill("content","安装验收：新增记录、退出重开后查看。此条仅用于模拟器。");save()
        shot("personal-new-record")
    }
    @Test fun archiveCustomerKeepsHistoryAndRelationships() {
        check(ui.activity.packageName.endsWith(".qa"))
        val before=snap()
        val customer=before.entries.first { c -> c.kind==Kind.CUSTOMER&&!c.archived&&before.entries.count{it.kind==Kind.INTERACTION&&it.parentId==c.id}>=2 }
        val children=before.entries.filter{it.parentId==customer.id}.associateBy{it.id}
        val attachments=before.attachments.filter{it.ownerId==customer.id||it.ownerId in children.keys}
        home();tag("nav_customers").performClick();tag("list_search").performTextReplacement(customer.title)
        tag("entry_${customer.id}").performScrollTo().performClick();clickText("归档客户");clickText("确认")
        ui.waitUntil(10_000){snap().find(customer.id)?.archived==true}
        assertEquals(children,snap().entries.filter{it.parentId==customer.id}.associateBy{it.id})
        assertEquals(attachments,snap().attachments.filter{it.ownerId==customer.id||it.ownerId in children.keys})
        DataValidation.validateSnapshot(snap())
        home();tag("nav_customers").performClick();tag("list_search").performTextReplacement(customer.title)
        tag("entry_${customer.id}").assertDoesNotExist();ui.onNode(isToggleable()).performScrollTo().performClick()
        tag("entry_${customer.id}").performScrollTo().assertIsDisplayed();shot("e13-customer-archive-retained")
        File(ui.activity.getExternalFilesDir(null),"evidence/customer-archive.txt").writeText("PASS customer=${customer.id}; children=${children.size}; history, contacts, recipients, tasks and attachments retained; default list hidden; archived filter shows object")
    }
    @Test fun finalPersonalReopen() {
        check(ui.activity.packageName=="cn.kangyang.workbook")
        val e=snap().entries.first{it["content"].startsWith("安装验收：")};home();tag("entry_${e.id}").performScrollTo().performClick();ui.onNodeWithText(e["content"]).assertExists();shot("personal-reopened")
    }

    @Test fun layoutScreens() {
        val label=InstrumentationRegistry.getArguments().getString("layoutLabel") ?: "baseline"
        home();shot("e14-$label-home")
        tag("nav_orgs").performClick();shot("e14-$label-list")
        val e=snap().entries.firstOrNull{it.kind==Kind.ORGANIZATION&&!it.archived}
        if(e!=null){tag("entry_${e.id}").performScrollTo().performClick();shot("e14-$label-detail");tag("edit_entry").performClick();fill("title",e.title);shot("e14-$label-keyboard-form");device.pressBack();ui.waitForIdle();shot("e14-$label-form")}
        home();tag("nav_activities").performClick()
        sFirstActivity()?.let{a->tag("entry_${a.id}").performScrollTo().performClick();shot("e14-$label-activity")}
        home();tag("settings").performClick();tag("open_backup").performScrollTo().performClick();shot("e14-$label-backup");tag("create_backup").performScrollTo();shot("e14-$label-backup-actions")
    }
    private fun sFirstActivity()=snap().entries.firstOrNull{it.kind==Kind.ACTIVITY}
}
