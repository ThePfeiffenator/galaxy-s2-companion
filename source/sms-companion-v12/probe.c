#include <jni.h>
#include "org_retrobridge_callprobe_CompanionService.h"
#include <sys/socket.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <poll.h>
#include <dlfcn.h>
#include <stdint.h>
#include <fcntl.h>
#include <pthread.h>

static int listening = -1;
static pthread_mutex_t listener_mutex = PTHREAD_MUTEX_INITIALIZER;
static char peer_diagnostic[512] = "No SCO peer observed";
struct sco_addr {sa_family_t family; unsigned char addr[6];};
_Static_assert(sizeof(struct sco_addr) == 8, "SCO address prefix must be 8 bytes");

static int supported_sco_length(socklen_t length) {
 /* The live FL24 kernel returns a ten-byte SCO address with a trailing
    packet-type field; upstream Linux uses the same eight-byte prefix. */
 return length == 8 || length == 10;
}

static void address_text(char text[18], const unsigned char addr[6]) {
 snprintf(text, 18, "%02X:%02X:%02X:%02X:%02X:%02X",
          addr[5], addr[4], addr[3], addr[2], addr[1], addr[0]);
}

JNIEXPORT jstring JNICALL Java_org_retrobridge_callprobe_CompanionService_voicePeerDiagnostic(JNIEnv *e,jobject o){
 pthread_mutex_lock(&listener_mutex);
 jstring text = (*e)->NewStringUTF(e, peer_diagnostic);
 pthread_mutex_unlock(&listener_mutex);
 return text;
}

static int nonblocking(int fd) {
 int flags = fcntl(fd, F_GETFL, 0);
 if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) return -errno;
 return 0;
}

/* A timeout is retryable. The subsequent socket operation is nonblocking too. */
static int wait_socket(int fd, short events) {
 struct pollfd p = {fd, events, 0};
 if (fd < 0) return -EBADF;
 int n = poll(&p, 1, 1000);
 if (n < 0) return -errno;
 if (n == 0) return -EAGAIN;
 if (p.revents & POLLNVAL) return -EBADF;
 return p.revents;
}

static int hex_digit(char c) {
 if (c >= '0' && c <= '9') return c - '0';
 if (c >= 'a' && c <= 'f') return c - 'a' + 10;
 if (c >= 'A' && c <= 'F') return c - 'A' + 10;
 return -1;
}

JNIEXPORT jint JNICALL Java_org_retrobridge_callprobe_CompanionService_listenVoice(JNIEnv *e,jobject o,jstring local){
 struct sco_addr a;
 int valid = 1;
 if (local == NULL) return -EINVAL;
 const char *address = (*e)->GetStringUTFChars(e, local, NULL);
 if (address == NULL) return -ENOMEM;
 memset(&a, 0, sizeof(a));
 a.family = 31;
 if (strlen(address) != 17) valid = 0;
 if (valid) {
  for (int i = 0; i < 6; ++i) {
   int high = hex_digit(address[i * 3]);
   int low = hex_digit(address[i * 3 + 1]);
   if (high < 0 || low < 0 || (i < 5 && address[i * 3 + 2] != ':')) {
    valid = 0;
    break;
   }
   a.addr[5 - i] = (unsigned char)((high << 4) | low);
  }
 }
 (*e)->ReleaseStringUTFChars(e, local, address);
 if (!valid) return -EINVAL;

 pthread_mutex_lock(&listener_mutex);
 if (listening >= 0) {
  pthread_mutex_unlock(&listener_mutex);
  return -EALREADY;
 }
 int fd = socket(31, SOCK_SEQPACKET, 2);
 int result = fd < 0 ? -errno : nonblocking(fd);
 if (result == 0 && (bind(fd, (struct sockaddr *)&a, sizeof(a)) < 0 || listen(fd, 1) < 0)) {
  result = -errno;
 }
 if (result < 0) {
  if (fd >= 0) close(fd);
 } else {
  listening = fd;
 }
 pthread_mutex_unlock(&listener_mutex);
 return result;
}

JNIEXPORT jint JNICALL Java_org_retrobridge_callprobe_CompanionService_acceptVoice(JNIEnv *e,jobject o,jstring expected){
 unsigned char expected_addr[6];
 int valid = 1;
 if (expected == NULL) return -EINVAL;
 const char *address = (*e)->GetStringUTFChars(e, expected, NULL);
 if (address == NULL) return -ENOMEM;
 if (strlen(address) != 17) valid = 0;
 if (valid) {
  for (int i = 0; i < 6; ++i) {
   int high = hex_digit(address[i * 3]);
   int low = hex_digit(address[i * 3 + 1]);
   if (high < 0 || low < 0 || (i < 5 && address[i * 3 + 2] != ':')) {
    valid = 0;
    break;
   }
   expected_addr[5 - i] = (unsigned char)((high << 4) | low);
  }
 }
 (*e)->ReleaseStringUTFChars(e, expected, address);
 if (!valid) return -EINVAL;

 /* Hold this lock through poll and nonblocking accept: closeListener cannot
    close the descriptor and let this call operate on a reused descriptor. */
 pthread_mutex_lock(&listener_mutex);
 int result = wait_socket(listening, POLLIN);
 if (result >= 0) {
  if (result & POLLIN) {
   struct sockaddr_storage remote_storage;
   socklen_t remote_size = sizeof(remote_storage);
   memset(&remote_storage, 0, sizeof(remote_storage));
   int fd = accept(listening, (struct sockaddr *)&remote_storage, &remote_size);
   result = fd < 0 ? -errno : 0;
   if (fd >= 0) {
    struct sco_addr remote;
    memcpy(&remote, &remote_storage, sizeof(remote));
    struct sockaddr_storage named_storage;
    socklen_t named_size = sizeof(named_storage);
    memset(&named_storage, 0, sizeof(named_storage));
    int named_result = getpeername(fd, (struct sockaddr *)&named_storage, &named_size);
    int named_error = named_result < 0 ? errno : 0;
    struct sco_addr named;
    memcpy(&named, &named_storage, sizeof(named));
    char remote_text[18], expected_text[18], named_text[18];
    address_text(remote_text, remote.addr);
    address_text(expected_text, expected_addr);
    address_text(named_text, named.addr);
    const char *reason = "match";
    if (!supported_sco_length(remote_size)) { result = -EMSGSIZE; reason = "accept length"; }
    else if (remote.family != 31) { result = -EAFNOSUPPORT; reason = "accept family"; }
    else if (memcmp(remote.addr, expected_addr, sizeof(expected_addr)) != 0) {
     result = -EACCES; reason = "accept address";
    } else if (named_result < 0) { result = -EACCES; reason = "getpeername failed"; }
    else if (!supported_sco_length(named_size)) { result = -EMSGSIZE; reason = "getpeername length"; }
    else if (named.family != 31) { result = -EAFNOSUPPORT; reason = "getpeername family"; }
    else if (memcmp(named.addr, expected_addr, sizeof(expected_addr)) != 0) {
     result = -EACCES; reason = "getpeername address";
    }
    snprintf(peer_diagnostic, sizeof(peer_diagnostic),
      "SCO peer %s: accept len=%u family=%u addr=%s; expected=%s; getpeername rc=%d errno=%d len=%u family=%u addr=%s",
      reason, (unsigned)remote_size, (unsigned)remote.family, remote_text, expected_text,
      named_result, named_error, (unsigned)named_size, (unsigned)named.family, named_text);
    /* A different paired device must never inherit the paired phone's live audio
       session merely because it connects to our local SCO listener. */
    if (result < 0) {
     shutdown(fd, SHUT_RDWR);
    } else {
     result = nonblocking(fd);
    }
   }
   if (result < 0) {
    if (fd >= 0) close(fd);
   } else {
    result = fd;
   }
  } else {
   result = (result & (POLLERR | POLLHUP)) ? -ENETDOWN : -EAGAIN;
  }
 }
 pthread_mutex_unlock(&listener_mutex);
 return result;
}

JNIEXPORT jint JNICALL Java_org_retrobridge_callprobe_CompanionService_readVoice(JNIEnv *e,jobject o,jint fd,jbyteArray buffer){
 char bytes[1024];
 if (fd < 0) return -EBADF;
 if (buffer == NULL) return -EINVAL;
 jsize capacity = (*e)->GetArrayLength(e, buffer);
 if (capacity <= 0) return -EINVAL;
 if (capacity > (jsize)sizeof(bytes)) capacity = sizeof(bytes);
 int ready = wait_socket(fd, POLLIN);
 if (ready < 0) return ready;
 if (!(ready & (POLLIN | POLLERR | POLLHUP))) return -EAGAIN;
 int n = recv(fd, bytes, capacity, MSG_DONTWAIT);
 if (n < 0) return -errno;
 if (n > 0) (*e)->SetByteArrayRegion(e, buffer, 0, n, (jbyte *)bytes);
 return n;
}

JNIEXPORT jint JNICALL Java_org_retrobridge_callprobe_CompanionService_writeVoice(JNIEnv *e,jobject o,jint fd,jbyteArray buffer,jint count){
 char bytes[1024];
 if (fd < 0) return -EBADF;
 if (buffer == NULL || count < 0 || count > (jint)sizeof(bytes)) return -EINVAL;
 if (count > (*e)->GetArrayLength(e, buffer)) return -EINVAL;
 if (count == 0) return 0;
 (*e)->GetByteArrayRegion(e, buffer, 0, count, (jbyte *)bytes);
 if ((*e)->ExceptionCheck(e)) return -EINVAL;
 int ready = wait_socket(fd, POLLOUT);
 if (ready < 0) return ready;
 if (!(ready & (POLLOUT | POLLERR | POLLHUP))) return -EAGAIN;
 int n = send(fd, bytes, count, MSG_DONTWAIT | MSG_NOSIGNAL);
 return n < 0 ? -errno : n;
}

JNIEXPORT void JNICALL Java_org_retrobridge_callprobe_CompanionService_stopVoice(JNIEnv *e,jobject o,jint fd){
 /* The owner retains the descriptor until both I/O threads have stopped. */
 if (fd >= 0) shutdown(fd, SHUT_RDWR);
}

JNIEXPORT void JNICALL Java_org_retrobridge_callprobe_CompanionService_releaseVoice(JNIEnv *e,jobject o,jint fd){
 /* Called exactly once by Java after joining the reader and writer. Do not
    retry close on EINTR: a retry could close a subsequently reused fd. */
 if (fd >= 0) close(fd);
}

JNIEXPORT void JNICALL Java_org_retrobridge_callprobe_CompanionService_closeListener(JNIEnv *e,jobject o){
 pthread_mutex_lock(&listener_mutex);
 if (listening >= 0) {
  int fd = listening;
  listening = -1;
  shutdown(fd, SHUT_RDWR);
  close(fd);
 }
 pthread_mutex_unlock(&listener_mutex);
}

JNIEXPORT jstring JNICALL Java_org_retrobridge_callprobe_CompanionService_nativeProbe(JNIEnv *env,jobject self){
 char result[512];int used=0;
 int protocols[3]={2,3,1},types[3]={SOCK_SEQPACKET,SOCK_STREAM,SOCK_RAW};
 const char *names[3]={"SCO voice socket","RFCOMM control socket","HCI controller socket"};
 for(int i=0;i<3;i++){int fd=socket(31,types[i],protocols[i]);int err=errno;
 used+=snprintf(result+used,sizeof(result)-used,"%s: %s\n",names[i],fd>=0?"available":strerror(err));if(fd>=0)close(fd);}
 return (*env)->NewStringUTF(env,result);
}
