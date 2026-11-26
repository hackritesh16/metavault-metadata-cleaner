"""Exercise the block viewer, SAF export and original-file preservation on API 29."""
import hashlib
import struct
import sys
import tempfile
import time
import zlib
from pathlib import Path
from xml.etree import ElementTree

from motion_smoke import PACKAGE, adb, bounds, shell


def png_chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def select(*labels, tap=False, scroll=False):
    for _ in range(14 if scroll else 5):
        shell("uiautomator", "dump", "/sdcard/block-viewer-window.xml")
        root = ElementTree.fromstring(shell("cat", "/sdcard/block-viewer-window.xml"))
        seen = []
        for node in root.iter("node"):
            text = (node.get("text", "") + "\n" + node.get("content-desc", "")).strip()
            if not text:
                continue
            seen.append(text[:80])
            if not any(label.casefold() in text.casefold().splitlines() for label in labels):
                continue
            if tap:
                x1, y1, x2, y2 = bounds(node)
                shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            return
        if scroll:
            shell("input", "swipe", "240", "680", "240", "340", "450")
        else:
            time.sleep(.3)
    raise AssertionError(f"Could not find {labels!r} on screen: {seen!r}")


def main(apk):
    if shell("getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Run only on a disposable emulator.")
    shell("wm", "size", "480x854")
    shell("wm", "density", "200")
    adb("install", "-r", apk)
    shell("pm", "grant", PACKAGE, "android.permission.READ_EXTERNAL_STORAGE")
    name = "MetaVault-block-test.png"
    source = "/sdcard/Pictures/" + name
    payload = bytes(range(256)) * 256 + b"END"
    header = struct.pack(">IIBBBBB", 1, 1, 8, 0, 0, 0, 0)
    content = (b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", header) + png_chunk(b"tEXt", b"Note\0Hello") +
               png_chunk(b"mvTe", payload) + png_chunk(b"IDAT", zlib.compress(b"\0\0")) + png_chunk(b"IEND", b""))
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / name
        path.write_bytes(content)
        shell("mkdir", "-p", "/sdcard/Pictures", "/sdcard/Download")
        adb("push", str(path), source)
    shell("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://" + source)
    shell("am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    select("Browse", tap=True)
    select("Photos", tap=True)
    select("Refresh", tap=True)
    select(name, tap=True)
    select("View More Data", tap=True, scroll=True)
    select("Advanced Metadata Viewer")
    select("PNG text", tap=True)
    select("Hello")
    select("Copy block as HEX", tap=True, scroll=True)
    select("ASCII", tap=True)
    select("Copy block as ASCII", tap=True)
    shell("input", "keyevent", "4")
    select("Unknown Block", tap=True)
    select("Go to byte…", tap=True)
    select(f"Offset (0–{len(payload) - 1})", tap=True)
    shell("input", "text", "65536")
    select("Go", tap=True)
    select("Bytes 65536–65538")
    select("45 4E 44")
    shell("input", "keyevent", "4")
    select("Unknown Block", tap=True)
    select("Export block", tap=True)
    # DocumentsUI can open at internal storage or a remembered folder on API 29.
    select('Allow access to "Downloads"', "Select", "Use this folder", "Select folder", tap=True)
    try:
        select("Allow", tap=True)
    except AssertionError:
        pass  # Some DocumentsUI versions return immediately after folder selection.
    select(f"Exported {len(payload)} bytes.")
    exported = shell("find", "/sdcard/", "-name", "'metadata-*.bin'").splitlines()
    assert len(exported) == 1, exported
    for path, expected in ((source, content), (exported[0], payload)):
        assert shell("sha256sum", path).split()[0] == hashlib.sha256(expected).hexdigest(), path
    print("PASS block detail, HEX/ASCII copy controls, last page, exact SAF export and original hash")


if __name__ == "__main__":
    try:
        main(sys.argv[1])
    except Exception as error:
        print(f"::error::{error}", flush=True)
        raise
