/* Only the authenticated SMS worker calls this. No voice/model lock is held
 * while verifying identity or running the fixed admitted-APK helper. */
static pid_t sms_delivery_orphan=-1;
static int property_workspace_fd(void);
static int sms_delivery(int peer,const SmsBatch *batch){
    long long started=now_ms();if(started<0)return -1;long long deadline=started+SMS_DELIVERY_MS;
    if(sms_delivery_orphan>0){pid_t r=waitpid(sms_delivery_orphan,NULL,WNOHANG);if(r==sms_delivery_orphan||(r<0&&errno==ECHILD))sms_delivery_orphan=-1;else return -1;}
    if(pthread_mutex_trylock(&identity_mutex))return -1;
    int identity=verified_identity_inner();pthread_mutex_unlock(&identity_mutex);
    char apk_hash[65];
    if(identity||file_hash(config.apk,apk_hash,0,0)||strcmp(apk_hash,config.apk_hash)||sms_stop||stop_requested||!sms_powered()||now_ms()<0||now_ms()>=deadline)return -1;
    /* Build.MODEL/DISPLAY in the helper require ICS's inherited property area.
     * Validate before creating pipes; this existing descriptor cannot alias them. */
    int property_fd=property_workspace_fd();if(property_fd<3)return -1;
    int input[2]={-1,-1},output[2]={-1,-1};
    if(pipe(input)||pipe(output))goto before_fork;
    if(cloexec(input[0])||cloexec(input[1])||cloexec(output[0])||cloexec(output[1])||nonblock(input[1])||nonblock(output[0]))goto before_fork;
    char classpath[300],until[32],property_env[80];
    if(snprintf(classpath,sizeof(classpath),"CLASSPATH=%s",config.apk)>=(int)sizeof(classpath)||snprintf(until,sizeof(until),"%lld",deadline)>=(int)sizeof(until)||snprintf(property_env,sizeof(property_env),"ANDROID_PROPERTY_WORKSPACE=%s",getenv("ANDROID_PROPERTY_WORKSPACE"))>=(int)sizeof(property_env))goto before_fork;
    char *args[]={"/system/bin/app_process","/system/bin","org.retrobridge.callprobe.SmsDelivery","--deliver",(char*)batch->id,until,NULL};
    char *env[]={classpath,property_env,"PATH=/system/bin","ANDROID_ROOT=/system","ANDROID_DATA=/data","ANDROID_ASSETS=/system/app","LD_LIBRARY_PATH=/vendor/lib:/system/lib","BOOTCLASSPATH=/system/framework/core.jar:/system/framework/core-junit.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/framework2.jar:/system/framework/android.policy.jar:/system/framework/services.jar:/system/framework/apache-xml.jar:/system/framework/filterfw.jar:/system/framework/sechardware.jar:/system/framework/wimax.jar",NULL};
    pid_t parent=getpid(),child=fork();if(child<0)goto before_fork;
    if(!child){
        if(prctl(PR_SET_PDEATHSIG,SIGKILL)||getppid()!=parent||dup2(input[0],0)<0||dup2(output[1],1)<0)_exit(120);
        int nullfd=open("/dev/null",O_WRONLY);if(nullfd<0||dup2(nullfd,2)<0)_exit(121);
        if(fcntl(property_fd,F_SETFD,0))_exit(123);
        for(int fd=3;fd<1024;fd++)if(fd!=property_fd)close(fd);
        execve(args[0],args,env);_exit(122);
    }
    close(input[0]);input[0]=-1;close(output[1]);output[1]=-1;
    unsigned char header[12]={'S','M','S','B',0,0,0,1,0,0,0,(unsigned char)batch->count};
    size_t sent=0,total=sizeof(header)+(size_t)batch->count*SMS_BYTES,used=0;char reply[128];
    int done=0,status=0,eof=0,bad=0;
    while(now_ms()>=0&&now_ms()<deadline&&!sms_stop&&!stop_requested&&sms_powered()){
        if(!done){pid_t r=waitpid(child,&status,WNOHANG);if(r==child)done=1;else if(r<0&&errno!=EINTR){bad=1;break;}}
        if(input[1]>=0){
            const unsigned char *data=sent<sizeof(header)?header+sent:(const unsigned char*)batch->bytes+sent-sizeof(header);
            size_t remain=sent<sizeof(header)?sizeof(header)-sent:total-sent;
            ssize_t n=write(input[1],data,remain);
            if(n>0)sent+=(size_t)n;else if(n<0&&errno!=EAGAIN&&errno!=EWOULDBLOCK&&errno!=EINTR){close(input[1]);input[1]=-1;}
            if(sent==total&&input[1]>=0){close(input[1]);input[1]=-1;}
        }
        if(!eof){ssize_t n=read(output[0],reply+used,sizeof(reply)-1-used);if(n>0){used+=(size_t)n;if(used==sizeof(reply)-1){bad=1;break;}}else if(!n)eof=1;else if(errno!=EAGAIN&&errno!=EWOULDBLOCK&&errno!=EINTR){bad=1;break;}}
        if(done&&eof)break;
        struct pollfd p[3]={{eof?-1:output[0],POLLIN,0},{input[1],POLLOUT,0},{peer,0,0}};
        if(poll(p,3,20)<0&&errno!=EINTR){bad=1;break;}
        if(p[2].revents&(POLLHUP|POLLERR|POLLNVAL)){bad=1;break;}
    }
    if(input[1]>=0)close(input[1]);close(output[0]);
    if(!done){if(reap_force(child))sms_delivery_orphan=child;return -2;}
    if(bad||!eof||sms_stop||stop_requested||!sms_powered()||now_ms()<0||now_ms()>=deadline||!WIFEXITED(status))return -2;
    return sms_delivery_result(batch->id,reply,used,WEXITSTATUS(status),sent==total);
before_fork:
    for(int i=0;i<2;i++){if(input[i]>=0)close(input[i]);if(output[i]>=0)close(output[i]);}return -1;
}
