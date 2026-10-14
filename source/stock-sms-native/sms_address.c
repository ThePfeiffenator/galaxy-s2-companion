#include "sms_address.h"
_Static_assert(__BYTE_ORDER__==__ORDER_LITTLE_ENDIAN__,"Exact S2 socket ABI requires little endian");
_Static_assert(sizeof(SMS_SOCKET)+2==SMS_ADDRESS_LENGTH,"Fixed SMS socket name length");
/* Exact Linux ARM32 sockaddr_un bytes. Scalar volatile stores intentionally
 * avoid depending on a vector zero retained across unrelated legacy calls.
 * The caller separately asserts the real system-header layout. */
__attribute__((noinline)) int sms_address_build(void *address,size_t capacity){
 if(!address||capacity!=SMS_ADDRESS_SIZE)return -1;
 volatile unsigned char *out=(volatile unsigned char*)address;
 for(size_t i=0;i<SMS_ADDRESS_SIZE;i++)out[i]=0;
 out[0]=1;out[1]=0; /* AF_UNIX == 1, native little-endian sa_family_t. */
 for(size_t i=0;i<sizeof(SMS_SOCKET)-1;i++)out[3+i]=(unsigned char)SMS_SOCKET[i];
 return SMS_ADDRESS_LENGTH;
}
