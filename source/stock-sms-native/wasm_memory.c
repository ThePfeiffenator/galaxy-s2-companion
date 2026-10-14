#include <stddef.h>
/* Freestanding host harness support only, never linked into the ARM payload. */
void *memcpy(void *destination,const void *source,size_t size){unsigned char *d=destination;const unsigned char *s=source;while(size--)*d++=*s++;return destination;}
