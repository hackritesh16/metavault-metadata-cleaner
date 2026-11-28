"""Drive the real Cleaner, file picker, foreground transform, review and export on API 29."""
import hashlib
import struct
import tempfile
import zlib
from pathlib import Path

from block_viewer_smoke import png_chunk, select
from motion_smoke import PACKAGE, adb, shell


def run():
    if shell("getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Use a disposable emulator.")
    shell("wm", "size", "480x854")
    shell("wm", "density", "200")
    shell("mkdir", "-p", "/sdcard/Download")
    name = "resize-sample.png"
    source = "/sdcard/Download/" + name
    header = struct.pack(">IIBBBBB", 80, 60, 8, 2, 0, 0, 0)
    fixture = (b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", header)
               + png_chunk(b"IDAT", zlib.compress((b"\0" + bytes([40, 160, 210]) * 80) * 60))
               + png_chunk(b"IEND", b""))
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / name
        path.write_bytes(fixture)
        adb("push", str(path), source)
        shell("am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
        select("Cleaner", tap=True)
        select("Resize, compress or convert", tap=True)
        select("Choose files", tap=True)
        select("Show roots", tap=True)
        select("Downloads", tap=True)
        select(name, "resize-sample", tap=True, document_name=True)
        select("Prepare preview", tap=True, scroll=True)
        select("Save options")
        select("Original")
        select("Final")
        select("Save as new in folder (1)", tap=True, scroll=True)
        select('Allow access to "Downloads"', "Select", "Use this folder", "Select folder", tap=True)
        try:
            select("Allow", tap=True)
        except AssertionError:
            pass
        select("1 saved · 0 errors")
        dest = Path(directory) / "result.jpg"
        adb("pull", "/sdcard/Download/resize-sample_export.jpg", str(dest))
        assert dest.read_bytes().startswith(b"\xff\xd8\xff"), "Expected a real JPEG"
        assert shell("sha256sum", source).split()[0] == hashlib.sha256(fixture).hexdigest()
        shell("am", "force-stop", PACKAGE)
    print("PASS SAF selection, foreground processing, review, JPEG export and original hash")


if __name__ == "__main__":
    try:
        run()
    except Exception as error:
        print(f"::error::{error}", flush=True)
        raise
