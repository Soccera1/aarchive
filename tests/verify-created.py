"""Check writer output with independent decoders, not the app's reader."""
import binascii
import bz2
import ctypes
import ctypes.util
import gzip
import io
import re
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import tarfile
import zipfile
import zlib
import lzma

root = Path(sys.argv[1])
payload = bytearray(b"Archive creation payload.\n" * 6000)
state = 12345
for _ in range(131072):
    state ^= (state << 13) & 0xffffffff
    state ^= state >> 17
    state ^= (state << 5) & 0xffffffff
    payload.append(state & 255)
payload = bytes(payload)
filename = "日本語/hello 🌿.txt"


def command(args, data):
    return subprocess.run(args, input=data, check=True, stdout=subprocess.PIPE).stdout


def snappy_block(data):
    position = 0
    expected = 0
    shift = 0
    while True:
        value = data[position]
        position += 1
        expected |= (value & 127) << shift
        if value < 128:
            break
        shift += 7
    result = bytearray()
    while position < len(data):
        tag = data[position]
        position += 1
        kind, length = tag & 3, tag >> 2
        if kind == 0:
            if length < 60:
                length += 1
            else:
                count = length - 59
                length = int.from_bytes(data[position:position + count], "little") + 1
                position += count
            result.extend(data[position:position + length])
            position += length
        else:
            count = (1, 2, 4)[kind - 1]
            offset = int.from_bytes(data[position:position + count], "little")
            position += count
            if kind == 1:
                offset |= (tag & 224) << 3
                length = 4 + ((tag >> 2) & 7)
            else:
                length += 1
            assert 0 < offset <= len(result)
            for _ in range(length):
                result.append(result[-offset])
    assert len(result) == expected
    return bytes(result)


def decode(codec, data):
    if codec == "none":
        return data
    if codec == "gzip":
        return gzip.decompress(data)
    if codec == "bzip2":
        return bz2.decompress(data)
    if codec in ("xz", "lzma"):
        return lzma.decompress(data)
    if codec in ("zlib", "deflate"):
        return zlib.decompress(data, 15 if codec == "zlib" else -15)
    if codec == "lzip":
        assert data[:5] == b"LZIP\x01"
        code = data[5]
        dictionary = (1 << (code & 31)) - ((1 << (code & 31)) // 16) * (code >> 5)
        result = lzma.decompress(data[6:-20], format=lzma.FORMAT_RAW,
                                 filters=[dict(id=lzma.FILTER_LZMA1, dict_size=dictionary, lc=3, lp=0, pb=2)])
        crc, size, member = struct.unpack("<IQQ", data[-20:])
        assert crc == zlib.crc32(result) and size == len(result) and member == len(data)
        return result
    if codec == "uuencode":
        lines = data.splitlines()
        assert lines[0].startswith(b"begin ") and lines[-1] == b"end"
        return b"".join(binascii.a2b_uu(line) for line in lines[1:-1])
    if codec == "snappy":
        assert data[:10] == b"\xff\x06\0\0sNaPpY"
        position, result = 10, bytearray()
        while position < len(data):
            kind = data[position]
            size = int.from_bytes(data[position + 1:position + 4], "little")
            chunk = data[position + 4:position + 4 + size]
            assert kind in (0, 1) and len(chunk) == size
            decoded = snappy_block(chunk[4:]) if kind == 0 else chunk[4:]
            crc = 0xffffffff
            for value in decoded:
                crc ^= value
                for _ in range(8):
                    crc = (crc >> 1) ^ (0x82f63b78 if crc & 1 else 0)
            crc ^= 0xffffffff
            masked = (((crc >> 15) | (crc << 17)) + 0xa282ead8) & 0xffffffff
            assert int.from_bytes(chunk[:4], "little") == masked
            result.extend(decoded)
            position += 4 + size
        return bytes(result)
    if codec == "lzop":
        assert data[:9] == b"\x89LZO\0\r\n\x1a\n"
        assert int.from_bytes(data[17:21], "big") == 0x0300000d
        header_end = 34 + data[33]
        assert int.from_bytes(data[header_end:header_end + 4], "big") == zlib.adler32(data[9:header_end])
        position, result = header_end + 4, bytearray()
        library = ctypes.CDLL(ctypes.util.find_library("lzo2"))
        library.lzo1x_decompress_safe.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p,
                                                 ctypes.POINTER(ctypes.c_size_t), ctypes.c_void_p]
        while True:
            size = int.from_bytes(data[position:position + 4], "big")
            position += 4
            if size == 0:
                assert position == len(data)
                return bytes(result)
            compressed, checksum = struct.unpack(">II", data[position:position + 8])
            position += 8
            block = data[position:position + compressed]
            position += compressed
            assert len(block) == compressed and compressed <= size
            if compressed < size:
                output = ctypes.create_string_buffer(size)
                length = ctypes.c_size_t(size)
                assert library.lzo1x_decompress_safe(block, len(block), output, ctypes.byref(length), None) == 0
                assert length.value == size
                block = output.raw
            assert zlib.adler32(block) == checksum
            result.extend(block)
    return command({"zstd": ["zstd", "-qdc"], "lz4": ["lz4", "-qdc"],
                    "brotli": ["brotli", "-dc"], "compress": ["uncompress", "-c"]}[codec], data)


count = 0
bsdtar_version = tuple(map(int, re.search(r"bsdtar (\d+)\.(\d+)\.(\d+)",
    command(["bsdtar", "--version"], b"").decode()).groups()))
for line in (root / "created-manifest.tsv").read_text().splitlines():
    name, format, codec = line.split("\t")
    if bsdtar_version < (3, 8, 0) and ((format == "zip" and codec in ("xz", "zstd"))
                                     or (format == "7zip" and codec == "zstd")):
        print("System bsdtar is too old for " + name + "; covered by the native round-trip suite.")
        continue
    print("Independent check " + name, flush=True)
    data = (root / name).read_bytes()
    if format == "raw":
        assert decode(codec, data) == payload, name
        suffix = name[len("created-raw-" + codec):]
        assert decode(codec, (root / ("created-empty" + suffix)).read_bytes()) == b"", name
    elif format == "zip" and codec in ("store", "deflate", "bzip2", "lzma"):
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            assert archive.read(filename) == payload, name
            assert archive.read("empty.txt") == b"", name
            assert archive.getinfo("empty-folder/").is_dir(), name
    else:
        if format in ("tar", "cpio"):
            data = decode(codec, data)
        if format == "tar":
            with tarfile.open(fileobj=io.BytesIO(data)) as archive:
                assert archive.extractfile(filename).read() == payload, name
                assert archive.extractfile("empty.txt").read() == b"", name
                assert archive.getmember("empty-folder").isdir(), name
        else:
            # 7z readers require seeking; give bsdtar a real file rather than stdin.
            with tempfile.NamedTemporaryFile() as source:
                source.write(data)
                source.flush()
                assert command(["bsdtar", "-xOf", source.name, filename], b"") == payload, name
                listing = command(["bsdtar", "-tf", source.name], b"").decode()
            assert "empty-folder/" in listing and "empty.txt" in listing, name
    count += 1
print(f"Independent decoders verified {count} creation combinations and all empty standalone streams.")
