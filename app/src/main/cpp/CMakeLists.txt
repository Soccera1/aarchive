cmake_minimum_required(VERSION 3.22)
project(aarchive LANGUAGES C CXX)
if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
option(AARCHIVE_HOST_TEST "Build the same bundled backends on the host" OFF)
set(BROTLI_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(SNAPPY_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(SNAPPY_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(SNAPPY_INSTALL OFF CACHE BOOL "" FORCE)
FetchContent_Declare(brotli
    URL https://codeload.github.com/google/brotli/tar.gz/refs/tags/v1.2.1
    URL_HASH SHA256=98d35d11a78dc453aebb7d10c3748f3f73ff78c8267c0dc697b8631f67bf7e8f)
FetchContent_Declare(snappy
    URL https://codeload.github.com/google/snappy/tar.gz/refs/tags/1.3.1
    URL_HASH SHA256=893f708a0bf4b5529d555ffcee390e940e932fcf90261f682604475a76cd0247)
FetchContent_MakeAvailable(brotli snappy)

foreach(option EXPAT_BUILD_TOOLS EXPAT_BUILD_EXAMPLES EXPAT_BUILD_TESTS EXPAT_BUILD_DOCS
        EXPAT_BUILD_PKGCONFIG EXPAT_ENABLE_INSTALL EXPAT_SHARED_LIBS)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
FetchContent_Declare(expat
    URL https://codeload.github.com/libexpat/libexpat/tar.gz/refs/tags/R_2_9_0
    URL_HASH SHA256=6e8ce7b52ebbaa423c59becc7cf1e6875c42e49228c062ac8123c10e700cec27
    SOURCE_SUBDIR expat)
FetchContent_MakeAvailable(expat)

set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)
set(USE_STATIC_MBEDTLS_LIBRARY ON CACHE BOOL "" FORCE)
FetchContent_Declare(mbedtls
    URL https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.7/mbedtls-3.6.7.tar.bz2
    URL_HASH SHA256=a7e8bcbec0e6f761b4af24f25677626b35f762f68eef79c08677a363212d11f6)
FetchContent_MakeAvailable(mbedtls)

FetchContent_Declare(bzip2
    URL https://sourceware.org/pub/bzip2/bzip2-1.0.8.tar.gz
    URL_HASH SHA256=ab5a03176ee106d3f0fa90e381da478ddae405918153cca248e682cd0c4a2269)
FetchContent_MakeAvailable(bzip2)
add_library(bz2 STATIC
    ${bzip2_SOURCE_DIR}/blocksort.c ${bzip2_SOURCE_DIR}/huffman.c
    ${bzip2_SOURCE_DIR}/crctable.c ${bzip2_SOURCE_DIR}/randtable.c
    ${bzip2_SOURCE_DIR}/compress.c ${bzip2_SOURCE_DIR}/decompress.c ${bzip2_SOURCE_DIR}/bzlib.c)
target_include_directories(bz2 PUBLIC ${bzip2_SOURCE_DIR})

set(ZSTD_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_TESTS OFF CACHE BOOL "" FORCE)
FetchContent_Declare(zstd
    URL https://codeload.github.com/facebook/zstd/tar.gz/refs/tags/v1.5.7
    URL_HASH SHA256=37d7284556b20954e56e1ca85b80226768902e2edabd3b649e9e72c0c9012ee3
    SOURCE_SUBDIR build/cmake)
FetchContent_MakeAvailable(zstd)

FetchContent_Declare(lz4
    URL https://codeload.github.com/lz4/lz4/tar.gz/refs/tags/v1.10.0
    URL_HASH SHA256=537512904744b35e232912055ccf8ec66d768639ff3abe5788d90d792ec5f48b)
FetchContent_MakeAvailable(lz4)
add_library(lz4_codec STATIC ${lz4_SOURCE_DIR}/lib/lz4.c ${lz4_SOURCE_DIR}/lib/lz4hc.c
    ${lz4_SOURCE_DIR}/lib/lz4frame.c ${lz4_SOURCE_DIR}/lib/xxhash.c)
target_include_directories(lz4_codec PUBLIC ${lz4_SOURCE_DIR}/lib)

# MIT-licensed LZO implementation, exposed through the API libarchive needs.
FetchContent_Declare(lzokay
    URL https://codeload.github.com/AxioDL/lzokay/tar.gz/db2df1fcbebc2ed06c10f727f72567d40f06a2be
    URL_HASH SHA256=e5314402b590e656474c0087af1f9d6fac727cb7718011791cdab8a61245a066
    SOURCE_SUBDIR unused)
FetchContent_MakeAvailable(lzokay)
add_library(lzo_compat STATIC lzo_compat.cpp ${lzokay_SOURCE_DIR}/lzokay.cpp)
target_include_directories(lzo_compat PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/compat PRIVATE ${lzokay_SOURCE_DIR})
target_link_libraries(lzo_compat PRIVATE z)
foreach(option XZ_NLS XZ_TOOL_XZ XZ_TOOL_XZDEC XZ_TOOL_LZMADEC XZ_TOOL_LZMAINFO XZ_TOOL_SCRIPTS XZ_DOC)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
FetchContent_Declare(xz
    URL https://codeload.github.com/tukaani-project/xz/tar.gz/refs/tags/v5.8.3
    URL_HASH SHA256=8ec1767fa517642ecb4cf08b891ce667ba6f143551e382b07c7ef437bda335e2)
FetchContent_MakeAvailable(xz)

# Use the bundled target rather than a library from the build machine.
set(CMAKE_FIND_PACKAGE_PREFER_CONFIG TRUE)
set(LibLZMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
set(BZip2_DIR "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
set(EXPAT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
set(MbedTLS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
# The bundled target is not linkable by configure-time try_compile probes.
# Select its known digest APIs explicitly, on host and Android alike.
foreach(algorithm MD5 RMD160 SHA1 SHA256 SHA384 SHA512)
    foreach(backend LIBC LIBC2 LIBC3 LIBSYSTEM LIBMD NETTLE OPENSSL WIN MBEDTLS)
        set(ARCHIVE_CRYPTO_${algorithm}_${backend} FALSE CACHE BOOL "" FORCE)
    endforeach()
endforeach()
foreach(algorithm MD5 SHA1 SHA256 SHA384 SHA512)
    set(ARCHIVE_CRYPTO_${algorithm} TRUE)
    set(ARCHIVE_CRYPTO_${algorithm}_MBEDTLS TRUE CACHE BOOL "" FORCE)
endforeach()
set(USE_BZIP2_STATIC TRUE CACHE BOOL "" FORCE)
set(LZ4_INCLUDE_DIR "${lz4_SOURCE_DIR}/lib" CACHE PATH "" FORCE)
set(LZ4_LIBRARY lz4_codec CACHE STRING "" FORCE)
set(ZSTD_INCLUDE_DIR "${zstd_SOURCE_DIR}/lib" CACHE PATH "" FORCE)
set(ZSTD_LIBRARY libzstd_static CACHE STRING "" FORCE)
set(HAVE_LIBZSTD TRUE CACHE BOOL "" FORCE)
set(HAVE_ZSTD_compressStream TRUE CACHE BOOL "" FORCE)
set(HAVE_ZSTD_minCLevel TRUE CACHE BOOL "" FORCE)
set(LZO2_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/compat" CACHE PATH "" FORCE)
set(LZO2_LIBRARY lzo_compat CACHE STRING "" FORCE)
set(WITHOUT_LZMA_API_STATIC TRUE CACHE BOOL "" FORCE)
set(HAVE_LZMA_STREAM_ENCODER_MT TRUE CACHE BOOL "" FORCE)
foreach(option ENABLE_TEST ENABLE_TAR ENABLE_CPIO ENABLE_CAT ENABLE_UNZIP ENABLE_INSTALL
        ENABLE_OPENSSL ENABLE_NETTLE ENABLE_LIBB2 ENABLE_MD
        ENABLE_LIBXML2 ENABLE_BSDXML
        ENABLE_PCREPOSIX ENABLE_PCRE2POSIX ENABLE_ACL ENABLE_XATTR ENABLE_WERROR)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
set(ENABLE_ZLIB ON CACHE BOOL "" FORCE)
set(ENABLE_LZMA ON CACHE BOOL "" FORCE)
foreach(option ENABLE_LZ4 ENABLE_LZO ENABLE_ZSTD ENABLE_BZip2 ENABLE_EXPAT ENABLE_MBEDTLS)
    set(${option} ON CACHE BOOL "" FORCE)
endforeach()
FetchContent_Declare(libarchive
    URL https://codeload.github.com/libarchive/libarchive/tar.gz/refs/tags/v3.8.10
    URL_HASH SHA256=6ec4ad1b96f92f9fe3a74bac2647e7b122a4496aa804e2fb9b50f2030283582c)
FetchContent_MakeAvailable(libarchive)

add_library(aarchive SHARED bridge.cpp stream_source.cpp)
target_include_directories(aarchive PRIVATE "${libarchive_SOURCE_DIR}/libarchive")
target_link_libraries(aarchive PRIVATE archive_static brotlidec snappy)
if(AARCHIVE_HOST_TEST)
    find_package(JNI REQUIRED)
    target_include_directories(aarchive PRIVATE ${JNI_INCLUDE_DIRS})
else()
    target_link_options(aarchive PRIVATE "-Wl,-z,max-page-size=16384")
endif()
