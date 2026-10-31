#!/usr/bin/env python3
"""Validate the recorded hooks against a user-supplied APK. pip install androguard==4.1.4"""
import hashlib
import json
import re
import sys
from pathlib import Path
from zipfile import ZipFile
from loguru import logger
logger.remove()
from androguard.core.dex import DEX

apk = Path(sys.argv[1])
contracts = json.loads((Path(__file__).resolve().parents[1] / "docs/systemui-hook-contracts.json").read_text())
assert hashlib.sha256(apk.read_bytes()).hexdigest() == contracts["apk_sha256"], "APK differs from the inspected firmware; reanalyse behavior before updating the baseline"
pending = dict(contracts["classes"])
with ZipFile(apk) as source:
    for name in source.namelist():
        if not re.fullmatch(r"classes\d*\.dex", name):
            continue
        dex = DEX(source.read(name))
        for cls in dex.get_classes():
            contract = pending.pop(cls.get_name(), None)
            if contract is None:
                continue
            fields = {field.get_name(): field.get_descriptor() for field in cls.get_fields()}
            methods = {(method.get_name(), method.get_descriptor()) for method in cls.get_methods()}
            for field, descriptor in contract["fields"].items():
                assert fields.get(field) == descriptor, (cls.get_name(), field, descriptor)
            for method in contract["methods"]:
                assert (method["name"], method["descriptor"]) in methods, (cls.get_name(), method)
            print("PASS", cls.get_name())
        del dex
assert not pending, list(pending)
print("PASS: all recorded hook classes, methods and fields match the supplied APK")
