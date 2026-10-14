#include "bridge_core.h"
#include "mute_core.h"
#include "lifecycle.h"
#include "sha256.h"
#include "sms_core.h"
#include "sms_batch.h"
#include "sms_address.h"
int candidate_self_test(void){int r=bridge_self_test();if(r)return r;r=mute_self_test();if(r)return 100+r;r=companion_sha256_self_test();if(r)return 200+r;r=lifecycle_self_test();if(r)return 300+r;r=sms_self_test();if(r)return 400+r;r=sms_batch_self_test();if(r)return 1000+r;r=sms_address_self_test();return r?2000+r:0;}
