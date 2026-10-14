#ifndef STOCK_MUTE_CORE_H
#define STOCK_MUTE_CORE_H
#include <stddef.h>
#include <stdint.h>
typedef struct { int valid, value; } MuteCache;
typedef struct { int active, sent, request; uint32_t id; } ControlSlot;
int mute_payload(const void *data,size_t size,int *value);
void mute_reset_verified(MuteCache *cache);
void mute_invalidate(MuteCache *cache);
int mute_acknowledge(MuteCache *cache,int requested,int ok);
int mute_read(const MuteCache *cache,int *value);
/* GET_MUTE and call commands await earlier SET_MUTE ACKs; MUTE itself stays prompt. */
int controls_next(const ControlSlot *slots,int count);
int mute_self_test(void);
#endif
