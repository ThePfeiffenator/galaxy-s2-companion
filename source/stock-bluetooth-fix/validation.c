/* Isolated app_process JNI validation. Only read-only D-Bus methods are sent. */
#define main property_probe_cli_main
#include "../stock-bluetooth-probe/probe.c"
#undef main
#include <jni.h>

static void error(JNIEnv *env,const char *message){
 jclass c=(*env)->FindClass(env,"java/io/IOException");if(c)(*env)->ThrowNew(env,c,message);
}
/* Independent flat encoding from D-Bus, omitting Services. No property value
 * is ever logged; Java compares the complete arrays before its parser test. */
static jobjectArray expected(JNIEnv *env,Msg *reply,int omit_services){
 jobjectArray by_key[17]={0};Iter top={0},entries={0};int total=0,properties=0;
 if(!init(reply,&top)||type(&top)!='a'||element_type(&top)!='e')return NULL;
 recurse(&top,&entries);if(next(&top))return NULL;
 jclass strings=(*env)->FindClass(env,"java/lang/String");if(!strings)return NULL;
 while(type(&entries)){
  if(++properties>64||type(&entries)!='e')return NULL;
  Iter fields={0},value={0};const char *name=NULL;recurse(&entries,&fields);
  if(type(&fields)!='s')return NULL;basic(&fields,&name);if(!name||strnlen(name,80)>=80)return NULL;
  int index=-1;for(int k=0;k<17;k++)if(!strcmp(name,known[k]))index=k;
  if(!next(&fields)||type(&fields)!='v')return NULL;recurse(&fields,&value);if(next(&fields))return NULL;
  if(index<0)return NULL;
  if(index==5&&omit_services){next(&entries);continue;}
  if(by_key[index])return NULL;
  int t=type(&value);jstring items[258]={0};int n=0;
  items[n++]=(*env)->NewStringUTF(env,known[index]);
  if(t=='a'){
   Iter values={0};recurse(&value,&values);int count=0;items[n++]=NULL;
   while(type(&values)){
    if(count>=256||(type(&values)!='s'&&type(&values)!='o'))return NULL;
    const char *s=NULL;basic(&values,&s);if(!s)return NULL;
    items[n++]=(*env)->NewStringUTF(env,s);count++;next(&values);
   }
   char b[20];snprintf(b,sizeof(b),"%d",count);items[1]=(*env)->NewStringUTF(env,b);
  }else{
   char text[64];const char *s=text;
   if(t=='s'||t=='o'){s=NULL;basic(&value,&s);if(!s)return NULL;}
   else if(t=='b'){uint32_t v=0;basic(&value,&v);s=v?"true":"false";}
   else if(t=='u'){uint32_t v=0;basic(&value,&v);snprintf(text,sizeof(text),"%u",v);}
   else if(t=='n'){int16_t v=0;basic(&value,&v);snprintf(text,sizeof(text),"%d",(int)v);}
   else return NULL;
   items[n++]=(*env)->NewStringUTF(env,s);
  }
  if(next(&value)||total+n>2048)return NULL;
  by_key[index]=(*env)->NewObjectArray(env,n,strings,NULL);if(!by_key[index])return NULL;
  for(int i=0;i<n;i++){if(!items[i])return NULL;(*env)->SetObjectArrayElement(env,by_key[index],i,items[i]);(*env)->DeleteLocalRef(env,items[i]);}
  total+=n;next(&entries);
 }
 jobjectArray out=(*env)->NewObjectArray(env,total,strings,NULL);if(!out)return NULL;
 int pos=0;for(int k=0;k<17;k++)if(by_key[k]){
  int n=(*env)->GetArrayLength(env,by_key[k]);for(int i=0;i<n;i++){
   jobject item=(*env)->GetObjectArrayElement(env,by_key[k],i);(*env)->SetObjectArrayElement(env,out,pos++,item);(*env)->DeleteLocalRef(env,item);
  }
  (*env)->DeleteLocalRef(env,by_key[k]);
 }
 return out;
}
JNIEXPORT jobjectArray JNICALL Java_org_retrobridge_bluetoothvalidation_ValidationMain_readProperties(JNIEnv *env,jclass klass){
 (void)klass;
 if(getuid()!=0||load()){error(env,"probe initialization failed");return NULL;}
 if(setenv("DBUS_SYSTEM_BUS_ADDRESS","unix:path=/dev/socket/dbus",1)){error(env,"bus environment failed");return NULL;}
 Conn *conn=bus_get(1,NULL);if(!conn){error(env,"bus unavailable");return NULL;}exit_disconnect(conn,0);
 Msg *reply=query(conn,"/","org.bluez.Manager","DefaultAdapter");char adapter[200],device[240];jobjectArray output=NULL;
 if(!reply){error(env,"adapter unavailable");goto done;}
 int bad=adapter_path(reply,adapter);unref(reply);if(bad){error(env,"adapter shape invalid");goto done;}
 int n=snprintf(device,sizeof(device),"%s/dev_08_8B_C8_50_DC_8B",adapter);if(n<1||n>=(int)sizeof(device)){error(env,"path failed");goto done;}
 reply=query(conn,device,"org.bluez.Device","GetProperties");if(!reply){error(env,"device unavailable");goto done;}
 jobjectArray reference=expected(env,reply,1);
 if(!reference){unref(reply);error(env,"independent reference shape failed");goto done;}
 void *runtime=dlopen("libandroid_runtime.so",RTLD_NOW|RTLD_LOCAL);
 jobjectArray (*parse)(JNIEnv*,Iter*)=runtime?dlsym(runtime,"_ZN7android30parse_remote_device_propertiesEP7_JNIEnvP15DBusMessageIter"):NULL;
 if(!parse){unref(reply);error(env,"runtime parser symbol unavailable");goto done;}
 Iter iter={0};if(!init(reply,&iter)){unref(reply);error(env,"reply empty");goto done;}
 jobjectArray parsed=parse(env,&iter);unref(reply);
 if(!parsed){error(env,"runtime parser returned null");goto done;}
 jclass object=(*env)->FindClass(env,"java/lang/Object");output=(*env)->NewObjectArray(env,2,object,NULL);
 if(output){(*env)->SetObjectArrayElement(env,output,0,parsed);(*env)->SetObjectArrayElement(env,output,1,reference);}
done:conn_close(conn);conn_unref(conn);return output;
}

static Msg *(*new_message)(int);
static void (*init_append)(Msg*,Iter*);
static int (*open_container)(Iter*,int,const char*,Iter*),(*close_container)(Iter*,Iter*),(*append_basic)(Iter*,int,const void*);
static int load_writers(void){
 void *lib=dlopen("/system/lib/libdbus.so",RTLD_NOW|RTLD_LOCAL);if(!lib)return -1;
#define W(target,name) do{*(void**)(&target)=dlsym(lib,name);if(!target)return -1;}while(0)
 W(new_message,"dbus_message_new");W(init_append,"dbus_message_iter_init_append");
 W(open_container,"dbus_message_iter_open_container");W(close_container,"dbus_message_iter_close_container");W(append_basic,"dbus_message_iter_append_basic");
#undef W
 return 0;
}
static int append_property(Iter *dict,const char *name,int type_value,int count){
 Iter entry={0},variant={0},array={0};char signature[3]={(char)type_value,0,0};
 if(count>=0){signature[0]='a';signature[1]=(char)type_value;}
 if(!open_container(dict,'e',NULL,&entry)||!append_basic(&entry,'s',&name)||!open_container(&entry,'v',signature,&variant))return -1;
 if(count>=0){
  char element[2]={(char)type_value,0};if(!open_container(&variant,'a',element,&array))return -1;
  for(int i=0;i<count;i++){
   const char *text=type_value=='o'?"/synthetic/service":"0000111f-0000-1000-8000-00805f9b34fb";
   if(!append_basic(&array,type_value,&text))return -1;
  }
  if(!close_container(&variant,&array))return -1;
 }else if(type_value=='s'||type_value=='o'){
  const char *text=type_value=='o'?"/synthetic/adapter":"Synthetic test value";
  if(!append_basic(&variant,type_value,&text))return -1;
 }else if(type_value=='b'||type_value=='u'){
  uint32_t value=type_value=='b'?1:0x1234;if(!append_basic(&variant,type_value,&value))return -1;
 }else return -1;
 return close_container(&entry,&variant)&&close_container(dict,&entry)?0:-1;
}
/* variant0 exact remote table; 1 wrong Services type; 2 unknown property;
 * 3 structurally identical but unrelated table, which must NOT be filtered.
 * These synthetic messages are never sent to the bus. */
JNIEXPORT jobjectArray JNICALL Java_org_retrobridge_bluetoothvalidation_ValidationMain_syntheticProperties(JNIEnv *env,jclass klass,jint count,jint variant){
 (void)klass;if(count<0||count>32||variant<0||variant>3||load()||load_writers()){error(env,"synthetic initialization");return NULL;}
 Msg *message=new_message(1);if(!message){error(env,"synthetic allocation");return NULL;}
 Iter top={0},dict={0};init_append(message,&top);int bad=!open_container(&top,'a',"{sv}",&dict);
 /* Name intentionally precedes empty Services: borrowed scalar storage must
    never be freed as if it were an allocated Services pointer array. */
 if(!bad)bad=append_property(&dict,"Name",'s',-1);
 if(!bad)bad=append_property(&dict,"Services",variant==1?'s':'o',variant==1?-1:count);
 if(!bad)bad=append_property(&dict,"Paired",'b',-1);
 if(!bad)bad=append_property(&dict,"UUIDs",'s',19);
 if(!bad)bad=append_property(&dict,"Class",'u',-1);
 if(!bad)bad=append_property(&dict,"Adapter",'o',-1);
 if(!bad&&variant==2)bad=append_property(&dict,"UnknownProperty",'s',-1);
 if(!bad)bad=!close_container(&top,&dict);
 if(bad){unref(message);error(env,"synthetic construction");return NULL;}
 void *runtime=dlopen("libandroid_runtime.so",RTLD_NOW|RTLD_LOCAL);
 jobjectArray (*remote_parse)(JNIEnv*,Iter*)=runtime?dlsym(runtime,"_ZN7android30parse_remote_device_propertiesEP7_JNIEnvP15DBusMessageIter"):NULL;
 struct Property {char name[32];int type;} definitions[17];
 jobjectArray (*generic_parse)(JNIEnv*,Iter*,struct Property*,int)=runtime?dlsym(runtime,"_ZN7android16parse_propertiesEP7_JNIEnvP15DBusMessageIterPNS_11_PropertiesEi"):NULL;
 if(!remote_parse||!generic_parse){unref(message);error(env,"synthetic parser symbols");return NULL;}
 Iter read={0};init(message,&read);jobjectArray parsed;
 if(variant==3){
  const char types[]={'s','s','s','u','a','a','b','b','b','b','s','a','o','b','n','u','b'};
  memset(definitions,0,sizeof(definitions));for(int i=0;i<17;i++){strcpy(definitions[i].name,known[i]);definitions[i].type=types[i];}
  parsed=generic_parse(env,&read,definitions,17);
 }else parsed=remote_parse(env,&read);
 if(variant==1||variant==2){
  unref(message);if(parsed)error(env,"malformed dictionary unexpectedly accepted");
  return NULL;
 }
 jobjectArray reference=expected(env,message,variant!=3);unref(message);
 if(!parsed||!reference){error(env,"synthetic result missing");return NULL;}
 jclass object=(*env)->FindClass(env,"java/lang/Object");jobjectArray out=(*env)->NewObjectArray(env,2,object,NULL);
 if(out){(*env)->SetObjectArrayElement(env,out,0,parsed);(*env)->SetObjectArrayElement(env,out,1,reference);}
 return out;
}
