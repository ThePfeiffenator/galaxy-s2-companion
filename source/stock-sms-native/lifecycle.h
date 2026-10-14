#ifndef COMPANION_LIFECYCLE_H
#define COMPANION_LIFECYCLE_H
#include <stdint.h>
typedef struct { uint32_t epoch; int online,retries; long long retry_at; } Lifecycle;
void lifecycle_init(Lifecycle *s);
uint32_t lifecycle_begin(Lifecycle *s,long long now);
void lifecycle_offline(Lifecycle *s,long long now);
int lifecycle_online(Lifecycle *s,uint32_t epoch);
int lifecycle_accept(const Lifecycle *s,uint32_t epoch);
int lifecycle_retire(int *active);
int lifecycle_finish(uint32_t current,uint32_t request,int *active);
int lifecycle_self_test(void);
#endif
