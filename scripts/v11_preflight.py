"""Preserve installed V1 sandboxes and verify copies without printing business content.

Use only the explicitly selected existing emulator. Personal receives HOME only;
QA is stopped to prepare a consistent, non-destructive upgrade fixture baseline.
Raw archives stay in ignored output/, never in the source repository.
"""
import hashlib
import io
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "output" / "v1.1" / "preflight"
ADB = Path(r"C:\Users\16562\AppData\Local\Android\Sdk\platform-tools\adb.exe")
SERIAL = "emulator-5554"


def adb(*args):
    return subprocess.run([str(ADB), "-s", SERIAL, *args], check=True, capture_output=True).stdout


def sha(data):
    return hashlib.sha256(data).hexdigest()


def tar_bytes(package):
    return adb("exec-out", "run-as", package, "tar", "-cf", "-", "databases", "files", "shared_prefs")


def regular_files(data):
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        result = {}
        for item in archive.getmembers():
            if item.isfile():
                assert not item.name.startswith("/") and ".." not in Path(item.name).parts
                result[item.name] = archive.extractfile(item).read()
        return result


def verify(package, first, second):
    files = regular_files(first)
    other = regular_files(second)
    # Both snapshots must agree on every business database and immutable attachment.
    relevant = lambda n: n.startswith("databases/") and not n.endswith("-shm") or n.startswith("files/workbook/")
    assert {n: sha(b) for n, b in files.items() if relevant(n)} == {n: sha(b) for n, b in other.items() if relevant(n)}
    work = OUT / package
    for name, data in files.items():
        dest = work / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
    db = sqlite3.connect(work / "databases" / "workbook.db")
    assert db.execute("pragma integrity_check").fetchone()[0] == "ok"
    counts = {t: db.execute(f"select count(*) from {t}").fetchone()[0] for t in ["entries", "drafts", "attachments", "sealed_drafts"]}
    for rel, expected, size in db.execute("select relativePath, sha256, size from attachments"):
        blob = (work / "files" / "workbook" / rel).read_bytes()
        assert len(blob) == size and sha(blob) == expected
    # SQLite backup API produces a self-contained, verified pre-upgrade database.
    standalone = sqlite3.connect(OUT / f"{package}-consistent.db")
    db.backup(standalone)
    assert standalone.execute("pragma integrity_check").fetchone()[0] == "ok"
    standalone.close()
    db.close()
    pkg = adb("shell", "dumpsys", "package", package).decode()
    return {"package": package, "versionCode": re.search(r"versionCode=(\d+)", pkg).group(1),
            "versionName": re.search(r"versionName=(\S+)", pkg).group(1),
            "tarSha256": sha(first), "stableRepeatedCopy": True, "integrity": "ok",
            "counts": counts, "allAttachmentHashes": "PASS"}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    if (OUT / "preflight.json").exists():
        raise RuntimeError("Pre-upgrade evidence already exists; preserve it instead of overwriting it")
    for package in ["cn.kangyang.workbook", "cn.kangyang.workbook.qa"]:
        info = adb("shell", "dumpsys", "package", package).decode()
        if not re.search(r"versionName=1\.0\.0(?:\s|$)", info):
            raise RuntimeError("This one-time V1 preflight requires the installed V1 packages")
    adb("shell", "input", "keyevent", "3")
    report = {"serial": SERIAL, "avd": adb("emu", "avd", "name").decode().strip(),
              "api": adb("shell", "getprop", "ro.build.version.sdk").decode().strip(),
              "size": adb("shell", "wm", "size").decode().strip(),
              "density": adb("shell", "wm", "density").decode().strip(),
              "fontScale": adb("shell", "settings", "get", "system", "font_scale").decode().strip(), "packages": []}
    for package in ["cn.kangyang.workbook", "cn.kangyang.workbook.qa"]:
        if package.endswith(".qa"):
            adb("shell", "am", "force-stop", package)
        first, second = tar_bytes(package), tar_bytes(package)
        (OUT / f"{package}-v1-sandbox.tar").write_bytes(first)
        report["packages"].append(verify(package, first, second))
    (OUT / "preflight.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
