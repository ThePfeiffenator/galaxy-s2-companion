#ifndef GALAXY_CHARGE_POLICY_H
#define GALAXY_CHARGE_POLICY_H
typedef struct {
 int soc, temp, uv, present, health_good, physical, cable, charger_enabled;
} ChargeReading;
enum { CHARGE_LEAVE=0, CHARGE_PAUSE=1, CHARGE_RESUME=2 };
enum { REASON_OFF=0, REASON_CHARGING, REASON_CAP, REASON_UNPLUGGED,
       REASON_TEMPERATURE, REASON_BATTERY, REASON_SENSOR, REASON_WAIT_CABLE };
typedef struct { int latched, action, reason; } ChargeDecision;
int charge_safe_resume(const ChargeReading *r);
ChargeDecision charge_decide(const ChargeReading *r,int enabled,int latched,int owned,int high,int low);
int charge_policy_tests(void);
#endif
