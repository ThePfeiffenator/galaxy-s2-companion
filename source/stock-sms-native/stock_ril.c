#define _GNU_SOURCE 1
#include "bridge_core.h"
#include "samsung_ril.h"
#include "mute_core.h"
#include "sms_core.h"
#include "sms_batch.h"
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/time.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <pthread.h>
#include <dlfcn.h>
#include <errno.h>
#include <time.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>

#define APP_SOCKET "org.retrobridge.callprobe.stockril.v1"
#define SERVICE "ril-daemon"
#define MAX_PENDING 12
#define REQUEST_MS 25000
#define QUERY_PERIOD_MS 4000
#define FRESH_MS 5000
enum { SUCCESS=0, RADIO_UNAVAILABLE=1, FAILURE=2, UNSUPPORTED=6, CANCELLED=7 };
static volatile sig_atomic_t stop_requested;
static int (*property_get_fn)(const char*,char*,const char*);
static int (*property_set_fn)(const char*,const char*);
static void (*complete_fn)(RilToken,int,void*,size_t);
static void (*unsol_fn)(int,const void*,size_t);
static pthread_mutex_t model_mutex=PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t diagnostic_mutex=PTHREAD_MUTEX_INITIALIZER;
static struct { RilToken token;int request; } diagnostic_requests[64];
static BridgeModel model;
static MuteCache mute_cache;
static int virtual_power, preferred_mode=4, dirty_radio, dirty_network, dirty_calls;
static long long snapshot_time;
static uint32_t snapshot_serial;
static uint32_t next_id=4;
static uint32_t connection_epoch;
static volatile sig_atomic_t drop_link_requested;
typedef struct { int active,sent,request,mute_value; uint32_t id,snapshot_before,epoch; RilToken token; long long deadline; char command[180]; } Pending;
static Pending pending[MAX_PENDING];

static void diagnostic(const char *text){size_t n=strlen(text);while(n){ssize_t w=write(2,text,n);if(w<0&&errno==EINTR)continue;if(w<=0)break;text+=w;n-=(size_t)w;}}
static int reject_stage(const char *stage,int code){char text[160];snprintf(text,sizeof(text),"Native stage=%s exit=%d\n",stage,code);diagnostic(text);return code;}
static long long now_ms(void) { struct timespec t; if(clock_gettime(CLOCK_MONOTONIC,&t))return -1;return (long long)t.tv_sec*1000+t.tv_nsec/1000000; }
static void signal_stop(int s) { (void)s;stop_requested=1; }
static int setup_signals(void) {
    /* ICS exports bsd_signal, while modern NDK headers import newer signal. */
    typedef void (*Handler)(int);Handler (*set_handler)(int,Handler);
    void *lib=dlopen("/system/lib/libc.so",RTLD_NOW|RTLD_LOCAL);if(!lib)return -1;
    set_handler=dlsym(lib,"bsd_signal");if(!set_handler)set_handler=dlsym(lib,"signal");if(!set_handler)return -1;
    return set_handler(SIGPIPE,SIG_IGN)==SIG_ERR||set_handler(SIGHUP,SIG_IGN)==SIG_ERR||set_handler(SIGINT,signal_stop)==SIG_ERR||set_handler(SIGTERM,signal_stop)==SIG_ERR?-1:0;
}
static void pause_ms(int ms) { poll(NULL,0,ms); }
static int nonblock(int fd) { int f=fcntl(fd,F_GETFL);return f<0?-1:fcntl(fd,F_SETFL,f|O_NONBLOCK); }
static int send_all(int fd,const void *data,size_t n,int timeout) {
    const char *p=data;long long end=now_ms()+timeout;
    while(n && !stop_requested) {
        ssize_t w=send(fd,p,n,MSG_NOSIGNAL|MSG_DONTWAIT);
        if(w>0){p+=w;n-=w;continue;}
        if(w<0&&errno==EINTR)continue;
        if(w<0&&(errno==EAGAIN||errno==EWOULDBLOCK)) {long long left=end-now_ms();if(left<=0)return -1;struct pollfd q={fd,POLLOUT,0};if(poll(&q,1,left>200?200:(int)left)<0&&errno!=EINTR)return -1;continue;}
        return -1;
    }
    return n?-1:0;
}
typedef struct { char text[BRIDGE_MAX_LINE+1];size_t used; } LineReader;
/* One-byte reads retain exact line boundaries; no unbounded line or hidden tail. */
static int read_line(int fd,LineReader *r,char **line) {
    for(;;) {char c;ssize_t n=recv(fd,&c,1,MSG_DONTWAIT);
        if(n==0)return -1;
        if(n<0){if(errno==EINTR)continue;if(errno==EAGAIN||errno==EWOULDBLOCK)return 0;return -1;}
        if(c=='\n'){r->text[r->used]=0;r->used=0;*line=r->text;return 1;}
        if(!c||r->used==BRIDGE_MAX_LINE)return -1;r->text[r->used++]=c;
    }
}
static int service_is(const char *wanted) {char value[128]={0};property_get_fn("init.svc." SERVICE,value,"");return !strcmp(value,wanted);}
static int wait_service(const char *wanted,int timeout) {long long end=now_ms()+timeout;do{if(service_is(wanted))return 0;pause_ms(100);}while(now_ms()<end&&!stop_requested);return -1;}
static int remaining_ms(long long deadline,int maximum) {long long left=deadline-now_ms();return left<=0?0:left<maximum?(int)left:maximum;}
static int library_matches(void) {
    FILE *f=fopen("/system/lib/libril.so","rb");if(!f)return 0;
    uint64_t h=UINT64_C(14695981039346656037);size_t size=0,n;unsigned char b[2048];
    while((n=fread(b,1,sizeof(b),f))){size+=n;for(size_t i=0;i<n;i++){h^=b[i];h*=UINT64_C(1099511628211);}}
    int good=!ferror(f)&&size==57688&&h==UINT64_C(0x3326cd70178f8827);fclose(f);return good;
}
#include "persistent_support.inc"
static int connect_app(uid_t expected) {
    int fd=socket(AF_UNIX,SOCK_STREAM,0);if(fd<0||nonblock(fd)){if(fd>=0)close(fd);return -1;}
    struct sockaddr_un a;memset(&a,0,sizeof(a));a.sun_family=AF_UNIX;memcpy(a.sun_path+1,APP_SOCKET,sizeof(APP_SOCKET)-1);
    int r=connect(fd,(struct sockaddr*)&a,offsetof(struct sockaddr_un,sun_path)+sizeof(APP_SOCKET));
    if(r<0&&errno!=EINPROGRESS){close(fd);return -1;}
    if(r<0){struct pollfd p={fd,POLLOUT,0};int error=0;socklen_t n=sizeof(error);if(poll(&p,1,3000)<=0||getsockopt(fd,SOL_SOCKET,SO_ERROR,&error,&n)||error){close(fd);return -1;}}
    struct ucred peer;socklen_t n=sizeof(peer);if(getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&peer,&n)||n!=sizeof(peer)||peer.uid!=expected){close(fd);return -1;}
    cloexec(fd);return fd;
}
static int singleton_socket(void) {
    static const char name[]="org.retrobridge.stockril.supervisor.v1";
    int fd=socket(AF_UNIX,SOCK_STREAM,0);if(fd<0)return -1;
    struct sockaddr_un a;memset(&a,0,sizeof(a));a.sun_family=AF_UNIX;memcpy(a.sun_path+1,name,sizeof(name)-1);
    if(bind(fd,(struct sockaddr*)&a,offsetof(struct sockaddr_un,sun_path)+sizeof(name))){close(fd);return -1;}return fd;
}
static int preflight_bridge(int fd,LineReader *reader,uint32_t epoch) {
    BridgeModel candidate;bridge_init(&candidate);if(send_all(fd,"HELLO\t1\n",8,1000))return -1;
    long long end=now_ms()+25000;int asked=0,snapshot=0,result=0,muting=0,final=0,final_snapshot=0;
    while(now_ms()<end&&!stop_requested&&!drop_link_requested) {
        if(heartbeat())return -1;char *line;int r=read_line(fd,reader,&line);if(r<0)return -1;
        if(r){BridgeEvent e;if(bridge_feed(&candidate,line,&e)|| (e.state_changed&&!candidate.ready))return -1;
            if(asked&&e.snapshot_complete)snapshot=1;if(final&&e.snapshot_complete)final_snapshot=1;
            if(e.result&&e.result_id==1){if(e.result<0)return -1;result=1;}
            if(muting&&e.result&&e.result_id==2){if(e.result<0)return -1;for(int i=0;i<candidate.count;i++)if(candidate.calls[i].mode==0)return -1;
                const char q[]="REQUEST\t3\tQUERY\n";if(send_all(fd,q,sizeof(q)-1,1000))return -1;final=1;}
            if(final&&e.result&&e.result_id==3){if(e.result<0||!final_snapshot)return -1;for(int i=0;i<candidate.count;i++)if(candidate.calls[i].mode==0)return -1;
                if(lifecycle_online(&lifecycle,epoch))return -1;pthread_mutex_lock(&model_mutex);model=candidate;connection_epoch=epoch;mute_reset_verified(&mute_cache);snapshot_time=now_ms();snapshot_serial++;dirty_network=dirty_calls=1;pthread_mutex_unlock(&model_mutex);return 0;}
            if(candidate.have_state&&candidate.ready&&candidate.have_calls&&!asked){const char q[]="REQUEST\t1\tQUERY\n";if(send_all(fd,q,sizeof(q)-1,1000))return -1;asked=1;}
            if(result&&snapshot&&!candidate.frame_active&&!muting){for(int i=0;i<candidate.count;i++)if(candidate.calls[i].mode==0)return -1;const char m[]="REQUEST\t2\tMUTE\t0\n";if(send_all(fd,m,sizeof(m)-1,1000))return -1;muting=1;}
        }else{struct pollfd q={fd,POLLIN,0};poll(&q,1,100);}
    }return -1;
}
static void note_request(RilToken token,int request) {
    pthread_mutex_lock(&diagnostic_mutex);for(int i=0;i<64;i++)if(!diagnostic_requests[i].token){diagnostic_requests[i].token=token;diagnostic_requests[i].request=request;break;}pthread_mutex_unlock(&diagnostic_mutex);
}
static void answer_now(RilToken t,int error,void *data,size_t size) {
    if(!t)return;int request=0;pthread_mutex_lock(&diagnostic_mutex);for(int i=0;i<64;i++)if(diagnostic_requests[i].token==t){request=diagnostic_requests[i].request;diagnostic_requests[i].token=NULL;break;}pthread_mutex_unlock(&diagnostic_mutex);
    printf("RIL request=%d error=%d\n",request,error);fflush(stdout);complete_fn(t,error,data,size);
}
#include "sms_runtime.inc"
static int live_locked(void) {return virtual_power && model.ready;}
static void answer_calls(RilToken token) {
    SamsungCall c;SamsungCall *ptr=&c;char number[BRIDGE_NUMBER_SIZE];int have=0;
    memset(&c,0,sizeof(c));pthread_mutex_lock(&model_mutex);const BridgeCall *b=bridge_selected(&model);
    if(live_locked()&&b){have=1;c.state=b->state;c.index=1;c.toa=b->type;c.isMT=b->direction;c.isVoice=1;c.numberPresentation=b->number[0]?0:2;memcpy(number,b->number,sizeof(number));c.number=number;c.namePresentation=2;}
    pthread_mutex_unlock(&model_mutex);answer_now(token,SUCCESS,have?&ptr:NULL,have?sizeof(ptr):0);
}
/* Must be called under model_mutex. Token data is never borrowed from libril. */
static int enqueue(int request,RilToken token,const char *verb,const char *arg) {
    for(int i=0;i<MAX_PENDING;i++)if(!pending[i].active){Pending *p=&pending[i];memset(p,0,sizeof(*p));p->active=1;p->request=request;p->token=token;p->id=next_id++;
        if(next_id>0x7fffffff)next_id=4;p->deadline=now_ms()+REQUEST_MS;p->epoch=connection_epoch;
        if(request==53)p->mute_value=arg&&arg[0]=='1';
        int n=snprintf(p->command,sizeof(p->command),arg?"REQUEST\t%u\t%s\t%s\n":"REQUEST\t%u\t%s\n",p->id,verb,arg);
        if(n<0||n>=(int)sizeof(p->command)){p->active=0;return -1;}return 0;}
    return -1;
}
static int query_pending(void) {for(int i=0;i<MAX_PENDING;i++)if(pending[i].active&&pending[i].request==9)return 1;return 0;}
static int ril_state(void) {pthread_mutex_lock(&model_mutex);int s=virtual_power?9:0;pthread_mutex_unlock(&model_mutex);return s;}
static const char *ril_version(void) {return "Galaxy S2 companion FL24 bridge";}
static int ril_supports(int r) {switch(r){case 9:case 10:case 12:case 13:case 14:case 17:case 18:case 19:case 20:case 21:case 22:case 23:case 24:case 40:case 51:case 53:case 54:case 57:case 61:case 73:case 74:case 77:case 85:case 87:case 88:case 95:case 104:return 1;default:return 0;}}
static void ril_cancel(RilToken token) {if(sms_cancel_request(token)){answer_now(token,CANCELLED,NULL,0);return;}int found=0;pthread_mutex_lock(&model_mutex);for(int i=0;i<MAX_PENDING;i++)if(pending[i].active&&pending[i].token==token){
        /* A dispatched mute still changes real audio; retain its ACK ownership. */
        if(pending[i].request==53&&pending[i].sent)pending[i].token=NULL;else pending[i].active=0;found=1;break;}
    pthread_mutex_unlock(&model_mutex);if(found)answer_now(token,CANCELLED,NULL,0);}
static void ril_request(int r,void *data,size_t n,RilToken token) {
    note_request(token,r);
    if(!ril_supports(r)){answer_now(token,UNSUPPORTED,NULL,0);return;}
    if(r==87){sms_request_send(data,n,token);return;}
    if(r==88){sms_request_ack(data,n,token);return;}
    if(r==53){int desired;if(mute_payload(data,n,&desired)){answer_now(token,FAILURE,NULL,0);return;}char value[2]={(char)('0'+desired),0};
        pthread_mutex_lock(&model_mutex);int ready=model.ready&&mute_cache.valid;int queued=ready?enqueue(53,token,"MUTE",value):-1;
        if(queued&&ready){mute_invalidate(&mute_cache);drop_link_requested=1;}pthread_mutex_unlock(&model_mutex);if(queued)answer_now(token,ready?FAILURE:RADIO_UNAVAILABLE,NULL,0);return;}
    if(r==54){if(n){answer_now(token,FAILURE,NULL,0);return;}pthread_mutex_lock(&model_mutex);int ready=model.ready&&mute_cache.valid;int queued=ready?enqueue(54,token,"LOCAL_MUTE_READ",NULL):-1;pthread_mutex_unlock(&model_mutex);if(queued)answer_now(token,ready?FAILURE:RADIO_UNAVAILABLE,NULL,0);return;}
    if(r==23){if(!data||n<sizeof(int)||(*(int*)data!=0&&*(int*)data!=1)){answer_now(token,FAILURE,NULL,0);return;}pthread_mutex_lock(&model_mutex);virtual_power=*(int*)data;if(!virtual_power)drop_link_requested=1;dirty_radio=dirty_network=dirty_calls=1;pthread_mutex_unlock(&model_mutex);answer_now(token,SUCCESS,NULL,0);return;}
    if(r==9){pthread_mutex_lock(&model_mutex);int online=model.ready;pthread_mutex_unlock(&model_mutex);if(!online){answer_calls(token);return;}pthread_mutex_lock(&model_mutex);int fresh=model.have_calls&&now_ms()-snapshot_time<=FRESH_MS;int queued=fresh?0:enqueue(9,token,"QUERY",NULL);pthread_mutex_unlock(&model_mutex);if(fresh)answer_calls(token);else if(queued)answer_now(token,FAILURE,NULL,0);return;}
    if(r==18){int cause=31;answer_now(token,SUCCESS,&cause,sizeof(cause));return;}
    if(r==19){SamsungSignal s={{99,-1,0,0,0,0,0,99,INT_MAX,INT_MAX,INT_MAX,INT_MAX}};answer_now(token,SUCCESS,&s,sizeof(s));return;}
    if(r==20){pthread_mutex_lock(&model_mutex);int service=live_locked()&&model.service,roam=model.roam;pthread_mutex_unlock(&model_mutex);char *v[]={service?(roam?"5":"1"):"0",NULL,NULL,"4",NULL,NULL,NULL,"0","0","0",roam?"0":"1","1","1","0"};answer_now(token,SUCCESS,v,sizeof(v));return;}
    if(r==21){char *v[]={"0",NULL,NULL,"0"};answer_now(token,SUCCESS,v,sizeof(v));return;}
    if(r==22){char *v[]={"Pixel bridge","Pixel",NULL};answer_now(token,SUCCESS,v,sizeof(v));return;}
    if(r==51){char v[]="Bluetooth HFP session";answer_now(token,SUCCESS,v,sizeof(char*));return;}
    if(r==57){answer_now(token,SUCCESS,NULL,0);return;}
    if(r==95){char *v[]={NULL,"0","0",NULL};answer_now(token,SUCCESS,v,sizeof(v));return;}
    if(r==104){int v=1;answer_now(token,SUCCESS,&v,sizeof(v));return;}
    if(r==74){pthread_mutex_lock(&model_mutex);int v=preferred_mode;pthread_mutex_unlock(&model_mutex);answer_now(token,SUCCESS,&v,sizeof(v));return;}
    if(r==73||r==77||r==61){if(!data||n<sizeof(int)){answer_now(token,FAILURE,NULL,0);return;}int v=*(int*)data;
        if((r==73&&v!=4&&v!=5)||(r==77&&v!=1)||(r==61&&v!=0&&v!=1)){answer_now(token,UNSUPPORTED,NULL,0);return;}
        if(r==73){pthread_mutex_lock(&model_mutex);preferred_mode=v;pthread_mutex_unlock(&model_mutex);}answer_now(token,SUCCESS,NULL,0);return;}
    const char *verb=NULL,*arg=NULL;char number[BRIDGE_NUMBER_SIZE]={0};
    if(r==10){if(!data||n!=sizeof(RilDial)||!((RilDial*)data)->address||((RilDial*)data)->clir!=0||((RilDial*)data)->uusInfo){answer_now(token,UNSUPPORTED,NULL,0);return;}const char *s=((RilDial*)data)->address;size_t l=strnlen(s,sizeof(number));if(l>=sizeof(number)||!bridge_number_valid(s)){answer_now(token,FAILURE,NULL,0);return;}memcpy(number,s,l+1);verb="DIAL";arg=number;}
    else if(r==24||r==85){const char *s=NULL;if(r==24&&data)s=data;if(r==85&&data&&n==3*sizeof(char*))s=((char**)data)[0];
        if(!s||!s[0]||s[1]||!strchr("0123456789*#",s[0])){answer_now(token,UNSUPPORTED,NULL,0);return;}number[0]=s[0];verb="DTMF";arg=number;}
    else if(r==40)verb="ANSWER";else verb="HANGUP";
    pthread_mutex_lock(&model_mutex);const BridgeCall *c=bridge_selected(&model);int ok=live_locked()&&!model.ambiguous;
    for(int i=0;i<MAX_PENDING;i++)if(pending[i].active&&pending[i].request!=9&&pending[i].request!=53&&pending[i].request!=54)ok=0;
    if(r==10)ok=ok&&model.service&&!c;
    else if(r==40||r==17)ok=ok&&c&&c->state==4;
    else if(r==13)ok=ok&&c&&(c->state==1||c->state==4);
    else if(r==12)ok=ok&&c&&data&&n>=sizeof(int)&&*(int*)data==1;
    else if(r==14)ok=ok&&c&&c->state!=1;
    else if(r==24||r==85)ok=ok&&c&&c->state==0;
    int queued=ok?enqueue(r,token,verb,arg):-1;pthread_mutex_unlock(&model_mutex);
    if(queued)answer_now(token,ok?FAILURE:UNSUPPORTED,NULL,0);
}
static RilFunctions functions={6,ril_request,ril_state,ril_supports,ril_cancel,ril_version};
static void fail_pending(void) {RilToken tokens[MAX_PENDING];int count=0;pthread_mutex_lock(&model_mutex);mute_invalidate(&mute_cache);for(int i=0;i<MAX_PENDING;i++)if(lifecycle_retire(&pending[i].active)){tokens[count++]=pending[i].token;}pthread_mutex_unlock(&model_mutex);for(int i=0;i<count;i++)answer_now(tokens[i],RADIO_UNAVAILABLE,NULL,0);}
static int handle_event(const BridgeEvent *e) {
    RilToken token=NULL;int request=0,valid_snapshot=1,mute_failed=0;
    pthread_mutex_lock(&model_mutex);
    if(e->state_changed){dirty_network=1;if(!model.ready){pthread_mutex_unlock(&model_mutex);return -1;}}
    /* CALLS describes telephony only. Mute persists until an explicit acknowledged change. */
    if(e->snapshot_complete){snapshot_time=now_ms();snapshot_serial++;}
    if(e->calls_changed)dirty_calls=1;
    if(e->result)for(int i=0;i<MAX_PENDING;i++)if(pending[i].active&&pending[i].id==e->result_id&&lifecycle_finish(connection_epoch,pending[i].epoch,&pending[i].active)){token=pending[i].token;request=pending[i].request;
        valid_snapshot=pending[i].sent&&(request==53||snapshot_serial!=pending[i].snapshot_before);
        if(request==53)mute_failed=mute_acknowledge(&mute_cache,pending[i].mute_value,e->result>0&&pending[i].sent)!=0;
        pending[i].active=0;break;}
    pthread_mutex_unlock(&model_mutex);
    if(mute_failed||(request==9&&e->result<0)||(request&&e->result>0&&!valid_snapshot)){answer_now(token,FAILURE,NULL,0);return -1;}
    if(token){if(e->result>0&&request==9)answer_calls(token);else answer_now(token,e->result>0?SUCCESS:FAILURE,NULL,0);}
    return 0;
}
static int pump_requests(int app) {
    char command[180];RilToken expired[MAX_PENDING];int errors=0,fatal=0;
    pthread_mutex_lock(&model_mutex);long long now=now_ms();
    for(int i=0;i<MAX_PENDING;i++)if(pending[i].active&&now>=pending[i].deadline){expired[errors++]=pending[i].token;fatal=1;pending[i].active=0;}
    pthread_mutex_unlock(&model_mutex);for(int i=0;i<errors;i++)answer_now(expired[i],FAILURE,NULL,0);if(fatal)return -1;
    for(;;){command[0]=0;RilToken local_token=NULL;int local=0,muted=0,local_error=SUCCESS;pthread_mutex_lock(&model_mutex);
        ControlSlot slots[MAX_PENDING];for(int i=0;i<MAX_PENDING;i++)slots[i]=(ControlSlot){pending[i].active,pending[i].sent,pending[i].request,pending[i].id};int chosen=controls_next(slots,MAX_PENDING);
        if(chosen>=0){if(pending[chosen].request==54){local=1;local_token=pending[chosen].token;pending[chosen].active=0;if(mute_read(&mute_cache,&muted))local_error=RADIO_UNAVAILABLE;}
            else{pending[chosen].sent=1;pending[chosen].snapshot_before=snapshot_serial;memcpy(command,pending[chosen].command,sizeof(command));}}
        pthread_mutex_unlock(&model_mutex);if(local){answer_now(local_token,local_error,local_error?NULL:&muted,local_error?0:sizeof(muted));continue;}if(!command[0])break;if(send_all(app,command,strlen(command),1000))return -1;}
    return 0;
}
static int receive_fds(int ctl,int out[2]) {
    char b,control[CMSG_SPACE(2*sizeof(int))];struct iovec io={&b,1};struct msghdr msg;memset(&msg,0,sizeof(msg));msg.msg_iov=&io;msg.msg_iovlen=1;msg.msg_control=control;msg.msg_controllen=sizeof(control);
    struct pollfd p={ctl,POLLIN,0};if(poll(&p,1,15000)<=0)return -1;if(recvmsg(ctl,&msg,0)!=1||b!='F'||(msg.msg_flags&(MSG_CTRUNC|MSG_TRUNC)))return -1;
    struct cmsghdr *c=CMSG_FIRSTHDR(&msg);if(!c||c->cmsg_level!=SOL_SOCKET||c->cmsg_type!=SCM_RIGHTS||c->cmsg_len!=CMSG_LEN(2*sizeof(int)))return -1;memcpy(out,CMSG_DATA(c),2*sizeof(int));return 0;
}
static void offline_link(int *app,LineReader *reader) {
    pthread_mutex_lock(&model_mutex);bridge_init(&model);model.have_calls=1;connection_epoch=0;dirty_calls=dirty_network=1;mute_invalidate(&mute_cache);pthread_mutex_unlock(&model_mutex);
    fail_pending();if(*app>=0){shutdown(*app,SHUT_RDWR);close(*app);*app=-1;}memset(reader,0,sizeof(*reader));drop_link_requested=0;lifecycle_offline(&lifecycle,now_ms());
}
static int daemon_main(int ctl) {
    worker_ctl=ctl;cloexec(ctl);lifecycle_init(&lifecycle);bridge_init(&model);model.have_calls=1;
    void *lib=dlopen("/system/lib/libril.so",RTLD_NOW|RTLD_LOCAL);if(!lib)return reject_stage("worker-libril-load",20);
    void (*register_fn)(const RilFunctions*)=dlsym(lib,"RIL_register");complete_fn=dlsym(lib,"RIL_onRequestComplete");unsol_fn=dlsym(lib,"RIL_onUnsolicitedResponse");
    if(!register_fn||!complete_fn||!unsol_fn||!dlsym(lib,"RIL_startEventLoop")||!dlsym(lib,"RIL_requestTimedCallback"))return reject_stage("worker-libril-exports",21);
    if(send_all(ctl,"P",1,1000))return reject_stage("worker-ready-send",22);int sockets[2];if(receive_fds(ctl,sockets))return 23;
    if(!socket_named(sockets[0],"/dev/socket/rild",0)||!socket_named(sockets[1],"/dev/socket/rild-debug",0))return 24;
    cloexec(sockets[0]);cloexec(sockets[1]);char value[24];snprintf(value,sizeof(value),"%d",sockets[0]);if(setenv("ANDROID_SOCKET_rild",value,1))return 24;snprintf(value,sizeof(value),"%d",sockets[1]);if(setenv("ANDROID_SOCKET_rild-debug",value,1))return 24;
    sms_init(&sms_core);register_fn(&functions);if(send_all(ctl,"S",1,1000))return 25;sms_start();dirty_radio=dirty_network=dirty_calls=1;
    int app=-1,result=0,last_announced_state=-1;LineReader reader;memset(&reader,0,sizeof(reader));long long last_query=now_ms();
    while(!stop_requested) {
        if(heartbeat()){result=26;break;}if(drop_link_requested)offline_link(&app,&reader);long long now=now_ms();
        pthread_mutex_lock(&model_mutex);int powered=virtual_power;pthread_mutex_unlock(&model_mutex);if(app<0&&powered){uint32_t epoch=lifecycle_begin(&lifecycle,now);if(epoch){if(!verified_identity())app=connect_app((uid_t)config.uid);if(app<0||preflight_bridge(app,&reader,epoch)){offline_link(&app,&reader);}else{last_query=now_ms();puts("Pixel bridge online; fresh idle state verified.");fflush(stdout);}}}
        struct pollfd fds[2]={{app,POLLIN,0},{ctl,POLLIN,0}};if(poll(fds,2,100)<0&&errno!=EINTR){result=27;break;}
        if(fds[1].revents&(POLLHUP|POLLERR|POLLNVAL)){result=28;break;}
        int failed=drop_link_requested!=0;
        if(app>=0&&fds[0].revents&(POLLHUP|POLLERR|POLLNVAL))failed=1;
        if(app>=0&&!failed)for(int count=0;count<64;count++){char *line;int r=read_line(app,&reader,&line);if(r<0){failed=1;break;}if(!r)break;BridgeEvent e;pthread_mutex_lock(&model_mutex);r=bridge_feed(&model,line,&e);pthread_mutex_unlock(&model_mutex);if(r||handle_event(&e)){failed=1;break;}}
        if(failed)offline_link(&app,&reader);
        now=now_ms();pthread_mutex_lock(&model_mutex);if(app>=0&&now-last_query>=QUERY_PERIOD_MS&&!query_pending()){if(enqueue(9,NULL,"QUERY",NULL))failed=1;last_query=now;}
        int radio=dirty_radio,network=dirty_network,calls=dirty_calls;const BridgeCall *current=bridge_selected(&model);int call_state=live_locked()&&current?current->state:-1;dirty_radio=dirty_network=dirty_calls=0;pthread_mutex_unlock(&model_mutex);
        if(radio)unsol_fn(1000,NULL,0);if(network)unsol_fn(1002,NULL,0);if(calls){unsol_fn(1001,NULL,0);if(call_state==4&&last_announced_state!=4){unsigned char ring[4]={0};unsol_fn(1018,ring,sizeof(ring));}last_announced_state=call_state;}
        if(app>=0&&(failed||pump_requests(app)))offline_link(&app,&reader);
    }
    sms_shutdown();offline_link(&app,&reader);return result;
}

typedef struct { const char *path;uid_t uid;gid_t gid;int fd,owned;dev_t dev;ino_t inode; } OwnedSocket;
static int create_reserved(OwnedSocket *s) {
    struct stat old;if(!lstat(s->path,&old)){if(!S_ISSOCK(old.st_mode)||old.st_uid!=s->uid||old.st_gid!=s->gid)return -1;if(unlink(s->path))return -1;}else if(errno!=ENOENT)return -1;
    s->fd=socket(AF_UNIX,SOCK_STREAM,0);if(s->fd<0)return -1;struct sockaddr_un a;memset(&a,0,sizeof(a));a.sun_family=AF_UNIX;if(strlen(s->path)>=sizeof(a.sun_path))return -1;strcpy(a.sun_path,s->path);
    if(bind(s->fd,(struct sockaddr*)&a,offsetof(struct sockaddr_un,sun_path)+strlen(a.sun_path)+1))return -1;
    if(lstat(s->path,&old))return -1;s->owned=1;s->dev=old.st_dev;s->inode=old.st_ino;
    if(chown(s->path,s->uid,s->gid)||chmod(s->path,0660))return -1;return 0;
}
static void cleanup_socket(OwnedSocket *s) {if(s->fd>=0){close(s->fd);s->fd=-1;}if(s->owned){struct stat st;if(!lstat(s->path,&st)&&S_ISSOCK(st.st_mode)&&st.st_dev==s->dev&&st.st_ino==s->inode)unlink(s->path);s->owned=0;}}
static int send_fds(int ctl,OwnedSocket sockets[2]) {
    char b='F',control[CMSG_SPACE(2*sizeof(int))];struct iovec io={&b,1};struct msghdr msg;memset(&msg,0,sizeof(msg));memset(control,0,sizeof(control));msg.msg_iov=&io;msg.msg_iovlen=1;msg.msg_control=control;msg.msg_controllen=sizeof(control);
    struct cmsghdr *c=CMSG_FIRSTHDR(&msg);c->cmsg_level=SOL_SOCKET;c->cmsg_type=SCM_RIGHTS;c->cmsg_len=CMSG_LEN(2*sizeof(int));int fds[]={sockets[0].fd,sockets[1].fd};memcpy(CMSG_DATA(c),fds,sizeof(fds));return sendmsg(ctl,&msg,MSG_NOSIGNAL)==1?0:-1;
}
static int stop_child(pid_t child,int *reaped) {
    if(*reaped)return 0;int status;pid_t r=waitpid(child,&status,WNOHANG);if(r==child|| (r<0&&errno==ECHILD)){*reaped=1;return 0;}
    kill(child,SIGTERM);long long end=now_ms()+2000;while(now_ms()<end){r=waitpid(child,&status,WNOHANG);if(r==child){*reaped=1;return 0;}pause_ms(50);}
    kill(child,SIGKILL);end=now_ms()+3000;while(now_ms()<end){r=waitpid(child,&status,WNOHANG);if(r==child){*reaped=1;return 0;}pause_ms(50);}return -1;
}
/* ICS libc maps this inherited read-only fd during process startup. Keeping the
 * environment while closing its fd silently removes property access after exec.
 * Retain only the exact root-owned property workspace, never an arbitrary fd. */
static int property_workspace_fd(void){
    const char *value=getenv("ANDROID_PROPERTY_WORKSPACE");if(!value)return -1;
    size_t size=strlen(value);if(size<3||size>40)return -2;char copy[41];memcpy(copy,value,size+1);char *comma=strchr(copy,',');if(!comma||strchr(comma+1,','))return -2;*comma++=0;
    int fd=decimal(copy,1023),bytes=decimal(comma,16*1024*1024);if(fd<3||bytes<128)return -2;
    struct stat st;int flags=fcntl(fd,F_GETFL);if(flags<0||(flags&O_ACCMODE)!=O_RDONLY||fstat(fd,&st)||!S_ISREG(st.st_mode)||st.st_uid!=0||st.st_size!=bytes)return -2;
    char proc[64],target[128];snprintf(proc,sizeof(proc),"/proc/self/fd/%d",fd);ssize_t n=readlink(proc,target,sizeof(target)-1);if(n<1||n>=(ssize_t)sizeof(target)-1)return -2;target[n]=0;
    if(strcmp(target,"/dev/__properties__")&&strcmp(target,"/dev/__properties__ (deleted)"))return -2;return fd;
}
static pid_t launch_worker(int *control,int guard) {
    int property_fd=property_workspace_fd();if(property_fd==-2){diagnostic("Worker launch rejected invalid property workspace\n");return -1;}
    int pair[2];if(socketpair(AF_UNIX,SOCK_STREAM,0,pair))return -1;cloexec(pair[0]);cloexec(pair[1]);pid_t parent=getpid();pid_t child=fork();
    if(child<0){close(pair[0]);close(pair[1]);return -1;}if(!child){if(prctl(PR_SET_PDEATHSIG,SIGKILL)||getppid()!=parent)_exit(120);fcntl(pair[1],F_SETFD,0);fcntl(guard,F_SETFD,0);
        char ctl[24],lock[24],owner[24];snprintf(ctl,sizeof(ctl),"%d",pair[1]);snprintf(lock,sizeof(lock),"%d",guard);snprintf(owner,sizeof(owner),"%d",parent);
        if(property_fd>=3&&fcntl(property_fd,F_SETFD,0))_exit(123);
        for(int fd=3;fd<1024;fd++)if(fd!=pair[1]&&fd!=guard&&fd!=property_fd)close(fd);
        char *args[]={config.worker,"--worker","--config",config_path,"--control",ctl,"--guard",lock,"--parent",owner,NULL};execv(config.worker,args);_exit(121);}
    close(pair[1]);*control=pair[0];return child;
}
static int worker_ready(int ctl,OwnedSocket sockets[2]){struct pollfd p={ctl,POLLIN,0};char b=0;if(poll(&p,1,8000)<=0||recv(ctl,&b,1,0)!=1||b!='P')return -1;return send_fds(ctl,sockets);}
static int supervise(int trial,int duration,int guard,int initial[2]) {
    int route=abstract_listener(ROUTE_SOCKET);if(route<0)return 40;
    OwnedSocket sockets[2]={{"/dev/socket/rild",0,1001,initial[0],0,0,0},{"/dev/socket/rild-debug",1001,1000,initial[1],0,0,0}};
    int ctl=-1,result=0,stop_attempted=0,reaped=1,started=0,failures=0,quarantine=0,voice_busy=1;pid_t child=-1,route_child=-1;long long route_deadline=0,expiry=trial?now_ms()+(long long)duration*1000:LLONG_MAX,lease=0,retry=0,window=now_ms();
    child=launch_worker(&ctl,guard);if(child<0){result=41;goto cleanup;}reaped=0;
    {struct pollfd p={ctl,POLLIN,0};char b=0;if(poll(&p,1,8000)<=0||recv(ctl,&b,1,0)!=1||b!='P'){int st=0;pid_t r=waitpid(child,&st,WNOHANG);if(r==child){char text[160];reaped=1;snprintf(text,sizeof(text),"Worker startup status exited=%d code=%d signal=%d\n",WIFEXITED(st),WIFEXITED(st)?WEXITSTATUS(st):-1,WIFSIGNALED(st)?WTERMSIG(st):0);diagnostic(text);}else diagnostic("Worker startup missing ready marker\n");result=42;goto cleanup;}}
    if(trial){if(stop_requested||!service_is("running")||now_ms()>=expiry){result=43;goto cleanup;}stop_attempted=1;
        puts("Explicit timed trial: supervisor armed before stopping factory service.");fflush(stdout);
        if(property_set_fn("ctl.stop",SERVICE)||wait_service("stopped",8000)){result=44;goto cleanup;}
        if(create_reserved(&sockets[0])||create_reserved(&sockets[1])){result=45;goto cleanup;}}
    cloexec(sockets[0].fd);cloexec(sockets[1].fd);if(send_fds(ctl,sockets)){result=46;goto cleanup;}lease=now_ms()+15000;
    while(!stop_requested&&now_ms()<expiry){
        long long now=now_ms();if(route_child>0){int status;pid_t r=waitpid(route_child,&status,WNOHANG);if(r==route_child)route_child=-1;else if(now>=route_deadline){if(!reap_force(route_child))route_child=-1;else route_deadline=now_ms()+1000;}}
        int dead=0;if(child>0){int status;pid_t r=waitpid(child,&status,WNOHANG);if(r==child){reaped=1;dead=1;}else if(now>lease)dead=1;}
        if(dead){if(stop_child(child,&reaped)){result=60;goto cleanup;}if(ctl>=0)close(ctl);ctl=-1;child=-1;voice_busy=1;started=0;
            if(now-window>60000){window=now;failures=0;}failures++;if(failures>=3)quarantine=1;retry=now+(long long)(failures==1?2:4)*1000;puts(quarantine?"Virtual worker quarantined; no vendor fallback.":"Virtual worker restarting; no vendor fallback.");fflush(stdout);}
        if(child<0&&!quarantine&&now>=retry){if(checked_file(config.worker,config.worker_hash,1)){quarantine=1;}else{child=launch_worker(&ctl,guard);reaped=0;if(child<0||worker_ready(ctl,sockets)){if(child>0&&stop_child(child,&reaped)){result=60;goto cleanup;}if(ctl>=0)close(ctl);ctl=-1;child=-1;quarantine=1;}else lease=now_ms()+15000;}}
        struct pollfd p[2]={{ctl,POLLIN,0},{route,POLLIN,0}};if(poll(p,2,100)<0&&errno!=EINTR){result=47;break;}
        if(ctl>=0&&(p[0].revents&(POLLHUP|POLLERR|POLLNVAL)))lease=0;
        if(ctl>=0&&(p[0].revents&POLLIN)){char b[64];ssize_t n=recv(ctl,b,sizeof(b),MSG_DONTWAIT);if(n<=0)lease=0;else{for(ssize_t i=0;i<n;i++){if(b[i]=='S'){started=1;puts("Virtual RIL worker registered; offline until Pixel validation.");fflush(stdout);}else if(b[i]=='A')voice_busy=1;else if(b[i]=='I')voice_busy=0;else lease=0;}if(lease)lease=now_ms()+15000;}}
        if(p[1].revents&POLLIN){int fd=accept(route,NULL,NULL);if(fd>=0){cloexec(fd);nonblock(fd);if(route_child>0||quarantine){close(fd);}else{pid_t parent=getpid();pid_t r=fork();if(!r){if(prctl(PR_SET_PDEATHSIG,SIGKILL)||getppid()!=parent)_exit(120);for(int f=3;f<1024;f++)if(f!=fd)close(f);route_client(fd,voice_busy||!started);}close(fd);if(r>0){route_child=r;route_deadline=now_ms()+9000;}}}}
    }
cleanup:
    if(route_child>0)reap_force(route_child);close(route);int child_stopped=child<0||!stop_child(child,&reaped);if(ctl>=0)close(ctl);cleanup_socket(&sockets[0]);cleanup_socket(&sockets[1]);
    if(trial&&stop_attempted){if(!child_stopped){fprintf(stderr,"TRIAL RESTORE BLOCKED: virtual worker not reaped. Reboot required.\n");return 60;}stop_requested=0;int restored=0;for(int i=0;i<3&&!restored;i++)if(!property_set_fn("ctl.start",SERVICE)&&!wait_service("running",5000))restored=1;
        if(!restored){fprintf(stderr,"TRIAL RESTORE FAILED. Reboot restores unchanged init.\n");return 60;}puts("Timed trial restored original ril-daemon; no Pixel hangup sent.");fflush(stdout);}
    return result;
}
static int self_test(void){int r=bridge_self_test();if(r)return r;r=mute_self_test();if(r)return 100+r;r=companion_sha256_self_test();if(r)return 200+r;r=lifecycle_self_test();if(r)return 300+r;r=sms_self_test();if(r)return 400+r;r=sms_batch_self_test();if(r)return 1000+r;r=sms_address_self_test();return r?2000+r:0;}
static int boot_quarantine(int error){fprintf(stderr,"Boot configuration rejected; virtual service offline, no vendor fallback.\n");while(!stop_requested)poll(NULL,0,1000);return error;}
int main(int argc,char **argv) {
    if(argc==2&&!strcmp(argv[1],"--self-test")){int r=self_test();printf("Offline persistent core self-test: %s (%d)\n",r?"FAIL":"PASS",r);return r;}
    if((argc==2&&!strcmp(argv[1],"--inspect"))||(argc==1&&getppid()!=1)){puts("Nonactivating Pixel-only companion inspection; explicit trial or verified init launch required.");return 0;}
    if(getuid()!=0||setup_signals()||now_ms()<0||props_init())return reject_stage("entry-runtime",2);
    if(argc==3&&(!strcmp(argv[1],"--check-config")||!strcmp(argv[1],"--verify-identity"))){
        if(load_config(argv[2])||!device_matches()||checked_file(config.launcher,config.launcher_hash,1)||checked_file(config.worker,config.worker_hash,1)||checked_file(config.jar,config.jar_hash,0))return 3;
        if(!strcmp(argv[1],"--verify-identity")&&verified_identity())return 4;puts("Configuration verification passed; no service/socket changes.");return 0;}
#ifdef LAUNCHER_BUILD
    int trial=0,duration=600;const char *path="/system/etc/galaxy-companion.conf";
    if(argc>1){if(strcmp(argv[1],"--trial"))return 2;trial=1;for(int i=2;i<argc;i++){if(!strcmp(argv[i],"--config")&&i+1<argc)path=argv[++i];else if(!strcmp(argv[i],"--duration")&&i+1<argc)duration=decimal(argv[++i],1800);else return 2;}}
    if(duration<1||load_config(path)||!device_matches()||!own_image_matches(config.launcher,config.launcher_hash)||checked_file(config.worker,config.worker_hash,1)||checked_file(config.jar,config.jar_hash,0))return !trial&&getppid()==1?boot_quarantine(3):3;
    int initial[2]={-1,-1};if(!trial){if(getppid()!=1||strcmp(config.launcher,"/system/bin/rild")||strcmp(config_path,"/system/etc/galaxy-companion.conf"))return 4;initial[0]=init_fd("ANDROID_SOCKET_rild","/dev/socket/rild",0,1001);initial[1]=init_fd("ANDROID_SOCKET_rild-debug","/dev/socket/rild-debug",1001,1000);if(initial[0]<0||initial[1]<0||initial[0]==initial[1])return boot_quarantine(4);}else if(!service_is("running")||checked_file("/system/bin/rild","12f3c38e67d45f993d44b6a3b29c8f6f885386b1d8efcb444735d8ab4b76e4ad",1))return 4;
    int guard=singleton_socket();if(guard<0)return 5;cloexec(guard);int r=supervise(trial,duration,guard,initial);close(guard);return r;
#else
    if(argc!=10||strcmp(argv[1],"--worker")||strcmp(argv[2],"--config")||strcmp(argv[4],"--control")||strcmp(argv[6],"--guard")||strcmp(argv[8],"--parent"))return reject_stage("worker-arguments",2);
    int ctl=decimal(argv[5],1023),guard=decimal(argv[7],1023),parent=decimal(argv[9],INT_MAX);struct ucred peer;socklen_t n=sizeof(peer);
    if(ctl<3||guard<3||ctl==guard||parent<2||getppid()!=parent||prctl(PR_SET_PDEATHSIG,SIGKILL)||getppid()!=parent||getsockopt(ctl,SOL_SOCKET,SO_PEERCRED,&peer,&n)||n!=sizeof(peer)||peer.uid!=0||peer.pid!=parent||!socket_named(guard,"org.retrobridge.stockril.supervisor.v1",1))return reject_stage("worker-parent-or-control-or-guard",3);
    if(load_config(argv[3]))return reject_stage("worker-config",4);
    if(!device_matches())return reject_stage("worker-device-properties-or-libril",4);
    if(!own_image_matches(config.worker,config.worker_hash))return reject_stage("worker-own-image",4);
    if(checked_file(config.launcher,config.launcher_hash,1))return reject_stage("worker-launcher-image",4);
    char parent_proc[80],parent_path[256];snprintf(parent_proc,sizeof(parent_proc),"/proc/%d/exe",parent);ssize_t size=readlink(parent_proc,parent_path,sizeof(parent_path)-1);if(size<1||size>=(ssize_t)sizeof(parent_path)-1)return reject_stage("worker-parent-readlink",5);parent_path[size]=0;if(strcmp(parent_path,config.launcher))return reject_stage("worker-parent-path",5);cloexec(guard);return daemon_main(ctl);
#endif
}
