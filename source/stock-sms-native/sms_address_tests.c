#include "sms_address.h"
static int checks;
#define CHECK(x) do{checks++;if(!(x))return checks;}while(0)
int sms_address_test_count(void){return checks;}
int sms_address_self_test(void){
 unsigned char bytes[SMS_ADDRESS_SIZE+2];checks=0;
 for(size_t i=0;i<sizeof(bytes);i++)bytes[i]=0xa5;
 CHECK(sms_address_build(bytes+1,SMS_ADDRESS_SIZE)==38);
 CHECK(bytes[0]==0xa5&&bytes[sizeof(bytes)-1]==0xa5);
 CHECK(bytes[1]==1&&bytes[2]==0&&bytes[3]==0);
 const char expected[]="org.retrobridge.callprobe.smsril.v1";int same=1;
 for(size_t i=0;i<sizeof(expected)-1;i++)if(bytes[4+i]!=(unsigned char)expected[i])same=0;
 CHECK(same);
 int tail_zero=1;for(size_t i=1+38;i<sizeof(bytes)-1;i++)if(bytes[i])tail_zero=0;CHECK(tail_zero);
 CHECK(sms_address_build(NULL,SMS_ADDRESS_SIZE)==-1);
 CHECK(sms_address_build(bytes,0)==-1);
 CHECK(sms_address_build(bytes,SMS_ADDRESS_SIZE-1)==-1);
 CHECK(sms_address_build(bytes,SMS_ADDRESS_SIZE+1)==-1);
 CHECK(bytes[0]==0xa5&&bytes[sizeof(bytes)-1]==0xa5);
 return 0;
}
