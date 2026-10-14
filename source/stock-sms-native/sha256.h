#ifndef COMPANION_SHA256_H
#define COMPANION_SHA256_H
#include <stddef.h>
#include <stdint.h>
typedef struct { uint32_t h[8]; uint64_t bytes; unsigned char block[64]; size_t used; } CompanionSha256;
void companion_sha256_init(CompanionSha256 *s);
void companion_sha256_update(CompanionSha256 *s,const void *data,size_t size);
void companion_sha256_final(CompanionSha256 *s,unsigned char digest[32]);
void companion_sha256_hex(const unsigned char digest[32],char hex[65]);
int companion_sha256_self_test(void);
#endif
