/* Included after the bounded socket/time helpers in stock_ril.c. */
#include <sys/prctl.h>
#include <sys/ioctl.h>
#include "sha256.h"
#include "lifecycle.h"
#define ROUTE_SOCKET "org.retrobridge.callprobe.route.v1"
typedef struct { int uid;char device[128],build[128],apk[256],apk_hash[65],cert_hash[65],jar[256],jar_hash[65],launcher[256],launcher_hash[65],worker[256],worker_hash[65]; } Config;
static Config config;
static char config_path[256];
static int worker_ctl=-1;
static Lifecycle lifecycle;
static long long last_heartbeat;
static int cloexec(int fd){return fcntl(fd,F_SETFD,FD_CLOEXEC);}
static int decimal(const char *p,int max){int n=0;if(!p||!*p)return -1;for(;*p;p++){if(*p<'0'||*p>'9'||n>(max-(*p-'0'))/10)return -1;n=n*10+*p-'0';}return n;}
static int valid_hash(const char *p){if(strlen(p)!=64)return 0;for(int i=0;i<64;i++)if(!strchr("0123456789abcdef",p[i]))return 0;return 1;}
static int safe_path(const char *p){if(!p||p[0]!='/'||strlen(p)>250||strstr(p,"/../")||strstr(p,"/./")||strstr(p,"//"))return 0;for(;*p;p++)if(!((*p>='a'&&*p<='z')||(*p>='A'&&*p<='Z')||(*p>='0'&&*p<='9')||strchr("/._-",*p)))return 0;return 1;}
static int file_hash(const char *path,char hex[65],int root,int executable){
 struct stat st,end;int fd=open(path,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return -1;
 if(fstat(fd,&st)||!S_ISREG(st.st_mode)||(root?st.st_uid!=0:(st.st_uid!=0&&st.st_uid!=1000))||(st.st_mode&022)||(executable&&!(st.st_mode&0100))||st.st_size<1||st.st_size>64*1024*1024){close(fd);return -1;}
 CompanionSha256 s;companion_sha256_init(&s);unsigned char b[4096];ssize_t n;while((n=read(fd,b,sizeof(b)))>0)companion_sha256_update(&s,b,(size_t)n);
 int bad=n<0||fstat(fd,&end)||st.st_size!=end.st_size||st.st_mtime!=end.st_mtime||st.st_ctime!=end.st_ctime;close(fd);if(bad)return -1;unsigned char d[32];companion_sha256_final(&s,d);companion_sha256_hex(d,hex);return 0;
}
static int checked_file(const char *p,const char *hash,int executable){char actual[65];return !safe_path(p)||!valid_hash(hash)||file_hash(p,actual,1,executable)||strcmp(actual,hash)?-1:0;}
static int load_config(const char *path){
 if(!safe_path(path))return -1;int fd=open(path,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return -1;struct stat st;char text[4097];
 if(fstat(fd,&st)||!S_ISREG(st.st_mode)||st.st_uid!=0||(st.st_mode&0777)!=0600||st.st_size<1||st.st_size>4095){close(fd);return -1;}
 ssize_t size=read(fd,text,sizeof(text)-1);close(fd);if(size!=st.st_size)return -1;text[size]=0;if(memchr(text,0,(size_t)size))return -1;
 static const char *keys[]={"version","device","build","app_uid","app_apk_path","app_apk_sha256","app_cert_sha256","identity_jar_path","identity_jar_sha256","launcher_path","launcher_sha256","worker_path","worker_sha256"};
 char *values[13]={0};unsigned seen=0;char *line=text;
 while(*line){char *next=strchr(line,'\n');if(next)*next++=0;else next=line+strlen(line);if(!*line)return -1;char *eq=strchr(line,'=');if(!eq)return -1;*eq++=0;int index=-1;for(int i=0;i<13;i++)if(!strcmp(line,keys[i]))index=i;if(index<0||(seen&(1u<<index))||!*eq||strlen(eq)>250)return -1;for(char *p=eq;*p;p++)if(*p<33||*p>126)return -1;seen|=1u<<index;values[index]=eq;line=next;}
 if(seen!=8191||strcmp(values[0],"1"))return -1;memset(&config,0,sizeof(config));config.uid=decimal(values[3],INT_MAX);if(config.uid<10000||strlen(values[1])>=sizeof(config.device)||strlen(values[2])>=sizeof(config.build))return -1;
 strcpy(config.device,values[1]);strcpy(config.build,values[2]);strcpy(config.apk,values[4]);strcpy(config.jar,values[7]);strcpy(config.launcher,values[9]);strcpy(config.worker,values[11]);
 if(!safe_path(config.apk)||!safe_path(config.jar)||!safe_path(config.launcher)||!safe_path(config.worker))return -1;
 int indexes[]={5,6,8,10,12};char *targets[]={config.apk_hash,config.cert_hash,config.jar_hash,config.launcher_hash,config.worker_hash};for(int i=0;i<5;i++){if(!valid_hash(values[indexes[i]]))return -1;strcpy(targets[i],values[indexes[i]]);}
 strcpy(config_path,path);return 0;
}
static int props_init(void){void *lib=dlopen("/system/lib/libcutils.so",RTLD_NOW|RTLD_LOCAL);if(!lib)return -1;property_get_fn=dlsym(lib,"property_get");property_set_fn=dlsym(lib,"property_set");return property_get_fn&&property_set_fn?0:-1;}
static int device_matches(void){char value[128];property_get_fn("ro.product.model",value,"");if(strcmp(value,config.device))return 0;property_get_fn("ro.build.display.id",value,"");return !strcmp(value,config.build)&&library_matches();}
static int own_image_matches(const char *wanted,const char *hash){char path[256];ssize_t n=readlink("/proc/self/exe",path,sizeof(path)-1);if(n<1||n>=(ssize_t)sizeof(path)-1)return 0;path[n]=0;return !strcmp(path,wanted)&&!checked_file(path,hash,1);}
static int reap_force(pid_t child){kill(child,SIGKILL);long long end=now_ms()+1000;do{pid_t r=waitpid(child,NULL,WNOHANG);if(r==child||(r<0&&errno==ECHILD))return 0;poll(NULL,0,20);}while(now_ms()<end);return -1;}
static pthread_mutex_t identity_mutex=PTHREAD_MUTEX_INITIALIZER;
/* Diagnostic-only numeric state; never stores identity record contents. */
static volatile int identity_diagnostic_stage,identity_diagnostic_result;
static void identity_diagnostic(int stage,int result){identity_diagnostic_result=result;identity_diagnostic_stage=stage;}
static int verified_identity_inner(void){
 identity_diagnostic(1,0);
 static pid_t orphan=-1;if(orphan>0){pid_t r=waitpid(orphan,NULL,WNOHANG);if(r==orphan||(r<0&&errno==ECHILD))orphan=-1;else{identity_diagnostic(2,0);return -1;}}
 if(checked_file(config.jar,config.jar_hash,0)){identity_diagnostic(3,0);return -1;}int pipefd[2];if(pipe(pipefd)){identity_diagnostic(4,errno);return -1;}cloexec(pipefd[0]);cloexec(pipefd[1]);
 char classpath[300];snprintf(classpath,sizeof(classpath),"CLASSPATH=%s",config.jar);
 /* Exact captured FL24 init environment, not inherited untrusted LD_PRELOAD. */
 char *env[]={classpath,"PATH=/system/bin","ANDROID_ROOT=/system","ANDROID_DATA=/data","ANDROID_ASSETS=/system/app","LD_LIBRARY_PATH=/vendor/lib:/system/lib","BOOTCLASSPATH=/system/framework/core.jar:/system/framework/core-junit.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/framework2.jar:/system/framework/android.policy.jar:/system/framework/services.jar:/system/framework/apache-xml.jar:/system/framework/filterfw.jar:/system/framework/sechardware.jar:/system/framework/wimax.jar",NULL};char *args[]={"/system/bin/app_process","/system/bin","org.retrobridge.galaxyidentity.IdentityMain","org.retrobridge.callprobe",NULL};pid_t parent=getpid();pid_t child=fork();
 if(child<0){identity_diagnostic(5,errno);close(pipefd[0]);close(pipefd[1]);return -1;}if(!child){if(prctl(PR_SET_PDEATHSIG,SIGKILL)||getppid()!=parent)_exit(120);if(dup2(pipefd[1],1)<0)_exit(121);int nullfd=open("/dev/null",O_WRONLY);if(nullfd>=0)dup2(nullfd,2);for(int fd=3;fd<1024;fd++)close(fd);execve(args[0],args,env);_exit(122);}
 identity_diagnostic(6,0);
 close(pipefd[1]);nonblock(pipefd[0]);char result[1024];size_t used=0;long long deadline=now_ms()+3000;int status=0,done=0,eof=0,bad=0;
 while(now_ms()<deadline&&!stop_requested){if(!done){pid_t r=waitpid(child,&status,WNOHANG);if(r==child)done=1;else if(r<0){bad=1;break;}}ssize_t n=read(pipefd[0],result+used,sizeof(result)-1-used);if(n>0){used+=(size_t)n;if(used==sizeof(result)-1){bad=1;break;}}else if(!n)eof=1;else if(errno!=EAGAIN&&errno!=EWOULDBLOCK&&errno!=EINTR){bad=1;break;}if(done&&eof)break;struct pollfd p={pipefd[0],POLLIN,0};poll(&p,1,20);}
 close(pipefd[0]);if(!done){identity_diagnostic(7,bad);if(reap_force(child))orphan=child;return -1;}if(bad||!eof||!WIFEXITED(status)||WEXITSTATUS(status)){identity_diagnostic(8,(bad?1:0)|(!eof?2:0)|(!WIFEXITED(status)?4:0)|(WIFEXITED(status)?WEXITSTATUS(status)<<8:0));return -1;}result[used]=0;
 char expected[1024];int n=snprintf(expected,sizeof(expected),"IDENTITY\t1\t%d\torg.retrobridge.callprobe\t%s\t%s\t%s\n",config.uid,config.apk,config.apk_hash,config.cert_hash);int match=n>0&&n<(int)sizeof(expected)&&used==(size_t)n&&!memcmp(result,expected,used);identity_diagnostic(9,match?0:used!=(size_t)n?1:2);return match?0:-1;
}
static int verified_identity(void){pthread_mutex_lock(&identity_mutex);int result=verified_identity_inner();pthread_mutex_unlock(&identity_mutex);return result;}
static int heartbeat(void){if(worker_ctl<0)return 0;long long now=now_ms();if(now-last_heartbeat<1000)return 0;int voice=0;pthread_mutex_lock(&model_mutex);if(model.ready)for(int i=0;i<model.count;i++)if(model.calls[i].mode==0)voice=1;pthread_mutex_unlock(&model_mutex);char b=voice?'A':'I';if(send_all(worker_ctl,&b,1,300))return -1;last_heartbeat=now;return 0;}
static int abstract_listener(const char *name){int fd=socket(AF_UNIX,SOCK_STREAM,0);if(fd<0)return -1;cloexec(fd);struct sockaddr_un a;memset(&a,0,sizeof(a));a.sun_family=AF_UNIX;size_t n=strlen(name);if(n>=sizeof(a.sun_path)-1){close(fd);return -1;}memcpy(a.sun_path+1,name,n);if(bind(fd,(struct sockaddr*)&a,offsetof(struct sockaddr_un,sun_path)+1+n)||listen(fd,4)||nonblock(fd)){close(fd);return -1;}return fd;}
static int socket_named(int fd,const char *name,int abstract){struct sockaddr_un a;socklen_t n=sizeof(a);int type=0;socklen_t t=sizeof(type);memset(&a,0,sizeof(a));if(getsockopt(fd,SOL_SOCKET,SO_TYPE,&type,&t)||type!=SOCK_STREAM||getsockname(fd,(struct sockaddr*)&a,&n)||a.sun_family!=AF_UNIX)return 0;size_t length=strlen(name);if(n<0||(size_t)n>sizeof(a)||length>=sizeof(a.sun_path)-1)return 0;if(abstract)return (size_t)n==offsetof(struct sockaddr_un,sun_path)+1+length&&!a.sun_path[0]&&!memcmp(a.sun_path+1,name,length);return (size_t)n>=offsetof(struct sockaddr_un,sun_path)+length&&!memcmp(a.sun_path,name,length+1);}
static int init_fd(const char *env,const char *path,uid_t uid,gid_t gid){int fd=decimal(getenv(env),1023);struct stat st;if(fd<3||!socket_named(fd,path,0)||lstat(path,&st)||!S_ISSOCK(st.st_mode)||st.st_uid!=uid||st.st_gid!=gid||(st.st_mode&0777)!=0660)return -1;cloexec(fd);return fd;}
/* HCIGETCONNLIST header ABI only: refuse a route WRITE if ANY BT link exists.
 * The oversized row area handles both legacy and Android-extended row layouts.
 * AOSP BlueZ include/bluetooth/hci.h defines _IOR('H',212,int), two u16 header.
 * We intentionally do not interpret rows or assume a Samsung row stride. */
static int route_transport(int voice_busy){
 struct HciRequest{uint16_t ogf,ocf;int event;void *cparam;int clen;void *rparam;int rlen;};void *lib=dlopen("/system/lib/libbluetooth.so",RTLD_NOW|RTLD_LOCAL);if(!lib)return -1;
 int(*open_dev)(int)=dlsym(lib,"hci_open_dev");int(*send_req)(int,struct HciRequest*,int)=dlsym(lib,"hci_send_req");if(!open_dev||!send_req)return -1;int fd=open_dev(0);if(fd<0)return -1;
 unsigned char current[6]={0},wanted[5],status=255,verify[6]={0};struct HciRequest r={0x3f,0x001d,0,NULL,0,current,6};int ok=0;
 if(send_req(fd,&r,1800)||r.rlen!=6||current[0])goto end;if(current[1]==1){ok=1;goto end;}if(voice_busy)goto end;
 struct {uint16_t dev_id,count;unsigned char rows[64*64];} connections;memset(&connections,0,sizeof(connections));connections.count=64;if(ioctl(fd,_IOR('H',212,int),&connections)||connections.count)goto end;
 memcpy(wanted,current+1,5);wanted[0]=1;struct HciRequest w={0x3f,0x001c,0,wanted,5,&status,1};if(send_req(fd,&w,1800)||w.rlen!=1||status)goto end;r.rparam=verify;r.rlen=6;if(send_req(fd,&r,1800)||r.rlen!=6||verify[0]||memcmp(verify+1,wanted,5))goto end;ok=1;
end: close(fd);return ok?0:-1;
}
static void route_client(int fd,int voice_busy){
 struct ucred peer;socklen_t n=sizeof(peer);int good=!getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&peer,&n)&&n==sizeof(peer)&&peer.uid==(uid_t)config.uid;
 LineReader reader;memset(&reader,0,sizeof(reader));char *line=NULL;long long end=now_ms()+1000;while(good&&now_ms()<end){int r=read_line(fd,&reader,&line);if(r<0){good=0;break;}if(r>0)break;struct pollfd p={fd,POLLIN,0};poll(&p,1,20);}if(!line||strcmp(line,"ROUTE\t1"))good=0;
 if(good&&(verified_identity()||route_transport(voice_busy)))good=0;const char *reply=good?"ROUTE\tOK\n":"ROUTE\tERROR\n";send_all(fd,reply,strlen(reply),500);shutdown(fd,SHUT_RDWR);close(fd);_exit(good?0:1);
}
