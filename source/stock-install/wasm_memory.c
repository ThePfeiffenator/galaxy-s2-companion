#include <stddef.h>
void *memset(void *target,int value,size_t size){unsigned char *p=target;for(size_t i=0;i<size;i++)p[i]=(unsigned char)value;return target;}
void *memcpy(void *target,const void *source,size_t size){unsigned char *p=target;const unsigned char *s=source;for(size_t i=0;i<size;i++)p[i]=s[i];return target;}
