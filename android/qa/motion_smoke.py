"""Exercise the real APK on a disposable low-resource Android emulator."""
from pathlib import Path
import re
import subprocess
import sys
import time
from xml.etree import ElementTree

PACKAGE = "com.metavault.cleaner"
REPORT = Path("motion-report")


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=35).strip()


def shell(*args):
    return adb("shell", *args)


def bounds(node):
    return list(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))


def locate(label, tap=False):
    for _ in range(5):
        shell("uiautomator", "dump", "/sdcard/motion-window.xml")
        root = ElementTree.fromstring(shell("cat", "/sdcard/motion-window.xml"))
        # Compose may merge the visible title and supporting text into one accessibility node.
        nodes = [n for n in root.iter("node") if any(label in n.get(k, "").splitlines()
                 for k in ("text", "content-desc"))]
        if nodes:
            node = max(nodes, key=lambda n: bounds(n)[1])
            if tap:
                x1, y1, x2, y2 = bounds(node)
                shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            return
        time.sleep(.3)
    raise AssertionError(f"Could not find {label!r}")


def exercise(scale, font):
    shell("settings", "put", "global", "animator_duration_scale", scale)
    shell("settings", "put", "system", "font_scale", font)
    shell("am", "force-stop", PACKAGE)
    shell("am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    locate("Inspect your files")
    shell("dumpsys", "gfxinfo", PACKAGE, "reset")
    locate("Batch clean files", tap=True)
    locate("Batch cleaner")
    locate("Close", tap=True)
    locate("Browse", tap=True)
    locate("Photos", tap=True)
    locate("Refresh")
    shell("input", "keyevent", "4")
    locate("Photos")
    locate("Cleaner", tap=True)
    locate("No file selected")
    locate("History", tap=True)
    locate("Nothing here yet")
    locate("Settings", tap=True)
    locate("Appearance & themes")
    locate("Ocean", tap=True)
    locate("Home", tap=True)
    locate("Inspect your files")
    report = shell("dumpsys", "gfxinfo", PACKAGE)
    (REPORT / f"frames-scale-{scale}.txt").write_text(report)
    print(f"PASS animator scale {scale}, font {font}", flush=True)
    print("\n".join(line for line in report.splitlines() if "frames rendered:" in line or "Janky frames:" in line))


def main():
    if shell("getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("This test changes device settings. Use a disposable emulator.")
    REPORT.mkdir(exist_ok=True)
    print(shell("cat", "/proc/meminfo").splitlines()[0], shell("cat", "/sys/devices/system/cpu/online"), flush=True)
    shell("wm", "size", "480x854")
    shell("wm", "density", "200")
    adb("install", "-r", sys.argv[1])
    exercise("1.0", "1.0")
    # Exercise a different palette in the second pass, too.
    shell("pm", "clear", PACKAGE)
    exercise("0.0", "1.3")


if __name__ == "__main__":
    main()
