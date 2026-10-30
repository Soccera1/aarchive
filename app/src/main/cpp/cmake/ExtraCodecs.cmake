# Kept separate so fixture generators can build the upstream CLIs on the host.
include(FetchContent)
# Define only portable library targets; upstream install rules and CLI defaults
# are not suitable for embedding in the Android build.
FetchContent_Declare(bzip3
    URL https://codeload.github.com/iczelia/bzip3/tar.gz/refs/tags/1.5.5
    URL_HASH SHA256=d61c05785bccb50d4af75107556d9fc7373995f873270b729184509e9f1be6dd
    SOURCE_SUBDIR unused)
FetchContent_MakeAvailable(bzip3)
add_library(bz3 STATIC ${bzip3_SOURCE_DIR}/src/libbz3.c)
target_include_directories(bz3 PUBLIC ${bzip3_SOURCE_DIR}/include)
target_compile_definitions(bz3 PRIVATE VERSION="1.5.5")
FetchContent_Declare(lzfse
    URL https://codeload.github.com/lzfse/lzfse/tar.gz/e634ca58b4821d9f3d560cdc6df5dec02ffc93fd
    URL_HASH SHA256=ca98aa6644d44500e3315858daa747ce15bd06d49e3edb12a5458e5525e8ebdb
    SOURCE_SUBDIR unused)
FetchContent_MakeAvailable(lzfse)
add_library(lzfse_codec STATIC
    ${lzfse_SOURCE_DIR}/src/lzfse_decode.c
    ${lzfse_SOURCE_DIR}/src/lzfse_decode_base.c
    ${lzfse_SOURCE_DIR}/src/lzfse_encode.c
    ${lzfse_SOURCE_DIR}/src/lzfse_encode_base.c
    ${lzfse_SOURCE_DIR}/src/lzfse_fse.c
    ${lzfse_SOURCE_DIR}/src/lzvn_decode_base.c
    ${lzfse_SOURCE_DIR}/src/lzvn_encode_base.c)
target_include_directories(lzfse_codec PUBLIC ${lzfse_SOURCE_DIR}/src)
set_property(TARGET lzfse_codec PROPERTY C_STANDARD 99)
