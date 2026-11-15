#ifndef CODEFORGE_SHA256_H
#define CODEFORGE_SHA256_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    uint32_t state[8];
    uint64_t total_bytes;
    uint8_t buffer[64];
    size_t buffer_len;
} cf_sha256_ctx;

void cf_sha256_init(cf_sha256_ctx *ctx);
void cf_sha256_update(cf_sha256_ctx *ctx, const void *data, size_t len);
void cf_sha256_final(cf_sha256_ctx *ctx, uint8_t out[32]);
void cf_sha256_hex(const void *data, size_t len, char out_hex[65]);
int cf_sha256_file(const char *utf8_path, char out_hex[65]);
const char *cf_sha256_version(void);

#ifdef __cplusplus
}
#endif

#endif
