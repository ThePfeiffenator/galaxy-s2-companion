#include "legacy_discovery.c"
#include <stdio.h>
int main(void){
 unsigned char ok[]={0x35,12,0x35,3,0x19,1,0,0x35,5,0x19,0,3,8,16};
 if(protocol(ok,sizeof(ok),0,0)!=16)return 1;
 for(int n=0;n<(int)sizeof(ok);n++)if(protocol(ok,n,0,0)>0)return 2;
 ok[13]=0;if(protocol(ok,sizeof(ok),0,0)>0)return 3;
 ok[13]=31;if(protocol(ok,sizeof(ok),0,0)>0)return 4;
 ok[13]=30;if(protocol(ok,sizeof(ok),0,0)!=30)return 5;
 unsigned char huge[]={0x37,0xff,0xff,0xff,0xff};if(protocol(huge,5,0,0)>0)return 6;
 unsigned char deep[128];int end=0;for(int i=0;i<10;i++){deep[end++]=0x35;deep[end++]=(unsigned char)(2*(9-i)+7);}unsigned char tail[]={0x35,5,0x19,0,3,8,17};memcpy(deep+end,tail,7);end+=7;
 if(protocol(deep,end,0,0)>0)return 7;
 unsigned char noise[4096];unsigned state=12345;
 for(int i=0;i<25000;i++){int length=i%4096;for(int j=0;j<length;j++){state=state*1664525u+1013904223u;noise[j]=(unsigned char)(state>>24);}int result=protocol(noise,length,0,0);if(result>30)return 8;}
 puts("SDP parser: valid channel, truncation, range, oversized length, nesting, 25000 bounded noise inputs passed");return 0;
}
