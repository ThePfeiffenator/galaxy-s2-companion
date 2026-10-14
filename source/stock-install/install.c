#define _GNU_SOURCE 1
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <sys/syscall.h>
#include <sys/mount.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/xattr.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stddef.h>
#include <signal.h>
#include <dlfcn.h>
#include <poll.h>
#include "sha256.h"
#include "transaction.h"

typedef struct { const char *source,*target,*sha; size_t size; mode_t mode; uid_t uid; gid_t gid; } Artifact;
#ifdef FIXTURE_BUILD
#include "fixture_manifest.h"
#else
#include "install_manifest.h"
#endif
#define ORIGINAL_SHA "12f3c38e67d45f993d44b6a3b29c8f6f885386b1d8efcb444735d8ab4b76e4ad"
#define LIBRIL_SHA "97770ea4d5a8568e230d225a7957ebb2141bcdabfa15c81ec122b2d5f3b11864"
#define RILD "/system/bin/rild"
#define BACKUP_DIR "/system/etc/galaxy-companion-recovery"
#define BACKUP BACKUP_DIR "/rild.original"
static const Artifact original={BACKUP,RILD,ORIGINAL_SHA,9872,0755,0,2000};
static const Artifact backup={RILD,BACKUP,ORIGINAL_SHA,9872,0755,0,2000};
static int (*getprop)(const char*,char*,const char*);
static int (*setprop)(const char*,const char*);
static const char *exercise_root;
static volatile sig_atomic_t interrupted;
static int fixture_readonly=1,fixture_running=1,fixture_fail=-1,fixture_postrename;
static enum TxMode active_mode;
static TxState transaction_state;

static void report(const char *text){size_t n=strlen(text);while(n){ssize_t w=write(1,text,n);if(w<0&&errno==EINTR)continue;if(w<=0)break;text+=w;n-=(size_t)w;}}
static void on_signal(int sig){(void)sig;interrupted=1;}
static int restoring(void){return active_mode==TX_RESTORE_RIL;}
static int safe_absolute(const char *p){
    if(!p||*p!='/'||strlen(p)>380||strstr(p,"//")||strstr(p,"/../")||strstr(p,"/./"))return 0;
    size_t n=strlen(p);if(n<2||p[n-1]=='/'||!strcmp(p+n-2,"/.")||(n>=3&&!strcmp(p+n-3,"/..")))return 0;
    for(;*p;p++)if(!((*p>='a'&&*p<='z')||(*p>='A'&&*p<='Z')||(*p>='0'&&*p<='9')||strchr("/._-",*p)))return 0;return 1;
}
/* Fixture builds map EVERY filesystem input. There is no real-path fallback. */
static const char *path(const char *input,char output[768]){
    if(!safe_absolute(input))return NULL;
    if(exercise_root){if(snprintf(output,768,"%s%s",exercise_root,input)>=768)return NULL;return output;}return input;
}
static int trusted_parents(const char *name){
    char p[768];size_t n=strlen(name);if(n>=sizeof(p))return -1;strcpy(p,name);size_t start=1;
    if(exercise_root){size_t base=strlen(exercise_root);if(strncmp(p,exercise_root,base)||p[base]!='/')return -1;start=base;}
    for(size_t i=start;i<n;i++)if(p[i]=='/'){struct stat s;char c=p[i];p[i]=0;int bad=lstat(p,&s)||!S_ISDIR(s.st_mode);
        if(!bad){int trusted=s.st_uid==0&&!(s.st_mode&0022);
            /* Exact factory ancestors; the root-owned stage beneath them is
             * independently checked. Android init intentionally owns these. */
            if(!exercise_root&&!strcmp(p,"/data"))trusted=trusted||(s.st_uid==1000&&s.st_gid==1000&&(s.st_mode&07777)==0771);
            if(!exercise_root&&!strcmp(p,"/data/local"))trusted=trusted||(s.st_uid==2000&&s.st_gid==2000&&(s.st_mode&07777)==0771);
            bad=!trusted;}
        p[i]=c;if(bad)return -1;
    }
    return 0;
}
static int digest_fd(int fd,char hex[65],size_t *length){
    CompanionSha256 s;companion_sha256_init(&s);unsigned char data[8192],digest[32];size_t total=0;ssize_t n;
    if(lseek(fd,0,SEEK_SET)<0)return -1;
    while((n=read(fd,data,sizeof(data)))!=0){if(n<0){if(errno==EINTR)continue;return -1;}total+=(size_t)n;if(total>16*1024*1024)return -1;companion_sha256_update(&s,data,(size_t)n);}
    companion_sha256_final(&s,digest);companion_sha256_hex(digest,hex);if(length)*length=total;return 0;
}
static int trusted_regular(int fd,struct stat *st){return fstat(fd,st)||!S_ISREG(st->st_mode)||st->st_nlink!=1||st->st_uid!=0||(st->st_mode&0022)?-1:0;}
static int matches(const char *name,const char *sha,size_t bytes,int attributes,mode_t mode,uid_t uid,gid_t gid){
    char mapped[768],hex[65];size_t length=0;const char *p=path(name,mapped);if(!p||trusted_parents(p))return 0;
    int fd=open(p,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return 0;struct stat st,end;int good=!trusted_regular(fd,&st)&&st.st_size==(off_t)bytes;
    if(attributes)good=good&&(st.st_mode&07777)==mode&&st.st_uid==uid&&st.st_gid==gid;
    good=good&&!digest_fd(fd,hex,&length)&&length==bytes&&!strcmp(hex,sha)&&!fstat(fd,&end)&&st.st_size==end.st_size&&st.st_mtime==end.st_mtime&&st.st_ctime==end.st_ctime;
    close(fd);return good;
}
static int exists(const char *name){char p[768];struct stat st;const char *q=path(name,p);return !q||!lstat(q,&st)||errno!=ENOENT;}
static int sync_parent(const char *name){char parent[768];if(strlen(name)>=sizeof(parent))return -1;strcpy(parent,name);char *end=strrchr(parent,'/');if(!end)return -1;*end=0;int fd=open(parent,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return -1;int r=fsync(fd);close(fd);return r;}
static int directory(const char *name,mode_t mode,int create){
    char mapped[768];const char *p=path(name,mapped);if(!p||trusted_parents(p))return -1;struct stat st;
    if(lstat(p,&st)){if(!create||errno!=ENOENT||mkdir(p,mode))return -1;if(chown(p,0,0)||chmod(p,mode)||sync_parent(p)||lstat(p,&st))return -1;}
    return S_ISDIR(st.st_mode)&&st.st_uid==0&&!(st.st_mode&0022)?0:-1;
}
static int no_xattrs(const char *name){char mapped[768];const char *p=path(name,mapped);if(!p)return 0;long n=syscall(__NR_llistxattr,p,NULL,0);return n==0||(n<0&&(errno==ENOTSUP||errno==EOPNOTSUPP));}
/* Restore tolerates missing/corrupt content; never a symlink or hardlink. */
static int replaceable(const char *name){char mapped[768];const char *p=path(name,mapped);if(!p||trusted_parents(p))return 0;struct stat st;if(lstat(p,&st))return errno==ENOENT;return S_ISREG(st.st_mode)&&st.st_uid==0&&st.st_nlink==1&&!(st.st_mode&0022)&&no_xattrs(name);}
static int copy_atomic(const Artifact *a,int replace,int final_commit){
    char mapped[768],source_path[768],temporary[800],hex[65];size_t bytes=0;
    const char *target=path(a->target,mapped),*source_name=path(a->source,source_path);
    if(!target||!source_name||trusted_parents(target)||trusted_parents(source_name))return -1;
    /* A crashed apply's preserved temp must not block independent restoration. */
    const char *suffix=final_commit&&restoring()?".gc-restore-stage":".gc-stage";
    if(snprintf(temporary,sizeof(temporary),"%s%s",target,suffix)>=(int)sizeof(temporary))return -1;
    if(!replace&&exists(a->target))return matches(a->target,a->sha,a->size,1,a->mode,a->uid,a->gid)?0:-1;
    if(!replaceable(a->target))return -1;
    int input=open(source_name,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);if(input<0)return -1;
    struct stat source,end;int output=-1,rc=-1,owned_temp=0;unsigned char buffer[8192];ssize_t n;size_t copied=0;
    if(trusted_regular(input,&source)||source.st_size!=(off_t)a->size||digest_fd(input,hex,&bytes)||bytes!=a->size||strcmp(hex,a->sha)||lseek(input,0,SEEK_SET)<0)goto done;
    output=open(temporary,O_RDWR|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);if(output<0)goto done;owned_temp=1;
    while((n=read(input,buffer,sizeof(buffer)))!=0){
        if(n<0){if(errno==EINTR)continue;goto done;}if((size_t)n>a->size-copied)goto done;copied+=(size_t)n;
        ssize_t off=0;while(off<n){ssize_t w=write(output,buffer+off,(size_t)(n-off));if(w<0&&errno==EINTR)continue;if(w<=0)goto done;off+=w;}
    }
    if(copied!=a->size||fstat(input,&end)||source.st_size!=end.st_size||source.st_mtime!=end.st_mtime||source.st_ctime!=end.st_ctime||digest_fd(output,hex,&bytes)||bytes!=a->size||strcmp(hex,a->sha)||fchown(output,a->uid,a->gid)||fchmod(output,a->mode)||fsync(output))goto done;
    if(close(output)){output=-1;goto done;}output=-1;
    if(!replaceable(a->target)||rename(temporary,target))goto done;owned_temp=0;
    if(final_commit)transaction_state.committed=1;
    if(final_commit&&exercise_root&&fixture_postrename)goto done;
    if(sync_parent(target)||!matches(a->target,a->sha,a->size,1,a->mode,a->uid,a->gid))goto done;rc=0;
done:
    if(output>=0)close(output);close(input);
    /* O_EXCL failure must not remove a prior invocation's temporary file. */
    if(owned_temp)unlink(temporary);return rc;
}
static int property_equals(const char *key,const char *wanted){char value[128]={0};getprop(key,value,"");return !strcmp(value,wanted);}
static int service_wait(const char *wanted){for(int i=0;i<100;i++){if(property_equals("init.svc.ril-daemon",wanted))return 0;poll(NULL,0,100);}return -1;}
static int stop_service(void){if(exercise_root){fixture_running=0;return 0;}return setprop("ctl.stop","ril-daemon")||service_wait("stopped")?-1:0;}
static int start_service(void){if(exercise_root){fixture_running=1;return 0;}return setprop("ctl.start","ril-daemon")||service_wait("running")?-1:0;}
static int mount_readonly(void){
    if(exercise_root)return fixture_readonly;
    FILE *f=fopen("/proc/mounts","r");if(!f)return -1;char line[1024],dev[256],where[256],type[80],options[400];int result=-1;
    while(fgets(line,sizeof(line),f))if(sscanf(line,"%255s %255s %79s %399s",dev,where,type,options)==4&&!strcmp(where,"/system")){
        if(strcmp(dev,"/dev/block/mmcblk0p9")||strcmp(type,"ext4"))break;result=!strncmp(options,"ro,",3)||!strcmp(options,"ro");break;
    }
    fclose(f);return result;
}
static int remount(int readonly){
    if(exercise_root){fixture_readonly=readonly;return 0;}
    if(mount("/dev/block/mmcblk0p9","/system","ext4",MS_REMOUNT|MS_NOATIME|(readonly?MS_RDONLY:0),"barrier=1,data=ordered"))return -1;
    return mount_readonly()==readonly?0:-1;
}
static int guard(void){
    if(getuid()!=0||companion_sha256_self_test())return -1;
    if(!exercise_root){void *lib=dlopen("/system/lib/libcutils.so",RTLD_NOW|RTLD_LOCAL);if(!lib)return -1;getprop=dlsym(lib,"property_get");setprop=dlsym(lib,"property_set");if(!getprop||!setprop)return -1;
        if(!property_equals("ro.serialno","@S2_SERIAL@")||!property_equals("ro.product.model","SPH-D710")||!property_equals("ro.build.display.id","IMM76I.FL24"))return -1;}
    if(!matches("/system/lib/libril.so",LIBRIL_SHA,57688,1,0644,0,0))return -1;
    if(directory("/system/bin",0755,0)||directory("/system/etc",0755,0)||directory("/system/framework",0755,0))return -1;return 0;
}
static int preflight(void){
    if(guard())return -1;int ro=mount_readonly();if(ro<0||(!restoring()&&ro!=1))return -1;
    if(restoring()){
        if(!matches(BACKUP,ORIGINAL_SHA,9872,1,0755,0,2000)||!replaceable(RILD))return -1;
    }else{
        if(!exercise_root&&!INSTALL_MANIFEST_READY)return -1;
        if(!matches(RILD,ORIGINAL_SHA,9872,1,0755,0,2000)||!no_xattrs(RILD))return -1;
        if(!exercise_root&&!property_equals("init.svc.ril-daemon","running"))return -1;
        if(exists(BACKUP)&&!matches(BACKUP,ORIGINAL_SHA,9872,1,0755,0,2000))return -1;
        for(int i=0;i<4;i++){
            const Artifact *a=&artifacts[i];if(!matches(a->source,a->sha,a->size,0,0,0,0))return -1;
            if(i<3&&exists(a->target)&&!matches(a->target,a->sha,a->size,1,a->mode,a->uid,a->gid))return -1;
        }
    }
    char mapped[768];const char *system_path=path("/system",mapped);struct statfs fs;
    unsigned long long needed=restoring()?9872+131072:1048576;
    if(!system_path||statfs(system_path,&fs)||(unsigned long long)fs.f_bavail*fs.f_bsize<needed)return -1;
    report(restoring()?"RESTORE_PREFLIGHT_OK\n":"INSTALL_PREFLIGHT_OK\n");return 0;
}
static int operation(enum TxStep step,TxState *state,void *context){
    (void)context;(void)state;
    if(exercise_root&&(int)step==fixture_fail)return -1;
    if(interrupted&&step!=TX_RO&&step!=TX_RECOVER_START)return -1;
    switch(step){
    case TX_PREFLIGHT:return preflight();
    case TX_RW:return remount(0);
    case TX_BACKUP:if(directory(BACKUP_DIR,0700,1))return -1;return copy_atomic(&backup,0,0);
    case TX_PREREQ0:case TX_PREREQ1:case TX_PREREQ2:return copy_atomic(&artifacts[step-TX_PREREQ0],0,0);
    case TX_STOP:return stop_service();
    case TX_VERIFY:return restoring()?(matches(BACKUP,ORIGINAL_SHA,9872,1,0755,0,2000)&&replaceable(RILD)?0:-1):(matches(RILD,ORIGINAL_SHA,9872,1,0755,0,2000)?0:-1);
    case TX_COMMIT:return copy_atomic(restoring()?&original:&artifacts[3],1,1);
    case TX_RO:return remount(1);
    case TX_START:return mount_readonly()==1?start_service():-1;
    case TX_RECOVER_START:/* ctl.stop can complete late; only resume verified original. */
        return mount_readonly()==1&&matches(RILD,ORIGINAL_SHA,9872,1,0755,0,2000)?start_service():-1;
    }return -1;
}
static int run_transaction(void){
    int rc=transaction_run(active_mode,operation,NULL,&transaction_state);
    if(rc){char text[160];snprintf(text,sizeof(text),"TRANSACTION_FAILED committed=%d readonly=%d\n",transaction_state.committed,mount_readonly());report(text);}
    else report(restoring()?"FACTORY_RESTORE_COMMITTED_AND_SERVICE_STARTED\n":"INSTALL_COMMITTED_AND_SERVICE_STARTED\n");
    if(exercise_root){char text[160];snprintf(text,sizeof(text),"FIXTURE committed=%d readonly=%d simulated_service_running=%d\n",transaction_state.committed,fixture_readonly,fixture_running);report(text);}return rc;
}
static int acquire_guard(void){
    int fd=socket(AF_UNIX,SOCK_DGRAM,0);if(fd<0)return -1;struct sockaddr_un address;memset(&address,0,sizeof(address));address.sun_family=AF_UNIX;
    const char *name=exercise_root?"org.retrobridge.galaxy.install.fixture.v1":"org.retrobridge.galaxy.install.v1";size_t size=strlen(name);memcpy(address.sun_path+1,name,size);
    if(bind(fd,(struct sockaddr*)&address,offsetof(struct sockaddr_un,sun_path)+1+size)||fcntl(fd,F_SETFD,FD_CLOEXEC)){close(fd);return -1;}return fd;
}
static int inspect(void){
    if(guard())return -1;char mapped[768],hex[65],text[300];size_t size;const char *p=path(RILD,mapped);if(!p)return -1;int fd=open(p,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return -1;struct stat st;
    if(fstat(fd,&st)||digest_fd(fd,hex,&size)){close(fd);return -1;}close(fd);
    snprintf(text,sizeof(text),"rild_sha256=%s\nrild_bytes=%u\nrild_mode=%04o\nrild_uid=%u\nrild_gid=%u\nsystem_readonly=%d\n",hex,(unsigned)size,st.st_mode&07777,st.st_uid,st.st_gid,mount_readonly());report(text);return 0;
}
#ifdef FIXTURE_BUILD
static int prepare_fixture(const char *root){
    const char *prefix="/data/local/tmp/galaxy-companion-exercise-";size_t n=strlen(prefix);if(strncmp(root,prefix,n)||!root[n]||strlen(root)>180)return -1;
    for(const char *p=root+n;*p;p++)if(!((*p>='a'&&*p<='z')||(*p>='A'&&*p<='Z')||(*p>='0'&&*p<='9')))return -1;
    char canonical[768];struct stat st;if(!realpath(root,canonical)||strcmp(canonical,root)||lstat(root,&st)||!S_ISDIR(st.st_mode)||st.st_uid!=0||(st.st_mode&0777)!=0700)return -1;
    exercise_root=root;return matches("/FIXTURE_ONLY",FIXTURE_SENTINEL_SHA,FIXTURE_SENTINEL_SIZE,1,0600,0,0)?0:-1;
}
#endif
int main(int argc,char **argv){
    if(argc==2&&!strcmp(argv[1],"--self-test")){int a=companion_sha256_self_test(),b=transaction_self_test();char text[100];snprintf(text,sizeof(text),"SELF_TEST sha256=%d transaction=%d\n",a,b);report(text);return a||b?1:0;}
#ifdef FIXTURE_BUILD
    if(argc<4||strcmp(argv[1],"--exercise")||getuid()!=0||prepare_fixture(argv[2])){report("FIXTURE_ONLY: --exercise requires a validated isolated /data/local/tmp tree.\n");return 2;}
    argc-=2;argv+=2;
    if(argc==4&&!strcmp(argv[2],"--fail-step")){char *end=NULL;long step=strtol(argv[3],&end,10);if(!end||*end||step<0||step>11)return 2;fixture_fail=(int)step;argc=2;}
    else if(argc==3&&!strcmp(argv[2],"--fail-after-rename")){fixture_postrename=1;argc=2;}
#endif
    if(argc!=2){report("Usage: --inspect | --preflight | --apply | --restore | --self-test\n");return 2;}
    int action=0;active_mode=TX_INSTALL_RIL;
    if(!strcmp(argv[1],"--inspect"))action=1;
    else if(!strcmp(argv[1],"--preflight"))action=2;
    else if(!strcmp(argv[1],"--apply"))action=3;
    else if(!strcmp(argv[1],"--restore")){action=3;active_mode=TX_RESTORE_RIL;}
    else return 2;
    int lock=acquire_guard();if(lock<0){report("TRANSACTION_LOCK_UNAVAILABLE\n");return 1;}
    struct sigaction sa;memset(&sa,0,sizeof(sa));sa.sa_handler=on_signal;
    if(sigaction(SIGINT,&sa,NULL)||sigaction(SIGTERM,&sa,NULL)||sigaction(SIGHUP,&sa,NULL)){close(lock);return 1;}
    int rc=action==1?inspect():action==2?preflight():run_transaction();close(lock);if(rc)report("INSTALLER_FAILURE\n");return rc?1:0;
}
