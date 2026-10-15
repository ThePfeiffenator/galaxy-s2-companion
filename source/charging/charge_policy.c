#include "charge_policy.h"
/* Temperatures are deci-Celsius; voltage is microvolts. These conservative
 * independent gates apply to our restoration, alongside the stock health gate.
 * A failed sensor is never interpreted as a healthy zero/default value. */
int charge_safe_resume(const ChargeReading *r) {
 return r->physical==1 && r->present==1 && r->health_good==1 &&
        r->soc>=0 && r->soc<=100 && r->temp>=100 && r->temp<=400 &&
        r->uv>=2500000 && r->uv<=4250000 && r->cable>=1 && r->cable<=3;
}
ChargeDecision charge_decide(const ChargeReading *r,int enabled,int latched,int owned,int high,int low) {
 ChargeDecision d={latched,CHARGE_LEAVE,REASON_SENSOR};
 if(high<20||high>80||low<10||low>=high)return d;
 if(!enabled)d.latched=0;
 else if(r->soc>=high&&r->soc<=100)d.latched=1;
 else if(r->soc>=0&&r->soc<=low)d.latched=0;
 if(r->physical==0){d.reason=REASON_UNPLUGGED;return d;}
 if(r->physical!=1)return d;
 if(r->cable<1||r->cable>3){d.reason=REASON_WAIT_CABLE;return d;}
 if(r->present!=1||r->health_good!=1||r->uv<2500000||r->uv>4250000)d.reason=REASON_BATTERY;
 else if(r->temp<100||r->temp>400)d.reason=REASON_TEMPERATURE;
 else if(r->soc<0||r->soc>100)d.reason=REASON_SENSOR;
 else if(enabled&&d.latched)d.reason=REASON_CAP;
 else d.reason=enabled?REASON_CHARGING:REASON_OFF;
 if(d.reason==REASON_OFF||d.reason==REASON_CHARGING){if(owned&&charge_safe_resume(r))d.action=CHARGE_RESUME;}
 else if(enabled||owned)d.action=CHARGE_PAUSE;
 return d;
}
int charge_policy_tests(void) {
 int n=0;ChargeReading r={79,250,3900000,1,1,1,1,1};ChargeDecision d;
#define CHECK(x) do{n++;if(!(x))return -n;}while(0)
 d=charge_decide(&r,1,0,0,80,75);CHECK(d.action==0&&!d.latched);
 r.soc=80;d=charge_decide(&r,1,0,0,80,75);CHECK(d.action==1&&d.latched);
 r.soc=79;d=charge_decide(&r,1,1,1,80,75);CHECK(d.action==1&&d.latched);
 r.soc=76;d=charge_decide(&r,1,1,1,80,75);CHECK(d.action==1&&d.latched);
 r.soc=75;d=charge_decide(&r,1,1,1,80,75);CHECK(d.action==2&&!d.latched);
 r.soc=74;CHECK(charge_decide(&r,1,1,1,80,75).action==2);
 r.soc=90;d=charge_decide(&r,0,1,1,80,75);CHECK(d.action==2&&!d.latched);
 CHECK(charge_decide(&r,0,0,0,80,75).action==0);
 r.physical=0;CHECK(charge_decide(&r,1,1,1,80,75).action==0);CHECK(!charge_safe_resume(&r));
 r.physical=-1;CHECK(charge_decide(&r,1,1,1,80,75).action==0);CHECK(!charge_safe_resume(&r));
 r.physical=1;r.cable=0;CHECK(charge_decide(&r,0,1,1,80,75).action==0);CHECK(!charge_safe_resume(&r));
 for(int type=1;type<=3;type++){r.cable=type;r.soc=74;CHECK(charge_safe_resume(&r));CHECK(charge_decide(&r,1,1,1,80,75).action==2);}
 r.temp=401;CHECK(!charge_safe_resume(&r));CHECK(charge_decide(&r,1,0,0,80,75).action==1);CHECK(charge_decide(&r,0,0,1,80,75).action==1);
 r.temp=99;CHECK(!charge_safe_resume(&r));CHECK(charge_decide(&r,1,0,0,80,75).action==1);
 r.temp=100;CHECK(charge_safe_resume(&r));r.temp=400;CHECK(charge_safe_resume(&r));r.temp=250;
 r.present=0;CHECK(!charge_safe_resume(&r));CHECK(charge_decide(&r,1,0,0,80,75).action==1);r.present=1;
 r.health_good=0;CHECK(!charge_safe_resume(&r));r.health_good=1;
 r.uv=4250001;CHECK(!charge_safe_resume(&r));r.uv=2499999;CHECK(!charge_safe_resume(&r));r.uv=3900000;
 r.soc=-1;CHECK(!charge_safe_resume(&r));CHECK(charge_decide(&r,1,0,0,80,75).action==1);
 r.soc=101;CHECK(!charge_safe_resume(&r));r.soc=74;
 CHECK(charge_decide(&r,1,0,1,80,80).action==0);CHECK(charge_decide(&r,1,0,1,81,75).action==0);
 /* Reboot preserves cap latch but never an unsafe decision to enable. */
 r.soc=78;d=charge_decide(&r,1,1,0,80,75);CHECK(d.latched&&d.action==1);
 r.soc=75;d=charge_decide(&r,1,1,0,80,75);CHECK(!d.latched&&d.action==0);
 /* A fresh user enable at 78 is allowed to charge to 80. */
 r.soc=78;d=charge_decide(&r,1,0,0,80,75);CHECK(!d.latched&&d.action==0);
 return n;
}
