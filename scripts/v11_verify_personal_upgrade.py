"""Read-only comparison with the preserved personal V1 snapshot after covering install.

Never stops, clears, restores or writes the personal app. Run after normal launch
and HOME, when its writes have settled. All raw files remain in ignored output/.
"""
import json
from pathlib import Path
import re
import sqlite3

from v11_preflight import adb, regular_files, sha, tar_bytes

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "cn.kangyang.workbook"
OUT = ROOT / "output/v1.1/personal-upgrade"
BASE = ROOT / "output/v1.1/preflight/cn.kangyang.workbook-consistent.db"


def rows(db, table):
    return {row["id"]: dict(row) for row in db.execute(f"SELECT * FROM {table}")}


def main():
    assert BASE.exists(), "A verified pre-upgrade baseline is required"
    assert not OUT.exists(), "Preserve existing evidence; do not overwrite it"
    info = adb("shell", "dumpsys", "package", PACKAGE).decode()
    assert re.search(r"versionCode=5(?:\s|$)", info)
    assert re.search(r"versionName=1\.1\.0(?:\s|$)", info)
    first, second = tar_bytes(PACKAGE), tar_bytes(PACKAGE)
    files, other = regular_files(first), regular_files(second)
    relevant = lambda name: (name.startswith("databases/") and not name.endswith("-shm")) or name.startswith("files/workbook/")
    assert {n: sha(b) for n, b in files.items() if relevant(n)} == {n: sha(b) for n, b in other.items() if relevant(n)}, "Wait for ordinary writes to settle and retry"
    OUT.mkdir(parents=True)
    (OUT / "personal-v11-sandbox.tar").write_bytes(first)
    for name, data in files.items():
        dest = OUT / "sandbox" / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
    old = sqlite3.connect(f"file:{BASE.as_posix()}?mode=ro", uri=True)
    new = sqlite3.connect(OUT / "sandbox/databases/workbook.db")
    old.row_factory = new.row_factory = sqlite3.Row
    assert old.execute("PRAGMA user_version").fetchone()[0] == 1
    assert new.execute("PRAGMA user_version").fetchone()[0] == 2
    assert new.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    counts = {}
    # Existing V1 personal fixtures contain two formal rows and one TASK draft.
    # Compare every persisted V1 column; new recovery columns may be additive.
    for table in ["entries", "drafts", "attachments", "sealed_drafts"]:
        before, after = rows(old, table), rows(new, table)
        for row_id, original in before.items():
            assert row_id in after, f"Missing prior {table} row"
            for key, value in original.items():
                actual = after[row_id][key]
                if key in ("entry", "fields"):
                    assert json.loads(actual) == json.loads(value), f"Changed prior {table}.{key}"
                else:
                    assert actual == value, f"Changed prior {table}.{key}"
        counts[table] = {"before": len(before), "after": len(after), "allV1ColumnsPreserved": True}
    report = {"result": "PASS", "serial": "emulator-5554", "package": PACKAGE,
              "versionName": "1.1.0", "versionCode": 5, "roomMigration": "1 -> 2",
              "integrity": "ok", "stableRepeatedCopy": True, "tarSha256": sha(first), "tables": counts,
              "personalDestructiveOperations": "none"}
    (OUT / "verification.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    old.close()
    new.close()


if __name__ == "__main__":
    main()
