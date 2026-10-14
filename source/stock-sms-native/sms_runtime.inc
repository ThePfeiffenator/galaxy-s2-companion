/* Separate authenticated SMS socket/queue. No call-model or AT queue ownership. */
#include "sms_address.h"
_Static_assert(AF_UNIX==1&&sizeof(struct sockaddr_un)==SMS_ADDRESS_SIZE&&offsetof(struct sockaddr_un,sun_path)==2,"Exact S2 abstract socket ABI");
static SmsCore sms_core;
static SmsBatch sms_batch;
static pthread_mutex_t sms_mutex=PTHREAD_MUTEX_INITIALIZER;
static volatile sig_atomic_t sms_stop;
static int sms_started;
static pthread_t sms_thread_id;
static volatile int sms_diagnostic_stage,sms_diagnostic_result;
static int (*sms_android_log_write)(int,const char*,const char*);
static void sms_diagnostic(int stage,int result){
 if(sms_diagnostic_stage==stage&&sms_diagnostic_result==result)return;
 sms_diagnostic_result=result;sms_diagnostic_stage=stage;
 if(sms_android_log_write){char line[96];int n=snprintf(line,sizeof(line),"stage=%d result=%d",stage,result);if(n>0&&n<(int)sizeof(line))sms_android_log_write(4,"GalaxySmsNative",line);}
}
static int sms_powered(void){pthread_mutex_lock(&model_mutex);int p=virtual_power;pthread_mutex_unlock(&model_mutex);return p;}
#include "sms_delivery.inc"
static void sms_complete(const SmsJob *job,int success){
 SamsungSmsResponse response={sms_message_ref(job->bytes),NULL,0};
 answer_now((RilToken)job->token,success?SUCCESS:FAILURE,success?&response:NULL,success?sizeof(response):0);
}
static void sms_request_send(void *data,size_t n,RilToken token){
 int queued=-1;if(sms_payload_valid(data,n,0)&&sms_powered()){
  pthread_mutex_lock(&sms_mutex);queued=sms_add(&sms_core,(uintptr_t)token,data,now_ms());pthread_mutex_unlock(&sms_mutex);
 }if(queued<0)answer_now(token,FAILURE,NULL,0);
}
static void sms_request_ack(void *data,size_t n,RilToken token){
 if(!data||n!=sizeof(SamsungSmsAck)){answer_now(token,FAILURE,NULL,0);return;}
 SamsungSmsAck ack;memcpy(&ack,data,sizeof(ack));if((ack.errorClass!=0&&ack.errorClass!=1)||ack.cause<0||ack.cause>255){answer_now(token,FAILURE,NULL,0);return;}
 pthread_mutex_lock(&sms_mutex);int result=sms_ack_incoming(&sms_core,!ack.errorClass&&!ack.cause);pthread_mutex_unlock(&sms_mutex);
 answer_now(token,result?FAILURE:SUCCESS,NULL,0);
}
static int sms_cancel_request(RilToken token){pthread_mutex_lock(&sms_mutex);int found=sms_cancel(&sms_core,(uintptr_t)token);pthread_mutex_unlock(&sms_mutex);return found;}
static int sms_connect(void){
 /* SMS never waits behind another verifier or launches a concurrent verifier. */
 int locked=pthread_mutex_trylock(&identity_mutex);if(locked){sms_diagnostic(3,locked);return -1;}sms_diagnostic(4,0);
 int identity=verified_identity_inner();int identity_stage=identity_diagnostic_stage,identity_result=identity_diagnostic_result;pthread_mutex_unlock(&identity_mutex);
 sms_diagnostic(50+identity_stage,identity_result);sms_diagnostic(5,identity);if(identity||sms_stop||stop_requested)return -1;
 int fd=socket(AF_UNIX,SOCK_STREAM,0);if(fd<0){sms_diagnostic(6,errno);return -1;}if(cloexec(fd)||nonblock(fd)){sms_diagnostic(6,errno);close(fd);return -1;}sms_diagnostic(6,0);
 struct sockaddr_un a;int address_length=sms_address_build(&a,sizeof(a));
 if(address_length!=SMS_ADDRESS_LENGTH){sms_diagnostic(7,-1);close(fd);return -1;}
 int r=connect(fd,(struct sockaddr*)&a,(socklen_t)address_length);
 if(r<0&&errno!=EINPROGRESS){sms_diagnostic(7,errno);close(fd);return -1;}
 if(r<0){struct pollfd p={fd,POLLOUT,0};int error=0;socklen_t n=sizeof(error);if(poll(&p,1,3000)<=0||getsockopt(fd,SOL_SOCKET,SO_ERROR,&error,&n)||error){sms_diagnostic(7,error?error:errno);close(fd);return -1;}}sms_diagnostic(7,0);
 struct ucred peer;socklen_t n=sizeof(peer);if(getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&peer,&n)||n!=sizeof(peer)||peer.uid!=(uid_t)config.uid){sms_diagnostic(8,1);close(fd);return -1;}sms_diagnostic(8,0);return fd;
}
typedef struct {char text[SMS_LINE_MAX+1];size_t used;} SmsReader;
static int sms_read_line(int fd,SmsReader *r,char **line){
 for(;;){unsigned char c;ssize_t n=recv(fd,&c,1,MSG_DONTWAIT);if(!n)return -1;
  if(n<0){if(errno==EINTR)continue;if(errno==EAGAIN||errno==EWOULDBLOCK)return 0;return -1;}
  if(c=='\n'){r->text[r->used]=0;r->used=0;*line=r->text;return 1;}
  if((c!='\t'&&(c<32||c>126))||r->used==SMS_LINE_MAX)return -1;r->text[r->used++]=(char)c;
 }
}
static int sms_handshake(int fd,SmsReader *reader){
 if(send_all(fd,"HELLO\t2\n",8,1000)){sms_diagnostic(9,1);return -1;}sms_diagnostic(9,0);long long end=now_ms()+5000;
 while(!sms_stop&&!stop_requested&&sms_powered()&&now_ms()<end){char *line;int r=sms_read_line(fd,reader,&line);if(r<0){sms_diagnostic(10,2);return -1;}
  if(r){int match=!strcmp(line,"READY\t2");sms_diagnostic(10,match?0:3);return match?0:-1;}
  struct pollfd p={fd,POLLIN,0};if(poll(&p,1,100)<0&&errno!=EINTR){sms_diagnostic(10,4);return -1;}
 }sms_diagnostic(10,1);return -1;
}
static void sms_offline(int *fd,SmsReader *reader){
 if(*fd>=0){shutdown(*fd,SHUT_RDWR);close(*fd);*fd=-1;}memset(reader,0,sizeof(*reader));
 sms_batch_disconnect(&sms_batch);
 for(;;){SmsJob job;pthread_mutex_lock(&sms_mutex);int have=sms_disconnect(&sms_core,&job);pthread_mutex_unlock(&sms_mutex);if(!have)break;sms_complete(&job,0);}
}
static int sms_feed(const SmsFrame *frame){
 if(frame->kind==SMS_RESULT){SmsJob job;pthread_mutex_lock(&sms_mutex);int have=sms_finish(&sms_core,sms_core.epoch,frame->id,&job);pthread_mutex_unlock(&sms_mutex);if(have<0)return -1;if(have)sms_complete(&job,frame->result==1&&now_ms()<job.deadline);return 0;}
 return -1; /* v2 never emits Samsung's faulty per-segment unsolicited path. */
}
static int sms_feed_line(int fd,const char *line){
 if(!strncmp(line,"RESULT\t",7)){SmsFrame f;return sms_parse(line,&f)||sms_feed(&f)?-1:0;}
 SmsBatchFrame f;if(sms_batch_parse(line,&f)||!sms_powered())return -1;int result=0;
 int r=sms_batch_feed(&sms_batch,&f,now_ms(),&result);if(r<0)return -1;if(!r)return 0;
 if(r==1){result=sms_delivery(fd,&sms_batch);if(sms_batch_complete(&sms_batch,result))return -1;}
 char response[96];int n=snprintf(response,sizeof(response),"BATCH_RESULT\t%s\t%s\n",f.id,result==1?"OK":result==-1?"ERROR":"UNKNOWN");
 return n<1||n>=(int)sizeof(response)||send_all(fd,response,(size_t)n,1000)?-1:0;
}
static int sms_pump(int fd){
 if(sms_batch_expired(&sms_batch,now_ms()))return -1;
 for(;;){SmsJob job;pthread_mutex_lock(&sms_mutex);int expired=sms_expire(&sms_core,now_ms(),&job);pthread_mutex_unlock(&sms_mutex);if(!expired)break;sms_complete(&job,0);}
 pthread_mutex_lock(&sms_mutex);int expired=sms_incoming_timeout(&sms_core,now_ms());pthread_mutex_unlock(&sms_mutex);if(expired)return -1;
 for(;;){SmsAck ack;pthread_mutex_lock(&sms_mutex);int have=sms_pop_ack(&sms_core,&ack);pthread_mutex_unlock(&sms_mutex);if(!have)break;
  char line[64];int n=snprintf(line,sizeof(line),"ACK\t%s\t%s\n",ack.id,ack.result?"OK":"ERROR");if(n<0||n>=(int)sizeof(line)||send_all(fd,line,(size_t)n,1000))return -1;
 }
 for(;;){SmsJob job;pthread_mutex_lock(&sms_mutex);int slot=sms_next(&sms_core);if(slot>=0){sms_core.jobs[slot].sent=1;job=sms_core.jobs[slot];}pthread_mutex_unlock(&sms_mutex);if(slot<0)break;
  int remaining=sms_remaining(&job,now_ms());if(!remaining){SmsJob expired;pthread_mutex_lock(&sms_mutex);int have=sms_finish(&sms_core,job.epoch,job.id,&expired);pthread_mutex_unlock(&sms_mutex);if(have>0)sms_complete(&expired,0);continue;}
  char hex[SMS_HEX_BYTES+1],line[SMS_LINE_MAX+1];if(sms_hex_encode(job.bytes,SMS_BYTES,hex,sizeof(hex)))return -1;
  int n=snprintf(line,sizeof(line),"SEND\t%u\t%d\t%lld\t%s\n",job.id,remaining,job.deadline,hex);if(n<0||n>=(int)sizeof(line)||send_all(fd,line,(size_t)n,1000))return -1;
 }return 0;
}
static void *sms_worker(void *unused){
 (void)unused;int fd=-1;SmsReader reader;memset(&reader,0,sizeof(reader));long long retry=0;unsigned failures=0;
 while(!sms_stop&&!stop_requested){
  if(!sms_powered()){sms_diagnostic(2,0);sms_offline(&fd,&reader);poll(NULL,0,100);continue;}
  if(fd<0){if(now_ms()<retry){poll(NULL,0,100);continue;}fd=sms_connect();if(fd<0||sms_handshake(fd,&reader)){
    sms_offline(&fd,&reader);unsigned delay=failures<4?(2u<<failures):30;if(failures<4)failures++;retry=now_ms()+(long long)delay*1000;continue;
   }pthread_mutex_lock(&sms_mutex);sms_online(&sms_core);pthread_mutex_unlock(&sms_mutex);failures=0;sms_diagnostic(11,0);
  }
  int failed=0;struct pollfd p={fd,POLLIN,0};if(poll(&p,1,100)<0&&errno!=EINTR)failed=1;if(p.revents&(POLLHUP|POLLERR|POLLNVAL))failed=1;
  if(failed)sms_diagnostic(12,1);
  if(!failed)for(int count=0;count<16;count++){char *line;int r=sms_read_line(fd,&reader,&line);if(r<0){sms_diagnostic(12,2);failed=1;break;}if(!r)break;if(sms_feed_line(fd,line)){sms_diagnostic(12,3);failed=1;break;}}
  if(!failed&&(sms_stop||stop_requested||!sms_powered()||sms_pump(fd))){sms_diagnostic(13,1);failed=1;}
  if(failed){sms_offline(&fd,&reader);retry=now_ms()+2000;}
 }sms_offline(&fd,&reader);return NULL;
}
static void sms_start(void){sms_stop=0;sms_batch_init(&sms_batch);void *log=dlopen("/system/lib/liblog.so",RTLD_NOW|RTLD_LOCAL);if(log)sms_android_log_write=dlsym(log,"__android_log_write");sms_diagnostic(1,0);int r=pthread_create(&sms_thread_id,NULL,sms_worker,NULL);sms_started=!r;if(!sms_started){sms_diagnostic(1,r);diagnostic("SMS bridge thread unavailable; voice bridge remains active.\n");}}
static void sms_shutdown(void){sms_stop=1;if(sms_started){pthread_join(sms_thread_id,NULL);sms_started=0;}}
