# RAR extraction fixtures

These small, independently produced archives come unchanged from libarchive
v3.8.10's `libarchive/test` directory. The checked-in uuencoded files are decoded
by `tests/make-fixtures.py`; no RAR encoder is required to build or test AArchive.

| Local fixture | Upstream fixture (before `.rar.uu`) |
| --- | --- |
| rar4-stored | test_read_format_rar |
| rar4-normal | test_read_format_rar_compress_normal |
| rar4-best | test_read_format_rar_compress_best |
| rar5-stored | test_read_format_rar5_stored |
| rar5-compressed | test_read_format_rar5_compressed |
| rar5-solid | test_read_format_rar5_multiple_files_solid |
| rar4-encrypted | test_read_format_rar4_encrypted |
| rar5-encrypted | test_read_format_rar5_encrypted |

Source: https://github.com/libarchive/libarchive/tree/v3.8.10/libarchive/test.
See `LICENSE.txt` for the RAR4 test notices, `rar5-LICENSE.txt` for RAR5, and
`rar-encryption-LICENSE.txt` for the encryption tests.

The RAR4 text is exactly `test text document\r\n`. The two compression fixtures
also contain identical 20,111-byte HTML files, whose full SHA-256 is
`ee16390e87152d7dec632ae2b37566da6135966e46b5166e6f40a1c827fa61ad`.
The stored RAR5 text is `hello libarchive test suite!\n`. Compressed and solid
RAR5 payloads use the integer sequence from upstream's `verify_data`, regenerated
in Java and compared byte for byte. Tests check paths, sizes, directory entries,
link rejection, all file contents, and skipping to the last file of a solid archive.

Fixture generation also adds gzip, XZ, Bzip3 and LZFSE wrappers and a chain of
XZ → gzip → ZIP → solid RAR5, with the nested file named `payload.bin` to require
magic-byte detection. Encrypted fixtures must fail with an encryption error,
including when a password is supplied; unsupported encryption must not yield
silently empty extracted files.
Damaged copies of both generations also verify CRC failures and truncated
compressed data are rejected.
