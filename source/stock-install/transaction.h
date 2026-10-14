#ifndef COMPANION_TRANSACTION_H
#define COMPANION_TRANSACTION_H
enum TxStep { TX_PREFLIGHT,TX_RW,TX_BACKUP,TX_PREREQ0,TX_PREREQ1,TX_PREREQ2,TX_STOP,TX_VERIFY,TX_COMMIT,TX_RO,TX_START,TX_RECOVER_START };
typedef struct { int rw_attempted,stop_attempted,committed; } TxState;
typedef int (*TxOperation)(enum TxStep step,TxState *state,void *context);
enum TxMode { TX_INSTALL_RIL,TX_RESTORE_RIL };
int transaction_run(enum TxMode mode,TxOperation operation,void *context,TxState *state);
int transaction_self_test(void);
#endif
