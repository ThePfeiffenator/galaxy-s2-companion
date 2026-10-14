#include "sms_core.h"
static void bytes_copy(void *d,const void *s,size_t n){unsigned char *a=d;const unsigned char *b=s;while(n--)*a++=*b++;}
static void bytes_zero(void *d,size_t n){unsigned char *a=d;while(n--)*a++=0;}
static int equal(const void *a,const void *b,size_t n){const unsigned char *x=a,*y=b;while(n--)if(*x++!=*y++)return 0;return 1;}
static int text_equal(const char *a,const char *b){while(*a&&*a==*b){a++;b++;}return *a==*b;}
static uint32_t little(const unsigned char *p){return (uint32_t)p[0]|((uint32_t)p[1]<<8)|((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24);}
static int nibble(char c){return c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:-1;}
int sms_payload_valid(const void *p,size_t n,int incoming){
 if(!p||n!=SMS_BYTES)return 0;const unsigned char *b=p;
 if(b[28]>36||b[73]>36||little(b+112)<1||little(b+112)>255)return 0;
 if(little(b+12)>1||little(b+16)>1||little(b+20)>7||little(b+24)>15||little(b+68)>1||b[72]>1||b[4]>1)return 0;
 if(incoming&&(little(b)!=4098||b[4]||little(b+8)||!b[28]))return 0;
 return 1;
}
int sms_message_ref(const unsigned char *b){
 unsigned n=little(b+112);if(n>255)return 0;
 for(unsigned i=0;i+2<=n;){unsigned tag=b[116+i++],size=b[116+i++];if(size>n-i)return 0;
  if(!tag&&size==3)return ((b[116+i]&15)<<12)|(b[117+i]<<4)|(b[118+i]>>4);i+=size;
 }return 0;
}
int sms_hex_encode(const unsigned char *p,size_t n,char *out,size_t size){
 static const char hex[]="0123456789abcdef";if(size<n*2+1)return -1;
 for(size_t i=0;i<n;i++){out[2*i]=hex[p[i]>>4];out[2*i+1]=hex[p[i]&15];}out[n*2]=0;return 0;
}
int sms_parse(const char *line,SmsFrame *out){
 if(!line||!out)return -1;char text[SMS_LINE_MAX+1];size_t n=0;
 while(line[n]){unsigned char c=(unsigned char)line[n];if(n==SMS_LINE_MAX||(c!='\t'&&(c<32||c>126)))return -1;text[n]=line[n];n++;}text[n]=0;
 char *parts[4]={text,0,0,0};int count=1;for(size_t i=0;i<n;i++)if(text[i]=='\t'){if(count==4)return -1;text[i]=0;parts[count++]=text+i+1;}
 bytes_zero(out,sizeof(*out));
 if(count==2&&text_equal(parts[0],"READY")&&text_equal(parts[1],"1")){out->kind=SMS_READY;return 0;}
 if(count==3&&text_equal(parts[0],"RESULT")){
  const char *p=parts[1];uint32_t id=0;if(!*p||*p=='0')return -1;for(;*p;p++){if(*p<'0'||*p>'9'||id>(2147483647u-(unsigned)(*p-'0'))/10)return -1;id=id*10+(unsigned)(*p-'0');}
  if(text_equal(parts[2],"OK"))out->result=1;else if(text_equal(parts[2],"ERROR"))out->result=-1;else if(text_equal(parts[2],"UNKNOWN"))out->result=-2;else return -1;
  out->kind=SMS_RESULT;out->id=id;return 0;
 }
 if(count==3&&text_equal(parts[0],"INCOMING")){
  for(int i=0;i<32;i++){if(!parts[1][i]||nibble(parts[1][i])<0)return -1;out->stable[i]=parts[1][i];}if(parts[1][32])return -1;
  for(int i=0;i<SMS_BYTES;i++){if(!parts[2][2*i]||!parts[2][2*i+1])return -1;int hi=nibble(parts[2][2*i]),lo=nibble(parts[2][2*i+1]);if(hi<0||lo<0)return -1;out->bytes[i]=(unsigned char)((hi<<4)|lo);}
  if(parts[2][SMS_HEX_BYTES]||!sms_payload_valid(out->bytes,SMS_BYTES,1))return -1;out->kind=SMS_INCOMING;return 0;
 }return -1;
}
void sms_init(SmsCore *s){bytes_zero(s,sizeof(*s));s->next_id=1;}
void sms_online(SmsCore *s){if(++s->epoch==0)++s->epoch;s->online=1;}
int sms_add(SmsCore *s,uintptr_t token,const void *p,long long now){
 if(!s->online||!token||!sms_payload_valid(p,SMS_BYTES,0)||s->next_id>2147483647u)return -1;
 for(int i=0;i<SMS_SLOTS;i++)if(!s->jobs[i].active){SmsJob *j=&s->jobs[i];bytes_zero(j,sizeof(*j));j->active=1;j->id=s->next_id++;j->epoch=s->epoch;j->token=token;j->deadline=now+SMS_TIMEOUT_MS;bytes_copy(j->bytes,p,SMS_BYTES);return i;}return -1;
}
int sms_next(const SmsCore *s){int found=-1;for(int i=0;i<SMS_SLOTS;i++)if(s->jobs[i].active&&!s->jobs[i].sent&&(found<0||s->jobs[i].id<s->jobs[found].id))found=i;return found;}
int sms_remaining(const SmsJob *job,long long now){long long left=job->deadline-now;return left<=0?0:left>SMS_TIMEOUT_MS?SMS_TIMEOUT_MS:(int)left;}
int sms_finish(SmsCore *s,uint32_t epoch,uint32_t id,SmsJob *out){
 if(!s->online||epoch!=s->epoch)return 0;for(int i=0;i<SMS_SLOTS;i++){SmsJob *j=&s->jobs[i];if(j->active&&j->epoch==epoch&&j->id==id){if(!j->sent)return -1;*out=*j;bytes_zero(j,sizeof(*j));return 1;}}return 0;
}
int sms_cancel(SmsCore *s,uintptr_t token){for(int i=0;i<SMS_SLOTS;i++)if(s->jobs[i].active&&s->jobs[i].token==token){bytes_zero(&s->jobs[i],sizeof(SmsJob));return 1;}return 0;}
int sms_expire(SmsCore *s,long long now,SmsJob *out){for(int i=0;i<SMS_SLOTS;i++)if(s->jobs[i].active&&now>=s->jobs[i].deadline){*out=s->jobs[i];bytes_zero(&s->jobs[i],sizeof(SmsJob));return 1;}return 0;}
int sms_disconnect(SmsCore *s,SmsJob *out){
 s->online=0;s->ack_count=0;if(s->incoming==1)s->incoming=2;
 for(int i=0;i<SMS_SLOTS;i++)if(s->jobs[i].active){*out=s->jobs[i];bytes_zero(&s->jobs[i],sizeof(SmsJob));return 1;}return 0;
}
static int queue_ack(SmsCore *s,const char *id,int result){if(s->ack_count==SMS_SLOTS)return -1;SmsAck *a=&s->acks[s->ack_count++];bytes_copy(a->id,id,33);a->result=result;return 0;}
int sms_accept_incoming(SmsCore *s,const SmsFrame *f,long long now){
 if(!s->online||f->kind!=SMS_INCOMING)return -1;
 if(s->incoming){if(equal(s->incoming_id,f->stable,33)&&equal(s->incoming_bytes,f->bytes,SMS_BYTES))return 0;return -1;}
 for(int i=0;i<SMS_HISTORY;i++)if(s->history[i].id[0]&&equal(s->history[i].id,f->stable,33))return queue_ack(s,f->stable,s->history[i].result);
 s->incoming=1;s->incoming_epoch=s->epoch;s->incoming_deadline=now+SMS_TIMEOUT_MS;bytes_copy(s->incoming_id,f->stable,33);bytes_copy(s->incoming_bytes,f->bytes,SMS_BYTES);return 1;
}
int sms_ack_incoming(SmsCore *s,int success){
 if(!s->incoming)return -1;SmsAck *history=&s->history[s->history_next];s->history_next=(s->history_next+1)%SMS_HISTORY;bytes_copy(history->id,s->incoming_id,33);history->result=success;
 int result=0;if(s->incoming==1&&s->online&&s->incoming_epoch==s->epoch)result=queue_ack(s,s->incoming_id,success);
 s->incoming=0;bytes_zero(s->incoming_id,sizeof(s->incoming_id));bytes_zero(s->incoming_bytes,sizeof(s->incoming_bytes));return result;
}
int sms_pop_ack(SmsCore *s,SmsAck *out){if(!s->ack_count)return 0;*out=s->acks[0];for(int i=1;i<s->ack_count;i++)s->acks[i-1]=s->acks[i];s->ack_count--;return 1;}
int sms_incoming_timeout(SmsCore *s,long long now){if(s->incoming==1&&now>=s->incoming_deadline){s->incoming=2;return 1;}return 0;}
