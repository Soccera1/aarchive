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
from stress_fixture import generate as generate_stress_fixture

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

# ZIP/7z need seekable input after their outer compression has been removed.
# Produce both stored and internally compressed containers independently.
with zipfile.ZipFile(root / "stored.zip", "w", zipfile.ZIP_STORED) as archive:
    archive.writestr(path, payload)
with zipfile.ZipFile(root / "empty.zip", "w"):
    pass
if shutil.which("bsdtar"):
    subprocess.run(["bsdtar", "--format=7zip", "--options=7zip:compression=store",
                    "-cf", "stored.7z", path], cwd=root, check=True)
else:
    subprocess.run(["7z", "a", "-mx=0", "stored.7z", path], cwd=root, check=True, stdout=subprocess.DEVNULL)

def wrap(data, suffix):
    if suffix == "gz": return gzip.compress(data)
    if suffix == "bz2": return bz2.compress(data)
    if suffix == "xz": return lzma.compress(data)
    if suffix == "lzma": return lzma.compress(data, format=lzma.FORMAT_ALONE)
    if suffix == "zlib": return zlib.compress(data)
    if suffix == "deflate": return zlib.compress(data, wbits=-15)
    if suffix == "snappy": return snappy_literal(data)
    if suffix == "sz":
        framed = bytearray(magic)
        for offset in range(0, len(data), 65536):
            chunk = data[offset:offset + 65536]
            body = struct.pack("<I", crc32c(chunk)) + snappy_literal(chunk)
            framed.extend(b"\0" + len(body).to_bytes(3, "little") + body)
        return bytes(framed)
    if suffix == "uu":
        return b"begin 644 archive\n" + b"".join(binascii.b2a_uu(data[i:i + 45])
            for i in range(0, len(data), 45)) + b"`\nend\n"
    if suffix == "lz":
        body = lzma.compress(data, format=lzma.FORMAT_RAW,
            filters=[dict(id=lzma.FILTER_LZMA1, dict_size=4096, lc=3, lp=0, pb=2)])
        return b"LZIP\x01\x0c" + body + struct.pack("<IQQ", zlib.crc32(data), len(data), 6 + len(body) + 20)
    if suffix == "lzo":
        output = ctypes.create_string_buffer(len(data) + len(data) // 16 + 67)
        size = ctypes.c_size_t()
        assert lzo.lzo1x_1_compress(data, len(data), output, ctypes.byref(size), workspace) == 0
        block = output.raw[:size.value] if size.value < len(data) else data
        # Data checksum only; stored blocks have no compressed-data checksum.
        block_header = struct.pack(">III", len(data), len(block), zlib.adler32(data))
        lzo_header = struct.pack(">HHHBBIIIIB", 0x1030, 0x20a0, 0x940, 1, 5, 1, 0x81a4, 0, 0, 0)
        return (b"\x89LZO\0\r\n\x1a\n" + lzo_header + struct.pack(">I", zlib.adler32(lzo_header))
                + block_header + block + bytes(4))
    command = {"zst": ["zstd", "-q", "-c"], "lz4": ["lz4", "-q", "-c"],
               "br": ["brotli", "-c"], "Z": ["compress", "-f", "-c"]}[suffix]
    return subprocess.run(command, input=data, check=True, stdout=subprocess.PIPE).stdout

for container in ["sample.zip", "stored.zip", "sample.7z", "stored.7z"]:
    data = (root / container).read_bytes()
    for suffix in encoded:
        (root / (container + "." + suffix)).write_bytes(wrap(data, suffix))
for container in ["secret.zip", "aes128.zip", "aes256.zip", "aes256-stored.zip", "empty.zip"]:
    (root / (container + ".gz")).write_bytes(wrap((root / container).read_bytes(), "gz"))
(root / "sample.7z.gz.xz").write_bytes(wrap(wrap((root / "sample.7z").read_bytes(), "gz"), "xz"))
(root / "sample.zip.gz.br").write_bytes(wrap(wrap((root / "sample.zip").read_bytes(), "gz"), "br"))
bad_wrapper = bytearray(wrap((root / "sample.7z").read_bytes(), "gz"))
bad_wrapper[-8] ^= 0x40
(root / "bad-wrapper.7z.gz").write_bytes(bad_wrapper)
(root / "truncated-wrapper.zip.xz").write_bytes(wrap((root / "sample.zip").read_bytes(), "xz")[:-10])

def tar_one(data, name="payload.bin"):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w") as archive:
        # Directory entries don't prevent an otherwise single-file unwrap.
        directory = tarfile.TarInfo("nested")
        directory.type = tarfile.DIRTYPE
        archive.addfile(directory)
        entry = tarfile.TarInfo("nested/" + name)
        entry.size = len(data)
        archive.addfile(entry, io.BytesIO(data))
    return output.getvalue()

# gzip -> TAR -> 7z -> LZ4 -> XZ -> TAR -> gzip -> ZIP -> ordinary file.
# Every contained file is deliberately named .bin: signatures must drive detection.
chain = wrap(tar_one(wrap((root / "sample.zip").read_bytes(), "gz")), "xz")
chain = wrap(chain, "lz4")
(root / "chain-input.bin").write_bytes(chain)
if shutil.which("bsdtar"):
    subprocess.run(["bsdtar", "--format=7zip", "-cf", "chain.7z", "chain-input.bin"], cwd=root, check=True)
else:
    subprocess.run(["7z", "a", "chain.7z", "chain-input.bin"], cwd=root, check=True, stdout=subprocess.DEVNULL)
(root / "chain.bin").write_bytes(wrap(tar_one((root / "chain.7z").read_bytes()), "gz"))

# A standalone compressed file in a one-file container is unwrapped too.
(root / "standalone-chain.bin").write_bytes(tar_one(wrap(csv, "xz"), "table.csv.xz"))
with zipfile.ZipFile(root / "multiple-nested.zip", "w") as archive:
    archive.writestr("inner.zip", (root / "sample.zip").read_bytes())
    archive.writestr("readme.txt", b"Keep this archive's entries visible.\n")
with tarfile.open(root / "unsafe-nested.tar", "w") as archive:
    entry = tarfile.TarInfo("../inner.zip")
    entry.size = (root / "sample.zip").stat().st_size
    archive.addfile(entry, io.BytesIO((root / "sample.zip").read_bytes()))
deep = (root / "sample.zip").read_bytes()
for _ in range(34):
    deep = tar_one(deep)
(root / "too-deep.bin").write_bytes(deep)

# Force seeks beyond libarchive's initial read buffer, for both ZIP and 7z.
large = bytes((i * 73 + (i >> 8)) & 255 for i in range(160000))
(root / "seek-data.bin").write_bytes(large)
with zipfile.ZipFile(root / "seek.zip", "w", zipfile.ZIP_STORED) as archive:
    archive.writestr("seek-data.bin", large)
    archive.writestr("tail.txt", payload)
if shutil.which("bsdtar"):
    subprocess.run(["bsdtar", "--format=7zip", "--options=7zip:compression=store", "-cf", "seek.7z",
                    "seek-data.bin", "chain-input.bin"], cwd=root, check=True)
else:
    subprocess.run(["7z", "a", "-mx=0", "seek.7z", "seek-data.bin", "chain-input.bin"], cwd=root, check=True, stdout=subprocess.DEVNULL)
for container in ["seek.zip", "seek.7z"]:
    (root / (container + ".gz")).write_bytes(wrap((root / container).read_bytes(), "gz"))

generate_stress_fixture(root)
shutil.copyfile(pathlib.Path(__file__).parent / "fixtures" / "many-layers.bin", root / "committed-many-layers.bin")
