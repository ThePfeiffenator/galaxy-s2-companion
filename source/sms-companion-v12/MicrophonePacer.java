package org.retrobridge.callprobe;

/** Absolute packet deadlines avoid cumulative sleep drift and unbounded catch-up bursts. */
final class MicrophonePacer {
    final long period;
    private long due;
    long resets;
    MicrophonePacer(int bytes,long now) {
        if(bytes<=0 || (bytes&1)!=0)throw new IllegalArgumentException("PCM packet size");
        period=bytes*1000000000L/16000L;due=now;
    }
    long remaining(long now){return Math.max(0,due-now);}
    void sent(long now) {
        if(now-due>period*4){due=now+period;resets++;}
        else due+=period;
    }
}
