#!/usr/bin/env python3
"""Walks the debug app on an emulator: first run, every tab, the bank-app notification list. Screenshots go to ./shots."""
import re, subprocess, sys, time, os
import xml.etree.ElementTree as ET

PKG = "com.filsspend.tracker"
ACT = PKG + "/com.uaefinancial.tracker.ui.MainActivity"
LISTENER = PKG + "/com.uaefinancial.tracker.notify.BankNotificationService"
os.makedirs("shots", exist_ok=True)
failures, n = [], [0]

def adb(*a, check=False):
    r = subprocess.run(["adb", *a], capture_output=True, text=True)
    return r.stdout

def shot(name):
    n[0] += 1
    f = f"shots/{n[0]:02d}-{name}.png"
    with open(f, "wb") as fh:
        fh.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)

def nodes():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/u.xml")
        xml = adb("exec-out", "cat", "/sdcard/u.xml")
        try:
            root = ET.fromstring(xml)
            out = []
            for e in root.iter("node"):
                t = (e.get("text") or "") or (e.get("content-desc") or "")
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", e.get("bounds") or "")
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    out.append((t, (x1 + x2) // 2, (y1 + y2) // 2, e.get("class") or "", e.get("checkable") == "true"))
            return out
        except ET.ParseError:
            time.sleep(1)
    return []

def find(text, exact=False):
    for t, x, y, c, _ in nodes():
        if (t == text) if exact else (text.lower() in t.lower()):
            return x, y
    return None

def tap(text, exact=False, wait=8):
    end = time.time() + wait
    while time.time() < end:
        p = find(text, exact)
        if p:
            adb("shell", "input", "tap", str(p[0]), str(p[1]))
            time.sleep(1.2)
            return True
        time.sleep(1)
    return False

def check(cond, msg):
    print(("ok   " if cond else "FAIL ") + msg)
    if not cond:
        failures.append(msg)

def scroll_to(text, tries=8):
    for _ in range(tries):
        if find(text): return True
        adb("shell", "input", "swipe", "540", "1700", "540", "600", "300")
        time.sleep(0.8)
    return bool(find(text))

# ---- start clean, allow the permissions a real user would
adb("shell", "pm", "grant", PKG, "android.permission.READ_SMS")
adb("shell", "pm", "grant", PKG, "android.permission.RECEIVE_SMS")
adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
adb("logcat", "-c")
adb("shell", "am", "start", "-n", ACT)
time.sleep(5)
shot("first-screen")

# ---- first run: walk the onboarding until the tab bar shows
steps = ["Get started", "Continue", "Allow", "Looking", "Skip", "Start"]
for _ in range(14):
    if find("Cards", exact=True) and find("More", exact=True) and find("Activity", exact=True):
        break
    for s in ["Continue", "Allow", "Start", "Skip", "Get started"]:
        if tap(s, exact=True, wait=2):
            shot("onboarding-" + s.lower().replace(" ", "-"))
            break
    else:
        time.sleep(2)
check(bool(find("Cards", exact=True)), "reached the main screen after first run")

# ---- every tab opens
for tab in ["Activity", "Cards", "More", "Home"]:
    ok = tap(tab, exact=True)
    check(ok, f"tab {tab} opens")
    time.sleep(1.5)
    shot("tab-" + tab.lower())

# ---- bank app notifications: grant access like a user would, then look at the list
adb("shell", "cmd", "notification", "allow_listener", LISTENER)
adb("shell", "am", "force-stop", PKG)
adb("shell", "am", "start", "-n", ACT)
time.sleep(4)
tap("More", exact=True)
found = scroll_to("Bank app notifications")
check(found, "More shows the Bank app notifications section")
if found:
    scroll_to("Search your apps")
    shot("notif-section")
    ns = nodes()
    check(any("search your apps" in t.lower() for t, *_ in ns), "the notification list has a search box")
    boxes = [1 for t, x, y, c, ck in ns if ck]
    check(len(boxes) >= 1, "the notification list shows apps to tick (%d rows)" % len(boxes))
    check(not any(t.startswith("com.") for t, *_ in ns), "no raw package names are shown")
    # search works
    p = find("Search your apps")
    if p:
        adb("shell", "input", "tap", str(p[0]), str(p[1]))
        adb("shell", "input", "text", "Settings")
        time.sleep(1.5)
        shot("notif-search")
        check(any("settings" in t.lower() for t, *_ in nodes() if "search" not in t.lower()), "searching finds an installed app")

# ---- no crash anywhere
log = adb("logcat", "-d", "-b", "crash")
check("FATAL EXCEPTION" not in log and PKG not in log.split("FATAL")[-1] if "FATAL" in log else True, "no crash in the log")
if "FATAL" in log:
    print(log[-3000:])

if failures:
    print("\n%d check(s) failed" % len(failures)); sys.exit(1)
print("\nall checks passed")
