#pragma once
#include "lzoconf.h"
/* LZOKay owns its compression workspace; the compatibility workspace is unused. */
#define LZO1X_1_MEM_COMPRESS 1
#define LZO1X_1_15_MEM_COMPRESS 1
#define LZO1X_999_MEM_COMPRESS 1
#ifdef __cplusplus
extern "C" {
#endif
int lzo1x_decompress_safe(const unsigned char*, lzo_uint, unsigned char*, lzo_uint*, void*);
int lzo1x_1_compress(const unsigned char*, lzo_uint, unsigned char*, lzo_uint*, void*);
int lzo1x_1_15_compress(const unsigned char*, lzo_uint, unsigned char*, lzo_uint*, void*);
int lzo1x_999_compress_level(const unsigned char*, lzo_uint, unsigned char*, lzo_uint*,
                           void*, const unsigned char*, lzo_uint, void*, int);
#ifdef __cplusplus
}
#endif
