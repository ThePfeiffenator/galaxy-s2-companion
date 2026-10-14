#include "mute_core.h"
int mute_payload(const void *data,size_t size,int *value) {
    if(!data||size!=4||!value)return -1;
    const unsigned char *b=data;
    if(b[0]>1||b[1]||b[2]||b[3])return -1;
    *value=b[0];return 0;
}
void mute_reset_verified(MuteCache *cache) {cache->valid=1;cache->value=0;}
void mute_invalidate(MuteCache *cache) {cache->valid=0;}
int mute_acknowledge(MuteCache *cache,int requested,int ok) {
    if(!ok||(requested!=0&&requested!=1)){mute_invalidate(cache);return -1;}
    cache->value=requested;cache->valid=1;return 0;
}
int mute_read(const MuteCache *cache,int *value) {if(!cache->valid)return -1;*value=cache->value;return 0;}
int controls_next(const ControlSlot *slots,int count) {
    int chosen=-1;
    for(int i=0;i<count;i++)if(slots[i].active&&!slots[i].sent&&(chosen<0||slots[i].id<slots[chosen].id))chosen=i;
    if(chosen>=0&&slots[chosen].request!=53&&slots[chosen].request!=9)
        for(int i=0;i<count;i++)if(slots[i].active&&slots[i].request==53&&slots[i].id<slots[chosen].id)return -1;
    return chosen;
}
int mute_self_test(void) {
    MuteCache c={0,0};int v=-1;unsigned char bytes[]={0,0,0,0,0};
    if(!mute_read(&c,&v))return 1;
    if(mute_payload(bytes,4,&v)||v!=0)return 2;
    bytes[0]=1;if(mute_payload(bytes,4,&v)||v!=1)return 3;
    bytes[0]=2;if(!mute_payload(bytes,4,&v))return 4;
    bytes[0]=1;if(!mute_payload(bytes,3,&v)||!mute_payload(bytes,5,&v)||!mute_payload(0,4,&v))return 5;
    bytes[3]=1;if(!mute_payload(bytes,4,&v))return 6;
    mute_reset_verified(&c);if(mute_read(&c,&v)||v!=0)return 7;
    if(mute_acknowledge(&c,1,1)||mute_read(&c,&v)||v!=1)return 8;
    if(!mute_acknowledge(&c,0,0)||!mute_read(&c,&v))return 9;
    mute_reset_verified(&c);if(mute_read(&c,&v)||v!=0)return 10;
    if(mute_acknowledge(&c,0,1)||mute_read(&c,&v)||v!=0)return 11;
    mute_invalidate(&c);if(!mute_read(&c,&v))return 12;
    ControlSlot slots[4]={{1,0,53,3},{1,0,54,4},{1,0,53,5},{1,0,10,6}};
    if(controls_next(slots,4)!=0)return 13;
    slots[0].sent=1;if(controls_next(slots,4)!=-1)return 14;
    slots[0].active=0;if(controls_next(slots,4)!=1)return 15;
    slots[1].active=0;if(controls_next(slots,4)!=2)return 16;
    slots[2].sent=1;if(controls_next(slots,4)!=-1)return 17; /* DIAL awaits preceding mute ACK. */
    slots[2].active=0;if(controls_next(slots,4)!=3)return 18;
    slots[0]=(ControlSlot){1,0,40,20};slots[1]=(ControlSlot){1,0,53,19};slots[2].active=0;slots[3].active=0;
    if(controls_next(slots,4)!=1)return 19;
    slots[1].sent=1;if(controls_next(slots,4)!=-1)return 20; /* ANSWER awaits mute0 ACK. */
    slots[1].active=0;if(controls_next(slots,4)!=0)return 21;
    slots[0]=(ControlSlot){1,0,54,21};slots[1]=(ControlSlot){1,1,53,19};
    if(controls_next(slots,4)!=-1)return 22; /* Sent/cancelled token still needs real ACK. */
    slots[0]=(ControlSlot){1,1,10,30};slots[1]=(ControlSlot){1,0,53,31};
    if(controls_next(slots,4)!=1)return 23; /* A prior slow DIAL cannot delay a new MUTE. */
    return 0;
}
