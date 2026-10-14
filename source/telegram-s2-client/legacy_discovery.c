#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/poll.h>
#include <fcntl.h>
#include <errno.h>

/* Narrow classic SDP query: fixed bonded Pixel, fixed application UUID, PSM1.
 * No root, HFP, controller changes, channel guesses, or unrelated records. */
struct address {uint16_t family,psm;uint8_t bdaddr[6];uint16_t cid;uint8_t type;};
static int be16(const unsigned char *p){return (p[0]<<8)|p[1];}
static int element(const unsigned char *b,int end,int at,int *type,int *start,int *stop){
 if(at<0||at>=end)return -1;unsigned h=b[at++];*type=h>>3;unsigned s=h&7,n;
 if(s<5)n=1u<<s;else {unsigned count=s==5?1:s==6?2:4;if(at+(int)count>end)return -1;n=0;while(count--)n=(n<<8)|b[at++];}
 if(n>(unsigned)(end-at))return -1;*start=at;*stop=at+(int)n;return *stop;
}
static int protocol(const unsigned char *b,int end,int at,int depth){
 if(depth>8)return -1;int type,start,stop,next=element(b,end,at,&type,&start,&stop);if(next<0)return -1;
 if(type==6){int t,s,e,n=element(b,stop,start,&t,&s,&e);if(n>0&&t==3&&e-s==2&&be16(b+s)==3){n=element(b,stop,n,&t,&s,&e);if(n>0&&t==1&&e-s==1&&b[s]>=1&&b[s]<=30)return b[s];}
  for(int p=start;p<stop;){int result=protocol(b,stop,p,depth+1);if(result>0)return result;int t2,s2,e2;p=element(b,stop,p,&t2,&s2,&e2);if(p<0)return -1;}}
 return -1;
}
JNIEXPORT jint JNICALL Java_org_retrobridge_galaxychat_s2_LegacyDiscovery_channel(JNIEnv *env,jclass cls){
 (void)env;(void)cls;int fd=socket(31,SOCK_SEQPACKET,0);if(fd<0)return -1;int flags=fcntl(fd,F_GETFL,0);fcntl(fd,F_SETFL,flags|O_NONBLOCK);
 struct address dst;memset(&dst,0,sizeof(dst));dst.family=31;dst.psm=1;const uint8_t peer[6]={@PIXEL_BLUETOOTH_LE@};memcpy(dst.bdaddr,peer,6);
 int rc=connect(fd,(struct sockaddr*)&dst,sizeof(dst));if(rc<0&&errno!=EINPROGRESS){close(fd);return -2;}struct pollfd wait={fd,POLLOUT,0};if(poll(&wait,1,4000)<=0){close(fd);return -3;}int error=0;socklen_t len=sizeof(error);if(getsockopt(fd,SOL_SOCKET,SO_ERROR,&error,&len)<0||error){close(fd);return -4;}
 const unsigned char query[]={6,0,1,0,27,0x35,17,0x1c,0xbf,0x06,0x7b,0x70,0x84,0x1f,0x4a,0x63,0x93,0xea,0x72,0x47,0x67,0xdd,0xd0,0xbd,0xff,0xff,0x35,3,9,0,4,0};
 if(send(fd,query,sizeof(query),0)!=(int)sizeof(query)){close(fd);return -5;}wait.events=POLLIN;if(poll(&wait,1,4000)<=0){close(fd);return -6;}unsigned char response[4096];int n=recv(fd,response,sizeof(response),0);close(fd);
 if(n<10||response[0]!=7||be16(response+1)!=1||be16(response+3)!=n-5)return -7;int count=be16(response+5);if(count+8!=n||response[n-1]!=0)return -8;
 return protocol(response+7,count,0,0);
}
