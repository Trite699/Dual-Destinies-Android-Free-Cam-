#!/usr/bin/env python3
"""
Non-root Freecam patcher.

Repackages your own copy of Phoenix Wright: Ace Attorney - Dual Destinies so that
Frida's *gadget* loads inside the game at startup and runs hook.js.  No root, no
Shizuku, no frida-inject needed.  The Freecam Loader app (overlay) then talks to the
hook over 127.0.0.1:27042 exactly as before.

How it works
  1. Finds lib/<abi>/libGS5_Android.so in your APK(s) (handles split APKs too).
  2. Uses LIEF to add libfridagadget.so as a DT_NEEDED dependency of that library, so the
     Android linker loads the gadget before the game code runs.  (No smali/apktool.)
  3. Adds the gadget, its config and hook.js (as libfridahook.so) to lib/<abi>/.
  4. Strips the old signature, zipaligns and re-signs ALL apks with one key.
  5. Optionally installs with adb.

Requirements (on your PC): python3, `pip install lief`, java (JRE 11+), adb (only for --install).

    python patch_apk.py base.apk                       # single APK
    python patch_apk.py ./game_apks/                   # folder of split APKs
    python patch_apk.py ./game_apks/ --install         # also uninstall + install-multiple

Get your APKs off the phone (the game must be installed from your own account):
    adb shell pm path jp.co.capcom.gyakusai5en
    adb pull /data/app/.../base.apk game_apks/        # repeat for each path printed

NOTE: the re-signed app has a different signature than the Play Store one, so the original
must be uninstalled first (this wipes its local data).  Back up saves first if you care.
"""
import argparse
import hashlib
import lzma
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

FRIDA_VERSION = "16.4.8"          # hook.js is written against Frida 16.x APIs
GAME_LIB = "libGS5_Android.so"
DEFAULT_PACKAGE = "jp.co.capcom.gyakusai5en"
GADGET_NAME = "libfridagadget.so"
GADGET_CONFIG_NAME = "libfridagadget.config.so"   # gadget looks for <its name minus .so>.config.so
HOOK_NAME = "libfridahook.so"                      # .js disguised as .so so the installer keeps it
SIGNER_VERSION = "1.3.0"

ABI_TO_FRIDA = {"arm64-v8a": "arm64", "armeabi-v7a": "arm"}

HERE = Path(__file__).resolve().parent
DEFAULT_HOOK = HERE.parent / "app" / "src" / "main" / "assets" / "hook.js"
CACHE = Path(os.environ.get("FREECAM_CACHE", Path.home() / ".cache" / "freecam-patcher"))


def die(msg):
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(1)


def download(url, dest):
    if dest.exists():
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"  downloading {url}")
    tmp = dest.with_suffix(dest.suffix + ".part")
    with urllib.request.urlopen(url) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f)
    tmp.rename(dest)
    return dest


def get_gadget(abi):
    arch = ABI_TO_FRIDA[abi]
    out = CACHE / f"frida-gadget-{FRIDA_VERSION}-android-{arch}.so"
    if out.exists():
        return out.read_bytes()
    url = (f"https://github.com/frida/frida/releases/download/{FRIDA_VERSION}/"
           f"frida-gadget-{FRIDA_VERSION}-android-{arch}.so.xz")
    xz = download(url, CACHE / (out.name + ".xz"))
    data = lzma.decompress(xz.read_bytes())
    out.write_bytes(data)
    return data


def get_signer_jar():
    url = (f"https://github.com/patrickfav/uber-apk-signer/releases/download/"
           f"v{SIGNER_VERSION}/uber-apk-signer-{SIGNER_VERSION}.jar")
    return download(url, CACHE / f"uber-apk-signer-{SIGNER_VERSION}.jar")


def add_needed(lib_bytes, needed_name):
    """Return lib_bytes with `needed_name` added as a DT_NEEDED entry."""
    try:
        import lief
    except ImportError:
        die("missing dependency: run `pip install lief`")
    with tempfile.TemporaryDirectory() as td:
        src = Path(td) / "in.so"
        dst = Path(td) / "out.so"
        src.write_bytes(lib_bytes)
        binary = lief.ELF.parse(str(src))
        if binary is None:
            die(f"could not parse {GAME_LIB} as ELF")
        if any(getattr(e, "name", None) == needed_name for e in binary.libraries) or \
                needed_name in [str(x) for x in binary.libraries]:
            print("  library already patched, leaving as is")
            return lib_bytes
        binary.add_library(needed_name)
        binary.write(str(dst))
        return dst.read_bytes()


def collect_apks(path):
    p = Path(path)
    if p.is_file():
        return [p]
    if p.is_dir():
        apks = sorted(p.glob("*.apk"))
        if apks:
            return apks
    die(f"no .apk found at {path}")


def find_game_libs(apks):
    """-> {apk_path: [abi, ...]} for apks containing GAME_LIB."""
    found = {}
    for apk in apks:
        with zipfile.ZipFile(apk) as z:
            abis = []
            for abi in ABI_TO_FRIDA:
                if f"lib/{abi}/{GAME_LIB}" in z.namelist():
                    abis.append(abi)
            if abis:
                found[apk] = abis
    return found


def is_signature_entry(name):
    return bool(re.match(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)$", name, re.I)) or \
        name.upper() == "META-INF/MANIFEST.MF"


def patch_apk(src, dst, abis, hook_bytes):
    gadget_config = (
        b'{\n'
        b'  "interaction": {\n'
        b'    "type": "script",\n'
        b'    "path": "' + HOOK_NAME.encode() + b'"\n'
        b'  }\n'
        b'}\n'
    )
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
        replaced = {f"lib/{abi}/{GAME_LIB}" for abi in abis}
        added = {}
        for abi in abis:
            base = f"lib/{abi}/"
            added[base + GADGET_NAME] = get_gadget(abi)
            added[base + GADGET_CONFIG_NAME] = gadget_config
            added[base + HOOK_NAME] = hook_bytes

        for item in zin.infolist():
            if is_signature_entry(item.filename):
                continue
            if item.filename in replaced:
                print(f"  patching {item.filename}")
                data = add_needed(zin.read(item.filename), GADGET_NAME)
                zi = zipfile.ZipInfo(item.filename, item.date_time)
                zi.compress_type = zipfile.ZIP_STORED     # must stay mmap-able / page aligned
                zout.writestr(zi, data)
            elif item.filename in added:
                continue  # we'll write our own
            else:
                # copy raw, preserving original compression
                zi = zipfile.ZipInfo(item.filename, item.date_time)
                zi.compress_type = item.compress_type
                zi.external_attr = item.external_attr
                zout.writestr(zi, zin.read(item.filename))

        for name, data in added.items():
            print(f"  adding  {name} ({len(data):,} bytes)")
            zi = zipfile.ZipInfo(name, (2024, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_STORED
            zout.writestr(zi, data)


def sign_all(apks, out_dir):
    jar = get_signer_jar()
    out_dir.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp())
    for a in apks:
        shutil.copy(a, work / a.name)
    cmd = ["java", "-jar", str(jar), "-a", str(work), "-o", str(out_dir),
           "--allowResign"]
    print("  signing:", " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout, r.stderr)
        die("signing failed (is Java installed?)")
    # uber-apk-signer names outputs <name>-aligned-debugSigned.apk
    signed = sorted(out_dir.glob("*-aligned-debugSigned.apk"))
    final = []
    for s in signed:
        target = out_dir / s.name.replace("-aligned-debugSigned", "-freecam")
        s.replace(target)
        final.append(target)
    for junk in out_dir.glob("*.idsig"):
        junk.unlink()
    shutil.rmtree(work, ignore_errors=True)
    return final


def adb(*args, check=True):
    r = subprocess.run(["adb", *args], capture_output=True, text=True)
    out = (r.stdout + r.stderr).strip()
    if check and r.returncode != 0:
        die(f"adb {' '.join(args)} failed:\n{out}")
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("apk", help="a single .apk, or a folder holding base.apk + split apks")
    ap.add_argument("-o", "--out", default="freecam_out", help="output folder (default: freecam_out)")
    ap.add_argument("--hook", default=str(DEFAULT_HOOK), help="path to hook.js")
    ap.add_argument("--package", default=DEFAULT_PACKAGE, help="package name (for --install)")
    ap.add_argument("--install", action="store_true",
                    help="adb uninstall the original (WIPES its data) and install the patched build")
    args = ap.parse_args()

    hook = Path(args.hook)
    if not hook.is_file():
        die(f"hook.js not found at {hook}")
    hook_bytes = hook.read_bytes()

    apks = collect_apks(args.apk)
    game = find_game_libs(apks)
    if not game:
        die(f"none of the APKs contain lib/<abi>/{GAME_LIB}. Wrong game, or the native lib "
            f"is in a split you haven't pulled (look for split_config.arm64_v8a.apk).")
    print(f"Found {GAME_LIB} in: " + ", ".join(f"{a.name} {v}" for a, v in game.items()))

    out_dir = Path(args.out)
    stage = Path(tempfile.mkdtemp())
    staged = []
    for apk in apks:
        dst = stage / apk.name
        if apk in game:
            print(f"Patching {apk.name}")
            patch_apk(apk, dst, game[apk], hook_bytes)
        else:
            # untouched, but must be re-signed with the same key as the rest
            with zipfile.ZipFile(apk) as zin, zipfile.ZipFile(dst, "w") as zout:
                for item in zin.infolist():
                    if is_signature_entry(item.filename):
                        continue
                    zi = zipfile.ZipInfo(item.filename, item.date_time)
                    zi.compress_type = item.compress_type
                    zi.external_attr = item.external_attr
                    zout.writestr(zi, zin.read(item.filename))
        staged.append(dst)

    final = sign_all(staged, out_dir)
    shutil.rmtree(stage, ignore_errors=True)
    print("\nDone. Output:")
    for f in final:
        print("  ", f)

    if args.install:
        print(f"\nUninstalling original {args.package} (this deletes its local data)...")
        print(adb("uninstall", args.package, check=False))
        print(adb("install-multiple", "-r", *map(str, final)))
        print("Installed. Launch the game, then use the Freecam Loader app overlay.")
    else:
        print("\nInstall with:\n  adb uninstall " + args.package +
              "\n  adb install-multiple -r " + " ".join(f'"{f}"' for f in final))


if __name__ == "__main__":
    main()
