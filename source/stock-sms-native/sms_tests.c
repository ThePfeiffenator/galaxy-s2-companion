#include "sms_core.h"
static int checks;
#define CHECK(x) do{checks++;if(!(x))return checks;}while(0)
static void zero(unsigned char *b,size_t n){while(n--)*b++=0;}
static void put(unsigned char *b,unsigned n){b[0]=(unsigned char)n;b[1]=(unsigned char)(n>>8);b[2]=(unsigned char)(n>>16);b[3]=(unsigned char)(n>>24);}
static void copy(char *d,const char *s){while((*d++=*s++));}
static unsigned length(const char *s){unsigned n=0;while(s[n])n++;return n;}
static void valid(unsigned char *b){zero(b,SMS_BYTES);put(b,4098);put(b+12,1);b[28]=1;b[29]='1';put(b+112,5);b[116]=0;b[117]=3;b[118]=0x21;b[119]=0x23;b[120]=0x40;}
static void incoming_line(char *line,const unsigned char *b){copy(line,"INCOMING\t0123456789abcdef0123456789abcdef\t");sms_hex_encode(b,SMS_BYTES,line+length(line),SMS_HEX_BYTES+1);}
int sms_self_test(void){
 checks=0;unsigned char bytes[SMS_BYTES];valid(bytes);SmsFrame frame;char line[SMS_LINE_MAX+2];SmsCore s;SmsJob job;SmsAck ack;
 CHECK(sms_payload_valid(bytes,SMS_BYTES,0));CHECK(sms_payload_valid(bytes,SMS_BYTES,1));CHECK(!sms_payload_valid(bytes,SMS_BYTES-1,0));CHECK(!sms_payload_valid(0,SMS_BYTES,0));
 CHECK(sms_message_ref(bytes)==0x1234);bytes[28]=37;CHECK(!sms_payload_valid(bytes,SMS_BYTES,0));valid(bytes);bytes[73]=37;CHECK(!sms_payload_valid(bytes,SMS_BYTES,0));valid(bytes);put(bytes+112,256);CHECK(!sms_payload_valid(bytes,SMS_BYTES,0));put(bytes+112,0);CHECK(!sms_payload_valid(bytes,SMS_BYTES,0));valid(bytes);put(bytes+12,2);CHECK(!sms_payload_valid(bytes,SMS_BYTES,0));valid(bytes);put(bytes,4099);CHECK(!sms_payload_valid(bytes,SMS_BYTES,1));valid(bytes);put(bytes+8,1);CHECK(!sms_payload_valid(bytes,SMS_BYTES,1));valid(bytes);bytes[4]=1;CHECK(!sms_payload_valid(bytes,SMS_BYTES,1));valid(bytes);bytes[28]=0;CHECK(!sms_payload_valid(bytes,SMS_BYTES,1));valid(bytes);
 CHECK(!sms_parse("READY\t1",&frame)&&frame.kind==SMS_READY);CHECK(sms_parse("READY\t2",&frame));CHECK(sms_parse("READY\t1\t",&frame));CHECK(sms_parse("READY\t1\r",&frame));
 CHECK(!sms_parse("RESULT\t1\tOK",&frame)&&frame.id==1&&frame.result==1);CHECK(!sms_parse("RESULT\t2147483647\tUNKNOWN",&frame)&&frame.result==-2);CHECK(!sms_parse("RESULT\t2\tERROR",&frame)&&frame.result==-1);
 CHECK(sms_parse("RESULT\t2147483648\tOK",&frame));CHECK(sms_parse("RESULT\t01\tOK",&frame));CHECK(sms_parse("RESULT\t0\tOK",&frame));CHECK(sms_parse("RESULT\t-1\tOK",&frame));CHECK(sms_parse("RESULT\t1\tRETRY",&frame));CHECK(sms_parse("RESULT\t1\tOK\t",&frame));
 incoming_line(line,bytes);CHECK(!sms_parse(line,&frame)&&frame.kind==SMS_INCOMING&&frame.bytes[118]==0x21);unsigned n=length(line);line[n-1]=0;CHECK(sms_parse(line,&frame));incoming_line(line,bytes);line[9]='A';CHECK(sms_parse(line,&frame));incoming_line(line,bytes);line[42]='A';CHECK(sms_parse(line,&frame));incoming_line(line,bytes);line[n]='0';line[n+1]=0;CHECK(sms_parse(line,&frame));for(unsigned i=0;i<SMS_LINE_MAX+1;i++)line[i]='x';line[SMS_LINE_MAX+1]=0;CHECK(sms_parse(line,&frame));
 incoming_line(line,bytes);CHECK(!sms_parse(line,&frame));sms_init(&s);CHECK(sms_add(&s,1,bytes,0)<0);sms_online(&s);uint32_t first=s.epoch;int slot=sms_add(&s,1,bytes,100);CHECK(slot>=0&&sms_next(&s)==slot);CHECK(s.jobs[slot].bytes[118]==0x21);bytes[118]=0;CHECK(s.jobs[slot].bytes[118]==0x21);valid(bytes);
 CHECK(sms_remaining(&s.jobs[slot],100)==90000);CHECK(sms_remaining(&s.jobs[slot],101)==89999);CHECK(sms_remaining(&s.jobs[slot],90099)==1);CHECK(sms_remaining(&s.jobs[slot],90100)==0);CHECK(sms_remaining(&s.jobs[slot],90101)==0);
 uint32_t id=s.jobs[slot].id;CHECK(sms_finish(&s,first,id,&job)==-1);s.jobs[slot].sent=1;CHECK(sms_finish(&s,first+1,id,&job)==0);CHECK(sms_finish(&s,first,id,&job)==1&&job.token==1);CHECK(sms_finish(&s,first,id,&job)==0);
 slot=sms_add(&s,2,bytes,200);CHECK(slot>=0);id=s.jobs[slot].id;CHECK(sms_cancel(&s,2)==1);CHECK(sms_cancel(&s,2)==0);CHECK(sms_finish(&s,first,id,&job)==0);
 slot=sms_add(&s,3,bytes,300);CHECK(slot>=0);s.jobs[slot].sent=1;id=s.jobs[slot].id;CHECK(!sms_expire(&s,300+SMS_TIMEOUT_MS-1,&job));CHECK(sms_expire(&s,300+SMS_TIMEOUT_MS,&job)&&job.token==3);CHECK(!sms_finish(&s,first,id,&job));CHECK(!sms_expire(&s,999999,&job));
 for(int i=0;i<SMS_SLOTS;i++)CHECK(sms_add(&s,(uintptr_t)(10+i),bytes,400)>=0);CHECK(sms_add(&s,99,bytes,400)<0);for(int i=0;i<SMS_SLOTS;i++)CHECK(sms_disconnect(&s,&job));CHECK(!sms_disconnect(&s,&job)&&!s.online);sms_online(&s);CHECK(s.epoch!=first&&sms_next(&s)<0);CHECK(!sms_finish(&s,first,id,&job));
 CHECK(sms_accept_incoming(&s,&frame,500)==1);CHECK(sms_accept_incoming(&s,&frame,501)==0);SmsFrame other=frame;other.stable[0]='1';CHECK(sms_accept_incoming(&s,&other,502)<0);CHECK(sms_ack_incoming(&s,1)==0);CHECK(sms_pop_ack(&s,&ack)&&ack.result==1);CHECK(!sms_pop_ack(&s,&ack));CHECK(sms_ack_incoming(&s,1)<0);CHECK(sms_accept_incoming(&s,&frame,503)==0);CHECK(sms_pop_ack(&s,&ack)&&ack.result==1);
 CHECK(sms_accept_incoming(&s,&other,600)==1);CHECK(!sms_disconnect(&s,&job)&&s.incoming==2);sms_online(&s);SmsFrame newer=other;newer.stable[0]='2';CHECK(sms_accept_incoming(&s,&newer,601)<0);CHECK(sms_ack_incoming(&s,0)==0&&!s.incoming);CHECK(!sms_pop_ack(&s,&ack));CHECK(sms_accept_incoming(&s,&other,602)==0);CHECK(sms_pop_ack(&s,&ack)&&ack.result==0);CHECK(sms_accept_incoming(&s,&newer,603)==1);
 CHECK(!sms_incoming_timeout(&s,603+SMS_TIMEOUT_MS-1));CHECK(sms_incoming_timeout(&s,603+SMS_TIMEOUT_MS)&&s.incoming==2);CHECK(!sms_incoming_timeout(&s,999999));CHECK(!sms_disconnect(&s,&job));sms_online(&s);CHECK(sms_accept_incoming(&s,&frame,999999)<0);CHECK(sms_ack_incoming(&s,1)==0);CHECK(!sms_pop_ack(&s,&ack));CHECK(sms_accept_incoming(&s,&newer,999999)==0);CHECK(sms_pop_ack(&s,&ack)&&ack.result==1);
 for(int i=0;i<SMS_SLOTS;i++)CHECK(sms_accept_incoming(&s,&frame,999999)==0);CHECK(sms_accept_incoming(&s,&frame,999999)<0);while(sms_pop_ack(&s,&ack)){}s.next_id=2147483647u;CHECK(sms_add(&s,999,bytes,0)>=0);CHECK(sms_add(&s,1000,bytes,0)<0);
 return 0;
}
int sms_test_count(void){return checks;}
