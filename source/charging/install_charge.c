#define _GNU_SOURCE 1
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/mount.h>
#include "sha256.h"
#include "install_manifest.h"
#define SCRIPT "/system/etc/install-recovery.sh"
#define BIN "/system/bin/galaxy-charge-manager"
#define SAVE "/system/etc/galaxy-charge-recovery"
#define ORIGINAL SAVE "/install-recovery.original.sh"
#define INPUT "/data/local/tmp/charge-limit-input/"
static volatile sig_atomic_t interrupted;
static void caught(int n){(void)n;interrupted=1;}
static int digest(int fd,char hex[65]){CompanionSha256 s;companion_sha256_init(&s);unsigned char b[8192],hash[32];ssize_t n;size_t total=0;if(lseek(fd,0,SEEK_SET)<0)return -1;while((n=read(fd,b,sizeof(b)))>0){total+=(size_t)n;if(total>2*1024*1024)return -1;companion_sha256_update(&s,b,(size_t)n);}if(n<0)return -1;companion_sha256_final(&s,hash);companion_sha256_hex(hash,hex);return 0;}
static int matches(const char *path,const char *hash,int trusted){int fd=open(path,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return 0;struct stat s;char h[65];int yes=!fstat(fd,&s)&&S_ISREG(s.st_mode)&&s.st_nlink==1&&(!trusted||(s.st_uid==0&&!(s.st_mode&0022)))&&!digest(fd,h)&&!strcmp(h,hash);close(fd);return yes;}
static int parent_sync(const char *name){char b[256];if(strlen(name)>=sizeof(b))return -1;strcpy(b,name);char *p=strrchr(b,'/');if(!p)return -1;*p=0;int fd=open(b,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return -1;int r=fsync(fd);close(fd);return r;}
static int copy(const char *from,const char *to,const char *hash,mode_t mode){
 char tmp[256],hex[65];snprintf(tmp,sizeof(tmp),"%s.charge-new",to);struct stat st;
 if(!lstat(to,&st)&&(!S_ISREG(st.st_mode)||st.st_uid||st.st_nlink!=1||(st.st_mode&0022)))return -1;
 int in=open(from,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);if(in<0)return -1;
 if(fstat(in,&st)||!S_ISREG(st.st_mode)||st.st_nlink!=1||digest(in,hex)||strcmp(hex,hash)||lseek(in,0,SEEK_SET)<0){close(in);return -1;}
 int out=open(tmp,O_RDWR|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);if(out<0){close(in);return -1;}
 int good=1;unsigned char b[8192];ssize_t n;size_t total=0;
 while((n=read(in,b,sizeof(b)))>0){total+=(size_t)n;if(total>2*1024*1024){good=0;break;}ssize_t off=0;while(off<n){ssize_t k=write(out,b+off,(size_t)(n-off));if(k<=0){good=0;break;}off+=k;}if(!good)break;}
 if(n<0||digest(out,hex)||strcmp(hex,hash)||fchown(out,0,0)||fchmod(out,mode)||fsync(out))good=0;
 close(in);if(close(out))good=0;if(good&&!rename(tmp,to)&&!parent_sync(to)&&matches(to,hash,1))return 0;unlink(tmp);return -1;
}
static int ro(void){FILE *f=fopen("/proc/mounts","r");if(!f)return -1;char b[1024],dev[256],where[256],type[80],options[400];int state=-1;while(fgets(b,sizeof(b),f)){if(sscanf(b,"%255s %255s %79s %399s",dev,where,type,options)!=4||strcmp(where,"/system"))continue;if(!strcmp(dev,"/dev/block/mmcblk0p9")&&!strcmp(type,"ext4"))state=!strncmp(options,"ro,",3)?1:!strncmp(options,"rw,",3)?0:-1;break;}fclose(f);return state;}
static int remount(int readonly){if(mount("/dev/block/mmcblk0p9","/system","ext4",MS_REMOUNT|MS_NOATIME|(readonly?MS_RDONLY:0),"barrier=1,data=ordered"))return -1;return ro()==readonly?0:-1;}
static int dirs(void){const char *paths[]={"/system","/system/bin","/system/etc"};for(unsigned i=0;i<3;i++){struct stat s;if(lstat(paths[i],&s)||!S_ISDIR(s.st_mode)||s.st_uid||(s.st_mode&0022))return -1;}return 0;}
static int exact(void){char model[PROP_VALUE_MAX],build[PROP_VALUE_MAX];if(__system_property_get("ro.product.model",model)<=0||__system_property_get("ro.build.display.id",build)<=0||strcmp(model,"SPH-D710")||strcmp(build,"IMM76I.FL24"))return 0;char b[512];FILE *f=fopen("/proc/version","r");if(!f)return 0;int yes=fgets(b,sizeof(b),f)&&strstr(b,"3.0.15-1173404")&&strstr(b,"Dec 24")&&strstr(b,"2012");fclose(f);return yes;}
static int signals(void){void *l=dlopen("/system/lib/libc.so",RTLD_NOW);if(!l)return -1;typedef void(*H)(int);H(*s)(int,H)=dlsym(l,"bsd_signal");return !s||s(SIGTERM,caught)==SIG_ERR||s(SIGINT,caught)==SIG_ERR?-1:0;}
int main(int argc,char **argv){
 if(argc!=2||getuid()!=0||!exact()||dirs()||signals())return 2;
 int apply=!strcmp(argv[1],"--apply"),restore=!strcmp(argv[1],"--restore"),inspect=!strcmp(argv[1],"--inspect"),pre=!strcmp(argv[1],"--preflight");if(!apply&&!restore&&!inspect&&!pre)return 2;
 int old=matches(SCRIPT,OLD_SCRIPT_SHA,1),new=matches(SCRIPT,NEW_SCRIPT_SHA,1),bin=matches(BIN,MANAGER_SHA,1),backup=matches(ORIGINAL,OLD_SCRIPT_SHA,1);
 if(inspect){printf("old_boot=%d new_boot=%d manager=%d backup=%d system_readonly=%d\n",old,new,bin,backup,ro());return ro()!=1;}
 if(ro()!=1||(!old&&!new))return 3;
 struct stat st;if(!lstat(SAVE,&st)&&(!S_ISDIR(st.st_mode)||st.st_uid||(st.st_mode&0777)!=0700))return 4;
 if(restore){if(!backup||(new&&!bin))return 5;}else{
  if(new&&bin&&backup){puts("Already installed");return 0;}
  if(!old||(!lstat(BIN,&st)&&!bin)||(!lstat(ORIGINAL,&st)&&!backup)||!matches(INPUT"install-recovery.sh",NEW_SCRIPT_SHA,0)||!matches(INPUT"galaxy-charge-manager",MANAGER_SHA,0))return 6;
 }
 if(pre){puts("CHARGE_PREFLIGHT_OK");return 0;}
 int result=7;if(remount(0))return 7;
 if(restore){if(!copy(ORIGINAL,SCRIPT,OLD_SCRIPT_SHA,0755)&&(!bin||!unlink(BIN))&&!parent_sync(BIN))result=0;}
 else{
  if(lstat(SAVE,&st)&&mkdir(SAVE,0700))goto finish;
  if(!backup&&copy(SCRIPT,ORIGINAL,OLD_SCRIPT_SHA,0600))goto finish;
  if(interrupted||copy(INPUT"galaxy-charge-manager",BIN,MANAGER_SHA,0755)||interrupted||copy(INPUT"install-recovery.sh",SCRIPT,NEW_SCRIPT_SHA,0755)||interrupted)goto rollback;
  if(matches(SCRIPT,NEW_SCRIPT_SHA,1)&&matches(BIN,MANAGER_SHA,1)&&matches(ORIGINAL,OLD_SCRIPT_SHA,1)){result=0;goto finish;}
rollback:
  if(matches(ORIGINAL,OLD_SCRIPT_SHA,1))copy(ORIGINAL,SCRIPT,OLD_SCRIPT_SHA,0755);
  if(matches(BIN,MANAGER_SHA,1))unlink(BIN);
 }
finish:
 if(remount(1))result=8;
 printf("CHARGE_INSTALL result=%d restored_original=%d new_boot=%d manager=%d system_readonly=%d\n",result,matches(SCRIPT,OLD_SCRIPT_SHA,1),matches(SCRIPT,NEW_SCRIPT_SHA,1),matches(BIN,MANAGER_SHA,1),ro());return result;
}
