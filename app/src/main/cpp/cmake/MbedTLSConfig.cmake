set(MbedTLS_FOUND TRUE)
set(MBEDTLS_FOUND TRUE)
set(MBEDTLS_INCLUDE_DIRS "${mbedtls_SOURCE_DIR}/include")
set(MBEDCRYPTO_LIBRARY mbedcrypto)
# Libarchive includes previously detected headers in subsequent header probes.
# Keep this path after its temporary Mbed TLS check state is popped, so the
# later Expat probe can still include mbedtls/aes.h, md.h, and pkcs5.h.
list(APPEND CMAKE_REQUIRED_INCLUDES "${MBEDTLS_INCLUDE_DIRS}")
