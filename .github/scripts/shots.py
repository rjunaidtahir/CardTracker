#!/usr/bin/env python3
"""Fills the debug app with made-up demo data and captures the main screens in every theme (raw, uncaptioned)."""
import subprocess, time, os, re
import xml.etree.ElementTree as ET

PKG = "com.filsspend.tracker"
ACT = PKG + "/com.uaefinancial.tracker.ui.MainActivity"
SEED = PKG + "/com.uaefinancial.tracker.debug.DemoSeed"
THEMES = ["neon", "ocean", "royal", "amoled", "light", "sand"]
os.makedirs("raw", exist_ok=True)

def adb(*a):
    return subprocess.run(["adb", *a], capture_output=True, text=True).stdout

def shot(name):
    with open(f"raw/{name}.png", "wb") as fh:
        fh.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)

def find(text):
    adb("shell", "uiautomator", "dump", "/sdcard/u.xml")
    try:
        root = ET.fromstring(adb("exec-out", "cat", "/sdcard/u.xml"))
    except ET.ParseError:
        return None
    for e in root.iter("node"):
        t = (e.get("text") or "") or (e.get("content-desc") or "")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", e.get("bounds") or "")
        if m and t == text:
            x1, y1, x2, y2 = map(int, m.groups())
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None

def tap(text):
    for _ in range(6):
        p = find(text)
        if p:
            adb("shell", "input", "tap", str(p[0]), str(p[1])); time.sleep(2.2); return True
        time.sleep(1)
    return False

def swipe_up(px=1100):
    adb("shell", "input", "swipe", "540", str(1900), "540", str(1900 - px), "500"); time.sleep(3)

def swipe_down(times=6):
    for _ in range(times):
        adb("shell", "input", "swipe", "540", "700", "540", "2000", "250"); time.sleep(0.4)
    time.sleep(1)

# tidy status bar: 9:41, full battery, wifi, no notification icons
adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
for cmd in (["-e", "command", "enter"], ["-e", "command", "clock", "-e", "hhmm", "0941"],
            ["-e", "command", "battery", "-e", "level", "100", "-e", "plugged", "false"],
            ["-e", "command", "network", "-e", "wifi", "show", "-e", "level", "4"],
            ["-e", "command", "network", "-e", "mobile", "show", "-e", "datatype", "none", "-e", "level", "4"],
            ["-e", "command", "notifications", "-e", "visible", "false"]):
    adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", *cmd)
for p in ("READ_SMS", "RECEIVE_SMS", "POST_NOTIFICATIONS"):
    adb("shell", "pm", "grant", PKG, "android.permission." + p)

adb("shell", "settings", "put", "global", "hide_error_dialogs", "1")
adb("shell", "am", "force-stop", PKG)
first = True
for th in THEMES:
    extra = ["--ez", "seed", "true"] if first else []
    print(adb("shell", "am", "broadcast", "-n", SEED, "--es", "theme", th, *extra).strip()[:80])
    if first:
        time.sleep(60)  # reading ~600 messages through the real pipeline
        first = False
    else:
        time.sleep(4)
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", ACT); time.sleep(6)
    # dismiss anything that sits on top on a fresh start
    for s in ("No thanks", "Wait", "Not now", "Skip", "OK", "Got it", "Later"):
        find(s) and tap(s)
    tap("Home"); swipe_down(); time.sleep(2)
    shot(f"{th}-home-1")
    swipe_up(); shot(f"{th}-home-2")
    swipe_up(); shot(f"{th}-home-3")
    swipe_up(); shot(f"{th}-home-4")
    swipe_up(); shot(f"{th}-home-5")
    for tab in ("Activity", "Cards", "More"):
        if tap(tab):
            shot(f"{th}-{tab.lower()}")
            if tab == "Activity":
                swipe_up(900); shot(f"{th}-activity-2")
print("done", len(os.listdir("raw")))
