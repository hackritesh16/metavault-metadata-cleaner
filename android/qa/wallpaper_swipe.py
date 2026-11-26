"""Run on the motion smoke test's disposable API 29 emulator."""
import csv
import math
import struct
import subprocess
import tempfile
import time
import zlib
from pathlib import Path

from motion_smoke import PACKAGE, REPORT, adb, locate, shell

COLORS = [(245, 192, 65), (76, 180, 215), (124, 61, 94)]


def apply_photo():
    name = "swatch.png"

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    # Three broad horizontal bands allow exact RGB checks away from foreground content.
    pixels = b"".join((b"\0" + bytes(color) * 48) * 30 for color in COLORS)
    with tempfile.TemporaryDirectory() as temp:
        image = Path(temp) / name
        image.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 48, 90, 8, 2, 0, 0, 0))
                          + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b""))
        dest = "/sdcard/Download/" + name
        adb("push", str(image), dest)
        shell("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://" + dest)
    locate("Settings", tap=True)
    for attempt in range(5):
        try:
            locate("Use your own photo", tap=True)
            break
        except AssertionError:
            if attempt == 4:
                raise
            shell("input", "swipe", "240", "670", "240", "370", "420")
    # Recents may omit a newly pushed image. Select the explicit Downloads root.
    from block_viewer_smoke import select
    select("Show roots", tap=True)
    select("Downloads", tap=True)
    select(name, "swatch", tap=True)
    locate("Home", tap=True)


def capture():
    raw = subprocess.check_output(["adb", "exec-out", "screencap"], timeout=10)
    w, h, fmt = struct.unpack_from("<III", raw)
    assert fmt in (1, 2), f"Unexpected screenshot format {fmt}"
    start = len(raw) - 4 * w * h
    assert 12 <= start <= 16
    return lambda x, y: tuple(raw[start + 4 * (y * w + x):start + 4 * (y * w + x) + 3])


def swipe_profile(left, right, y):
    costs = []
    intervals = []
    # Warm each destination, then sample continuous input separately from UIAutomator waits.
    for i in range(4):
        x1, x2 = (right, left) if i % 2 == 0 else (left, right)
        shell("dumpsys", "gfxinfo", PACKAGE, "reset")
        shell("input", "swipe", str(x1), str(y), str(x2), str(y), "900")
        frames = shell("dumpsys", "gfxinfo", PACKAGE, "framestats")
        (REPORT / f"swipe-{i}.txt").write_text(frames)
        for table in frames.split("---PROFILEDATA---")[1::2]:
            rows = csv.DictReader(table.strip().splitlines())
            previous = None
            for row in rows:
                if row.get("Flags") != "0":
                    continue
                vsync, completed = int(row["IntendedVsync"]), int(row["FrameCompleted"])
                if completed <= vsync or completed >= 2**63 - 1:
                    continue
                costs.append((completed - vsync) / 1e6)
                if previous is not None:
                    intervals.append((vsync - previous) / 1e6)
                previous = vsync
        time.sleep(.5)
    assert len(costs) > 30 and intervals, "Insufficient gfxinfo samples"
    def percentile(values, q):
        return sorted(values)[math.ceil(q * len(values)) - 1]
    budget = 1000 / 60
    stats = (f"Swipe profile: {len(costs)} frames, median completion {percentile(costs, .5):.2f} ms, "
             f"p95 {percentile(costs, .95):.2f} ms, {sum(c <= budget for c in costs)}/{len(costs)} within 16.67 ms, "
             f"median vsync interval {percentile(intervals, .5):.2f} ms.")
    (REPORT / "swipe-summary.txt").write_text(stats + "\n")
    print("::notice::" + stats, flush=True)


def check_wallpaper_and_swipes():
    apply_photo()
    locate("Inspect your files")
    pixel = capture()
    for y, expected in zip([150, 430, 650], COLORS):
        actual = pixel(3, y)
        assert max(abs(a - b) for a, b in zip(actual, expected)) <= 2, (y, actual, expected)
    left, top, right, bottom = locate("Home")
    x1, y = (left + right) // 2, (top + bottom) // 2
    left, _, right, _ = locate("Settings")
    x2 = (left + right) // 2
    # Compare an idle and in-flight frame while the touch is still held. The unselected middle
    # of the bar should gain a visible pill even though Home hasn't switched pages yet.
    idle = capture()
    with subprocess.Popen(["adb", "shell", "input", "swipe", str(x1), str(y), str(x2), str(y), "2400"]) as gesture:
        time.sleep(1.2)
        moving = capture()
        assert gesture.poll() is None, "Missed the in-flight drag"
        changed = sum(max(abs(a-b) for a, b in zip(idle(x, j), moving(x, j))) > 12
                      for x in range(175, 305, 3) for j in range(y-8, y+3, 2))
        assert changed > 35, f"Pill did not follow finger ({changed} changed samples)"
        assert gesture.wait(timeout=6) == 0
    locate("Appearance & themes")
    swipe_profile(x1, x2, y)
    locate("Appearance & themes")
    print("PASS original wallpaper RGB, live drag, bidirectional tab switching", flush=True)
