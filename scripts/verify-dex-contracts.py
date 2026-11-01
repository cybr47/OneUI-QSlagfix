#!/usr/bin/env python3
"""Check docs/systemui-hook-contracts.json against raw classes*.dex files.

Standard library only: needs neither androguard nor the APK. Unlike verify-systemui-contracts.py it also
checks that every field listed under "static_fields" really is static (the module reads those without an
instance, so an instance field would silently break the gesture).

usage: python3 scripts/verify-dex-contracts.py classes.dex classes2.dex classes3.dex ...
"""
import json
import struct
import sys
from pathlib import Path

ACC_STATIC = 0x8


def uleb(data, pos):
    result = shift = 0
    while True:
        byte = data[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        shift += 7
        if not byte & 0x80:
            return result, pos


class Dex:
    def __init__(self, path):
        self.data = data = Path(path).read_bytes()
        (self.n_str, self.str_off, self.n_type, self.type_off, self.n_proto, self.proto_off,
         self.n_field, self.field_off, self.n_meth, self.meth_off,
         self.n_class, self.class_off) = struct.unpack_from("<12I", data, 0x38)
        self.strings = {}

    def string(self, index):
        if index not in self.strings:
            offset = struct.unpack_from("<I", self.data, self.str_off + 4 * index)[0]
            _, pos = uleb(self.data, offset)
            self.strings[index] = self.data[pos:self.data.index(b"\0", pos)].decode("utf-8", "replace")
        return self.strings[index]

    def type(self, index):
        return self.string(struct.unpack_from("<I", self.data, self.type_off + 4 * index)[0])

    def proto(self, index):
        _, ret, params_off = struct.unpack_from("<3I", self.data, self.proto_off + 12 * index)
        params = []
        if params_off:
            count = struct.unpack_from("<I", self.data, params_off)[0]
            params = [self.type(struct.unpack_from("<H", self.data, params_off + 4 + 2 * i)[0])
                      for i in range(count)]
        return "(" + "".join(params) + ")" + self.type(ret)

    def field(self, index):
        _, type_index, name = struct.unpack_from("<HHI", self.data, self.field_off + 8 * index)
        return self.string(name), self.type(type_index)

    def method(self, index):
        _, proto, name = struct.unpack_from("<HHI", self.data, self.meth_off + 8 * index)
        return self.string(name), self.proto(proto)

    def classes(self):
        """Yield (descriptor, {field name: (descriptor, flags)}, {(method name, descriptor)})."""
        for i in range(self.n_class):
            class_idx, _, _, _, _, _, data_off, _ = struct.unpack_from("<8I", self.data, self.class_off + 32 * i)
            fields, methods = {}, set()
            if data_off:
                pos = data_off
                sizes = []
                for _ in range(4):
                    value, pos = uleb(self.data, pos)
                    sizes.append(value)
                for group in (sizes[0], sizes[1]):  # static fields, then instance fields
                    index = 0
                    for _ in range(group):
                        diff, pos = uleb(self.data, pos)
                        flags, pos = uleb(self.data, pos)
                        index += diff
                        name, descriptor = self.field(index)
                        fields[name] = (descriptor, flags)
                for group in (sizes[2], sizes[3]):  # direct methods, then virtual methods
                    index = 0
                    for _ in range(group):
                        diff, pos = uleb(self.data, pos)
                        _, pos = uleb(self.data, pos)
                        _, pos = uleb(self.data, pos)
                        index += diff
                        methods.add(self.method(index))
            yield self.type(class_idx), fields, methods


def main(paths):
    contracts = json.loads((Path(__file__).resolve().parents[1] / "docs/systemui-hook-contracts.json").read_text())
    pending = dict(contracts["classes"])
    problems = []
    for path in paths:
        for descriptor, fields, methods in Dex(path).classes():
            contract = pending.pop(descriptor, None)
            if contract is None:
                continue
            for name, expected in contract["fields"].items():
                actual = fields.get(name)
                if actual is None or actual[0] != expected:
                    problems.append(f"{descriptor} field {name}: expected {expected}, found {actual and actual[0]}")
            for name in contract.get("static_fields", []):
                actual = fields.get(name)
                if actual is None or not actual[1] & ACC_STATIC:
                    problems.append(f"{descriptor} field {name} must be static")
            for method in contract["methods"]:
                wanted = (method["name"], method["descriptor"].replace(" ", ""))
                if wanted not in methods:
                    problems.append(f"{descriptor} missing method {wanted[0]}{wanted[1]}")
            print(("FAIL" if any(descriptor in p for p in problems) else "PASS"), descriptor)
    problems += [f"class not found in the supplied dex files: {name}" for name in pending]
    for problem in problems:
        print("PROBLEM:", problem)
    if problems:
        sys.exit(1)
    print("PASS: every recorded class, field, method and static flag matches the supplied dex files")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
