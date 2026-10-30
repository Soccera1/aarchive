"""Build a 32-layer fixture with independent encoders, without the app's JNI.

Run: python3 tests/stress_fixture.py OUTPUT_DIRECTORY
"""
import binascii
import bz2
import ctypes
import ctypes.util
import gzip
import hashlib
import io
import json
import lzma
from pathlib import Path
import struct
import subprocess
import sys
import tarfile
import zipfile
import zlib


# Inside out: 20 compression/encoding layers and 12 containers, including 8 TARs.
LAYERS = [
    "zip", "gz", "tar", "bz2", "xz", "tar", "zst", "lz4",
    "tar", "lz", "lzo", "cpio", "br", "tar", "zlib", "deflate",
    "zip", "gz", "tar", "lzma", "Z", "tar", "sz", "uu",
    "7z", "gz", "tar", "snappy", "xz", "tar", "bz2", "zst",
]
PAYLOAD_NAME = "hello.txt"


def payload():
    return b"Hello World!\n"


def tar(data, name):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w", format=tarfile.PAX_FORMAT) as archive:
        folder = tarfile.TarInfo("nested")
        folder.type, folder.mode = tarfile.DIRTYPE, 0o755
        archive.addfile(folder)
        entry = tarfile.TarInfo("nested/" + name)
        entry.size, entry.mode = len(data), 0o644
        archive.addfile(entry, io.BytesIO(data))
    return output.getvalue()


def run(command, data):
    return subprocess.run(command, input=data, check=True, stdout=subprocess.PIPE).stdout


def snappy_literal(data):
    size, prefix = len(data), bytearray()
    while size >= 128:
        prefix.append((size & 127) | 128)
        size >>= 7
    prefix.append(size)
    length = len(data) - 1
    if length < 60:
        tag = bytes([length << 2])
    else:
        count = (length.bit_length() + 7) // 8
        tag = bytes([(59 + count) << 2]) + length.to_bytes(count, "little")
    return bytes(prefix) + tag + data


def masked_crc32c(data):
    crc = 0xffffffff
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ (0x82f63b78 if crc & 1 else 0)
    crc ^= 0xffffffff
    return (((crc >> 15) | (crc << 17)) + 0xa282ead8) & 0xffffffff


def compress(data, codec):
    if codec == "gz": return gzip.compress(data, mtime=0)
    if codec == "bz2": return bz2.compress(data)
    if codec == "xz": return lzma.compress(data)
    if codec == "lzma": return lzma.compress(data, format=lzma.FORMAT_ALONE)
    if codec == "zlib": return zlib.compress(data)
    if codec == "deflate": return zlib.compress(data, wbits=-15)
    if codec == "snappy": return snappy_literal(data)
    if codec == "sz":
        result = bytearray(b"\xff\x06\0\0sNaPpY")
        for offset in range(0, len(data), 65536):
            block = data[offset:offset + 65536]
            body = struct.pack("<I", masked_crc32c(block)) + snappy_literal(block)
            result.extend(b"\0" + len(body).to_bytes(3, "little") + body)
        return bytes(result)
    if codec == "uu":
        return b"begin 644 layer.bin\n" + b"".join(binascii.b2a_uu(data[i:i + 45])
            for i in range(0, len(data), 45)) + b"`\nend\n"
    if codec == "lz":
        body = lzma.compress(data, format=lzma.FORMAT_RAW,
            filters=[dict(id=lzma.FILTER_LZMA1, dict_size=4096, lc=3, lp=0, pb=2)])
        return b"LZIP\x01\x0c" + body + struct.pack("<IQQ", zlib.crc32(data), len(data), 6 + len(body) + 20)
    if codec == "lzo":
        library = ctypes.CDLL(ctypes.util.find_library("lzo2"))
        library.lzo1x_1_compress.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p,
                                           ctypes.POINTER(ctypes.c_size_t), ctypes.c_void_p]
        result = bytearray()
        header = struct.pack(">HHHBBIIIIB", 0x1030, 0x20a0, 0x940, 1, 5, 1, 0x81a4, 0, 0, 0)
        result.extend(b"\x89LZO\0\r\n\x1a\n" + header + struct.pack(">I", zlib.adler32(header)))
        workspace = ctypes.create_string_buffer(1024 * 1024)
        for offset in range(0, len(data), 262144):
            block = data[offset:offset + 262144]
            output = ctypes.create_string_buffer(len(block) + len(block) // 16 + 67)
            size = ctypes.c_size_t()
            assert library.lzo1x_1_compress(block, len(block), output, ctypes.byref(size), workspace) == 0
            body = output.raw[:size.value] if size.value < len(block) else block
            result.extend(struct.pack(">III", len(block), len(body), zlib.adler32(block)) + body)
        return bytes(result) + bytes(4)
    return run({"zst": ["zstd", "-q", "-c"], "lz4": ["lz4", "-q", "-c"],
                "br": ["brotli", "-c"], "Z": ["compress", "-f", "-c"]}[codec], data)


def generate(root):
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    original = payload()
    data, name, layers = original, PAYLOAD_NAME, []
    for index, codec in enumerate(LAYERS, 1):
        input_name = name
        if codec == "zip":
            output = io.BytesIO()
            with zipfile.ZipFile(output, "w") as archive:
                entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.external_attr = 0o100644 << 16
                archive.writestr(entry, data)
            data = output.getvalue()
        elif codec == "tar":
            data = tar(data, name)
        elif codec in ("7z", "cpio"):
            # Read fixed metadata from TAR, rather than filesystem timestamps.
            format = "7zip" if codec == "7z" else "newc"
            data = run(["bsdtar", "--format=" + format, "-cf", "-", "@-"], tar(data, name))
        else:
            data = compress(data, codec)
        name = f"layer-{index:02}.bin" if codec in ("tar", "zip", "7z", "cpio") else name + "." + codec
        layers.append(dict(layer=index, format=codec, input_name=input_name, output_name=name, bytes=len(data)))
    (root / "many-layers.bin").write_bytes(data)
    (root / "stress-payload.bin").write_bytes(original)
    manifest = dict(layer_order="innermost to outermost", layer_count=len(layers),
                    payload_name=PAYLOAD_NAME, payload_bytes=len(original),
                    payload_sha256=hashlib.sha256(original).hexdigest(),
                    fixture_sha256=hashlib.sha256(data).hexdigest(), layers=layers)
    (root / "many-layers.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


if __name__ == "__main__":
    manifest = generate(sys.argv[1])
    print(f"Created {manifest['layer_count']}-layer fixture; payload SHA-256 {manifest['payload_sha256']}")
