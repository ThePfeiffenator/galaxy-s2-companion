/* Self-contained SHA-256 (FIPS 180-4), no dynamic crypto dependency. */
#include "sha256.h"
static uint32_t rr(uint32_t n,unsigned b){return (n>>b)|(n<<(32-b));}
static void transform(CompanionSha256 *s){
 static const uint32_t k[64]={0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
 uint32_t w[64];for(unsigned i=0;i<16;i++)w[i]=(uint32_t)s->block[i*4]<<24|(uint32_t)s->block[i*4+1]<<16|(uint32_t)s->block[i*4+2]<<8|s->block[i*4+3];
 for(unsigned i=16;i<64;i++){uint32_t a=w[i-15],b=w[i-2];w[i]=w[i-16]+(rr(a,7)^rr(a,18)^(a>>3))+w[i-7]+(rr(b,17)^rr(b,19)^(b>>10));}
 uint32_t a=s->h[0],b=s->h[1],c=s->h[2],d=s->h[3],e=s->h[4],f=s->h[5],g=s->h[6],h=s->h[7];
 for(unsigned i=0;i<64;i++){uint32_t t=h+(rr(e,6)^rr(e,11)^rr(e,25))+((e&f)^((~e)&g))+k[i]+w[i];uint32_t u=(rr(a,2)^rr(a,13)^rr(a,22))+((a&b)^(a&c)^(b&c));h=g;g=f;f=e;e=d+t;d=c;c=b;b=a;a=t+u;}
 s->h[0]+=a;s->h[1]+=b;s->h[2]+=c;s->h[3]+=d;s->h[4]+=e;s->h[5]+=f;s->h[6]+=g;s->h[7]+=h;
}
void companion_sha256_init(CompanionSha256 *s){static const uint32_t h[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};for(int i=0;i<8;i++)s->h[i]=h[i];s->bytes=0;s->used=0;}
void companion_sha256_update(CompanionSha256 *s,const void *data,size_t size){const unsigned char *p=data;s->bytes+=size;while(size--){s->block[s->used++]=*p++;if(s->used==64){transform(s);s->used=0;}}}
void companion_sha256_final(CompanionSha256 *s,unsigned char out[32]){uint64_t bits=s->bytes*8;s->block[s->used++]=128;if(s->used>56){while(s->used<64)s->block[s->used++]=0;transform(s);s->used=0;}while(s->used<56)s->block[s->used++]=0;for(int i=7;i>=0;i--)s->block[s->used++]=(unsigned char)(bits>>(i*8));transform(s);for(int i=0;i<32;i++)out[i]=(unsigned char)(s->h[i/4]>>(24-(i%4)*8));}
void companion_sha256_hex(const unsigned char d[32],char hex[65]){static const char h[]="0123456789abcdef";for(int i=0;i<32;i++){hex[i*2]=h[d[i]>>4];hex[i*2+1]=h[d[i]&15];}hex[64]=0;}
static int digest_is(CompanionSha256 *s,const char *wanted){unsigned char d[32];char h[65];companion_sha256_final(s,d);companion_sha256_hex(d,h);for(int i=0;i<65;i++)if(h[i]!=wanted[i])return 0;return 1;}
int companion_sha256_self_test(void){CompanionSha256 s;companion_sha256_init(&s);if(!digest_is(&s,"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))return 1;companion_sha256_init(&s);companion_sha256_update(&s,"a",1);companion_sha256_update(&s,"bc",2);if(!digest_is(&s,"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))return 2;companion_sha256_init(&s);for(int i=0;i<1000000;i++)companion_sha256_update(&s,"a",1);if(!digest_is(&s,"cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"))return 3;return 0;}
