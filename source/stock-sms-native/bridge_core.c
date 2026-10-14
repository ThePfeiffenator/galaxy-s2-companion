#include "bridge_core.h"
static void zero(void *p, size_t n) { unsigned char *b=p; while(n--) *b++=0; }
static void copy(void *d, const void *s, size_t n) { unsigned char *a=d; const unsigned char *b=s; while(n--) *a++=*b++; }
static int same(const char *a,const char *b) { while(*a && *a==*b) {a++;b++;} return *a==*b; }
static int numeric(const char *s, uint32_t max, uint32_t *out) {
    uint32_t n=0; if(!*s)return -1;
    while(*s) { unsigned d=(unsigned char)*s++-'0'; if(d>9 || n>(max-d)/10 || d>max)return -1; n=n*10+d; }
    *out=n;return 0;
}
void bridge_init(BridgeModel *m) {zero(m,sizeof(*m));m->selected=-1;}
int bridge_number_valid(const char *s) {
    int n=0; if(*s=='+')s++;
    while(*s) {if(*s<'0'||*s>'9'||++n>=BRIDGE_NUMBER_SIZE-1)return 0;s++;}
    return n>0;
}
const BridgeCall *bridge_selected(const BridgeModel *m) {
    return m->selected>=0 && m->selected<m->count ? &m->calls[m->selected] : 0;
}
static void commit(BridgeModel *m,BridgeEvent *e) {
    const BridgeCall *old=bridge_selected(m); int old_index=old?old->index:-1;
    int changed=!m->have_calls||m->count!=m->frame_expected;
    for(int i=0;!changed&&i<m->count;i++) {
        BridgeCall *a=&m->calls[i],*b=&m->frame[i];
        changed=a->index!=b->index||a->direction!=b->direction||a->state!=b->state||a->mode!=b->mode||a->multiparty!=b->multiparty||a->type!=b->type||!same(a->number,b->number);
    }
    m->count=m->frame_expected;copy(m->calls,m->frame,sizeof(m->calls));
    m->selected=-1;m->ambiguous=0;int voices=0;
    for(int i=0;i<m->count;i++) {
        BridgeCall *c=&m->calls[i];if(c->mode!=0)continue;voices++;
        if(c->state>4||c->multiparty){m->ambiguous=1;continue;}
        if(m->selected<0||c->index==old_index)m->selected=i;
        if(c->index==old_index)old_index=-2; /* Later rows cannot displace it. */
    }
    if(voices>1)m->ambiguous=1;
    m->have_calls=1;m->frame_active=0;e->snapshot_complete=1;e->calls_changed=changed;
}
int bridge_feed(BridgeModel *m,const char *line,BridgeEvent *e) {
    char text[BRIDGE_MAX_LINE+1];char *f[10];size_t len=0;int n=1;
    zero(e,sizeof(*e));while(line[len]){if(len==BRIDGE_MAX_LINE)return -1;text[len]=line[len];len++;}text[len]=0;
    f[0]=text;for(size_t i=0;i<len;i++) {if(text[i]=='\r'||text[i]=='\n')return -1;if(text[i]=='\t'){if(n==10)return -1;text[i]=0;f[n++]=text+i+1;}}
    if(m->frame_active && !same(f[0],"CALL"))return -1;
    uint32_t v[7];
    if(same(f[0],"STATE")) {
        if(n!=5||numeric(f[1],1,&v[0])||numeric(f[2],1,&v[1])||numeric(f[3],5,&v[2])||numeric(f[4],1,&v[3]))return -1;
        m->ready=v[0];m->service=v[1];m->signal=v[2];m->roam=v[3];m->have_state=1;e->state_changed=1;return 0;
    }
    if(same(f[0],"CALLS")) {
        if(n!=2||numeric(f[1],BRIDGE_MAX_CALLS,&v[0]))return -1;
        m->frame_expected=v[0];m->frame_received=0;m->frame_active=1;zero(m->frame,sizeof(m->frame));
        if(!v[0])commit(m,e);return 0;
    }
    if(same(f[0],"CALL")) {
        if(n!=8||!m->frame_active||numeric(f[1],0x7fffffff,&v[0])||!v[0]||numeric(f[2],1,&v[1])||numeric(f[3],6,&v[2])||numeric(f[4],9,&v[3])||numeric(f[5],1,&v[4])||numeric(f[6],255,&v[5]))return -1;
        for(int i=0;i<m->frame_received;i++)if(m->frame[i].index==(int)v[0])return -1;
        BridgeCall *c=&m->frame[m->frame_received++];c->index=v[0];c->direction=v[1];c->state=v[2];c->mode=v[3];c->multiparty=v[4];c->type=v[5];
        if(bridge_number_valid(f[7])){size_t l=0;while(f[7][l])l++;copy(c->number,f[7],l+1);}
        if(m->frame_received==m->frame_expected)commit(m,e);return 0;
    }
    if(same(f[0],"RESULT")) {
        if(n!=3||numeric(f[1],0x7fffffff,&v[0])||!v[0])return -1;
        if(same(f[2],"OK"))e->result=1;else if(same(f[2],"ERROR"))e->result=-1;else return -1;
        e->result_id=v[0];return 0;
    }
    return -1;
}
int bridge_self_test(void) {
    BridgeModel m;BridgeEvent e;bridge_init(&m);
    if(bridge_feed(&m,"STATE\t1\t1\t5\t0",&e)||!m.ready)return 1;
    if(bridge_feed(&m,"CALLS\t1",&e)||m.have_calls)return 2;
    if(bridge_feed(&m,"CALL\t7\t1\t4\t0\t0\t145\t+15550000000",&e)||!m.have_calls||!e.calls_changed)return 3;
    if(!bridge_selected(&m)||bridge_selected(&m)->index!=7||m.ambiguous)return 4;
    if(bridge_feed(&m,"CALLS\t2",&e)||bridge_feed(&m,"CALL\t3\t0\t0\t0\t0\t129\t123",&e)||bridge_feed(&m,"CALL\t7\t1\t0\t0\t0\t129\t123",&e)||bridge_selected(&m)->index!=7||!m.ambiguous)return 5;
    if(bridge_feed(&m,"CALLS\t2",&e)||bridge_feed(&m,"CALL\t7\t1\t0\t0\t0\t129\t123",&e)||bridge_feed(&m,"CALL\t3\t0\t0\t0\t0\t129\t123",&e)||bridge_selected(&m)->index!=7)return 6;
    if(bridge_feed(&m,"CALLS\t1",&e)||bridge_feed(&m,"CALL\t3\t0\t6\t0\t0\t129\t123",&e)||bridge_selected(&m)||!m.ambiguous)return 7;
    if(bridge_feed(&m,"CALLS\t1",&e)||bridge_feed(&m,"CALL\t3\t0\t0\t0\t0\t129\t12;34",&e)||bridge_selected(&m)->number[0])return 8;
    if(!bridge_feed(&m,"CALLS\t9",&e)||!bridge_feed(&m,"STATE\t1\t1\t6\t0",&e)||!bridge_feed(&m,"RESULT\t0\tOK",&e)||!bridge_feed(&m,"RESULT\t2147483648\tOK",&e))return 9;
    if(bridge_feed(&m,"CALLS\t2",&e)||bridge_feed(&m,"CALL\t3\t0\t0\t0\t0\t129\t",&e)||!bridge_feed(&m,"CALL\t3\t0\t0\t0\t0\t129\t",&e))return 10;
    bridge_init(&m);if(bridge_feed(&m,"CALLS\t1",&e)||!bridge_feed(&m,"RESULT\t1\tOK",&e))return 11;
    bridge_init(&m);if(bridge_feed(&m,"CALLS\t0",&e)||!m.have_calls||bridge_selected(&m))return 12;
    if(bridge_feed(&m,"RESULT\t2147483647\tERROR",&e)||e.result!=-1||e.result_id!=2147483647)return 13;
    if(bridge_number_valid("+")||bridge_number_valid("12\n3")||!bridge_number_valid("123"))return 14;
    if(bridge_feed(&m,"CALLS\t0",&e)||e.calls_changed||!e.snapshot_complete)return 15;
    return 0;
}
