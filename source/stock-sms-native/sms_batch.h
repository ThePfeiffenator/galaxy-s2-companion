#ifndef SMS_BATCH_H
#define SMS_BATCH_H
#include "sms_core.h"
#include "sha256.h"
#define SMS_BATCH_PARTS 255
#define SMS_BATCH_ASSEMBLY_MS 10000
#define SMS_DELIVERY_MS 30000
enum { SMS_BATCH_BEGIN=1,SMS_BATCH_PART=2,SMS_BATCH_COMMIT=3 };
typedef struct {int kind,number;char id[33];unsigned char bytes[SMS_BYTES];} SmsBatchFrame;
typedef struct {char id[33];unsigned char hash[32];int result,count;} SmsBatchReceipt;
typedef struct {
 int active,count,received,history_next;long long deadline;char id[33];
 CompanionSha256 hash_state;unsigned char hash[32],bytes[SMS_BATCH_PARTS][SMS_BYTES];
 SmsBatchReceipt history[SMS_HISTORY];
} SmsBatch;
int sms_batch_parse(const char*,SmsBatchFrame*);
void sms_batch_init(SmsBatch*);
void sms_batch_disconnect(SmsBatch*);
/* Returns0 collecting,1 execute once,2 cached result,-1 reject/disconnect. */
int sms_batch_feed(SmsBatch*,const SmsBatchFrame*,long long,int*);
int sms_batch_complete(SmsBatch*,int);
int sms_batch_expired(const SmsBatch*,long long);
int sms_delivery_result(const char*,const char*,size_t,int,int);
int sms_batch_self_test(void);
int sms_batch_test_count(void);
#endif
