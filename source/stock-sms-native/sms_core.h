#ifndef SMS_CORE_H
#define SMS_CORE_H
#include <stddef.h>
#include <stdint.h>
#define SMS_BYTES 372
#define SMS_HEX_BYTES (SMS_BYTES*2)
#define SMS_LINE_MAX 800
#define SMS_SLOTS 8
#define SMS_HISTORY 64
#define SMS_TIMEOUT_MS 90000
typedef struct { int kind,result;uint32_t id;char stable[33];unsigned char bytes[SMS_BYTES]; } SmsFrame;
typedef struct { int active,sent;uint32_t id,epoch;uintptr_t token;long long deadline;unsigned char bytes[SMS_BYTES]; } SmsJob;
typedef struct { char id[33];int result; } SmsAck;
typedef struct {
 int online,incoming,ack_count,history_next;uint32_t epoch,next_id,incoming_epoch;
 long long incoming_deadline;char incoming_id[33];unsigned char incoming_bytes[SMS_BYTES];
 SmsJob jobs[SMS_SLOTS];SmsAck acks[SMS_SLOTS],history[SMS_HISTORY];
} SmsCore;
enum { SMS_READY=1,SMS_RESULT=2,SMS_INCOMING=3 };
int sms_payload_valid(const void*,size_t,int incoming);
int sms_message_ref(const unsigned char*);
int sms_parse(const char*,SmsFrame*);
int sms_hex_encode(const unsigned char*,size_t,char*,size_t);
void sms_init(SmsCore*);
void sms_online(SmsCore*);
int sms_add(SmsCore*,uintptr_t,const void*,long long);
int sms_next(const SmsCore*);
int sms_remaining(const SmsJob*,long long);
int sms_finish(SmsCore*,uint32_t,uint32_t,SmsJob*);
int sms_cancel(SmsCore*,uintptr_t);
int sms_expire(SmsCore*,long long,SmsJob*);
int sms_disconnect(SmsCore*,SmsJob*);
int sms_accept_incoming(SmsCore*,const SmsFrame*,long long);
int sms_ack_incoming(SmsCore*,int);
int sms_pop_ack(SmsCore*,SmsAck*);
int sms_incoming_timeout(SmsCore*,long long);
int sms_self_test(void);
#endif
