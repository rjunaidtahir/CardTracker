"""Turns the iPhone build and test output into GitHub annotations (readable through the API)."""
import glob
import os
import re
import xml.etree.ElementTree as ET

ROOT = os.getcwd()


def esc(s):
    return s.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def emit(level, title, lines, limit=30000):
    if not lines:
        return
    chunks, cur = [], ""
    for line in lines:
        if len(cur) + len(line) + 1 > limit:
            chunks.append(cur)
            cur = ""
        cur += line + "\n"
    if cur:
        chunks.append(cur)
    for i, c in enumerate(chunks[:8]):
        t = title if len(chunks) == 1 else f"{title} ({i + 1}/{len(chunks)})"
        print(f"::{level} title={t}::{esc(c)}")


def read(name):
    try:
        with open(name, encoding="utf-8", errors="replace") as f:
            return f.read().splitlines()
    except FileNotFoundError:
        return []


def rel(s):
    return s.replace(ROOT + "/", "")


# Kotlin (shared engine) compile errors and simulator test failures
kotlin = []
for line in read("ios-shared.log"):
    if line.startswith("e: ") or "What went wrong" in line or re.search(r"FAILED$", line):
        kotlin.append(rel(line))
tests = fails = 0
failed = []
for f in glob.glob("shared/build/test-results/iosSimulatorArm64Test/*.xml"):
    root = ET.parse(f).getroot()
    tests += int(root.get("tests", 0))
    for case in root.iter("testcase"):
        for fail in list(case.findall("failure")) + list(case.findall("error")):
            fails += 1
            msg = (fail.get("message") or (fail.text or "")).strip()
            failed.append(f"{case.get('classname')}.{case.get('name')}: {msg[:800]}")

# Xcode: Swift errors, warnings in our files, test results
swift_errors, swift_warnings, xc_failed, xc_summary = [], [], [], []
for log in ("xcode.log", "device.log"):
    for line in read(log):
        if " error: " in line or line.startswith("error:") or ": error:" in line:
            swift_errors.append(rel(line.strip()))
        elif ": warning:" in line and "/ios/" in line:
            swift_warnings.append(rel(line.strip()))
        elif re.search(r"Test Case .* failed", line):
            xc_failed.append(line.strip())
        elif re.match(r"\*\* (BUILD|TEST) ", line) or "Executed" in line and "test" in line:
            xc_summary.append(f"{log}: {line.strip()}")
        elif "Run script build phase" in line or "PhaseScriptExecution" in line and "failed" in line.lower():
            swift_errors.append(line.strip())

emit("error", "Kotlin", list(dict.fromkeys(kotlin)))
emit("error", "Failed shared tests (simulator)", failed)
emit("error", "Swift errors", list(dict.fromkeys(swift_errors)))
emit("error", "Failed app tests", xc_failed)
emit("warning", "Swift warnings", list(dict.fromkeys(swift_warnings))[:150])
emit("notice", "Summary", [f"shared simulator tests={tests} failed={fails}"] + xc_summary)
