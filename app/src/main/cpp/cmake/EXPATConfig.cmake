set(EXPAT_FOUND TRUE)
set(EXPAT_INCLUDE_DIR "${expat_SOURCE_DIR}/expat/lib")
set(EXPAT_INCLUDE_DIRS "${EXPAT_INCLUDE_DIR}")
set(EXPAT_LIBRARIES expat)
# Retry a probe that may have been cached before the bundled Mbed TLS include
# path was retained for libarchive's cumulative header checks.
unset(HAVE_EXPAT_H CACHE)
