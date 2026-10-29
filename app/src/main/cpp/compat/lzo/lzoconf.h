#pragma once
#include <stddef.h>
#include <stdint.h>
typedef size_t lzo_uint;
typedef uint32_t lzo_uint32;
typedef void* lzo_voidp;
#define LZO_E_OK 0
#define LZO_E_ERROR (-1)
#define LZO_E_OUT_OF_MEMORY (-2)
#define LZO_VERSION_STRING "LZOKay (LZO API compatibility)"
#ifdef __cplusplus
extern "C" {
#endif
int lzo_init(void);
unsigned lzo_version(void);
const char* lzo_version_string(void);
lzo_uint32 lzo_adler32(lzo_uint32, const unsigned char*, lzo_uint);
#ifdef __cplusplus
}
#endif
