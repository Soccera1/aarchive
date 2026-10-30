"""Verify generated compiler/linker inputs, not just CMake configuration options."""
import json
from pathlib import Path
import re
import shlex
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "app/.cxx/RelWithDebInfo")
expected = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}
verified = set()
for cache in root.glob("*/*/CMakeCache.txt"):
    if "AARCHIVE_OPTIMIZED:BOOL=ON" not in cache.read_text():
        continue
    directory = cache.parent
    abi = directory.name
    if abi not in expected:
        continue
    commands = json.loads((directory / "compile_commands.json").read_text())
    count = 0
    for command in commands:
        if Path(command["file"]).suffix.lower() not in (".c", ".cc", ".cpp", ".cxx"):
            continue
        args = shlex.split(command["command"])
        output = Path(args[args.index("-o") + 1])
        if not output.is_absolute():
            output = Path(command["directory"]) / output
        if not output.is_file():
            continue  # Configured dependency targets not linked into the APK.
        levels = [arg for arg in args if re.fullmatch(r"-O[0-3sgz]", arg)]
        assert levels and levels[-1] == "-O3", (abi, command["file"], levels)
        assert "-flto=thin" in args, (abi, command["file"], "missing ThinLTO")
        with output.open("rb") as compiled:
            assert compiled.read(4) in (b"BC\xc0\xde", b"\xde\xc0\x17\x0b"), (abi, str(output), "not LLVM bitcode")
        count += 1
    assert count > 0, (abi, "no compiled targets")
    ninja = (directory / "build.ninja").read_text()
    link = re.search(r"^build [^\n]*libaarchive\.so:[^\n]*\n(?:[ \t]+[^\n]*\n)*", ninja, re.MULTILINE)
    assert link and "-flto=thin" in link.group() and "-fuse-ld=lld" in link.group(), (abi, "missing LTO/lld at final link")
    verified.add(abi)
    print(f"{abi}: {count} C/C++ inputs use -O3 and LLVM bitcode; final link uses ThinLTO/lld")
assert verified == expected, ("Missing optimized ABIs", expected - verified)
