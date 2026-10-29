"""Generate fixtures with Python and bsdtar (or 7z), not the JNI bridge under test."""
import io
import bz2
import binascii
import ctypes
import ctypes.util
import gzip
import lzma
import pathlib
import shutil
import subprocess
import sys
import tarfile
import zipfile
import zlib
import struct

root = pathlib.Path(sys.argv[1])
path = "日本語/hello 🌿.txt"
payload = b"Hello from libarchive!\n"
with zipfile.ZipFile(root / "sample.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    archive.writestr(path, payload)
for suffix, mode in [("tar.gz", "w:gz"), ("tar.xz", "w:xz")]:
    with tarfile.open(root / f"sample.{suffix}", mode) as archive:
        entry = tarfile.TarInfo(path)
        entry.size = len(payload)
        archive.addfile(entry, io.BytesIO(payload))
with tarfile.open(root / "links.tar", "w") as archive:
    for kind in [tarfile.SYMTYPE, tarfile.LNKTYPE]:
        entry = tarfile.TarInfo("link")
        entry.type = kind
        entry.linkname = "../outside"
        archive.addfile(entry)
(root / "broken.zip").write_text("This is not an archive")
(root / "secret.txt").write_bytes(payload)
subprocess.run(["zip", "-q", "-P", "open-sesame", "secret.zip", "secret.txt"], cwd=root, check=True)
for variant in ["aes128", "aes256", "aes256-stored"]:
    lines = (pathlib.Path(__file__).parent / "fixtures" / (variant + ".zip.uu")).read_bytes().splitlines()
    (root / (variant + ".zip")).write_bytes(b"".join(binascii.a2b_uu(line) for line in lines[1:] if line != b"end"))
(root / path).parent.mkdir(parents=True)
(root / path).write_bytes(payload)
if shutil.which("bsdtar"):
    subprocess.run(["bsdtar", "--format=7zip", "-cf", "sample.7z", path], cwd=root, check=True)
else:
    subprocess.run(["7z", "a", "sample.7z", path], cwd=root, check=True, stdout=subprocess.DEVNULL)

# Minimal XAR with an independently constructed zlib-compressed XML TOC.
toc = (f'<?xml version="1.0" encoding="UTF-8"?><xar><toc><file id="1">'
       f'<name>日本語</name><type>directory</type><file id="2"><name>hello 🌿.txt</name>'
       f'<type>file</type><mode>0644</mode><data><length>{len(payload)}</length><offset>0</offset>'
       f'<size>{len(payload)}</size><encoding style="application/octet-stream"/></data>'
       f'</file></file></toc></xar>').encode()
compressed_toc = zlib.compress(toc)
(root / "sample.xar").write_bytes(struct.pack(">IHHQQI", 0x78617221, 28, 1, len(compressed_toc), len(toc), 0)
                                   + compressed_toc + payload)

# Independently produced standalone streams, larger than a decoder output block.
plain = payload * 6000
(root / "report.csv").write_bytes(plain)
encoded = {
    "gz": gzip.compress(plain), "bz2": bz2.compress(plain),
    "xz": lzma.compress(plain), "lzma": lzma.compress(plain, format=lzma.FORMAT_ALONE),
    "zlib": zlib.compress(plain), "deflate": zlib.compress(plain, wbits=-15),
}
for suffix, command in {
    "zst": ["zstd", "-q", "-c"], "lz4": ["lz4", "-q", "-c"],
    "br": ["brotli", "-c"], "Z": ["compress", "-f", "-c"],
}.items():
    encoded[suffix] = subprocess.run(command, input=plain, check=True, stdout=subprocess.PIPE).stdout

# Lzip v1: fixed header, raw LZMA1 payload, CRC32 / size / member-size trailer.
lz = lzma.compress(plain, format=lzma.FORMAT_RAW,
                   filters=[{"id": lzma.FILTER_LZMA1, "dict_size": 4096, "lc": 3, "lp": 0, "pb": 2}])
encoded["lz"] = b"LZIP\x01\x0c" + lz + struct.pack("<IQQ", zlib.crc32(plain), len(plain), 6 + len(lz) + 20)

# LZOP fixture uses the independent system liblzo encoder, not the bundled LZOKay.
lzo = ctypes.CDLL(ctypes.util.find_library("lzo2"))
lzo.lzo1x_1_compress.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p,
                                ctypes.POINTER(ctypes.c_size_t), ctypes.c_void_p]
compressed = ctypes.create_string_buffer(len(plain) + len(plain) // 16 + 67)
size = ctypes.c_size_t()
workspace = ctypes.create_string_buffer(1024 * 1024)
assert lzo.lzo1x_1_compress(plain, len(plain), compressed, ctypes.byref(size), workspace) == 0
header = struct.pack(">HHHBBIIIIB", 0x1030, 0x20a, 0x940, 1, 5, 3, 0x81a4, 0, 0, 0)
encoded["lzo"] = (b"\x89LZO\x00\r\n\x1a\n" + header + struct.pack(">I", zlib.adler32(header))
                  + struct.pack(">IIII", len(plain), size.value, zlib.adler32(plain), zlib.adler32(compressed.raw[:size.value]))
                  + compressed.raw[:size.value] + bytes(4))

def crc32c(data):
    crc = 0xffffffff
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ (0x82f63b78 if crc & 1 else 0)
    crc ^= 0xffffffff
    return (((crc >> 15) | (crc << 17)) + 0xa282ead8) & 0xffffffff

def varint(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    return bytes(result) + bytes([value])

def snappy_literal(data):
    length = len(data) - 1
    if length < 60:
        tag = bytes([length << 2])
    else:
        count = max(1, (length.bit_length() + 7) // 8)
        tag = bytes([(59 + count) << 2]) + length.to_bytes(count, "little")
    return varint(len(data)) + tag + data

magic = b"\xff\x06\x00\x00sNaPpY"
framed = bytearray(magic)
for i, offset in enumerate(range(0, len(plain), 65536)):
    data = plain[offset:offset + 65536]
    kind = i % 2
    body = struct.pack("<I", crc32c(data)) + (snappy_literal(data) if kind == 0 else data)
    framed.extend(bytes([kind]) + len(body).to_bytes(3, "little") + body)
    framed.extend(b"\x80\x03\x00\x00pad" + magic) # Skippable metadata and concatenated streams.
encoded["sz"] = bytes(framed)
encoded["snappy"] = snappy_literal(plain)
encoded["uu"] = b"begin 644 report.csv\n" + b"".join(
    binascii.b2a_uu(plain[i:i + 45]) for i in range(0, len(plain), 45)) + b"`\nend\n"
for suffix, data in encoded.items():
    (root / ("report.csv." + suffix)).write_bytes(data)

# Container recognition still wins over raw-file fallback, including extra codecs.
with tarfile.open(root / "sample.tar", "w") as archive:
    entry = tarfile.TarInfo(path)
    entry.size = len(payload)
    archive.addfile(entry, io.BytesIO(payload))
tar = (root / "sample.tar").read_bytes()
for suffix, data in {
    "bz2": bz2.compress(tar),
    "zst": subprocess.run(["zstd", "-q", "-c"], input=tar, check=True, stdout=subprocess.PIPE).stdout,
    "lz4": subprocess.run(["lz4", "-q", "-c"], input=tar, check=True, stdout=subprocess.PIPE).stdout,
    "br": subprocess.run(["brotli", "-c"], input=tar, check=True, stdout=subprocess.PIPE).stdout,
}.items():
    (root / ("sample.tar." + suffix)).write_bytes(data)

(root / "empty.gz").write_bytes(gzip.compress(b""))
(root / "empty.br").write_bytes(subprocess.run(["brotli", "-c"], input=b"", check=True, stdout=subprocess.PIPE).stdout)
(root / "empty.sz").write_bytes(magic)
(root / "concatenated.gz").write_bytes(gzip.compress(plain[:70000]) + gzip.compress(plain[70000:]))
(root / "nested.gz.xz").write_bytes(lzma.compress(gzip.compress(plain)))
(root / "outer.br.gz").write_bytes(gzip.compress(encoded["br"]))
(root / "opaque.bin").write_bytes(encoded["zlib"])
csv = b"name,value\nalpha,42\nbeta,73\n"
(root / "table.csv.gz").write_bytes(gzip.compress(csv))
(root / "table.csv.br").write_bytes(subprocess.run(["brotli", "-c"], input=csv, check=True, stdout=subprocess.PIPE).stdout)
(root / "table.csv.zst").write_bytes(subprocess.run(["zstd", "-q", "-c"], input=csv, check=True, stdout=subprocess.PIPE).stdout)
for suffix in ["gz", "bz2", "xz", "zst", "lz4", "br", "sz", "zlib", "deflate"]:
    data = encoded[suffix]
    (root / ("truncated." + suffix)).write_bytes(data[:len(data) // 2])
bad_gzip = bytearray(encoded["gz"])
bad_gzip[-8] ^= 0x40
(root / "bad-checksum.gz").write_bytes(bad_gzip)
bad_snappy = bytearray(encoded["sz"])
bad_snappy[14] ^= 1
(root / "bad-checksum.sz").write_bytes(bad_snappy)
(root / "reserved.sz").write_bytes(magic + b"\x02\x00\x00\x00")
