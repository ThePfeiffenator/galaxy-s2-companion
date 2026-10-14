#include "transaction.h"
int transaction_run(enum TxMode mode,TxOperation op,void *context,TxState *state) {
    static const enum TxStep install[]={TX_PREFLIGHT,TX_RW,TX_BACKUP,TX_PREREQ0,TX_PREREQ1,TX_PREREQ2,TX_STOP,TX_VERIFY,TX_COMMIT,TX_RO,TX_START};
    static const enum TxStep restore[]={TX_PREFLIGHT,TX_RW,TX_STOP,TX_VERIFY,TX_COMMIT,TX_RO,TX_START};
    const enum TxStep *lists[]={install,restore};
    const int counts[]={11,7};
    if(mode<0||mode>TX_RESTORE_RIL)return -1;
    const enum TxStep *steps=lists[mode];int count=counts[mode];
    state->rw_attempted=state->stop_attempted=state->committed=0;
    for(int i=0;i<count;i++) {
        enum TxStep step=steps[i];
        if(step==TX_RW)state->rw_attempted=1;
        if(step==TX_STOP)state->stop_attempted=1;
        if(op(step,state,context)||(step==TX_COMMIT&&!state->committed)) {
            if(state->rw_attempted)op(TX_RO,state,context);
            if(state->stop_attempted&&!state->committed)op(TX_RECOVER_START,state,context);
            return -1;
        }
    }
    return 0;
}
typedef struct { int fail,postcommit,restore,original,new_files,prereq,stopped,readonly,started,commits,cleanup,called[12]; } Fixture;
static int fixture_op(enum TxStep step,TxState *s,void *context) {
    Fixture *f=context;f->called[step]++;
    if(step==TX_PREFLIGHT&&(!f->original||(!f->restore&&!f->new_files)))return -1;
    if((int)step==f->fail&&!(step==TX_COMMIT&&f->postcommit))return -1;
    if(step==TX_RW)f->readonly=0;
    if(step>=TX_PREREQ0&&step<=TX_PREREQ2)f->prereq++;
    if(step==TX_STOP)f->stopped=1;
    if(step==TX_COMMIT){if(!f->restore&&f->prereq!=3)return -1;s->committed=1;f->commits++;if(f->postcommit)return -1;}
    if(step==TX_RO){f->readonly=1;f->cleanup++;}
    if(step==TX_START){if(!f->readonly)return -1;f->started=1;f->stopped=0;}
    if(step==TX_RECOVER_START){f->started=1;f->stopped=0;}
    return 0;
}
int transaction_self_test(void) {
    TxState s;Fixture f={.fail=-1,.original=1,.new_files=1,.readonly=1};
    if(transaction_run(0,fixture_op,&f,&s)||!s.committed||f.commits!=1||!f.started||!f.readonly)return 1;
    f=(Fixture){.fail=TX_PREFLIGHT,.original=1,.new_files=1,.readonly=1};
    if(!transaction_run(0,fixture_op,&f,&s)||s.rw_attempted||f.called[TX_RW]||f.called[TX_STOP])return 2;
    f=(Fixture){.fail=TX_VERIFY,.original=1,.new_files=1,.readonly=1};
    if(!transaction_run(0,fixture_op,&f,&s)||s.committed||f.commits||!f.readonly||!f.called[TX_RECOVER_START])return 3;
    f=(Fixture){.fail=TX_COMMIT,.postcommit=1,.original=1,.new_files=1,.readonly=1};
    if(!transaction_run(0,fixture_op,&f,&s)||!s.committed||!f.readonly||f.called[TX_RECOVER_START]||f.started)return 4;
    f=(Fixture){.fail=TX_RO,.original=1,.new_files=1,.readonly=1};
    if(!transaction_run(0,fixture_op,&f,&s)||!s.committed||f.started||f.called[TX_RO]!=2)return 5;
    f=(Fixture){.fail=-1,.restore=1,.original=1,.new_files=0,.readonly=1};
    if(transaction_run(1,fixture_op,&f,&s)||!s.committed||!f.started||f.prereq||f.called[TX_BACKUP])return 6;
    f=(Fixture){.fail=TX_START,.restore=1,.original=1,.readonly=1};
    if(!transaction_run(1,fixture_op,&f,&s)||!s.committed||!f.readonly||f.called[TX_RECOVER_START])return 7;
    f=(Fixture){.fail=-1,.restore=1,.original=0,.readonly=1};
    if(!transaction_run(1,fixture_op,&f,&s)||s.rw_attempted||f.commits)return 8;
    return 0;
}
