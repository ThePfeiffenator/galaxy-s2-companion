#ifndef SAMSUNG_RIL_H
#define SAMSUNG_RIL_H
#include <stddef.h>
#include <stdint.h>
typedef void *RilToken;
typedef struct { int version; void (*request)(int,void*,size_t,RilToken); int (*state)(void); int (*supports)(int); void (*cancel)(RilToken); const char *(*version_string)(void); } RilFunctions;
typedef struct { int state,index,toa; unsigned char isMpty,isMT,als,isVoice,isVideo,isVoicePrivacy; char *number; int numberPresentation; char *name; int namePresentation; void *uusInfo; } SamsungCall;
typedef struct { char *address; int clir; void *uusInfo; } RilDial;
typedef struct { int values[12]; } SamsungSignal;
typedef struct {int messageRef;char *ackPDU;int errorCode;} SamsungSmsResponse;
typedef struct {int errorClass,cause;} SamsungSmsAck;
typedef struct {int digitMode,numberMode,numberType,numberPlan;unsigned char count,digits[36];} SamsungSmsAddress;
typedef struct {int type;unsigned char odd,count,digits[36];} SamsungSmsSubaddress;
typedef struct {int teleservice;unsigned char servicePresent;int category;SamsungSmsAddress address;SamsungSmsSubaddress subaddress;int bearerLength;unsigned char bearer[255];} SamsungSms;
_Static_assert(sizeof(void*)==4,"This ABI requires ARM32");
_Static_assert(sizeof(RilFunctions)==24,"Factory callback table");
_Static_assert(sizeof(SamsungCall)==40,"Factory Samsung RIL_Call");
_Static_assert(offsetof(SamsungCall,isVideo)==16,"Samsung video field");
_Static_assert(offsetof(SamsungCall,isVoicePrivacy)==17,"Samsung privacy field");
_Static_assert(offsetof(SamsungCall,number)==20,"Factory number pointer");
_Static_assert(offsetof(SamsungCall,uusInfo)==36,"Factory UUS pointer");
_Static_assert(sizeof(RilDial)==12,"Factory RIL_Dial");
_Static_assert(sizeof(SamsungSignal)==48,"Factory signal payload");
_Static_assert(sizeof(SamsungSmsResponse)==12,"Factory SMS response");
_Static_assert(sizeof(SamsungSmsAck)==8,"Factory CDMA SMS ACK");
_Static_assert(sizeof(SamsungSms)==372,"Factory CDMA SMS size");
_Static_assert(offsetof(SamsungSms,address)==12,"Factory CDMA address");
_Static_assert(offsetof(SamsungSmsAddress,count)==16,"Factory CDMA digit count");
_Static_assert(offsetof(SamsungSms,subaddress)==68,"Factory CDMA subaddress");
_Static_assert(offsetof(SamsungSms,bearerLength)==112,"Factory CDMA bearer length");
_Static_assert(offsetof(SamsungSms,bearer)==116,"Factory CDMA bearer bytes");
#endif
