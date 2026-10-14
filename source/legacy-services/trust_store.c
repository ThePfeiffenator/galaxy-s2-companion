#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/mount.h>
#include "trust_certificate.h"

static const char *target="/system/etc/security/cacerts/" CERT_NAME;
static const char *temp="/system/etc/security/cacerts/.galaxy-services-ca.new";
static int readonly(void){FILE *f=fopen("/proc/mounts","r");if(!f)return -1;char line[1024],dev[256],dir[256],type[64],opts[400];int r=-1;while(fgets(line,sizeof line,f)){if(sscanf(line,"%255s %255s %63s %399s",dev,dir,type,opts)==4&&!strcmp(dir,"/system")){if(!strcmp(dev,"/dev/block/mmcblk0p9")&&!strcmp(type,"ext4"))r=!strncmp(opts,"ro,",3);break;}}fclose(f);return r;}
static int remount(int ro){return mount("/dev/block/mmcblk0p9","/system","ext4",MS_REMOUNT|MS_NOATIME|(ro?MS_RDONLY:0),"barrier=1,data=ordered")||readonly()!=ro?-1:0;}
static int guard(void){if(getuid()!=0||readonly()!=1)return -1;const char *dirs[]={"/system","/system/etc","/system/etc/security","/system/etc/security/cacerts"};struct stat s;for(unsigned i=0;i<sizeof dirs/sizeof dirs[0];i++){if(lstat(dirs[i],&s)||!S_ISDIR(s.st_mode))return -1;if(i<2){if(s.st_uid!=0||(s.st_mode&0022))return -1;}else if(s.st_uid!=1000||s.st_gid!=1000||(s.st_mode&0777)!=0775)return -1;}return 0;}
static void report(const char *s){write(1,s,strlen(s));}
static int matches(void){struct stat s;if(lstat(target,&s)){return errno==ENOENT?0:-1;}if(!S_ISREG(s.st_mode)||s.st_uid!=0||s.st_gid!=0||(s.st_mode&0777)!=0644||s.st_size!=(off_t)sizeof certificate)return -1;int f=open(target,O_RDONLY|O_NOFOLLOW);if(f<0)return -1;unsigned char data[sizeof certificate+1];ssize_t n=read(f,data,sizeof data);close(f);return n==(ssize_t)sizeof certificate&&!memcmp(data,certificate,sizeof certificate)?1:-1;}
static int install(void){int prior=matches();if(prior==1)return 0;if(prior<0)return -1;struct stat st;if(lstat(temp,&st)==0||errno!=ENOENT)return -1;if(remount(0))return -1;int ok=0,f=open(temp,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW,0600);if(f>=0){size_t at=0;while(at<sizeof certificate){ssize_t n=write(f,certificate+at,sizeof certificate-at);if(n<=0)break;at+=(size_t)n;}ok=at==sizeof certificate&&!fchown(f,0,0)&&!fchmod(f,0644)&&!fsync(f);close(f);if(ok)ok=link(temp,target)==0;unlink(temp);}
 int d=open("/system/etc/security/cacerts",O_RDONLY|O_DIRECTORY);if(d>=0){fsync(d);close(d);}int restored=remount(1);return ok&&!restored&&matches()==1?0:-1;}
int main(int argc,char **argv){if(argc!=2||guard()){report("Trust store preflight failed\n");return 1;}if(!strcmp(argv[1],"--check")){int m=matches();report(m==1?"Certificate=installed System=read-only\n":m==0?"Certificate=absent System=read-only\n":"Unexpected certificate file\n");return m<0;}if(!strcmp(argv[1],"--install")){int r=install();report(r?"Certificate install failed\n":"Certificate install verified\n");report(readonly()==1?"System=read-only\n":"ERROR system not read-only\n");return r?1:0;}if(!strcmp(argv[1],"--remove")){int m=matches();if(m<0)return 1;if(m==0)return 0;if(remount(0))return 1;int r=unlink(target);int ro=remount(1);report(r||ro?"Removal failed\n":"Certificate removed; system read-only\n");return r||ro;}return 2;}
