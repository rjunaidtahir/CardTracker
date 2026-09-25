"""Turns build/test/lint output into GitHub annotations (readable through the API)."""
import glob
import os
import re
import xml.etree.ElementTree as ET

ROOT = os.getcwd()


def esc(s: str) -> str:
    return s.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def emit(level: str, title: str, lines: list[str], limit: int = 30000):
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


def rel(path: str) -> str:
    path = path.replace("file://", "")
    return path.replace(ROOT + "/", "")


def read(name):
    try:
        with open(name, encoding="utf-8", errors="replace") as f:
            return f.read().splitlines()
    except FileNotFoundError:
        return []


# Kotlin / Java compile errors and warnings
errors, warnings = [], []
for log in ("tests.log", "build.log"):
    for line in read(log):
        if line.startswith("e: "):
            errors.append(rel(line[3:]))
        elif line.startswith("w: ") and "/build/" not in line:
            warnings.append(rel(line[3:]))
        elif re.search(r"(error:|FAILED|What went wrong|Could not)", line) and "BUILD FAILED" not in line:
            errors.append(line.strip())
errors = list(dict.fromkeys(errors))
warnings = list(dict.fromkeys(warnings))

# Unit tests
tests = fails = skipped = 0
failed = []
for f in glob.glob("app/build/test-results/testDebugUnitTest/*.xml"):
    root = ET.parse(f).getroot()
    tests += int(root.get("tests", 0))
    skipped += int(root.get("skipped", 0))
    for case in root.iter("testcase"):
        for fail in list(case.findall("failure")) + list(case.findall("error")):
            fails += 1
            msg = (fail.get("message") or "").strip()
            body = (fail.text or "").strip().splitlines()
            where = next((b.strip() for b in body if "cardtracker" in b or "uaefin" in b), "")
            failed.append(f"{case.get('classname')}.{case.get('name')}: {msg[:600]} @ {where}")

# Lint
lint = []
lint_counts = {}
for f in glob.glob("app/build/reports/lint-results-debug.xml") + glob.glob("app/build/intermediates/lint_intermediate_text_report/**/*.xml", recursive=True):
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    for issue in root.iter("issue"):
        sev = issue.get("severity")
        loc = issue.find("location")
        where = ""
        if loc is not None:
            where = f"{rel(loc.get('file', ''))}:{loc.get('line', '')}"
        key = f"{sev}/{issue.get('id')}"
        lint_counts[key] = lint_counts.get(key, 0) + 1
        if sev in ("Error", "Fatal", "Warning"):
            lint.append(f"[{sev}] {issue.get('id')}: {issue.get('message')} @ {where}")
    break

emit("error", "Compile errors", errors)
emit("error", "Failed tests", failed)
emit("warning", "Kotlin warnings", warnings)
emit("warning", "Lint", lint)
summary = [
    f"tests={tests} failed={fails} skipped={skipped}",
    f"compile_errors={len(errors)} kotlin_warnings={len(warnings)}",
    "lint: " + ", ".join(f"{k}={v}" for k, v in sorted(lint_counts.items())),
]
emit("notice", "Summary", summary)
