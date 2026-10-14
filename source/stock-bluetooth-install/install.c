#define _GNU_SOURCE 1
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/mount.h>
#include <sys/file.h>
#include <sys/syscall.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <signal.h>
#include <dlfcn.h>
#include <dirent.h>
#include "sha256.h"

#define BYTES 816612
#define STOCK "ec764fdfaccb460819256cd48e666181ddfde6227e9bae407baac4820b9be473"
#define PATCH "e70c1ee712617e2e0544f6ae689e5db1bfcc232a29b8b38cdc39896feb4e14e7"
#define LIBRARY "libandroid_runtime.so"
#define BACKUP "libandroid_runtime.original.so"
#define RECOVERY "galaxy-companion-recovery"
#define BLOCK_DEVICE "/dev/block/mmcblk0p9"

typedef struct {
    int lib, etc, recovery;
    int exercise, fail_before_commit, rename_committed;
} Context;
static volatile sig_atomic_t interrupted;
static int readonly_cleanup;

static void on_signal(int n) { (void)n; interrupted=1; }
/* Do not use the modern NDK stdout/stderr FILE macros with ICS libc. */
static void error_text(const char *text) { size_t n=strlen(text);while(n){ssize_t w=write(2,text,n);if(w<0&&errno==EINTR)continue;if(w<=0)return;text+=w;n-=(size_t)w;} }
static int error(const char *what) { char b[400];snprintf(b,sizeof(b),"ERROR: %s (errno=%d)\n",what,errno);error_text(b);return -1; }
static int no_xattrs(int fd) {
    long n=syscall(__NR_flistxattr,fd,NULL,0);
    return n==0 || (n<0 && (errno==ENOTSUP || errno==EOPNOTSUPP));
}
static int digest(int fd,char hex[65]) {
    unsigned char buffer[8192],out[32]; size_t total=0; CompanionSha256 s;
    if(lseek(fd,0,SEEK_SET)<0)return -1;
    companion_sha256_init(&s);
    for(;;) {
        if(interrupted)return -1;
        ssize_t n=read(fd,buffer,sizeof(buffer));
        if(n<0 && errno==EINTR)continue;
        if(n<0)return -1;
        if(!n)break;
        total+=(size_t)n;
        if(total>BYTES)return -1;
        companion_sha256_update(&s,buffer,(size_t)n);
    }
    if(total!=BYTES)return -1;
    companion_sha256_final(&s,out); companion_sha256_hex(out,hex); return 0;
}
static int regular_meta(int fd,int exact_mode) {
    struct stat s;
    return !fstat(fd,&s) && S_ISREG(s.st_mode) && s.st_nlink==1 &&
        s.st_uid==0 && s.st_gid==0 &&
        (exact_mode ? (s.st_mode&07777)==0644 : !(s.st_mode&07022)) && no_xattrs(fd);
}
static int regular(int fd,int exact_mode) { struct stat s;return regular_meta(fd,exact_mode)&&!fstat(fd,&s)&&s.st_size==BYTES; }
static int file_is(int fd,const char *hash,int exact_mode) {
    char hex[65]; return regular(fd,exact_mode) && !digest(fd,hex) && !strcmp(hex,hash);
}
/* Parent directories are held open; final names never traverse a symlink. */
static int open_file(int parent,const char *name) {
    return openat(parent,name,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);
}
static int kind_at(int parent,const char *name) {
    int fd=open_file(parent,name); char hex[65]; int result=-1;
    if(fd<0)return errno==ENOENT?0:-1;
    if(regular_meta(fd,1)) {
        result=3; /* Safely replaceable regular file, but not a known image. */
        if(!digest(fd,hex))result=!strcmp(hex,STOCK)?1:!strcmp(hex,PATCH)?2:3;
        if(interrupted)result=-1;
    }
    close(fd); return result;
}
static int absent(int parent,const char *name) {
    struct stat s; return fstatat(parent,name,&s,AT_SYMLINK_NOFOLLOW)<0 && errno==ENOENT;
}
static int secure_dir_fd(int fd,int exact0700) {
    struct stat s;
    return !fstat(fd,&s) && S_ISDIR(s.st_mode) && s.st_uid==0 && s.st_gid==0 &&
        (exact0700 ? (s.st_mode&07777)==0700 : !(s.st_mode&07022)) && no_xattrs(fd);
}
/* All components must be directories, never symbolic links. System components
 * additionally require root ownership and no write access for other users. */
static int directory_path(const char *path,int strict_chain,int exact0700) {
    if(!path || path[0]!='/' || strlen(path)>=512)return -1;
    char part[512];strcpy(part,path);
    int fd=open("/",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0)return -1;
    char *save=NULL,*p=strtok_r(part+1,"/",&save);
    while(p) {
        if(!strcmp(p,".") || !strcmp(p,"..")){close(fd);return -1;}
        int next=openat(fd,p,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
        close(fd); fd=next;
        if(fd<0)return -1;
        if(strict_chain && !secure_dir_fd(fd,0)){close(fd);return -1;}
        p=strtok_r(NULL,"/",&save);
    }
    if(!secure_dir_fd(fd,exact0700)){close(fd);return -1;}
    return fd;
}
static int source_fd(const char *path,const char *hash) {
    if(!path || strlen(path)>=512)return -1;
    char parent[512];strcpy(parent,path);
    char *slash=strrchr(parent,'/');if(!slash || !slash[1])return -1;
    char name[128];if(strlen(slash+1)>=sizeof(name))return -1;strcpy(name,slash+1);*slash=0;
    if(strcmp(parent,"/data/local/galaxy-bt-validation") && strcmp(parent,"/data/local/galaxy-bt-install"))return -1;
    for(const char *p=name;*p;p++)if(!((*p>='a'&&*p<='z')||(*p>='A'&&*p<='Z')||(*p>='0'&&*p<='9')||*p=='.'||*p=='_'||*p=='-'))return -1;
    if(!strcmp(name,".")||!strcmp(name,".."))return -1;
    int dir=directory_path(parent,0,1);if(dir<0)return -1;
    int fd=open_file(dir,name);close(dir);
    if(fd>=0 && !file_is(fd,hash,0)){close(fd);fd=-1;}
    return fd;
}
static int stage_absent(int dir,const char *name,int restoring) {
    char stage[128];if(snprintf(stage,sizeof(stage),"%s.%s",name,restoring?"bt-restore-stage":"bt-stage")>=(int)sizeof(stage))return 0;
    return absent(dir,stage);
}
static int copy_atomic(Context *c,int input,int dir,const char *name,const char *hash,int expected_kind,int restoring) {
    char stage[128],hex[65];int output=-1,created=0,rc=-1;
    unsigned char buffer[8192];size_t copied=0;
    if(snprintf(stage,sizeof(stage),"%s.%s",name,restoring?"bt-restore-stage":"bt-stage")>=(int)sizeof(stage))return -1;
    if(!file_is(input,hash,0) || kind_at(dir,name)!=expected_kind || interrupted)return -1;
    output=openat(dir,stage,O_RDWR|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if(output<0)return -1;created=1;
    if(lseek(input,0,SEEK_SET)<0)goto done;
    for(;;) {
        if(interrupted)goto done;
        ssize_t n=read(input,buffer,sizeof(buffer));
        if(n<0&&errno==EINTR)continue;
        if(n<0)goto done;if(!n)break;
        copied+=(size_t)n;if(copied>BYTES)goto done;
        ssize_t off=0;
        while(off<n){ssize_t w=write(output,buffer+off,(size_t)(n-off));if(w<0&&errno==EINTR)continue;if(w<=0)goto done;off+=w;}
    }
    if(digest(output,hex)||strcmp(hex,hash)||fchown(output,0,0)||fchmod(output,0644)||!no_xattrs(output)||fsync(output))goto done;
    if(close(output)){output=-1;goto done;}output=-1;
    /* Recheck the prior destination immediately before its one atomic commit. */
    if(kind_at(dir,name)!=expected_kind || interrupted)goto done;
    if(renameat(dir,stage,dir,name))goto done;
    created=0;c->rename_committed=1; /* Record before a possibly failing directory fsync. */
    if(fsync(dir)||kind_at(dir,name)!=(!strcmp(hash,STOCK)?1:2))goto done;
    rc=0;
done:
    if(output>=0)close(output);
    if(created){if(unlinkat(dir,stage,0)||fsync(dir))error("temporary file cleanup failed");}
    return rc;
}
static int get_recovery(Context *c,int create) {
    if(c->recovery>=0)return 0;
    c->recovery=openat(c->etc,RECOVERY,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    if(c->recovery<0 && errno==ENOENT && create) {
        if(mkdirat(c->etc,RECOVERY,0700)||fsync(c->etc))return -1;
        c->recovery=openat(c->etc,RECOVERY,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
        if(c->recovery<0)return -1;
        if(fchown(c->recovery,0,0)||fchmod(c->recovery,0700)||fsync(c->recovery)||fsync(c->etc))return -1;
    }
    if(c->recovery<0)return errno==ENOENT?1:-1;
    return secure_dir_fd(c->recovery,1)?0:-1;
}
static int inspect_context(Context *c) {
    int current=kind_at(c->lib,LIBRARY),recovery=get_recovery(c,0),backup=0;
    if(recovery<0)return error("unsafe recovery directory");
    if(!recovery)backup=kind_at(c->recovery,BACKUP);
    printf("LIBRARY=%s BACKUP=%s\n",current==1?"original":current==2?"patched":current==0?"missing":"unrecognized_or_unsafe",backup==1?"original":backup==0?"absent":"unrecognized_or_unsafe");
    return current==1||current==2?0:-1;
}
static int preflight(Context *c,int source,int restore) {
    if(interrupted)return -1;
    int current=kind_at(c->lib,LIBRARY);
    if(restore?current<0:(current!=1&&current!=2))return error("library is not a safe restoration target or exact recognized install image");
    /* Explicit original rescue is independent of the backup directory. */
    int r=restore&&source>=0?1:get_recovery(c,0);
    if(r<0 || (!r && !absent(c->recovery,BACKUP) && kind_at(c->recovery,BACKUP)!=1))return error("original backup is unsafe or does not match");
    if(!stage_absent(c->lib,LIBRARY,restore) || (!restore&&!r&&!stage_absent(c->recovery,BACKUP,0)))return error("pre-existing stage file; refusing to remove it");
    if(restore) {
        if(source>=0){if(!file_is(source,STOCK,0))return error("rescue original does not match");}
        else if(r || kind_at(c->recovery,BACKUP)!=1)return error("no verified original backup or rescue");
    } else {
        if(!file_is(source,PATCH,0))return error("candidate does not match");
        if(current==2 && (r || kind_at(c->recovery,BACKUP)!=1))return error("patched library has no exact original backup");
    }
    struct statfs fs;
    if(fstatfs(c->lib,&fs) || (uint64_t)fs.f_bavail*(uint64_t)fs.f_bsize<3*1024*1024)return error("less than 3 MiB free");
    return 0;
}
static int apply_files(Context *c,int candidate) {
    if(preflight(c,candidate,0))return -1;
    if(kind_at(c->lib,LIBRARY)==2){puts("ALREADY_PATCHED_WITH_ORIGINAL_BACKUP");return 0;}
    if(get_recovery(c,1))return error("cannot create or verify recovery directory");
    if(absent(c->recovery,BACKUP)) {
        int original=open_file(c->lib,LIBRARY);
        if(original<0)return -1;
        int rc=copy_atomic(c,original,c->recovery,BACKUP,STOCK,0,0);close(original);
        if(rc)return error("original backup commit failed; library not replaced");
    }
    if(kind_at(c->recovery,BACKUP)!=1)return -1;
    puts("ORIGINAL_BACKUP_DURABLE");fflush(NULL);
    if(c->exercise && c->fail_before_commit)return error("injected failure before library replacement");
    if(copy_atomic(c,candidate,c->lib,LIBRARY,PATCH,1,0))return error("candidate replacement failed; inspect destination");
    puts("PATCH_COMMITTED");return 0;
}
static int restore_files(Context *c,int rescue) {
    if(preflight(c,rescue,1))return -1;
    int current=kind_at(c->lib,LIBRARY);
    if(current==1){puts("ALREADY_ORIGINAL");return 0;}
    int source=rescue>=0?dup(rescue):open_file(c->recovery,BACKUP);
    if(source<0)return -1;
    int rc=copy_atomic(c,source,c->lib,LIBRARY,STOCK,current,1);close(source);
    if(rc)return error("original restoration failed; inspect destination");
    puts("ORIGINAL_RESTORED");return 0;
}
static int mount_state(void) {
    FILE *f=fopen("/proc/mounts","r");if(!f)return -1;
    char line[1024],device[256],where[256],type[64],options[400];int result=-1,count=0;
    while(fgets(line,sizeof(line),f)) {
        if(sscanf(line,"%255s %255s %63s %399s",device,where,type,options)!=4 || strcmp(where,"/system"))continue;
        count++;
        if(strcmp(device,BLOCK_DEVICE)||strcmp(type,"ext4")){result=-1;break;}
        char *save=NULL,*p=strtok_r(options,",",&save);int ro=-1;
        while(p){if(!strcmp(p,"ro"))ro=1;else if(!strcmp(p,"rw"))ro=0;p=strtok_r(NULL,",",&save);}
        result=ro;
    }
    fclose(f);return count==1?result:-1;
}
static int remount_system(int ro) {
    if(mount_state()<0)return error("system mount identity changed");
    if(mount(BLOCK_DEVICE,"/system","ext4",MS_REMOUNT|MS_NOATIME|(ro?MS_RDONLY:0),"barrier=1,data=ordered"))return error(ro?"read-only remount failed":"read-write remount failed");
    return mount_state()==ro?0:error("remount state verification failed");
}
static int identity(void) {
    void *lib=dlopen("/system/lib/libcutils.so",RTLD_NOW|RTLD_LOCAL);
    int (*property_get)(const char*,char*,const char*)=lib?dlsym(lib,"property_get"):NULL;
    if(!property_get)return -1;
    const char *keys[]={"ro.serialno","ro.product.model","ro.build.display.id"};
    const char *values[]={"@S2_SERIAL@","SPH-D710","IMM76I.FL24"};
    for(int i=0;i<3;i++){char value[128]={0};property_get(keys[i],value,"");if(strcmp(value,values[i]))return -1;}
    return 0;
}
static void close_context(Context *c) {
    if(c->recovery>=0)close(c->recovery);if(c->etc>=0)close(c->etc);if(c->lib>=0)close(c->lib);
    c->lib=c->etc=c->recovery=-1;
}
static int open_context(Context *c,const char *fixture) {
    char path[512];
    if(fixture){snprintf(path,sizeof(path),"%s/system/lib",fixture);c->lib=directory_path(path,0,0);}
    else c->lib=directory_path("/system/lib",1,0);
    if(c->lib<0 || flock(c->lib,LOCK_EX|LOCK_NB))return error("library directory unavailable or another installer holds lock");
    if(fixture){snprintf(path,sizeof(path),"%s/system/etc",fixture);c->etc=directory_path(path,0,0);}
    else c->etc=directory_path("/system/etc",1,0);
    return c->etc>=0?0:-1;
}
static int new_subdir(int parent,const char *name) {
    if(mkdirat(parent,name,0700)||fsync(parent))return -1;
    int fd=openat(parent,name,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0)return -1;
    if(fchown(fd,0,0)||fchmod(fd,0700)||fsync(fd)||fsync(parent)||!secure_dir_fd(fd,1)){close(fd);return -1;}
    return fd;
}
static int fixture_empty(int fd) {
    int duplicate=dup(fd);if(duplicate<0)return 0;
    DIR *d=fdopendir(duplicate);if(!d){close(duplicate);return 0;}
    struct dirent *e;int empty=1;
    while((e=readdir(d)))if(strcmp(e->d_name,".")&&strcmp(e->d_name,"..")){empty=0;break;}
    closedir(d);return empty;
}
static int exercise(const char *root,const char *candidate_path,const char *original_path) {
    const char *prefix="/data/local/galaxy-bt-exercise-";
    if(strncmp(root,prefix,strlen(prefix))||strlen(root)>=256||!root[strlen(prefix)])return error("exercise path rejected");
    for(const char *p=root+strlen(prefix);*p;p++)if(!((*p>='a'&&*p<='z')||(*p>='A'&&*p<='Z')||(*p>='0'&&*p<='9')||*p=='-'))return -1;
    int directory=directory_path(root,0,1),candidate=-1,original=-1,system=-1,lib=-1,etc=-1,rc=-1;
    Context c={-1,-1,-1,1,0,0};
    if(directory<0 || !fixture_empty(directory))return error("fixture must be an empty root:root 0700 directory");
    candidate=source_fd(candidate_path,PATCH);original=source_fd(original_path,STOCK);
    if(candidate<0||original<0)goto done;
    system=new_subdir(directory,"system");if(system<0)goto done;
    lib=new_subdir(system,"lib");etc=new_subdir(system,"etc");if(lib<0||etc<0)goto done;
    close(lib);lib=-1;close(etc);etc=-1;
    if(open_context(&c,root)||copy_atomic(&c,original,c.lib,LIBRARY,STOCK,0,0)||preflight(&c,candidate,0))goto done;
    puts("EXERCISE: original fixture and normal preflight PASS");
    if(copy_atomic(&c,original,c.lib,LIBRARY,PATCH,1,0)==0 || kind_at(c.lib,LIBRARY)!=1)goto done;
    puts("EXERCISE: mismatched source rejected before commit PASS");
    int testfd=openat(c.lib,LIBRARY,O_RDWR|O_NOFOLLOW|O_CLOEXEC);
    if(testfd<0 || fchmod(testfd,0666)){if(testfd>=0)close(testfd);goto done;}
    if(preflight(&c,candidate,0)==0){close(testfd);goto done;}
    if(fchmod(testfd,0644)){close(testfd);goto done;}close(testfd);
    puts("EXERCISE: writable target metadata rejected PASS");
    if(syscall(__NR_linkat,c.lib,LIBRARY,c.lib,"exercise-hardlink",0))goto done;
    if(preflight(&c,candidate,0)==0)goto done;
    if(unlinkat(c.lib,"exercise-hardlink",0)||fsync(c.lib))goto done;
    puts("EXERCISE: multiply linked target rejected PASS");
    if(renameat(c.lib,LIBRARY,c.lib,"exercise-hold")||syscall(__NR_symlinkat,"exercise-hold",c.lib,LIBRARY))goto done;
    if(restore_files(&c,original)==0)goto done;
    if(unlinkat(c.lib,LIBRARY,0)||renameat(c.lib,"exercise-hold",c.lib,LIBRARY)||fsync(c.lib))goto done;
    puts("EXERCISE: symlink target rejected even for restore PASS");
    testfd=openat(c.lib,LIBRARY,O_RDWR|O_NOFOLLOW|O_CLOEXEC);
    if(testfd<0)goto done;
    if(syscall(__NR_fsetxattr,testfd,"user.galaxy_bt_exercise","1",1,0)==0) {
        if(preflight(&c,candidate,0)==0){close(testfd);goto done;}
        if(syscall(__NR_fremovexattr,testfd,"user.galaxy_bt_exercise")){close(testfd);goto done;}
        puts("EXERCISE: nonempty xattrs rejected PASS");
    } else if(errno==ENOTSUP||errno==EOPNOTSUPP)puts("EXERCISE: xattr injection unavailable on fixture filesystem SKIP");
    else {close(testfd);goto done;}
    if(fsync(testfd)){close(testfd);goto done;}close(testfd);
    int collision=openat(c.lib,LIBRARY ".bt-stage",O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if(collision<0 || write(collision,"keep",4)!=4){if(collision>=0)close(collision);goto done;}close(collision);
    if(apply_files(&c,candidate)==0 || kind_at(c.lib,LIBRARY)!=1)goto done;
    collision=open_file(c.lib,LIBRARY ".bt-stage");char sentinel[4];
    if(collision<0 || read(collision,sentinel,4)!=4 || memcmp(sentinel,"keep",4)){if(collision>=0)close(collision);goto done;}close(collision);
    if(unlinkat(c.lib,LIBRARY ".bt-stage",0)||fsync(c.lib))goto done;
    puts("EXERCISE: pre-existing stage preserved and replacement refused PASS");
    c.fail_before_commit=1;
    if(apply_files(&c,candidate)==0||kind_at(c.lib,LIBRARY)!=1||kind_at(c.recovery,BACKUP)!=1)goto done;
    c.fail_before_commit=0;
    puts("EXERCISE: injected failure leaves durable original and untouched target PASS");
    if(apply_files(&c,candidate)||kind_at(c.lib,LIBRARY)!=2||kind_at(c.recovery,BACKUP)!=1)goto done;
    puts("EXERCISE: atomic candidate replacement PASS");
    collision=openat(c.lib,LIBRARY ".bt-stage",O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if(collision<0||write(collision,"keep",4)!=4){if(collision>=0)close(collision);goto done;}close(collision);
    close(candidate);candidate=-1;
    if(restore_files(&c,-1)||kind_at(c.lib,LIBRARY)!=1)goto done;
    puts("EXERCISE: backup-only restore with no candidate descriptor PASS");
    collision=open_file(c.lib,LIBRARY ".bt-stage");
    if(collision<0||read(collision,sentinel,4)!=4||memcmp(sentinel,"keep",4)){if(collision>=0)close(collision);goto done;}close(collision);
    if(unlinkat(c.lib,LIBRARY ".bt-stage",0)||fsync(c.lib))goto done;
    puts("EXERCISE: restore succeeds while preserving stale install stage PASS");
    candidate=source_fd(candidate_path,PATCH);
    if(candidate<0||apply_files(&c,candidate)||unlinkat(c.recovery,BACKUP,0)||fsync(c.recovery))goto done;
    close(candidate);candidate=-1;
    if(restore_files(&c,original)||kind_at(c.lib,LIBRARY)!=1)goto done;
    puts("EXERCISE: exact rescue restore without backup or candidate descriptor PASS");
    testfd=openat(c.lib,LIBRARY,O_WRONLY|O_TRUNC|O_NOFOLLOW|O_CLOEXEC);
    if(testfd<0||fsync(testfd)){if(testfd>=0)close(testfd);goto done;}close(testfd);
    if(restore_files(&c,original)||kind_at(c.lib,LIBRARY)!=1)goto done;
    puts("EXERCISE: corrupt regular target restored from exact rescue PASS");
    if(unlinkat(c.lib,LIBRARY,0)||fsync(c.lib)||restore_files(&c,original)||kind_at(c.lib,LIBRARY)!=1)goto done;
    puts("EXERCISE: missing target restored from exact rescue PASS");
    puts("EXERCISE_PASS: 13 mandatory checks plus optional xattr check; no properties, mount calls, services, or radio operations");rc=0;
done:
    close_context(&c);if(directory>=0)close(directory);if(system>=0)close(system);if(lib>=0)close(lib);if(etc>=0)close(etc);if(candidate>=0)close(candidate);if(original>=0)close(original);
    return rc;
}
int main(int argc,char **argv) {
    umask(0077);
    if(getuid()!=0||geteuid()!=0||companion_sha256_self_test()){error("root and SHA self-test required");return 1;}
    void *libc=dlopen("/system/lib/libc.so",RTLD_NOW|RTLD_LOCAL);
    void (*(*bsd_signal_fn)(int,void(*)(int)))(int)=libc?dlsym(libc,"bsd_signal"):NULL;
    if(!bsd_signal_fn){error_text("Cannot resolve legacy bsd_signal from explicit libc handle.\n");return 1;}
    if(bsd_signal_fn(SIGINT,on_signal)==SIG_ERR || bsd_signal_fn(SIGTERM,on_signal)==SIG_ERR || bsd_signal_fn(SIGHUP,on_signal)==SIG_ERR){error_text("Cannot install bounded interruption handlers.\n");return 1;}
    if(argc==5&&!strcmp(argv[1],"--exercise"))return exercise(argv[2],argv[3],argv[4])?1:0;
    int inspect=argc==2&&!strcmp(argv[1],"--inspect");
    int pre=argc==3&&!strcmp(argv[1],"--preflight");
    int apply=argc==3&&!strcmp(argv[1],"--apply");
    int restore=(argc==2||argc==3)&&!strcmp(argv[1],"--restore");
    if(!inspect&&!pre&&!apply&&!restore){error_text("Usage: --inspect | --preflight CANDIDATE | --apply CANDIDATE | --restore [ORIGINAL_RESCUE] | --exercise ROOT CANDIDATE ORIGINAL\n");return 2;}
    Context c={-1,-1,-1,0,0,0};int rc=-1,source=-1;
    if(identity()){error("exact S2 identity required");goto done;}
    int state=mount_state();
    if(state<0 || (!restore&&!inspect&&state!=1)){error("exact ext4 system mount required; apply/preflight also require read-only");goto done;}
    if(restore&&state==0)readonly_cleanup=1;
    if(inspect)printf("SYSTEM_MOUNT=%s\n",state?"read-only":"read-write");
    if(open_context(&c,NULL))goto done;
    if(inspect){rc=inspect_context(&c);goto done;}
    if(pre||apply||argc==3){source=source_fd(argv[2],restore?STOCK:PATCH);if(source<0){error("source rejected");goto done;}}
    if(preflight(&c,source,restore))goto done;
    puts(restore?"RESTORE_PREFLIGHT_OK":"APPLY_PREFLIGHT_OK");fflush(NULL);
    if(pre){rc=0;goto done;}
    readonly_cleanup=1; /* Even a failed RW attempt must be followed by an RO attempt. */
    if(remount_system(0))goto done;
    rc=restore?restore_files(&c,source):apply_files(&c,source);
done:
    if(readonly_cleanup){
        if(remount_system(1)){error_text("SYSTEM_READ_ONLY_CLEANUP_FAILED\n");rc=-1;}
        else puts("SYSTEM_READ_ONLY_OK");
    }
    if(rc && c.rename_committed)error_text("A FILE RENAME COMMITTED: inspect current library/backup before retry.\n");
    if(source>=0)close(source);close_context(&c);
    if(rc)error_text("OPERATION_FAILED\n");return rc?1:0;
}
