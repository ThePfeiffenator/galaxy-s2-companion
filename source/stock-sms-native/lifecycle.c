#include "lifecycle.h"
void lifecycle_init(Lifecycle *s){s->epoch=0;s->online=0;s->retries=0;s->retry_at=0;}
uint32_t lifecycle_begin(Lifecycle *s,long long now){if(s->online||now<s->retry_at)return 0;if(++s->epoch==0)++s->epoch;return s->epoch;}
void lifecycle_offline(Lifecycle *s,long long now){s->online=0;if(++s->epoch==0)++s->epoch;int delay=s->retries<5?(1<<s->retries):30;if(s->retries<5)s->retries++;s->retry_at=now+(long long)delay*1000;}
int lifecycle_online(Lifecycle *s,uint32_t epoch){if(!epoch||s->epoch!=epoch)return -1;s->online=1;s->retries=0;return 0;}
int lifecycle_accept(const Lifecycle *s,uint32_t epoch){return s->online&&epoch&&s->epoch==epoch;}
int lifecycle_retire(int *active){if(!*active)return 0;*active=0;return 1;}
int lifecycle_finish(uint32_t current,uint32_t request,int *active){return current&&current==request?lifecycle_retire(active):0;}
int lifecycle_self_test(void){Lifecycle s;lifecycle_init(&s);uint32_t first=lifecycle_begin(&s,0);if(!first||s.online)return 1;if(lifecycle_online(&s,first)||!lifecycle_accept(&s,first))return 2;if(lifecycle_begin(&s,0))return 3;lifecycle_offline(&s,100);if(s.online||lifecycle_accept(&s,first)||!lifecycle_online(&s,first))return 4;if(lifecycle_begin(&s,1099))return 5;uint32_t second=lifecycle_begin(&s,1100);if(!second||second==first)return 6;lifecycle_offline(&s,1100);if(s.retry_at!=3100)return 7;for(int i=0;i<10;i++)lifecycle_offline(&s,4000);if(s.retry_at!=34000)return 8;second=lifecycle_begin(&s,34000);if(lifecycle_online(&s,second)||s.retries)return 9;lifecycle_offline(&s,35000);if(s.retry_at!=36000)return 10;s.epoch=0xffffffffu;if(!lifecycle_begin(&s,36000))return 11;
 int active=1;if(lifecycle_finish(7,6,&active)||!active)return 12;if(!lifecycle_finish(7,7,&active)||active||lifecycle_finish(7,7,&active))return 13;
 active=1;if(!lifecycle_retire(&active)||lifecycle_retire(&active)||lifecycle_finish(7,7,&active))return 14;
 active=1;if(lifecycle_finish(0,0,&active)||!active)return 15;return 0;}
