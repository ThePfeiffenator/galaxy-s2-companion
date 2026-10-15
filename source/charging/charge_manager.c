/* Exact FL24 battery controller. No modem commands or direct GPIO writes.
 * Normal startup: stock install-recovery.sh execs --run as root.
 * UI commands are bounded and authenticate both Android UID and APK bytes.
 */
#define _GNU_SOURCE 1
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stddef.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <time.h>
#include <dlfcn.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include "charge_policy.h"
#include "sha256.h"
#include "app_identity.h"

#define ROOT "/data/misc/galaxy-charge"
#define PS "/sys/class/power_supply/"
#define ONLINE PS "battery/online"
#define SOCKET "galaxy-companion.charge.v1"
#define GUARD "galaxy-companion.charge.guard.v1"
static char boot_id[64];
static volatile sig_atomic_t stopping;
static int online_fd=-1, high=80, low=75, trial=0;
static long long trial_end;
static const char *reasons[]={"off","charging","paused","unplugged","temperature","battery","sensor","waiting_cable"};
static long long now_ms(void){struct timespec t;if(clock_gettime(CLOCK_MONOTONIC,&t))return -1;return (long long)t.tv_sec*1000+t.tv_nsec/1000000;}
static long long boot_ms(void){struct timespec t;if(clock_gettime(CLOCK_BOOTTIME,&t))return -1;return (long long)t.tv_sec*1000+t.tv_nsec/1000000;}
static void sleep_ms(int n){poll(NULL,0,n);}
static void log_event(const char *msg){
 char path[180];snprintf(path,sizeof(path),ROOT "/events.log");
 struct stat st;if(!lstat(path,&st)&&(!S_ISREG(st.st_mode)||st.st_uid||st.st_nlink!=1))return;
 if(!lstat(path,&st)&&st.st_size>16384)rename(path,ROOT "/events.previous.log");
 int fd=open(path,O_WRONLY|O_CREAT|O_APPEND|O_NOFOLLOW|O_CLOEXEC,0600);if(fd<0)return;
 char b[240];int n=snprintf(b,sizeof(b),"%lld %s\n",now_ms(),msg);if(n>0&&n<(int)sizeof(b))write(fd,b,(size_t)n);close(fd);
}
static int read_text(const char *path,char *b,size_t n){
 int fd=open(path,O_RDONLY|O_CLOEXEC);if(fd<0)return -1;
 ssize_t k=read(fd,b,n-1);close(fd);if(k<=0||k>=(ssize_t)n-1)return -1;
 b[k]=0;b[strcspn(b,"\r\n")]=0;return 0;
}
static int number(const char *p){char b[80],*e; if(read_text(p,b,sizeof(b)))return -9999;errno=0;long n=strtol(b,&e,10);return errno||!*b||*e||n<-10000000||n>10000000?-9999:(int)n;}
static int is_text(const char *p,const char *s){char b[80];return !read_text(p,b,sizeof(b))&&!strcmp(b,s);}
static int private_read(const char *name,char *out,size_t cap){
 char p[160];snprintf(p,sizeof(p),ROOT "/%s",name);int fd=open(p,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return -1;
 struct stat st;int ok=!fstat(fd,&st)&&S_ISREG(st.st_mode)&&st.st_uid==0&&st.st_nlink==1&&(st.st_mode&0777)==0600;
 ssize_t n=ok?read(fd,out,cap-1):-1;close(fd);if(n<1||n>=(ssize_t)cap-1)return -1;out[n]=0;return 0;
}
static int private_write(const char *name,const char *value){
 char p[160],tmp[180];snprintf(p,sizeof(p),ROOT "/%s",name);snprintf(tmp,sizeof(tmp),"%s.new",p);
 struct stat st;if(!lstat(p,&st)&&(!S_ISREG(st.st_mode)||st.st_uid||st.st_nlink!=1||(st.st_mode&0777)!=0600))return -1;
 if(!lstat(tmp,&st)){if(!S_ISREG(st.st_mode)||st.st_uid||st.st_nlink!=1)return -1;if(unlink(tmp))return -1;}
 int fd=open(tmp,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);if(fd<0)return -1;
 size_t n=strlen(value);int good=write(fd,value,n)==(ssize_t)n&&!fsync(fd);if(close(fd))good=0;
 if(!good||rename(tmp,p)){unlink(tmp);return -1;}
 fd=open(ROOT,O_RDONLY|O_DIRECTORY|O_CLOEXEC);if(fd<0)return -1;int r=fsync(fd);close(fd);return r;
}
static int setting(const char *name,int fallback){char b[32];if(private_read(name,b,sizeof(b)))return fallback;if(!strcmp(b,"1\n"))return 1;if(!strcmp(b,"0\n"))return 0;return fallback;}
static int set_setting(const char *name,int on){return private_write(name,on?"1\n":"0\n");}
static int journal_get(void){char b[160],id[64],extra;int cable;if(private_read("pause",b,sizeof(b)))return 0;
 if(sscanf(b,"%63s %d %c",id,&cable,&extra)!=2||strcmp(id,boot_id)||cable<1||cable>3)return 0;return cable;}
static int journal_set(int cable){char b[160];snprintf(b,sizeof(b),"%s %d\n",boot_id,cable);return private_write("pause",b);}
static int gpio_level(const char *text,const char *label){const char *p=strstr(text,label);if(!p)return -1;const char *end=strchr(p,'\n');const char *paren=strchr(p,')');if(!paren||(end&&paren>end))return -1;char direction[8],level[8];if(sscanf(paren+1,"%7s %7s",direction,level)!=2)return -1;return !strcmp(level,"hi")?1:!strcmp(level,"lo")?0:-1;}
static ChargeReading reading(int remembered){
 ChargeReading r={-1,-9999,-1,-1,0,-1,0,-1};
 r.soc=number(PS"battery/capacity");r.temp=number(PS"battery/temp");r.uv=number(PS"battery/voltage_now");r.present=number(PS"battery/present");r.health_good=is_text(PS"battery/health","Good");
 int reported=number(ONLINE);if(reported>=1&&reported<=3)r.cable=reported;else if(reported==0&&remembered>=1&&remembered<=3)r.cable=remembered;
 /* seq_file GPIO output must be consumed to EOF; a single read can be partial. */
 FILE *f=fopen("/sys/kernel/debug/gpio","r");if(f){char b[512];while(fgets(b,sizeof(b),f)){
  if(strstr(b,"MAX8922 TA_nCONNECTE")){int v=gpio_level(b,"MAX8922 TA_nCONNECTE");if(v>=0)r.physical=!v;}
  if(strstr(b,"MAX8922 CHG_EN"))r.charger_enabled=gpio_level(b,"MAX8922 CHG_EN");
 }fclose(f);}return r;
}
static int open_control(void){
 struct stat st;if(stat(ONLINE,&st)||!S_ISREG(st.st_mode)||(st.st_mode&0777)!=0444)return -1;
 if(chmod(ONLINE,0644))return -1;
 int fd=open(ONLINE,O_WRONLY|O_CLOEXEC);int restored=chmod(ONLINE,0444)==0;
 if(fd<0||!restored){if(fd>=0)close(fd);return -1;}return fd;
}
static int write_cable(int cable){
 const char *v=cable==0?"0\n":cable==1?"3\n":cable==2?"2\n":cable==3?"8\n":NULL;
 if(!v||online_fd<0)return -1;
 if(lseek(online_fd,0,SEEK_SET)<0)return -1;
 if(write(online_fd,v,2)!=2)return -1;
 for(int i=0;i<15;i++){sleep_ms(100);int seen=number(ONLINE);ChargeReading r=reading(cable);
  if(!cable&&seen==0&&r.charger_enabled==0)return 0;
  if(cable&&seen==cable)return 0;
 }return -1;
}
/* Restore only this boot's owned pause, with fresh independent sensor gates. */
static int restore_owned(void){
 int cable=journal_get();if(!cable)return 0;ChargeReading r=reading(cable);
 if(r.physical==0){journal_set(0);return 0;}
 if(!charge_safe_resume(&r)){log_event("Restore deferred: independent safety/cable check");return -1;}
 if(write_cable(r.cable)){log_event("Stock charging restoration not confirmed");return -1;}
 if(journal_set(0))return -1;log_event("Stock charging restored");return 0;
}
static int exact_device(void){char model[PROP_VALUE_MAX],build[PROP_VALUE_MAX];if(__system_property_get("ro.product.model",model)<=0||__system_property_get("ro.build.display.id",build)<=0||strcmp(model,"SPH-D710")||strcmp(build,"IMM76I.FL24"))return 0;char b[512];return !read_text("/proc/version",b,sizeof(b))&&strstr(b,"3.0.15-1173404")&&strstr(b,"Dec 24")&&strstr(b,"2012");}
static int prepare(void){
 if(getuid()!=0||!exact_device()||read_text("/proc/sys/kernel/random/boot_id",boot_id,sizeof(boot_id))||strlen(boot_id)!=36)return -1;
 for(const char *p=boot_id;*p;p++)if(!strchr("0123456789abcdef-",*p))return -1;
 struct stat st;if(lstat(ROOT,&st)){if(errno!=ENOENT||mkdir(ROOT,0700))return -1;}
 if(lstat(ROOT,&st)||!S_ISDIR(st.st_mode)||st.st_uid||st.st_nlink<2||(st.st_mode&0777)!=0700)return -1;
 online_fd=open_control();return online_fd<0?-1:0;
}
static int named_socket(const char *name,int listen_now){
 int fd=socket(AF_UNIX,SOCK_STREAM,0);if(fd<0)return -1;fcntl(fd,F_SETFD,FD_CLOEXEC);
 struct sockaddr_un a;memset(&a,0,sizeof(a));a.sun_family=AF_UNIX;size_t n=strlen(name);if(n>=sizeof(a.sun_path)-1){close(fd);return -1;}memcpy(a.sun_path+1,name,n);
 if(bind(fd,(struct sockaddr*)&a,offsetof(struct sockaddr_un,sun_path)+n+1)||(listen_now&&listen(fd,4))){close(fd);return -1;}
 return fd;
}
static int attribute(const char *line,const char *key,char *out,size_t cap){
 char needle[64];snprintf(needle,sizeof(needle)," %s=\"",key);const char *p=strstr(line,needle);if(!p)return -1;p+=strlen(needle);const char *end=strchr(p,'"');if(!end||end==p||(size_t)(end-p)>=cap)return -1;memcpy(out,p,(size_t)(end-p));out[end-p]=0;return 0;
}
static int apk_identity(void){
 FILE *f=fopen("/data/system/packages.xml","r");if(!f)return 0;char line[8192],path[256]={0},uid[32]={0};int found=0;
 while(fgets(line,sizeof(line),f)){if(!strstr(line,"<package ")||!strstr(line," name=\"org.retrobridge.callprobe\""))continue;
  if(!attribute(line,"codePath",path,sizeof(path))&&!attribute(line,"userId",uid,sizeof(uid)))found=1;break;}
 fclose(f);if(!found||atoi(uid)!=CHARGE_APP_UID)return 0;
 if(strcmp(path,"/data/app/org.retrobridge.callprobe-1.apk")&&strcmp(path,"/data/app/org.retrobridge.callprobe-2.apk"))return 0;
 int fd=open(path,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return 0;struct stat st;int good=!fstat(fd,&st)&&S_ISREG(st.st_mode)&&st.st_uid==1000&&st.st_nlink==1&&!(st.st_mode&0022)&&st.st_size>0&&st.st_size<2*1024*1024;
 CompanionSha256 h;companion_sha256_init(&h);unsigned char b[8192],digest[32];char hex[65];ssize_t n;size_t total=0;
 while(good&&(n=read(fd,b,sizeof(b)))>0){total+=(size_t)n;if(total>2*1024*1024){good=0;break;}companion_sha256_update(&h,b,(size_t)n);}
 close(fd);if(!good||total!=(size_t)st.st_size)return 0;companion_sha256_final(&h,digest);companion_sha256_hex(digest,hex);return !strcmp(hex,CHARGE_APP_SHA);
}
static int allowed_client(int fd){struct ucred c;socklen_t n=sizeof(c);if(getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&c,&n)||n!=sizeof(c))return 0;return c.uid==0||(c.uid==CHARGE_APP_UID&&apk_identity());}
static void on_signal(int sig){(void)sig;stopping=1;}
static int signals(void){void *lib=dlopen("/system/lib/libc.so",RTLD_NOW);if(!lib)return -1;typedef void(*H)(int);H(*s)(int,H)=dlsym(lib,"bsd_signal");if(!s)return -1;
 return s(SIGTERM,on_signal)==SIG_ERR||s(SIGINT,on_signal)==SIG_ERR||s(SIGHUP,SIG_IGN)==SIG_ERR||s(SIGPIPE,SIG_IGN)==SIG_ERR?-1:0;}
static int enabled,latched,reason=REASON_SENSOR,fault;
static long long retry_after;
static void tick(void){
 int owned=journal_get();ChargeReading r=reading(owned);
 if(r.physical==0&&owned){journal_set(0);owned=0;}
 if(!trial)enabled=setting("enabled",0);
 ChargeDecision d=charge_decide(&r,enabled,latched,owned!=0,high,low);reason=d.reason;
 if(d.latched!=latched){if(!trial&&set_setting("latched",d.latched)){fault=1;return;}latched=d.latched;}
 if(now_ms()<retry_after)return;
 if(d.action==CHARGE_PAUSE){
  if(owned&&number(ONLINE)==0&&r.charger_enabled==0){fault=0;return;}
  if(journal_set(r.cable)||write_cable(0)){fault=1;retry_after=now_ms()+60000;log_event("Pause failed; retry limited to once per minute");return;}
  fault=0;log_event(reason==REASON_CAP?"Charging paused at limit":"Charging paused by safety check");
 }else if(d.action==CHARGE_RESUME){
  if(restore_owned()){fault=1;retry_after=now_ms()+60000;return;}
  fault=0;log_event(enabled?"Charging resumed below limit":"Limit disabled; stock charging resumed");
 }else fault=0;
}
static void status_json(char *out,size_t cap){ChargeReading r=reading(journal_get());int remaining=trial?(int)((trial_end-boot_ms())/1000):0;
 snprintf(out,cap,"{\"enabled\":%s,\"limit\":%d,\"resume\":%d,\"soc\":%d,\"temperature\":%d,\"plugged\":%s,\"paused\":%s,\"chargerEnabled\":%s,\"reason\":\"%s\",\"fault\":%s,\"worker\":%d,\"supervisor\":%d,\"trialSeconds\":%d}\n",enabled?"true":"false",high,low,r.soc,r.temp,r.physical==1?"true":"false",journal_get()&&r.charger_enabled==0?"true":"false",r.charger_enabled==1?"true":"false",reasons[reason],fault?"true":"false",getpid(),getppid(),remaining>0?remaining:0);
}
static void serve(int listener){
 int fd=accept(listener,NULL,NULL);if(fd<0)return;fcntl(fd,F_SETFD,FD_CLOEXEC);
 if(!allowed_client(fd)){static const char msg[]="{\"error\":\"unauthorized\"}\n";send(fd,msg,sizeof(msg)-1,MSG_NOSIGNAL);close(fd);return;}
 struct pollfd p={fd,POLLIN,0};char cmd[32]={0};if(poll(&p,1,1000)<=0){close(fd);return;}
 ssize_t n=recv(fd,cmd,sizeof(cmd)-1,0);if(n<=0){close(fd);return;}cmd[n]=0;
 if(!strcmp(cmd,"ENABLE\n")||!strcmp(cmd,"DISABLE\n")){
  if(trial){send(fd,"{\"error\":\"trial\"}\n",18,MSG_NOSIGNAL);close(fd);return;}
  int value=!strcmp(cmd,"ENABLE\n");if(set_setting("enabled",value)){close(fd);return;}enabled=value;
 }else if(strcmp(cmd,"STATUS\n")){close(fd);return;}
 tick();char b[640];status_json(b,sizeof(b));send(fd,b,strlen(b),MSG_NOSIGNAL);close(fd);
}
static int worker(int ctl,pid_t parent){
 stopping=0;if(setsid()<0)return 10;
 int server=named_socket(SOCKET,1);if(server<0)return 11;
 latched=trial?0:setting("latched",0);enabled=trial?1:setting("enabled",0);retry_after=0;fault=0;
 long long next=0,last=0;log_event("Charge worker started");
 while(!stopping){long long now=now_ms();if(getppid()!=parent||(trial&&boot_ms()>=trial_end))break;
  if(now>=next){tick();next=now_ms()+10000;}
  if(now-last>=5000){if(send(ctl,"H",1,MSG_NOSIGNAL)!=1)break;last=now;}
  struct pollfd fds[2]={{server,POLLIN,0},{ctl,POLLIN|POLLHUP,0}};int r=poll(fds,2,1000);if(r<0&&errno!=EINTR)break;
  if(fds[1].revents&(POLLIN|POLLHUP|POLLERR)){char b;if(recv(ctl,&b,1,0)<=0||b=='S')break;}
  if(fds[0].revents&POLLIN)serve(server);
 }
 close(server);restore_owned();close(ctl);return 0;
}
static int supervise(void){
 int lock=named_socket(GUARD,0);if(lock<0){puts("Controller already running");return 5;}
 int failures=0;while(!stopping&&(!trial||boot_ms()<trial_end)){
  int pair[2];if(socketpair(AF_UNIX,SOCK_STREAM,0,pair))break;pid_t parent=getpid(),child=fork();
  if(child<0){close(pair[0]);close(pair[1]);break;}
  if(!child){close(lock);close(pair[0]);int r=worker(pair[1],parent);_exit(r);}
  close(pair[1]);long long lease=now_ms()+30000;int status=0,reaped=0;
  while(!stopping&&(!trial||boot_ms()<trial_end)){
   pid_t found=waitpid(child,&status,WNOHANG);if(found==child){reaped=1;break;}
   if(now_ms()>lease)break;struct pollfd p={pair[0],POLLIN|POLLHUP,0};poll(&p,1,1000);
   if(p.revents&POLLIN){char b[64];ssize_t n=recv(pair[0],b,sizeof(b),MSG_DONTWAIT);if(n<=0)break;int valid=1;for(ssize_t i=0;i<n;i++)if(b[i]!='H')valid=0;if(!valid)break;lease=now_ms()+30000;}
   if(p.revents&(POLLHUP|POLLERR))break;
  }
  if(!reaped){send(pair[0],"S",1,MSG_NOSIGNAL);long long until=now_ms()+8000;
   while(now_ms()<until){pid_t r=waitpid(child,&status,WNOHANG);if(r==child||r<0){reaped=1;break;}sleep_ms(100);}
   if(!reaped){kill(child,SIGKILL);while(waitpid(child,&status,0)<0&&errno==EINTR){}reaped=1;}
  }
  close(pair[0]);restore_owned();chmod(ONLINE,0444);
  if(!stopping&&(!trial||boot_ms()<trial_end)){failures++;log_event("Worker stopped; restored and restarting");sleep_ms(failures<4?2000:30000);}
 }
 restore_owned();chmod(ONLINE,0444);close(lock);return 0;
}
static int client(const char *command){
 if(strcmp(command,"STATUS")&&strcmp(command,"ENABLE")&&strcmp(command,"DISABLE"))return 2;
 int fd=socket(AF_UNIX,SOCK_STREAM,0);if(fd<0)return 3;struct sockaddr_un a;memset(&a,0,sizeof(a));a.sun_family=AF_UNIX;memcpy(a.sun_path+1,SOCKET,strlen(SOCKET));
 if(connect(fd,(struct sockaddr*)&a,offsetof(struct sockaddr_un,sun_path)+1+strlen(SOCKET))){close(fd);return 4;}
 struct ucred peer;socklen_t n=sizeof(peer);if(getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&peer,&n)||peer.uid!=0){close(fd);return 5;}
 char b[1024];snprintf(b,sizeof(b),"%s\n",command);send(fd,b,strlen(b),MSG_NOSIGNAL);struct pollfd p={fd,POLLIN,0};if(poll(&p,1,7000)<=0){close(fd);return 6;}
 ssize_t got=recv(fd,b,sizeof(b)-1,0);close(fd);if(got<=0)return 7;b[got]=0;write(1,b,(size_t)got);return strstr(b,"\"error\"")?8:0;
}
int main(int argc,char **argv){
 if(argc==2&&!strcmp(argv[1],"--self-test")){int n=charge_policy_tests();printf("policy_checks=%d\n",n);return n<0;}
 if(argc==3&&!strcmp(argv[1],"--client")){int r=client(argv[2]);if(r)printf("client_error=%d errno=%d\n",r,errno);return r;}
 if(argc==5&&!strcmp(argv[1],"--trial")){int seconds=atoi(argv[2]);high=atoi(argv[3]);low=atoi(argv[4]);long long start=boot_ms();if(start<0||seconds<20||seconds>300||high<20||high>80||low<10||low>=high)return 2;trial=1;trial_end=start+seconds*1000LL;}
 else if(argc!=2||strcmp(argv[1],"--run"))return 2;
 if(prepare()||signals()){puts("Controller preflight failed");return 3;}
 int r=supervise();close(online_fd);return r;
}
