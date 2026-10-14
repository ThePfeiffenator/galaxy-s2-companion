#ifndef SMS_ADDRESS_H
#define SMS_ADDRESS_H
#include <stddef.h>
#define SMS_SOCKET "org.retrobridge.callprobe.smsril.v1"
#define SMS_ADDRESS_SIZE 110
#define SMS_ADDRESS_LENGTH 38
int sms_address_build(void*,size_t);
int sms_address_self_test(void);
int sms_address_test_count(void);
#endif
