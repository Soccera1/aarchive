# libarchive 3.8.10 writes only an end marker for an empty LZOP stream.
# Emit the normal header first, as it does for nonempty streams.
set(lzop_source "${libarchive_SOURCE_DIR}/libarchive/archive_write_add_filter_lzop.c")
file(READ "${lzop_source}" lzop_text)
set(before "\t/* Write a zero uncompressed size as the end mark of the series of")
set(after "\tif (!lzop->header_written) {\n\t\tr = __archive_write_filter(f->next_filter, lzop->compressed, make_header(f));\n\t\tif (r != ARCHIVE_OK) return (r);\n\t\tlzop->header_written = 1;\n\t}\n${before}")
if(NOT lzop_text MATCHES "if \\(!lzop->header_written\\) \\{\n\t\tr = __archive_write_filter")
    string(FIND "${lzop_text}" "${before}" location)
    if(location EQUAL -1)
        message(FATAL_ERROR "The libarchive empty-LZOP fix needs review")
    endif()
    string(REPLACE "${before}" "${after}" lzop_text "${lzop_text}")
    file(WRITE "${lzop_source}" "${lzop_text}")
endif()
