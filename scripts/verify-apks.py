#!/usr/bin/env python3
"""Verify actual APK entry, scope, API packaging, release status log lines and the debuggable flag."""
import struct
import subprocess
import sys
import os
from pathlib import Path
from zipfile import ZipFile

def defined_classes(dex):
    u32 = lambda off: struct.unpack_from("<I", dex, off)[0]
    count, offset = u32(56), u32(60)
    strings = []
    for index in range(count):
        pos = u32(offset + index * 4)
        while dex[pos] & 0x80:
            pos += 1
        pos += 1
        strings.append(dex[pos:dex.index(b"\0", pos)].decode("utf-8", errors="replace"))
    type_count, type_offset = u32(64), u32(68)
    types = [strings[u32(type_offset + index * 4)] for index in range(type_count)]
    class_count, class_offset = u32(96), u32(100)
    return {types[u32(class_offset + index * 32)] for index in range(class_count)}

for index, argument in enumerate(sys.argv[1:]):
    path = Path(argument)
    with ZipFile(path) as apk:
        props = dict(line.split("=", 1) for line in apk.read("META-INF/xposed/module.prop").decode().splitlines() if "=" in line)
        assert props["minApiVersion"] == props["targetApiVersion"] == "102"
        assert apk.read("META-INF/xposed/scope.list").decode().strip() == "com.android.systemui"
        entries = apk.read("META-INF/xposed/java_init.list").decode().splitlines()
        dexes = [apk.read(name) for name in apk.namelist() if name.startswith("classes") and name.endswith(".dex")]
        classes = set().union(*(defined_classes(dex) for dex in dexes))
        for entry in entries:
            assert "L" + entry.replace(".", "/") + ";" in classes, "module entry class missing from the APK"
        assert not any(name.startswith("Lio/github/libxposed/") for name in classes), "compileOnly API bundled"
        assert "assets/xposed_init" not in apk.namelist(), "legacy entry remains"
        if index == 1:
            # Release is intentionally not minified (see app/build.gradle.kts) and writes one short status
            # line per SystemUI start, so the LSPosed log proves the module loaded and its hooks installed.
            blob = b"".join(dexes)
            for message in [b"Loaded in SystemUI, module version", b"Legacy wave sampling installed",
                            b"Colorful wave sampling installed", b"Panel gesture hooks installed"]:
                assert message in blob, "release lacks its status log line: " + message.decode()
    sdk = os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"]
    actual = subprocess.check_output([str(Path(sdk) / "cmdline-tools/latest/bin/apkanalyzer"),
                                      "manifest", "debuggable", str(path)], text=True).strip()
    assert actual == ("true" if index == 0 else "false"), (path, actual)
    print(f"PASS {path}: entry, API 102, SystemUI scope, compileOnly API, debuggable={actual}")
