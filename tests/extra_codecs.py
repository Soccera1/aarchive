"""Use unmodified upstream CLIs as independent fixture encoders/decoders."""
import os
from pathlib import Path
import subprocess

TOOLS = Path(os.environ.get("AARCHIVE_CODEC_TOOLS", Path(__file__).resolve().parents[1] / "build/codec-tools"))


def encode(codec, data):
    # The upstream LZFSE CLI mishandles zero-byte stdin; an end marker is a
    # complete empty stream, as defined by its decoder.
    if codec == "lzfse" and not data:
        return b"bvx$"
    args = [str(TOOLS / "bzip3-tool"), "-b", "1", "-c"] if codec == "bz3" else [str(TOOLS / "lzfse-tool"), "-encode"]
    return subprocess.run(args, input=data, check=True, stdout=subprocess.PIPE).stdout


def decode(codec, data):
    # Its CLI treats a zero-byte decoded result as a full output buffer and
    # retries indefinitely. Validate the complete empty stream directly.
    if codec == "lzfse" and data == b"bvx$":
        return b""
    args = [str(TOOLS / "bzip3-tool"), "-dc"] if codec == "bzip3" else [str(TOOLS / "lzfse-tool"), "-decode"]
    return subprocess.run(args, input=data, check=True, stdout=subprocess.PIPE).stdout
