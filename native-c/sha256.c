#include "sha256.h"

#include <stdio.h>
#include <string.h>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#endif

static const uint32_t CF_K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
    0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
    0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
    0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
    0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
    0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
};

#define CF_ROTR(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

static uint32_t cf_load_be32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
           ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

static void cf_store_be32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);
    p[3] = (uint8_t)v;
}

static void cf_transform(cf_sha256_ctx *ctx, const uint8_t block[64]) {
    uint32_t w[64];
    uint32_t a, b, c, d, e, f, g, h;
    size_t i;

    for (i = 0; i < 16; ++i) {
        w[i] = cf_load_be32(block + i * 4);
    }
    for (i = 16; i < 64; ++i) {
        uint32_t s0 = CF_ROTR(w[i - 15], 7) ^ CF_ROTR(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = CF_ROTR(w[i - 2], 17) ^ CF_ROTR(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }

    a = ctx->state[0];
    b = ctx->state[1];
    c = ctx->state[2];
    d = ctx->state[3];
    e = ctx->state[4];
    f = ctx->state[5];
    g = ctx->state[6];
    h = ctx->state[7];

    for (i = 0; i < 64; ++i) {
        uint32_t s1 = CF_ROTR(e, 6) ^ CF_ROTR(e, 11) ^ CF_ROTR(e, 25);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t t1 = h + s1 + ch + CF_K[i] + w[i];
        uint32_t s0 = CF_ROTR(a, 2) ^ CF_ROTR(a, 13) ^ CF_ROTR(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t t2 = s0 + maj;

        h = g;
        g = f;
        f = e;
        e = d + t1;
        d = c;
        c = b;
        b = a;
        a = t1 + t2;
    }

    ctx->state[0] += a;
    ctx->state[1] += b;
    ctx->state[2] += c;
    ctx->state[3] += d;
    ctx->state[4] += e;
    ctx->state[5] += f;
    ctx->state[6] += g;
    ctx->state[7] += h;
}

void cf_sha256_init(cf_sha256_ctx *ctx) {
    ctx->state[0] = 0x6a09e667;
    ctx->state[1] = 0xbb67ae85;
    ctx->state[2] = 0x3c6ef372;
    ctx->state[3] = 0xa54ff53a;
    ctx->state[4] = 0x510e527f;
    ctx->state[5] = 0x9b05688c;
    ctx->state[6] = 0x1f83d9ab;
    ctx->state[7] = 0x5be0cd19;
    ctx->total_bytes = 0;
    ctx->buffer_len = 0;
}

void cf_sha256_update(cf_sha256_ctx *ctx, const void *data, size_t len) {
    const uint8_t *p = (const uint8_t *)data;
    size_t take;

    if (len == 0) {
        return;
    }
    ctx->total_bytes += (uint64_t)len;

    if (ctx->buffer_len > 0) {
        take = 64 - ctx->buffer_len;
        if (take > len) {
            take = len;
        }
        memcpy(ctx->buffer + ctx->buffer_len, p, take);
        ctx->buffer_len += take;
        p += take;
        len -= take;
        if (ctx->buffer_len == 64) {
            cf_transform(ctx, ctx->buffer);
            ctx->buffer_len = 0;
        }
    }

    while (len >= 64) {
        cf_transform(ctx, p);
        p += 64;
        len -= 64;
    }

    if (len > 0) {
        memcpy(ctx->buffer, p, len);
        ctx->buffer_len = len;
    }
}

void cf_sha256_final(cf_sha256_ctx *ctx, uint8_t out[32]) {
    uint64_t bits = ctx->total_bytes * 8u;
    uint8_t pad = 0x80;
    uint8_t zero = 0x00;
    uint8_t length_be[8];
    size_t i;

    cf_sha256_update(ctx, &pad, 1);
    while (ctx->buffer_len != 56) {
        cf_sha256_update(ctx, &zero, 1);
    }

    for (i = 0; i < 8; ++i) {
        length_be[7 - i] = (uint8_t)(bits >> (i * 8));
    }
    cf_sha256_update(ctx, length_be, 8);

    for (i = 0; i < 8; ++i) {
        cf_store_be32(out + i * 4, ctx->state[i]);
    }
    memset(ctx, 0, sizeof(*ctx));
}

void cf_sha256_hex(const void *data, size_t len, char out_hex[65]) {
    static const char digits[] = "0123456789abcdef";
    uint8_t digest[32];
    cf_sha256_ctx ctx;
    size_t i;

    cf_sha256_init(&ctx);
    cf_sha256_update(&ctx, data, len);
    cf_sha256_final(&ctx, digest);

    for (i = 0; i < 32; ++i) {
        out_hex[i * 2] = digits[digest[i] >> 4];
        out_hex[i * 2 + 1] = digits[digest[i] & 0x0f];
    }
    out_hex[64] = '\0';
    memset(digest, 0, sizeof(digest));
}

#if defined(_WIN32)
static FILE *cf_open_file_utf8(const char *utf8_path) {
    wchar_t wide_path[32768];
    int chars = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, utf8_path, -1,
                                    wide_path, (int)(sizeof(wide_path) / sizeof(wide_path[0])));
    if (chars <= 0) {
        return NULL;
    }
    return _wfopen(wide_path, L"rb");
}
#else
static FILE *cf_open_file_utf8(const char *utf8_path) {
    return fopen(utf8_path, "rb");
}
#endif

int cf_sha256_file(const char *utf8_path, char out_hex[65]) {
    FILE *fp;
    uint8_t buffer[65536];
    size_t got;
    cf_sha256_ctx ctx;
    uint8_t digest[32];
    static const char digits[] = "0123456789abcdef";
    size_t i;

    if (utf8_path == NULL || out_hex == NULL) {
        return -1;
    }
    fp = cf_open_file_utf8(utf8_path);
    if (fp == NULL) {
        return -1;
    }

    cf_sha256_init(&ctx);
    while ((got = fread(buffer, 1, sizeof(buffer), fp)) > 0) {
        cf_sha256_update(&ctx, buffer, got);
    }
    if (ferror(fp)) {
        fclose(fp);
        return -1;
    }
    fclose(fp);
    cf_sha256_final(&ctx, digest);

    for (i = 0; i < 32; ++i) {
        out_hex[i * 2] = digits[digest[i] >> 4];
        out_hex[i * 2 + 1] = digits[digest[i] & 0x0f];
    }
    out_hex[64] = '\0';
    memset(digest, 0, sizeof(digest));
    memset(buffer, 0, sizeof(buffer));
    return 0;
}

const char *cf_sha256_version(void) {
    return "1.0.7";
}
