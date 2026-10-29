"""Add explicit synthetic migration fixtures to the backed-up, stopped V1 QA DB.

This is upgrade-fixture preparation, NOT UI acceptance evidence. Never target
the personal package. Existing rows and immutable attachment bytes are retained.
"""
import json
import re
import sqlite3
from pathlib import Path
from v11_preflight import OUT, adb, sha

PKG = "cn.kangyang.workbook.qa"
BASE = 1793232000000


def enc(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def entry(id, kind="INTERACTION", title="", fields=None, parent=None, updated=None):
    return dict(id=id, kind=kind, title=title, parentId=parent, sourceId=None,
                occurredAt=BASE, createdAt=BASE, updatedAt=updated or BASE,
                archived=False, fields=fields or {})


def main():
    assert PKG.endswith(".qa")
    installed = adb("shell", "dumpsys", "package", PKG).decode()
    if not re.search(r"versionName=1\.0\.0(?:\s|$)", installed):
        raise RuntimeError("Fixture preparation is only allowed on V1 QA, never an upgraded installation")
    report = json.loads((OUT / "preflight.json").read_text(encoding="utf-8"))
    assert report["serial"] == "emulator-5554"
    baseline = next(p for p in report["packages"] if p["package"] == PKG)
    assert baseline["integrity"] == "ok" and baseline["allAttachmentHashes"] == "PASS"
    source_path = OUT / f"{PKG}-consistent.db"
    assert source_path.is_file(), "Verified V1 backup is required before fixture preparation"
    adb("shell", "am", "force-stop", PKG)
    source = sqlite3.connect(source_path)
    assert source.execute("pragma user_version").fetchone()[0] == 1
    target_path = OUT / "qa-v1-with-legacy-fixtures.db"
    if target_path.exists():
        raise RuntimeError("Fixture database already exists; inspect before rerunning")
    db = sqlite3.connect(target_path)
    source.backup(db)
    original = {t: source.execute(f"select * from {t}").fetchall() for t in ["entries", "drafts", "attachments", "sealed_drafts"]}
    source.close()
    assert not db.execute("select id from entries where id like 'v11legacy_%'").fetchall()
    def add(e):
        names = ["id", "kind", "title", "parentId", "sourceId", "occurredAt", "createdAt", "updatedAt", "archived", "fields"]
        db.execute(f"insert into entries ({','.join(names)}) values ({','.join('?' for _ in names)})", [enc(e[k]) if k == "fields" else e[k] for k in names])
    def draft(id, e):
        db.execute("insert into drafts(id,entry,revision,updatedAt) values (?,?,?,?)", (id, enc(e), 7, BASE + 1000))
    org = entry("v11legacy_org", "ORGANIZATION", "示例·V1升级单位", {"type": "养老机构", "notes": "仅供V1.1覆盖升级验收"})
    add(org)
    draft("v11legacy_new_draft", entry("v11legacy_new", title="旧版手写标题应保留", parent=org["id"], fields={"content": "V11旧新建草稿：周院长希望先看服务介绍。", "date": "2026-10-29", "time": "08:00", "nextStep": "旧下一步需主动确认，不自动创建任务", "nextDate": "2026-11-01", "nextReminder": "true", "participants": "示例参与人"}))
    current = entry("v11legacy_conflict", title="旧编辑冲突记录", fields={"content": "V11冲突正式版本：这段较新，必须保留。"}, updated=BASE + 10000)
    add(current)
    draft("v11legacy_conflict_draft", entry(current["id"], title=current["title"], fields={"content": "V11冲突编辑草稿：这段未提交，也必须可恢复。"}))
    existing = entry("v11legacy_edit", title="旧编辑无冲突记录", fields={"content": "V11编辑前正式正文。"})
    add(existing)
    draft("v11legacy_edit_draft", entry(existing["id"], title=existing["title"], fields={"content": "V11已有记录编辑草稿，沿用原ID。"}))
    draft("v11legacy_attachment_draft", entry("v11legacy_attachment", fields={"mode": "临时速记"}))
    source_attachment = db.execute("select name,mime,relativePath,size,sha256 from attachments limit 1").fetchone()
    assert source_attachment, "Use an already verified V1 fixture attachment"
    name, mime, rel, size, digest = source_attachment
    db.execute("insert into attachments(id,ownerId,name,mime,relativePath,size,sha256) values(?,?,?,?,?,?,?)", ("v11legacy_attachment_file", "v11legacy_attachment", "V11旧附件图片.png", mime, rel, size, digest))
    draft("v11legacy_invalid_draft", entry("v11legacy_invalid", fields={"content": "V11非法元数据正文必须能找回。", "date": "2026-02-30", "time": "09:30:45", "needs": "保留原始无效输入供修正"}))
    draft("v11legacy_customer_draft", entry("v11legacy_customer", "CUSTOMER", "示例·V1普通客户草稿", {"notes": "不能偷偷变为正式客户"}))
    db.commit()
    assert db.execute("pragma integrity_check").fetchone()[0] == "ok"
    for table, rows in original.items():
        final_rows = set(db.execute(f"select * from {table}").fetchall())
        assert all(row in final_rows for row in rows), f"Original rows changed: {table}"
    counts = {t: db.execute(f"select count(*) from {t}").fetchone()[0] for t in original}
    db.execute("pragma wal_checkpoint(truncate)")
    db.execute("pragma journal_mode=delete")
    db.close()
    adb("push", str(target_path), "/data/local/tmp/workbook-v11-fixtures.db")
    adb("shell", "run-as", PKG, "rm", "-f", "databases/workbook.db-wal", "databases/workbook.db-shm")
    adb("shell", "run-as", PKG, "cp", "/data/local/tmp/workbook-v11-fixtures.db", "databases/workbook.db")
    copied = adb("exec-out", "run-as", PKG, "cat", "databases/workbook.db")
    assert sha(copied) == sha(target_path.read_bytes())
    report = {"scope": "V1 QA migration fixture setup only; not UI acceptance", "package": PKG,
              "originalRowsPreserved": True, "counts": counts, "sqliteIntegrity": "ok",
              "databaseSha256": sha(copied), "fixtureIds": ["v11legacy_new", "v11legacy_edit", "v11legacy_conflict", "v11legacy_attachment", "v11legacy_invalid", "v11legacy_customer"],
              "attachmentSha256": digest, "noPersonalMutation": True}
    (OUT / "legacy-fixtures.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
