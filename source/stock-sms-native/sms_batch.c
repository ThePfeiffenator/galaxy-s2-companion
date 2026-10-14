#include "sms_batch.h"
static void clear(void *p,size_t n){unsigned char *b=p;while(n--)*b++=0;}
static void copy(void *p,const void *q,size_t n){unsigned char *d=p;const unsigned char *s=q;while(n--)*d++=*s++;}
static int same(const void *p,const void *q,size_t n){const unsigned char *a=p,*b=q;while(n--)if(*a++!=*b++)return 0;return 1;}
static int text(const char *a,const char *b){while(*a&&*a==*b){a++;b++;}return *a==*b;}
static int hex(char c){return c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:-1;}
static int number(const char *p){if(!p||!*p||*p=='0')return -1;int n=0;for(;*p;p++){if(*p<'0'||*p>'9'||n>(SMS_BATCH_PARTS-(*p-'0'))/10)return -1;n=n*10+*p-'0';}return n;}
int sms_batch_parse(const char *line,SmsBatchFrame *f){
 if(!line||!f)return -1;char buf[SMS_LINE_MAX+1];size_t n=0;
 while(line[n]){unsigned char c=line[n];if(n==SMS_LINE_MAX||(c!='\t'&&(c<32||c>126)))return -1;buf[n]=line[n];n++;}buf[n]=0;
 char *part[4]={buf,0,0,0};int fields=1;for(size_t i=0;i<n;i++)if(buf[i]=='\t'){if(fields==4)return -1;buf[i]=0;part[fields++]=buf+i+1;}
 clear(f,sizeof(*f));if(fields<2)return -1;
 for(int i=0;i<32;i++){if(!part[1][i]||hex(part[1][i])<0)return -1;f->id[i]=part[1][i];}if(part[1][32])return -1;
 if(fields==2&&text(part[0],"COMMIT")){f->kind=SMS_BATCH_COMMIT;return 0;}
 if(fields==3&&text(part[0],"BEGIN")){f->number=number(part[2]);if(f->number<1)return -1;f->kind=SMS_BATCH_BEGIN;return 0;}
 if(fields==4&&text(part[0],"PART")){
  f->number=number(part[2]);if(f->number<1)return -1;
  for(int i=0;i<SMS_BYTES;i++){if(!part[3][2*i]||!part[3][2*i+1])return -1;int a=hex(part[3][2*i]),b=hex(part[3][2*i+1]);if(a<0||b<0)return -1;f->bytes[i]=(unsigned char)((a<<4)|b);}
  if(part[3][SMS_HEX_BYTES]||!sms_payload_valid(f->bytes,SMS_BYTES,1))return -1;f->kind=SMS_BATCH_PART;return 0;
 }return -1;
}
void sms_batch_init(SmsBatch *b){clear(b,sizeof(*b));}
void sms_batch_disconnect(SmsBatch *b){b->active=b->count=b->received=0;b->deadline=0;clear(b->id,sizeof(b->id));clear(b->bytes,sizeof(b->bytes));clear(b->hash,sizeof(b->hash));clear(&b->hash_state,sizeof(b->hash_state));}
int sms_batch_expired(const SmsBatch *b,long long now){return b->active==1&&now>=b->deadline;}
int sms_batch_feed(SmsBatch *b,const SmsBatchFrame *f,long long now,int *result){
 if(now<0||!f||!result||sms_batch_expired(b,now))return -1;
 if(f->kind==SMS_BATCH_BEGIN){
  if(b->active||f->number<1||f->number>SMS_BATCH_PARTS)return -1;
  b->active=1;b->count=f->number;b->received=0;b->deadline=now+SMS_BATCH_ASSEMBLY_MS;copy(b->id,f->id,33);
  unsigned char count[4]={0,0,0,(unsigned char)b->count};companion_sha256_init(&b->hash_state);companion_sha256_update(&b->hash_state,count,sizeof(count));return 0;
 }
 if(b->active!=1||!same(b->id,f->id,33))return -1;
 if(f->kind==SMS_BATCH_PART){
  if(f->number!=b->received+1||f->number>b->count||!sms_payload_valid(f->bytes,SMS_BYTES,1))return -1;
  copy(b->bytes[b->received++],f->bytes,SMS_BYTES);companion_sha256_update(&b->hash_state,f->bytes,SMS_BYTES);return 0;
 }
 if(f->kind!=SMS_BATCH_COMMIT||b->received!=b->count)return -1;
 companion_sha256_final(&b->hash_state,b->hash);
 for(int i=0;i<SMS_HISTORY;i++)if(b->history[i].id[0]&&same(b->history[i].id,b->id,33)){
  if(b->history[i].count!=b->count||!same(b->history[i].hash,b->hash,32))return -1;
  *result=b->history[i].result;sms_batch_disconnect(b);return 2;
 }
 b->active=2;return 1;
}
int sms_batch_complete(SmsBatch *b,int result){
 if(b->active!=2||(result!=1&&result!=-1&&result!=-2))return -1;
 SmsBatchReceipt *r=&b->history[b->history_next];b->history_next=(b->history_next+1)%SMS_HISTORY;
 copy(r->id,b->id,33);copy(r->hash,b->hash,32);r->count=b->count;r->result=result;sms_batch_disconnect(b);return 0;
}
int sms_delivery_result(const char *id,const char *reply,size_t size,int exit_code,int fully_written){
 const char prefix[]="SMSDELIVERY\t1\t";const size_t head=sizeof(prefix)-1;
 if(!id||!reply||size<head+33||!same(reply,prefix,head)||!same(reply+head,id,32)||reply[head+32]!='\t')return -2;
 const char *word=reply+head+33;size_t n=size-head-33;
 if(n==3&&same(word,"OK\n",3)&&exit_code==0&&fully_written)return 1;
 if(n==6&&same(word,"ERROR\n",6)&&exit_code==1)return -1;
 return -2; /* Includes exact UNKNOWN/exit2 and every malformed output. */
}
