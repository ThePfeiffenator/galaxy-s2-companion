#include "sms_batch.h"
static int checks;
#define CHECK(x) do{checks++;if(!(x))return checks;}while(0)
static void clear(void *p,size_t n){unsigned char *b=p;while(n--)*b++=0;}
static void text(char *p,const char *s){while((*p++=*s++));}
static unsigned len(const char *s){unsigned n=0;while(s[n])n++;return n;}
static void put(unsigned char *p,unsigned n){p[0]=n;p[1]=n>>8;p[2]=n>>16;p[3]=n>>24;}
static void valid(unsigned char *b){clear(b,SMS_BYTES);put(b,4098);put(b+12,1);b[28]=1;b[29]='1';put(b+112,5);b[116]=0;b[117]=3;b[118]=0x21;b[119]=0x23;b[120]=0x40;}
static SmsBatch batch;
static const char id[]="0123456789abcdef0123456789abcdef";
static void frame(SmsBatchFrame *f,int kind,int number){clear(f,sizeof(*f));text(f->id,id);f->kind=kind;f->number=number;valid(f->bytes);}
static int fill(int result){SmsBatchFrame f;int old=0;frame(&f,SMS_BATCH_BEGIN,2);if(sms_batch_feed(&batch,&f,100,&old))return -1;frame(&f,SMS_BATCH_PART,1);if(sms_batch_feed(&batch,&f,101,&old))return -1;f.number=2;if(sms_batch_feed(&batch,&f,102,&old))return -1;frame(&f,SMS_BATCH_COMMIT,0);int r=sms_batch_feed(&batch,&f,103,&old);if(result==0)return r==2?old:0;return r==1?sms_batch_complete(&batch,result):-1;}
int sms_batch_self_test(void){
 checks=0;SmsBatchFrame f;int result=0;char line[SMS_LINE_MAX+2];unsigned char bytes[SMS_BYTES];valid(bytes);
 CHECK(!sms_batch_parse("BEGIN\t0123456789abcdef0123456789abcdef\t1",&f)&&f.number==1);
 CHECK(!sms_batch_parse("BEGIN\t0123456789abcdef0123456789abcdef\t255",&f)&&f.number==255);
 CHECK(sms_batch_parse("BEGIN\t0123456789abcdef0123456789abcdef\t256",&f));CHECK(sms_batch_parse("BEGIN\t0123456789abcdef0123456789abcdef\t01",&f));CHECK(sms_batch_parse("BEGIN\t0123456789abcdef0123456789abcdef\t0",&f));CHECK(sms_batch_parse("BEGIN\t0123456789abcdef0123456789abcdef\t-1",&f));
 CHECK(sms_batch_parse("BEGIN\t0123456789abcdef0123456789abcdef\t2\t",&f));CHECK(sms_batch_parse("BEGIN\t0123456789abcdef0123456789abcde\t2",&f));CHECK(sms_batch_parse("BEGIN\t0123456789abcdef0123456789abcdeF\t2",&f));
 CHECK(!sms_batch_parse("COMMIT\t0123456789abcdef0123456789abcdef",&f)&&f.kind==SMS_BATCH_COMMIT);CHECK(sms_batch_parse("COMMIT\t0123456789abcdef0123456789abcdef\t",&f));CHECK(sms_batch_parse("COMMIT\t0123456789abcdef0123456789abcdef\r",&f));
 text(line,"PART\t0123456789abcdef0123456789abcdef\t1\t");unsigned offset=len(line);sms_hex_encode(bytes,SMS_BYTES,line+offset,sizeof(line)-offset);
 CHECK(!sms_batch_parse(line,&f)&&f.number==1&&f.bytes[118]==0x21);unsigned n=len(line);line[n-1]=0;CHECK(sms_batch_parse(line,&f));sms_hex_encode(bytes,SMS_BYTES,line+offset,sizeof(line)-offset);line[n]='0';line[n+1]=0;CHECK(sms_batch_parse(line,&f));
 sms_hex_encode(bytes,SMS_BYTES,line+offset,sizeof(line)-offset);line[offset]='A';CHECK(sms_batch_parse(line,&f));sms_hex_encode(bytes,SMS_BYTES,line+offset,sizeof(line)-offset);line[offset+56]='f';line[offset+57]='f';CHECK(sms_batch_parse(line,&f));
 for(unsigned i=0;i<SMS_LINE_MAX+1;i++)line[i]='x';line[SMS_LINE_MAX+1]=0;CHECK(sms_batch_parse(line,&f));
 sms_batch_init(&batch);frame(&f,SMS_BATCH_PART,1);CHECK(sms_batch_feed(&batch,&f,1,&result)<0);frame(&f,SMS_BATCH_BEGIN,2);CHECK(!sms_batch_feed(&batch,&f,10,&result));CHECK(sms_batch_feed(&batch,&f,11,&result)<0);
 frame(&f,SMS_BATCH_PART,2);CHECK(sms_batch_feed(&batch,&f,11,&result)<0);f.number=1;f.id[0]='f';CHECK(sms_batch_feed(&batch,&f,11,&result)<0);f.id[0]=id[0];CHECK(!sms_batch_feed(&batch,&f,11,&result));CHECK(sms_batch_feed(&batch,&f,12,&result)<0);
 frame(&f,SMS_BATCH_COMMIT,0);CHECK(sms_batch_feed(&batch,&f,12,&result)<0);frame(&f,SMS_BATCH_PART,2);CHECK(!sms_batch_feed(&batch,&f,12,&result));frame(&f,SMS_BATCH_COMMIT,0);CHECK(sms_batch_feed(&batch,&f,13,&result)==1);CHECK(sms_batch_feed(&batch,&f,14,&result)<0);CHECK(sms_batch_complete(&batch,0)<0);CHECK(!sms_batch_complete(&batch,1));CHECK(!batch.active);CHECK(fill(0)==1);
 for(int r=-1;r>=-2;r--){sms_batch_init(&batch);CHECK(!fill(r));CHECK(fill(0)==r);}
 sms_batch_init(&batch);CHECK(!fill(1));frame(&f,SMS_BATCH_BEGIN,2);CHECK(!sms_batch_feed(&batch,&f,100,&result));frame(&f,SMS_BATCH_PART,1);f.bytes[119]++;CHECK(!sms_batch_feed(&batch,&f,101,&result));f.number=2;CHECK(!sms_batch_feed(&batch,&f,102,&result));frame(&f,SMS_BATCH_COMMIT,0);CHECK(sms_batch_feed(&batch,&f,103,&result)<0);sms_batch_disconnect(&batch);CHECK(!batch.active);CHECK(fill(0)==1);
 sms_batch_init(&batch);frame(&f,SMS_BATCH_BEGIN,1);CHECK(!sms_batch_feed(&batch,&f,100,&result));CHECK(!sms_batch_expired(&batch,100+SMS_BATCH_ASSEMBLY_MS-1));CHECK(sms_batch_expired(&batch,100+SMS_BATCH_ASSEMBLY_MS));frame(&f,SMS_BATCH_PART,1);CHECK(sms_batch_feed(&batch,&f,100+SMS_BATCH_ASSEMBLY_MS,&result)<0);sms_batch_disconnect(&batch);CHECK(!batch.active&&!batch.count&&!batch.received);
 sms_batch_init(&batch);frame(&f,SMS_BATCH_BEGIN,255);CHECK(!sms_batch_feed(&batch,&f,100,&result));frame(&f,SMS_BATCH_PART,1);for(int i=1;i<=255;i++){f.number=i;CHECK(!sms_batch_feed(&batch,&f,100+i,&result));}frame(&f,SMS_BATCH_COMMIT,0);CHECK(sms_batch_feed(&batch,&f,400,&result)==1);CHECK(!sms_batch_complete(&batch,-2));
 const char ok[]="SMSDELIVERY\t1\t0123456789abcdef0123456789abcdef\tOK\n";
 const char error[]="SMSDELIVERY\t1\t0123456789abcdef0123456789abcdef\tERROR\n";
 const char unknown[]="SMSDELIVERY\t1\t0123456789abcdef0123456789abcdef\tUNKNOWN\n";
 CHECK(sms_delivery_result(id,ok,sizeof(ok)-1,0,1)==1);CHECK(sms_delivery_result(id,ok,sizeof(ok)-1,1,1)==-2);CHECK(sms_delivery_result(id,ok,sizeof(ok)-1,0,0)==-2);CHECK(sms_delivery_result(id,ok,sizeof(ok)-2,0,1)==-2);CHECK(sms_delivery_result(id,ok,sizeof(ok),0,1)==-2);
 CHECK(sms_delivery_result(id,error,sizeof(error)-1,1,1)==-1);CHECK(sms_delivery_result(id,error,sizeof(error)-1,0,1)==-2);CHECK(sms_delivery_result(id,unknown,sizeof(unknown)-1,2,1)==-2);CHECK(sms_delivery_result("1123456789abcdef0123456789abcdef",ok,sizeof(ok)-1,0,1)==-2);
 return 0;
}
int sms_batch_test_count(void){return checks;}
