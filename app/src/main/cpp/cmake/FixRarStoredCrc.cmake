# The RAR reader's offset-based EOF shortcut bypasses read_data_stored's
# end-of-entry CRC check. Verify the final data chunk before returning it.
set(rar_source "${libarchive_SOURCE_DIR}/libarchive/archive_read_support_format_rar.c")
file(READ "${rar_source}" rar_text)
set(before [=[  rar->crc_calculated = __archive_crc32(rar->crc_calculated, *buff,
    (unsigned)bytes_avail);
  return (ARCHIVE_OK);
}]=])
set(after [=[  rar->crc_calculated = __archive_crc32(rar->crc_calculated, *buff,
    (unsigned)bytes_avail);
  /* AArchive: check stored CRC before the offset-based EOF shortcut. */
  if (rar->bytes_remaining == 0 &&
      !(rar->main_flags & MHD_VOLUME && rar->file_flags & FHD_SPLIT_AFTER) &&
      rar->file_crc != rar->crc_calculated) {
    archive_set_error(&a->archive, ARCHIVE_ERRNO_FILE_FORMAT, "File CRC error");
    return (ARCHIVE_FAILED);
  }
  return (ARCHIVE_OK);
}]=])
if(NOT rar_text MATCHES "AArchive: check stored CRC")
    string(FIND "${rar_text}" "${before}" location)
    if(location EQUAL -1)
        message(FATAL_ERROR "The libarchive stored-RAR CRC fix needs review")
    endif()
    string(REPLACE "${before}" "${after}" rar_text "${rar_text}")
    file(WRITE "${rar_source}" "${rar_text}")
endif()

# RAR5's stored path advances last_unstore_ptr, not last_write_ptr. Checking
# the latter prevents its final CRC/BLAKE2 verification from running at all.
set(rar5_source "${libarchive_SOURCE_DIR}/libarchive/archive_read_support_format_rar5.c")
file(READ "${rar5_source}" rar5_text)
set(before [=[	if(rar5->file.bytes_remaining == 0 &&
			rar5->cstate.last_write_ptr == rar5->file.unpacked_size)
	{]=])
set(after [=[	/* AArchive: stored entries advance last_unstore_ptr. */
	if(rar5->file.bytes_remaining == 0 &&
	    ((rar5->cstate.method == 0 || rar5->file.service > 0)
	        ? rar5->cstate.last_unstore_ptr : rar5->cstate.last_write_ptr)
	        == rar5->file.unpacked_size)
	{]=])
if(NOT rar5_text MATCHES "AArchive: stored entries advance")
    string(FIND "${rar5_text}" "${before}" location)
    if(location EQUAL -1)
        message(FATAL_ERROR "The libarchive stored-RAR5 CRC fix needs review")
    endif()
    string(REPLACE "${before}" "${after}" rar5_text "${rar5_text}")
    file(WRITE "${rar5_source}" "${rar5_text}")
endif()

# Once a stored entry reaches the verified EOF path, report its actual end
# offset rather than the unused decompressor counter (which remains zero).
set(before [=[		if (offset)
			*offset = rar5->cstate.last_write_ptr;
		return ARCHIVE_EOF;]=])
set(after [=[		/* AArchive: EOF offsets also cover stored entries. */
		if (offset)
			*offset = rar5->file.unpacked_size;
		return ARCHIVE_EOF;]=])
if(NOT rar5_text MATCHES "AArchive: EOF offsets also cover")
    string(FIND "${rar5_text}" "${before}" location)
    if(location EQUAL -1)
        message(FATAL_ERROR "The libarchive stored-RAR5 EOF fix needs review")
    endif()
    string(REPLACE "${before}" "${after}" rar5_text "${rar5_text}")
    file(WRITE "${rar5_source}" "${rar5_text}")
endif()
