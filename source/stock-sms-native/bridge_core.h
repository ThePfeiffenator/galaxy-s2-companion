#ifndef STOCK_BRIDGE_CORE_H
#define STOCK_BRIDGE_CORE_H
#include <stddef.h>
#include <stdint.h>
#define BRIDGE_MAX_LINE 1024
#define BRIDGE_MAX_CALLS 8
#define BRIDGE_NUMBER_SIZE 81
typedef struct {
    int index, direction, state, mode, multiparty, type;
    char number[BRIDGE_NUMBER_SIZE];
} BridgeCall;
typedef struct {
    int ready, service, signal, roam, have_state, have_calls;
    int count, selected, ambiguous;
    BridgeCall calls[BRIDGE_MAX_CALLS];
    int frame_expected, frame_received, frame_active;
    BridgeCall frame[BRIDGE_MAX_CALLS];
} BridgeModel;
typedef struct { int state_changed, snapshot_complete, calls_changed, result; uint32_t result_id; } BridgeEvent;
void bridge_init(BridgeModel *m);
int bridge_number_valid(const char *s);
int bridge_feed(BridgeModel *m, const char *line, BridgeEvent *event);
const BridgeCall *bridge_selected(const BridgeModel *m);
int bridge_self_test(void);
#endif
